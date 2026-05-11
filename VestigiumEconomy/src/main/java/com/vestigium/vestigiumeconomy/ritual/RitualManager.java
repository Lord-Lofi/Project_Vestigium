package com.vestigium.vestigiumeconomy.ritual;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.model.Season;
import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ritual Crafting — six high-tier items crafted at Crying Obsidian stations.
 *
 * Stations are tagged via /veritual station set <recipeId> and persisted to
 * ritual_stations.yml. Each recipe may require minimum omen, a specific season,
 * or night-only timing.
 *
 * PDC keys on result items:
 *   vestigium:ritual_item     STRING  — recipe ID
 *   vestigium:ritual_cooldown LONG    — Unix ms expiry for reusable items
 */
public class RitualManager implements Listener, CommandExecutor {

    private static final NamespacedKey RITUAL_ITEM_KEY     = new NamespacedKey("vestigium", "ritual_item");
    private static final NamespacedKey RITUAL_COOLDOWN_KEY = new NamespacedKey("vestigium", "ritual_cooldown");

    private final VestigiumEconomy plugin;
    private final Map<Location, String> stations = new HashMap<>();
    private BukkitRunnable soulLanternTask;
    private final Map<UUID, Long> wardenHeartActive = new HashMap<>();

    record RitualCondition(int minOmen, Season requiredSeason, boolean nightOnly) {}
    record RitualRecipe(String id, String displayName,
                        Map<Material, Integer> ingredients, RitualCondition condition) {}

    private static final Map<String, RitualRecipe> RECIPES = new LinkedHashMap<>();

    static {
        RECIPES.put("warden_heart", new RitualRecipe(
                "warden_heart", "§5Warden's Heart",
                Map.of(Material.ECHO_SHARD, 2, Material.SCULK_CATALYST, 1, Material.AMETHYST_BLOCK, 1),
                new RitualCondition(600, null, true)));

        RECIPES.put("omen_candle", new RitualRecipe(
                "omen_candle", "§6Omen Candle",
                Map.of(Material.CANDLE, 3, Material.BLAZE_POWDER, 2, Material.SCULK_SENSOR, 1),
                new RitualCondition(200, null, false)));

        RECIPES.put("ancient_compass", new RitualRecipe(
                "ancient_compass", "§bAncient Compass",
                Map.of(Material.COMPASS, 1, Material.ECHO_SHARD, 1,
                        Material.AMETHYST_SHARD, 2, Material.NAUTILUS_SHELL, 1),
                new RitualCondition(0, null, false)));

        RECIPES.put("season_lens", new RitualRecipe(
                "season_lens", "§aSeason Lens",
                Map.of(Material.GLASS_PANE, 4, Material.AMETHYST_SHARD, 2, Material.BLAZE_ROD, 1),
                new RitualCondition(0, null, false)));

        RECIPES.put("soul_lantern", new RitualRecipe(
                "soul_lantern", "§fSoul Lantern",
                Map.of(Material.LANTERN, 1, Material.BLAZE_POWDER, 2,
                        Material.BONE, 4, Material.SOUL_SAND, 2),
                new RitualCondition(0, null, true)));

        RECIPES.put("void_extract", new RitualRecipe(
                "void_extract", "§8Void Extract",
                Map.of(Material.ENDER_PEARL, 2, Material.SCULK, 8, Material.CHORUS_FRUIT, 3),
                new RitualCondition(400, null, false)));
    }

    public RitualManager(VestigiumEconomy plugin) {
        this.plugin = plugin;
    }

    public void init() {
        loadStations();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("veritual");
        if (cmd != null) cmd.setExecutor(this);

        // Soul Lantern passive: Glowing + Slowness I to nearby monsters every second
        soulLanternTask = new BukkitRunnable() {
            @Override public void run() {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (!playerHasSoulLantern(p)) continue;
                    p.getLocation().getNearbyEntities(6, 6, 6).forEach(e -> {
                        if (e instanceof Monster monster) {
                            monster.addPotionEffect(new PotionEffect(
                                    PotionEffectType.GLOWING, 30, 0, false, false));
                            monster.addPotionEffect(new PotionEffect(
                                    PotionEffectType.SLOWNESS, 30, 0, false, false));
                        }
                    });
                }
            }
        };
        soulLanternTask.runTaskTimer(plugin, 20L, 20L);

