package com.vestigium.vestigiumeconomy.alchemy;

import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.*;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Alchemy Expansion — multi-ingredient brewing at registered Alembic stations
 * (Brewing Stands tagged via admin command/designator item).
 *
 * Unknown effects: each flask hides which outcome from its pool was rolled until
 * the player consumes it. The recipe name is visible; the specific effect is not.
 * Failed batches (per-recipe failure chance) deal negative effects.
 *
 * PDC keys on result flasks:
 *   vestigium:alchemy_recipe   STRING  — recipe ID (shown before consuming)
 *   vestigium:alchemy_effect   STRING  — encoded effects (hidden until consuming)
 *   vestigium:alchemy_failed   BYTE    — 1 if this is a corrupted batch
 *
 * Station designator item (CMD 40004): right-click a Brewing Stand to register it.
 * Stations persisted to plugins/VestigiumEconomy/alchemy_stations.yml.
 */
public class AlchemyManager implements Listener, CommandExecutor {

    private static final NamespacedKey FLASK_RECIPE_KEY   = new NamespacedKey("vestigium", "alchemy_recipe");
    private static final NamespacedKey FLASK_EFFECT_KEY   = new NamespacedKey("vestigium", "alchemy_effect");
    private static final NamespacedKey FLASK_FAILED_KEY   = new NamespacedKey("vestigium", "alchemy_failed");
    private static final NamespacedKey DESIGNATOR_KEY     = new NamespacedKey("vestigium", "alchemy_designator");

    public static final int FLASK_CMD             = 30001;
    public static final int STATION_DESIGNATOR_CMD = 40004;

    // -------------------------------------------------------------------------
    // Data model
    // -------------------------------------------------------------------------

    record AlchemyEffect(PotionEffectType type, int durationTicks, int amplifier) {

        String encode() {
            return type.getKey().getKey().toUpperCase() + ":" + durationTicks + ":" + amplifier;
        }

        static AlchemyEffect decode(String s) {
            String[] p = s.split(":");
            PotionEffectType t = Registry.EFFECT.get(NamespacedKey.minecraft(p[0].toLowerCase()));
            if (t == null) throw new IllegalArgumentException("Unknown effect: " + p[0]);
            return new AlchemyEffect(t, Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        }

        String displayName() {
            String[] words = type.getKey().getKey().split("_");
            return Arrays.stream(words)
                    .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                    .collect(Collectors.joining(" "));
        }

        String durationStr() {
            int s = durationTicks / 20;
            return s < 60 ? s + "s" : (s / 60) + "m";
        }
    }

    record AlchemyOutcome(List<AlchemyEffect> effects) {
        String encode() {
            return effects.stream().map(AlchemyEffect::encode).collect(Collectors.joining(","));
        }

        static List<AlchemyEffect> decodeEffects(String encoded) {
            return Arrays.stream(encoded.split(","))
                    .map(AlchemyEffect::decode)
                    .collect(Collectors.toList());
        }

        String summary() {
            return effects.stream()
                    .map(e -> e.displayName() + " (" + e.durationStr() + ")")
                    .collect(Collectors.joining(" + "));
        }
    }

    record AlchemyRecipe(String id, String displayName, String colorCode,
                         Map<Material, Integer> ingredients,
                         List<AlchemyOutcome> successOutcomes,
                         AlchemyOutcome failureOutcome,
                         int failureChancePct) {}

    private static final Map<String, AlchemyRecipe> RECIPES = new LinkedHashMap<>();

    static {
        RECIPES.put("echo_draught", new AlchemyRecipe(
                "echo_draught", "Echo Draught", "§9",
                Map.of(Material.ECHO_SHARD, 1, Material.GLOWSTONE_DUST, 2,
                        Material.FERMENTED_SPIDER_EYE, 1),
                List.of(
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.NIGHT_VISION, 12000, 0))),
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.NIGHT_VISION, 12000, 0),
                                new AlchemyEffect(PotionEffectType.SPEED, 600, 0)))),
                new AlchemyOutcome(List.of(
                        new AlchemyEffect(PotionEffectType.BLINDNESS, 600, 0),
                        new AlchemyEffect(PotionEffectType.NAUSEA, 200, 0))),
                20));

        RECIPES.put("verdant_tonic", new AlchemyRecipe(
                "verdant_tonic", "Verdant Tonic", "§a",
                Map.of(Material.VINE, 4, Material.HONEY_BOTTLE, 1, Material.SPIDER_EYE, 2),
                List.of(
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.REGENERATION, 2400, 1))),
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.SPEED, 2400, 1))),
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.REGENERATION, 4800, 0),
                                new AlchemyEffect(PotionEffectType.SPEED, 4800, 0)))),
                new AlchemyOutcome(List.of(
                        new AlchemyEffect(PotionEffectType.POISON, 200, 1),
                        new AlchemyEffect(PotionEffectType.SLOWNESS, 300, 1))),
                25));

        RECIPES.put("void_serum", new AlchemyRecipe(
                "void_serum", "Void Serum", "§8",
                Map.of(Material.CHORUS_FRUIT, 3, Material.END_STONE, 4, Material.ENDER_PEARL, 1),
                List.of(
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.RESISTANCE, 6000, 0),
                                new AlchemyEffect(PotionEffectType.SLOW_FALLING, 6000, 0))),
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.SLOW_FALLING, 9600, 0)))),
                new AlchemyOutcome(List.of(
                        new AlchemyEffect(PotionEffectType.LEVITATION, 200, 1),
                        new AlchemyEffect(PotionEffectType.BLINDNESS, 300, 0))),
                15));

        RECIPES.put("tidal_essence", new AlchemyRecipe(
                "tidal_essence", "Tidal Essence", "§3",
                Map.of(Material.PRISMARINE_SHARD, 4, Material.KELP, 8,
                        Material.NAUTILUS_SHELL, 1),
                List.of(
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.WATER_BREATHING, 18000, 0))),
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.DOLPHINS_GRACE, 6000, 0),
                                new AlchemyEffect(PotionEffectType.WATER_BREATHING, 6000, 0))),
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.CONDUIT_POWER, 3600, 0)))),
                new AlchemyOutcome(List.of(
                        new AlchemyEffect(PotionEffectType.MINING_FATIGUE, 600, 0),
                        new AlchemyEffect(PotionEffectType.SLOWNESS, 600, 0))),
                20));

        RECIPES.put("ember_flask", new AlchemyRecipe(
                "ember_flask", "Ember Flask", "§6",
                Map.of(Material.BLAZE_POWDER, 4, Material.MAGMA_CREAM, 2,
                        Material.FIRE_CHARGE, 1),
                List.of(
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.FIRE_RESISTANCE, 12000, 0))),
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.STRENGTH, 6000, 0),
                                new AlchemyEffect(PotionEffectType.FIRE_RESISTANCE, 6000, 0))),
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.STRENGTH, 3600, 1)))),
                new AlchemyOutcome(List.of(
                        new AlchemyEffect(PotionEffectType.WITHER, 200, 0),
                        new AlchemyEffect(PotionEffectType.WEAKNESS, 600, 0))),
                30));

        RECIPES.put("warden_ichor", new AlchemyRecipe(
                "warden_ichor", "Warden Ichor", "§5",
                Map.of(Material.ECHO_SHARD, 3, Material.SCULK_CATALYST, 2,
                        Material.SCULK_VEIN, 8),
                List.of(
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.STRENGTH, 12000, 1))),
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.RESISTANCE, 12000, 0),
                                new AlchemyEffect(PotionEffectType.NIGHT_VISION, 18000, 0))),
                        new AlchemyOutcome(List.of(
                                new AlchemyEffect(PotionEffectType.INVISIBILITY, 6000, 0),
                                new AlchemyEffect(PotionEffectType.STRENGTH, 6000, 0)))),
                new AlchemyOutcome(List.of(
                        new AlchemyEffect(PotionEffectType.DARKNESS, 2400, 0),
                        new AlchemyEffect(PotionEffectType.WEAKNESS, 2400, 1),
                        new AlchemyEffect(PotionEffectType.WITHER, 600, 1))),
                35));
    }

    // -------------------------------------------------------------------------
    // Manager state
    // -------------------------------------------------------------------------

    private final VestigiumEconomy plugin;
    private final Set<Location> stations = new HashSet<>();

    public AlchemyManager(VestigiumEconomy plugin) {
        this.plugin = plugin;
    }

    public void init() {
        loadStations();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("vealchemy");
        if (cmd != null) cmd.setExecutor(this);
        plugin.getLogger().info("[AlchemyManager] Initialized — " + RECIPES.size() + " recipes.");
    }

    public void shutdown() {
        saveStations();
    }

    // -------------------------------------------------------------------------
    // Station interaction
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrewingStandInteract(PlayerInteractEvent event) {
        if (event.getAction()       != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand()         != EquipmentSlot.HAND)       return;
        if (event.getClickedBlock() == null)                      return;
        if (event.getClickedBlock().getType() != Material.BREWING_STAND) return;

        Player    player = event.getPlayer();
        ItemStack held   = player.getInventory().getItemInMainHand();

        // Station designator: register this Brewing Stand
        if (isDesignatorItem(held)) {
            event.setCancelled(true);
            Location loc = event.getClickedBlock().getLocation();
            stations.add(loc);
            saveStations();
            player.sendMessage("§6Alembic station registered at §f"
                    + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "§6.");
            player.getWorld().playSound(loc, Sound.BLOCK_BREWING_STAND_BREW, 1f, 1.2f);
            return;
        }

        // Registered station: attempt brew
        if (!stations.contains(event.getClickedBlock().getLocation())) return;
        event.setCancelled(true);
        openAlchemyMenu(player);
    }

    // -------------------------------------------------------------------------
    // Flask consumption
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlaskConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (!item.hasItemMeta()) return;
        String recipeId = item.getItemMeta().getPersistentDataContainer()
                .get(FLASK_RECIPE_KEY, PersistentDataType.STRING);
        if (recipeId == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        String encoded = item.getItemMeta().getPersistentDataContainer()
                .get(FLASK_EFFECT_KEY, PersistentDataType.STRING);
        byte failed = item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(FLASK_FAILED_KEY, PersistentDataType.BYTE, (byte) 0);

        // Apply effects
        List<AlchemyEffect> effects = AlchemyOutcome.decodeEffects(encoded);
        for (AlchemyEffect ae : effects) {
            player.addPotionEffect(new PotionEffect(ae.type(), ae.durationTicks(), ae.amplifier()));
        }

        // Reveal message
        AlchemyRecipe recipe = RECIPES.get(recipeId);
        String color = recipe != null ? recipe.colorCode() : "§7";
        String recipeName = recipe != null ? recipe.displayName() : recipeId;
        String summary = effects.stream()
                .map(e -> e.displayName() + " (" + e.durationStr() + ")")
                .collect(Collectors.joining(" + "));

        if (failed == 1) {
            player.sendMessage("§cThe batch was corrupted. §7You feel: §f" + summary);
            player.getWorld().playSound(player.getLocation(),
                    Sound.ENTITY_WITCH_DRINK, 1f, 0.6f);
        } else {
            player.sendMessage(color + "The " + recipeName + " §7manifests: §f" + summary);
            player.getWorld().playSound(player.getLocation(),
                    Sound.ENTITY_WITCH_DRINK, 1f, 1.4f);
        }

        // Consume one flask
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(new ItemStack(Material.GLASS_BOTTLE));
        }
    }

    // -------------------------------------------------------------------------
    // Alchemy logic
    // -------------------------------------------------------------------------

    private void openAlchemyMenu(Player player) {
        player.sendMessage("§6§l── Alembic ──");
        player.sendMessage("§7Available recipes (right-click station while holding ingredients):");
        for (AlchemyRecipe r : RECIPES.values()) {
            boolean canBrew = hasIngredients(player, r.ingredients());
            String marker = canBrew ? "§a✔" : "§c✘";
            player.sendMessage("  " + marker + " " + r.colorCode() + r.displayName());
        }
        player.sendMessage("§7Use §f/vealchemy brew <recipe> §7to attempt a brew.");
    }

    void attemptBrew(Player player, String recipeId) {
        AlchemyRecipe recipe = RECIPES.get(recipeId);
        if (recipe == null) {
            player.sendMessage("§cUnknown recipe: §f" + recipeId); return;
        }
        if (!hasIngredients(player, recipe.ingredients())) {
            player.sendMessage("§cYou are missing ingredients for §f" + recipe.displayName() + "§c:");
            recipe.ingredients().forEach((mat, amt) ->
                    player.sendMessage("  §7" + amt + "× " + formatMaterial(mat)));
            return;
        }
        consumeIngredients(player, recipe.ingredients());

        boolean failed = ThreadLocalRandom.current().nextInt(100) < recipe.failureChancePct();
        AlchemyOutcome outcome = failed
                ? recipe.failureOutcome()
                : recipe.successOutcomes().get(
                        ThreadLocalRandom.current().nextInt(recipe.successOutcomes().size()));

        ItemStack flask = createFlask(recipe, outcome, failed);
        player.getInventory().addItem(flask);

        if (failed) {
            player.sendMessage("§c§oSomething went wrong during the brew...");
        } else {
            player.sendMessage("§6The alembic steams. §7An unknown §f" + recipe.displayName()
                    + " §7has been prepared.");
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 1f, 1.0f);
        player.getWorld().spawnParticle(Particle.WITCH,
                player.getLocation().add(0, 1.5, 0), 20, 0.3, 0.5, 0.3, 0.05);
    }

    private ItemStack createFlask(AlchemyRecipe recipe, AlchemyOutcome outcome, boolean failed) {
        ItemStack item = new ItemStack(Material.GLASS_BOTTLE);
        ItemMeta meta  = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(recipe.colorCode() + "?? " + recipe.displayName() + " ??");
        List<String> lore = new ArrayList<>();
        lore.add("§8Effect: §7unknown until consumed");
        if (failed) lore.add("§c§o[Corrupted Batch]");
        meta.setLore(lore);

        var pdc = meta.getPersistentDataContainer();
        pdc.set(FLASK_RECIPE_KEY, PersistentDataType.STRING, recipe.id());
        pdc.set(FLASK_EFFECT_KEY, PersistentDataType.STRING, outcome.encode());
        pdc.set(FLASK_FAILED_KEY, PersistentDataType.BYTE, failed ? (byte) 1 : (byte) 0);

        // Custom model data for resource pack
        meta.setCustomModelData(FLASK_CMD);

        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------
    // Ingredient helpers
    // -------------------------------------------------------------------------

    private boolean hasIngredients(Player player, Map<Material, Integer> needed) {
        for (Map.Entry<Material, Integer> e : needed.entrySet()) {
            int found = 0;
            for (ItemStack s : player.getInventory().getContents()) {
                if (s != null && s.getType() == e.getKey()) found += s.getAmount();
            }
            if (found < e.getValue()) return false;
        }
        return true;
    }

    private void consumeIngredients(Player player, Map<Material, Integer> needed) {
        for (Map.Entry<Material, Integer> e : needed.entrySet()) {
            int toRemove = e.getValue();
            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < contents.length && toRemove > 0; i++) {
                ItemStack s = contents[i];
                if (s == null || s.getType() != e.getKey()) continue;
                if (s.getAmount() <= toRemove) {
                    toRemove -= s.getAmount();
                    player.getInventory().setItem(i, null);
                } else {
                    s.setAmount(s.getAmount() - toRemove);
                    toRemove = 0;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Designator item
    // -------------------------------------------------------------------------

    public ItemStack createStationDesignator() {
        ItemStack item = new ItemStack(Material.BREWING_STAND);
        ItemMeta meta  = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName("§6§lAlembic Set §8[Alchemy Station]");
        meta.setLore(List.of(
                "§7Right-click a §fBrewing Stand §7to register it as an alchemy station.",
                "§8Admin use only."));
        meta.setCustomModelData(STATION_DESIGNATOR_CMD);
        meta.getPersistentDataContainer()
                .set(DESIGNATOR_KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isDesignatorItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(DESIGNATOR_KEY, PersistentDataType.BYTE);
    }

    // -------------------------------------------------------------------------
    // Station persistence
    // -------------------------------------------------------------------------

    private File stationFile() {
        return new File(plugin.getDataFolder(), "alchemy_stations.yml");
    }

    private void loadStations() {
        File f = stationFile();
        if (!f.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        for (String key : cfg.getKeys(false)) {
            World world = Bukkit.getWorld(cfg.getString(key + ".world", "world"));
            if (world == null) continue;
            double x = cfg.getDouble(key + ".x");
            double y = cfg.getDouble(key + ".y");
            double z = cfg.getDouble(key + ".z");
            stations.add(new Location(world, x, y, z));
        }
    }

    private void saveStations() {
        YamlConfiguration cfg = new YamlConfiguration();
        int i = 0;
        for (Location loc : stations) {
            String key = "station_" + i++;
            cfg.set(key + ".world", loc.getWorld().getName());
            cfg.set(key + ".x",     loc.getX());
            cfg.set(key + ".y",     loc.getY());
            cfg.set(key + ".z",     loc.getZ());
        }
        try { cfg.save(stationFile()); } catch (IOException ex) {
            plugin.getLogger().warning("[AlchemyManager] Failed to save stations: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Command — /vealchemy
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7/vealchemy <recipes | brew <id> | station <give [player] | remove | list>>");
            return true;
        }
        switch (args[0].toLowerCase()) {

            case "recipes" -> {
                sender.sendMessage("§6§l── Alchemy Recipes ──");
                RECIPES.forEach((id, r) -> {
                    sender.sendMessage(r.colorCode() + r.displayName() + " §8(" + id + ")");
                    r.ingredients().forEach((mat, amt) ->
                            sender.sendMessage("    §8" + amt + "× §7" + formatMaterial(mat)));
                    sender.sendMessage("    §8Failure chance: §7" + r.failureChancePct() + "%");
                    sender.sendMessage("    §8Success outcomes: §7" + r.successOutcomes().size());
                });
            }

            case "brew" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cPlayers only."); return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§7/vealchemy brew <recipeId>"); return true;
                }
                attemptBrew(player, args[1]);
            }

            case "station" -> {
                if (!sender.hasPermission("vestigium.alchemy.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§7/vealchemy station <give [player] | remove | list>"); return true;
                }
                switch (args[1].toLowerCase()) {

                    case "give" -> {
                        Player target = args.length >= 3
                                ? plugin.getServer().getPlayer(args[2])
                                : (sender instanceof Player p ? p : null);
                        if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                        target.getInventory().addItem(createStationDesignator());
                        sender.sendMessage("§aGave Alembic Set to §f" + target.getName() + "§a.");
                    }

                    case "remove" -> {
                        if (!(sender instanceof Player player)) {
                            sender.sendMessage("§cPlayers only."); return true;
                        }
                        var target = player.getTargetBlockExact(5);
                        if (target == null || target.getType() != Material.BREWING_STAND) {
                            player.sendMessage("§cLook at a §fBrewing Stand §cto remove a station."); return true;
                        }
                        if (stations.remove(target.getLocation())) {
                            saveStations();
                            player.sendMessage("§aStation removed.");
                        } else {
                            player.sendMessage("§cNo alchemy station at that block.");
                        }
                    }

                    case "list" -> {
                        if (stations.isEmpty()) {
                            sender.sendMessage("§7No alchemy stations registered.");
                        } else {
                            sender.sendMessage("§6§l── Alchemy Stations ──");
                            stations.forEach(loc -> sender.sendMessage("§7"
                                    + loc.getWorld().getName() + " "
                                    + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()));
                        }
                    }

                    default -> sender.sendMessage("§7/vealchemy station <give [player] | remove | list>");
                }
            }

            default -> sender.sendMessage(
                    "§7/vealchemy <recipes | brew <id> | station <give [player] | remove | list>>");
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
}