        plugin.getLogger().info("[RitualManager] Initialized — " + RECIPES.size() + " ritual recipes.");
    }

    public void shutdown() {
        if (soulLanternTask != null) soulLanternTask.cancel();
        saveStations();
    }

    // -------------------------------------------------------------------------
    // Station interaction
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRitualStation(PlayerInteractEvent event) {
        if (event.getAction()       != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand()         != EquipmentSlot.HAND)       return;
        if (event.getClickedBlock() == null)                      return;
        if (event.getClickedBlock().getType() != Material.CRYING_OBSIDIAN) return;

        String recipeId = stations.get(event.getClickedBlock().getLocation());
        if (recipeId == null) return;

        event.setCancelled(true);
        attemptRitual(event.getPlayer(), recipeId);
    }

    // -------------------------------------------------------------------------
    // Ritual item use
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRitualItemUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR || !held.hasItemMeta()) return;

        String ritualId = held.getItemMeta().getPersistentDataContainer()
                .get(RITUAL_ITEM_KEY, PersistentDataType.STRING);
        if (ritualId == null) return;

        event.setCancelled(true);
        useRitualItem(event.getPlayer(), held, ritualId);
    }

    // -------------------------------------------------------------------------
    // Warden Heart — cancel targeting while active
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onWardenTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Warden)) return;
        if (!(event.getTarget() instanceof Player player)) return;
        Long expiry = wardenHeartActive.get(player.getUniqueId());
        if (expiry != null && System.currentTimeMillis() < expiry) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------------------------
    // Ritual crafting logic
    // -------------------------------------------------------------------------

    private void attemptRitual(Player player, String recipeId) {
        RitualRecipe recipe = RECIPES.get(recipeId);
        if (recipe == null) {
            player.sendMessage("§cUnknown ritual: §f" + recipeId);
            return;
        }
        if (!checkConditions(player, recipe.condition())) return;
        if (!hasIngredients(player, recipe.ingredients())) {
            player.sendMessage("§cYou need:");
            recipe.ingredients().forEach((mat, amt) ->
                    player.sendMessage("  §7" + amt + "× " + formatMaterial(mat)));
            return;
        }
        consumeIngredients(player, recipe.ingredients());
        player.getInventory().addItem(createResult(recipeId));
        player.sendMessage("§d§lRitual complete. §f" + recipe.displayName() + " §7has been forged.");
        playRitualEffect(player.getLocation());
    }

    private boolean checkConditions(Player player, RitualCondition cond) {
        if (cond.minOmen() > 0) {
            int omen = VestigiumLib.getOmenAPI().getOmenScore();
            if (omen < cond.minOmen()) {
                player.sendMessage("§cThe omen must reach §f" + cond.minOmen()
                        + " §cbefore this ritual is possible. Current: §f" + omen);
                return false;
            }
        }
        if (cond.requiredSeason() != null) {
            Season current = VestigiumLib.getSeasonAPI().getCurrentSeason();
            if (current != cond.requiredSeason()) {
                player.sendMessage("§cThis ritual requires §f"
                        + capitalize(cond.requiredSeason().name()) + "§c.");
                return false;
            }
        }
        if (cond.nightOnly()) {
            long time = player.getWorld().getTime();
            if (time < 13000 || time > 23000) {
                player.sendMessage("§cThis ritual can only be performed at night.");
                return false;
            }
        }
        return true;
    }

    private boolean hasIngredients(Player player, Map<Material, Integer> ingredients) {
        for (Map.Entry<Material, Integer> entry : ingredients.entrySet()) {
            int found = 0;
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == entry.getKey()) found += item.getAmount();
            }
            if (found < entry.getValue()) return false;
        }
        return true;
    }

    private void consumeIngredients(Player player, Map<Material, Integer> ingredients) {
        for (Map.Entry<Material, Integer> entry : ingredients.entrySet()) {
            int toRemove = entry.getValue();
            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < contents.length && toRemove > 0; i++) {
                ItemStack item = contents[i];
                if (item == null || item.getType() != entry.getKey()) continue;
                if (item.getAmount() <= toRemove) {
                    toRemove -= item.getAmount();
                    player.getInventory().setItem(i, null);
                } else {
                    item.setAmount(item.getAmount() - toRemove);
                    toRemove = 0;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Result item factory
    // -------------------------------------------------------------------------

    private ItemStack createResult(String recipeId) {
        return switch (recipeId) {
            case "warden_heart"    -> makeItem(Material.ECHO_SHARD,     "§5Warden's Heart",
                    List.of("§7Suppresses Warden targeting for §f5 minutes§7.",
                            "§8Right-click to activate. 10-min recharge."), recipeId);
            case "omen_candle"     -> makeItem(Material.CANDLE,         "§6Omen Candle",
                    List.of("§7Channels omen into a §f2-minute §7buff scaled to omen level.",
                            "§8Right-click to use. 30-min cooldown."), recipeId);
            case "ancient_compass" -> makeItem(Material.COMPASS,        "§bAncient Compass",
                    List.of("§7Reads omen, season, tidal phase, and next threshold.",
                            "§8Right-click to sense. 5-min cooldown."), recipeId);
            case "season_lens"     -> makeItem(Material.AMETHYST_SHARD, "§aSeason Lens",
                    List.of("§7Grants a §f3-minute §7buff from the current season.",
                            "§8Right-click to use. 20-min cooldown."), recipeId);
            case "soul_lantern"    -> makeItem(Material.SOUL_LANTERN,   "§fSoul Lantern",
                    List.of("§7Afflicts nearby hostile mobs with §fGlowing §7and §fSlowness§7.",
                            "§8Passive while held in main hand."), recipeId);
            case "void_extract"    -> makeItem(Material.ENDER_PEARL,    "§8Void Extract",
                    List.of("§7Tears you 50–200 blocks in a random direction.",
                            "§8Causes Blindness II. Subtracts 30 omen. §cSingle use."), recipeId);
            default -> new ItemStack(Material.BARRIER);
        };
    }

    private ItemStack makeItem(Material mat, String name, List<String> lore, String ritualId) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta  = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.getPersistentDataContainer()
                .set(RITUAL_ITEM_KEY, PersistentDataType.STRING, ritualId);
        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------
    // Item activation
    // -------------------------------------------------------------------------

    private void useRitualItem(Player player, ItemStack item, String ritualId) {
        switch (ritualId) {
            case "warden_heart"    -> activateWardenHeart(player, item);
            case "omen_candle"     -> activateOmenCandle(player, item);
            case "ancient_compass" -> activateAncientCompass(player, item);
            case "season_lens"     -> activateSeasonLens(player, item);
            case "void_extract"    -> activateVoidExtract(player, item);
            // soul_lantern is passive — no right-click action
        }
    }

    private void activateWardenHeart(Player player, ItemStack item) {
        if (isOnCooldown(item)) {
            player.sendMessage("§cWarden's Heart is recharging. §f"
                    + formatTime(getCooldownRemaining(item)) + " §cremaining.");
            return;
        }
        wardenHeartActive.put(player.getUniqueId(), System.currentTimeMillis() + 300_000L);
        setCooldown(item, player, 600_000L);
        player.sendMessage("§5The Heart pulses with void resonance. §7Wardens cannot see you for §f5 minutes§7.");
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 40, 0, false, true));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.7f);
        player.getWorld().spawnParticle(Particle.SCULK_SOUL,
                player.getLocation().add(0, 1, 0), 16, 0.3, 0.5, 0.3, 0.05);
    }

    private void activateOmenCandle(Player player, ItemStack item) {
        if (isOnCooldown(item)) {
            player.sendMessage("§cOmen Candle is cooling down. §f"
                    + formatTime(getCooldownRemaining(item)) + " §cremaining.");
            return;
        }
        int omen = VestigiumLib.getOmenAPI().getOmenScore();
        PotionEffectType effectType;
        String effectName;
        int amplifier;
        if (omen >= 600) {
            effectType = PotionEffectType.STRENGTH; effectName = "§cStrength II"; amplifier = 1;
        } else if (omen >= 400) {
            effectType = PotionEffectType.STRENGTH; effectName = "§cStrength I";  amplifier = 0;
        } else if (omen >= 200) {
            effectType = PotionEffectType.SPEED;    effectName = "§eSpeed I";     amplifier = 0;
        } else {
            effectType = PotionEffectType.REGENERATION; effectName = "§aRegeneration I"; amplifier = 0;
        }
        player.addPotionEffect(new PotionEffect(effectType, 2400, amplifier));
        setCooldown(item, player, 1_800_000L);
        player.sendMessage("§6The candle flares with omen energy. §7You feel "
                + effectName + " §7for §f2 minutes§7.");
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_SCULK_SENSOR_CLICKING, 1f, 1.5f);
        player.getWorld().spawnParticle(Particle.FLAME,
                player.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.02);
    }

    private void activateAncientCompass(Player player, ItemStack item) {
        if (isOnCooldown(item)) {
            player.sendMessage("§cAncient Compass is calibrating. §f"
                    + formatTime(getCooldownRemaining(item)) + " §cremaining.");
            return;
        }
        int omen            = VestigiumLib.getOmenAPI().getOmenScore();
        int effectiveOmen   = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
        Season season       = VestigiumLib.getSeasonAPI().getCurrentSeason();
        int tidal           = VestigiumLib.getSeasonAPI().getTidalPhase();
        int[] thresholds    = {200, 400, 600, 800, 1000};
        int nextThreshold   = -1;
        for (int t : thresholds) { if (omen < t) { nextThreshold = t; break; } }

        player.sendMessage("§b§l── World State ──");
        player.sendMessage("§7Omen: §f" + omen + " §8(effective: §f" + effectiveOmen + "§8)");
        player.sendMessage("§7Next threshold: §f" + (nextThreshold == -1 ? "max" : String.valueOf(nextThreshold)));
        player.sendMessage("§7Season: §f" + capitalize(season.name()));
        player.sendMessage("§7Tidal phase: §f" + tidal);

        setCooldown(item, player, 300_000L);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1.2f);
    }

    private void activateSeasonLens(Player player, ItemStack item) {
        if (isOnCooldown(item)) {
            player.sendMessage("§cSeason Lens is recharging. §f"
                    + formatTime(getCooldownRemaining(item)) + " §cremaining.");
            return;
        }
        Season season = VestigiumLib.getSeasonAPI().getCurrentSeason();
        PotionEffectType effectType = switch (season) {
            case SPRING -> PotionEffectType.REGENERATION;
            case SUMMER -> PotionEffectType.STRENGTH;
            case AUTUMN -> PotionEffectType.FIRE_RESISTANCE;
            case WINTER -> PotionEffectType.RESISTANCE;
        };
        String effectName = switch (season) {
            case SPRING -> "§aRegeneration I";
            case SUMMER -> "§cStrength I";
            case AUTUMN -> "§6Fire Resistance";
            case WINTER -> "§bResistance I";
        };
        player.addPotionEffect(new PotionEffect(effectType, 3600, 0));
        setCooldown(item, player, 1_200_000L);
        player.sendMessage("§aThe lens focuses §f" + capitalize(season.name())
                + " §aenergy. §7You feel " + effectName + " §7for §f3 minutes§7.");
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.0f);
        player.getWorld().spawnParticle(Particle.END_ROD,
                player.getLocation().add(0, 1, 0), 16, 0.3, 0.5, 0.3, 0.05);
    }

    private void activateVoidExtract(Player player, ItemStack item) {
        ThreadLocalRandom rng   = ThreadLocalRandom.current();
        double angle  = rng.nextDouble(0, Math.PI * 2);
        double dist   = rng.nextInt(50, 201);
        double nx     = player.getLocation().getX() + Math.cos(angle) * dist;
        double nz     = player.getLocation().getZ() + Math.sin(angle) * dist;
        int ny        = player.getWorld().getHighestBlockYAt((int) nx, (int) nz) + 1;
        Location dest = new Location(player.getWorld(), nx, ny, nz,
                player.getLocation().getYaw(), player.getLocation().getPitch());

        player.teleport(dest);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1));
        VestigiumLib.getOmenAPI().subtractOmen(30);

        player.sendMessage("§8The void pulls you. §7You surface §f" + (int) dist + " §7blocks away.");
        player.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);
        player.getWorld().spawnParticle(Particle.REVERSE_PORTAL, dest, 32, 0.5, 1, 0.5, 0.1);

        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
    }

    // -------------------------------------------------------------------------
    // Crafting effect
    // -------------------------------------------------------------------------

    private void playRitualEffect(Location loc) {
        loc.getWorld().spawnParticle(Particle.END_ROD,    loc, 40, 0.5, 1.0, 0.5, 0.05);
        loc.getWorld().spawnParticle(Particle.SCULK_SOUL, loc, 20, 0.3, 0.5, 0.3, 0.05);
        loc.getWorld().playSound(loc, Sound.BLOCK_SCULK_SENSOR_CLICKING_STOP, 1f, 0.7f);
        loc.getWorld().playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 0.5f);
    }

    // -------------------------------------------------------------------------
    // Cooldown helpers (stored in item PDC so they survive server restarts)
    // -------------------------------------------------------------------------

    private boolean isOnCooldown(ItemStack item) {
        if (!item.hasItemMeta()) return false;
        Long cd = item.getItemMeta().getPersistentDataContainer()
                .get(RITUAL_COOLDOWN_KEY, PersistentDataType.LONG);
        return cd != null && System.currentTimeMillis() < cd;
    }

    private long getCooldownRemaining(ItemStack item) {
        if (!item.hasItemMeta()) return 0;
        Long cd = item.getItemMeta().getPersistentDataContainer()
                .get(RITUAL_COOLDOWN_KEY, PersistentDataType.LONG);
        return cd == null ? 0 : Math.max(0, cd - System.currentTimeMillis());
    }

    private void setCooldown(ItemStack item, Player player, long durationMs) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer()
                .set(RITUAL_COOLDOWN_KEY, PersistentDataType.LONG,
                        System.currentTimeMillis() + durationMs);
        item.setItemMeta(meta);
        player.getInventory().setItemInMainHand(item);
    }

    // -------------------------------------------------------------------------
    // Soul Lantern check
    // -------------------------------------------------------------------------

    private boolean playerHasSoulLantern(Player player) {
        return isRitualItem(player.getInventory().getItemInMainHand(), "soul_lantern");
    }

    private boolean isRitualItem(ItemStack item, String id) {
        if (item == null || !item.hasItemMeta()) return false;
        String stored = item.getItemMeta().getPersistentDataContainer()
                .get(RITUAL_ITEM_KEY, PersistentDataType.STRING);
        return id.equals(stored);
    }

    // -------------------------------------------------------------------------
    // Station persistence
    // -------------------------------------------------------------------------

    private File stationFile() {
        return new File(plugin.getDataFolder(), "ritual_stations.yml");
    }

    private void loadStations() {
        File f = stationFile();
        if (!f.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        for (String key : cfg.getKeys(false)) {
            String recipeId = cfg.getString(key + ".recipe");
            World  world    = Bukkit.getWorld(cfg.getString(key + ".world", "world"));
            if (world == null || recipeId == null) continue;
            double x = cfg.getDouble(key + ".x");
            double y = cfg.getDouble(key + ".y");
            double z = cfg.getDouble(key + ".z");
            stations.put(new Location(world, x, y, z), recipeId);
        }
    }

    private void saveStations() {
        YamlConfiguration cfg = new YamlConfiguration();
        int i = 0;
        for (Map.Entry<Location, String> e : stations.entrySet()) {
            String key = "station_" + i++;
            cfg.set(key + ".world",  e.getKey().getWorld().getName());
            cfg.set(key + ".x",      e.getKey().getX());
            cfg.set(key + ".y",      e.getKey().getY());
            cfg.set(key + ".z",      e.getKey().getZ());
            cfg.set(key + ".recipe", e.getValue());
        }
        try { cfg.save(stationFile()); } catch (IOException ex) {
            plugin.getLogger().warning("[RitualManager] Failed to save stations: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Command — /veritual
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7/veritual <recipes | station <set <id> | remove | list> | give <recipe> [player]>");
            return true;
        }
        switch (args[0].toLowerCase()) {

            case "recipes" -> {
                sender.sendMessage("§d§l── Ritual Recipes ──");
                RECIPES.forEach((id, recipe) -> {
                    sender.sendMessage(recipe.displayName() + " §8(" + id + ")");
                    recipe.ingredients().forEach((mat, amt) ->
                            sender.sendMessage("    §8" + amt + "× §7" + formatMaterial(mat)));
                    RitualCondition c = recipe.condition();
                    if (c.minOmen() > 0)
                        sender.sendMessage("    §8Requires omen ≥ §7" + c.minOmen());
                    if (c.requiredSeason() != null)
                        sender.sendMessage("    §8Season: §7" + capitalize(c.requiredSeason().name()));
                    if (c.nightOnly())
                        sender.sendMessage("    §8Night only");
                });
            }

            case "station" -> {
                if (!sender.hasPermission("vestigium.ritual.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§7/veritual station <set <id> | remove | list>"); return true;
                }
                switch (args[1].toLowerCase()) {

                    case "set" -> {
                        if (!(sender instanceof Player player)) {
                            sender.sendMessage("§cPlayers only."); return true;
                        }
                        if (args.length < 3) {
                            player.sendMessage("§7/veritual station set <recipeId>"); return true;
                        }
                        String recipeId = args[2];
                        if (!RECIPES.containsKey(recipeId)) {
                            player.sendMessage("§cUnknown recipe: §f" + recipeId); return true;
                        }
                        var target = player.getTargetBlockExact(5);
                        if (target == null || target.getType() != Material.CRYING_OBSIDIAN) {
                            player.sendMessage("§cLook at a §fCrying Obsidian §cblock."); return true;
                        }
                        stations.put(target.getLocation(), recipeId);
                        saveStations();
                        player.sendMessage("§aStation set for §f" + recipeId + "§a.");
                    }

                    case "remove" -> {
                        if (!(sender instanceof Player player)) {
                            sender.sendMessage("§cPlayers only."); return true;
                        }
                        var target = player.getTargetBlockExact(5);
                        if (target == null || target.getType() != Material.CRYING_OBSIDIAN) {
                            player.sendMessage("§cLook at a §fCrying Obsidian §cblock."); return true;
                        }
                        if (stations.remove(target.getLocation()) != null) {
                            saveStations();
                            player.sendMessage("§aStation removed.");
                        } else {
                            player.sendMessage("§cNo station at that block.");
                        }
                    }

                    case "list" -> {
                        if (stations.isEmpty()) {
                            sender.sendMessage("§7No ritual stations registered."); return true;
                        }
                        sender.sendMessage("§d§l── Ritual Stations ──");
                        stations.forEach((loc, rid) ->
                                sender.sendMessage("§7" + rid + " §8at §7"
                                        + loc.getWorld().getName() + " "
                                        + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()));
                    }

                    default -> sender.sendMessage("§7/veritual station <set <id> | remove | list>");
                }
            }

            case "give" -> {
                if (!sender.hasPermission("vestigium.ritual.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§7/veritual give <recipe> [player]"); return true;
                }
                String recipeId = args[1];
                if (!RECIPES.containsKey(recipeId)) {
                    sender.sendMessage("§cUnknown recipe: §f" + recipeId); return true;
                }
                Player target = args.length >= 3
                        ? plugin.getServer().getPlayer(args[2])
                        : (sender instanceof Player p ? p : null);
                if (target == null) {
                    sender.sendMessage("§cPlayer not found."); return true;
                }
                target.getInventory().addItem(createResult(recipeId));
                sender.sendMessage("§aGave §f" + recipeId + " §ato §f" + target.getName() + "§a.");
            }

            default -> sender.sendMessage(
                    "§7/veritual <recipes | station <set <id> | remove | list> | give <recipe> [player]>");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String formatMaterial(Material m) {
        String s = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private static String formatTime(long ms) {
        long secs = ms / 1000;
        return secs < 60 ? secs + "s" : (secs / 60) + "m " + (secs % 60) + "s";
    }
}
