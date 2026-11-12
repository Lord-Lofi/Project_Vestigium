package com.vestigium.vestigiumocean.civilization;

import com.vestigium.lib.VestigiumLib;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.merchant.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class DrownedCivilizationManager implements Listener, CommandExecutor, TabCompleter {

    // ── Entity PDC keys ────────────────────────────────────────────────────────
    private static final NamespacedKey NPC_TYPE_KEY = new NamespacedKey("vestigium", "drowned_civ_npc");
    private static final NamespacedKey NPC_SITE_KEY = new NamespacedKey("vestigium", "civ_site_id");

    // ── Player PDC keys ────────────────────────────────────────────────────────
    private static final NamespacedKey VISITED_KEY       = new NamespacedKey("vestigium", "civ_visited");
    private static final NamespacedKey T4_UNLOCKED_KEY   = new NamespacedKey("vestigium", "civ_t4_unlocked");
    private static final NamespacedKey RECOGNITION_KEY   = new NamespacedKey("vestigium", "recognition_achieved");

    // ── NPC type identifiers ───────────────────────────────────────────────────
    private static final String NPC_REFUGEE  = "refugee";
    private static final String NPC_CRAFTER  = "crafter";
    private static final String NPC_ARCHIVIST = "archivist";
    private static final String NPC_WARDEN   = "warden";

    // ── Refugee dialogue tiers ─────────────────────────────────────────────────
    private static final String[][] REFUGEE_DIALOGUE = {
        {   // interaction 0
            "§8[Drowned] §7...you move above the silt. Not many do.",
            "§8[Drowned] §7We do not speak of the surface. We remember it differently now."
        },
        {   // interaction 1
            "§8[Drowned] §7The Current Engine still runs. We feel it in the stone.",
            "§8[Drowned] §7It was not built for warmth. It was built for memory."
        },
        {   // interaction 2
            "§8[Drowned] §7The Archive holds what the surface forgot.",
            "§8[Drowned] §7If you have read from it, you carry something now. Be careful with it."
        },
        {   // interaction 3
            "§8[Drowned] §7The Door at the fourth depth is not locked.",
            "§8[Drowned] §7It is waiting. There is a difference."
        }
    };

    private static final String[] TIER_AMBIENT = {
        "§8The structure below holds its shape against the current.",
        "§8Carved stone. Intentional. Someone descended here with a plan.",
        "§8The water is colder here than outside the ruin.",
        "§8Bioluminescent moss lines the walls in deliberate rows.",
        "§8The silence here is different from the silence above."
    };

    private static final String[] ENGINE_AMBIENT = {
        "§8The current passes through you like a current through stone.",
        "§8The engine does not stop. It has not stopped in recorded time.",
        "§8You feel the hum before you hear it.",
        "§8The water moves in structured channels here. Deliberate architecture."
    };

    private final JavaPlugin plugin;
    private final Map<String, CivSite> sites = new LinkedHashMap<>();
    private BukkitTask proximityTask;
    private File dataFile;

    public DrownedCivilizationManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getCommand("vcivil").setExecutor(this);
        plugin.getCommand("vcivil").setTabCompleter(this);

        dataFile = new File(plugin.getDataFolder(), "drowned_civ.yml");
        plugin.getDataFolder().mkdirs();
        loadSites();

        proximityTask = new BukkitRunnable() {
            @Override public void run() { tickProximity(); }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public void shutdown() {
        if (proximityTask != null) proximityTask.cancel();
        saveSites();
    }

    // ── Proximity tick ─────────────────────────────────────────────────────────

    private void tickProximity() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            for (CivSite site : sites.values()) {
                Location center = site.center();
                if (center == null) continue;
                if (!Objects.equals(player.getWorld().getName(), center.getWorld().getName())) continue;
                if (player.getLocation().distance(center) > site.radius()) continue;

                checkFirstVisit(player, site);
                applyPassives(player, site);
            }
        }
    }

    // Fragment is the first-visit guard — returns immediately once granted.
    // T4 locked: hasVisited gates the "seam does not yield" message so it shows once,
    // not every tick. When the player later gets unlocked, hasFragment is still false
    // so they reach the unlocked branch and receive the discovery content.
    private void checkFirstVisit(Player player, CivSite site) {
        String fragmentId = "drowned_civ_tier" + site.tier() + "_" + site.id();
        if (VestigiumLib.getLoreRegistry().hasFragment(player.getUniqueId(), fragmentId)) return;

        switch (site.tier()) {
            case 1 -> {
                VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), fragmentId);
                player.sendMessage("§8§o" + site.displayName() + " §8— " + TIER_AMBIENT[new Random().nextInt(TIER_AMBIENT.length)]);
                player.sendMessage("§8Discovered: §7" + site.displayName());
                player.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_AMBIENT, 0.6f, 0.8f);
            }
            case 2 -> {
                VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), fragmentId);
                player.sendMessage("§3" + site.displayName() + " §8— §7The architecture deepens.");
                spawnDiscoveryParticles(player, site.center(), Particle.BUBBLE_COLUMN_UP, 30, 4.0);
                player.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_ACTIVATE, 0.7f, 0.9f);
            }
            case 3 -> {
                VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), fragmentId);
                player.sendMessage("§b§l" + site.displayName() + " §r§8— §7The bioluminescence responds to your presence.");
                spawnDiscoveryParticles(player, site.center(), Particle.DRIPPING_WATER, 50, 5.0);
                player.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_AMBIENT_SHORT, 0.9f, 1.2f);
                checkRecognition(player);
            }
            case 4 -> {
                boolean unlocked = player.getPersistentDataContainer()
                        .getOrDefault(T4_UNLOCKED_KEY, PersistentDataType.BYTE, (byte) 0) == 1;
                if (unlocked) {
                    VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), fragmentId);
                    player.sendMessage("§d§l" + site.displayName() + " §r§8— §7The seam yields. You were expected.");
                    player.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_ATTACK_DAMAGE, 0.8f, 0.6f);
                    spawnDiscoveryParticles(player, site.center(), Particle.END_ROD, 60, 6.0);
                } else if (!hasVisited(player, site.id())) {
                    markVisited(player, site.id());
                    player.sendMessage("§8The seam does not yield. Something in the architecture recognises a condition not yet met.");
                }
            }
        }
    }

    private void applyPassives(Player player, CivSite site) {
        if (site.tier() == 3 && "engine".equals(site.id())) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 60, 0, true, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,           60, 0, true, false));

            if (Math.random() < 0.05) {
                player.sendMessage("§8§o" + ENGINE_AMBIENT[new Random().nextInt(ENGINE_AMBIENT.length)]);
            }
        }
    }

    private void checkRecognition(Player player) {
        boolean alreadyRecognised = player.getPersistentDataContainer()
                .getOrDefault(RECOGNITION_KEY, PersistentDataType.BYTE, (byte) 0) == 1;
        if (alreadyRecognised) return;

        int deepArchiveFrags = VestigiumLib.getLoreRegistry()
                .getFragmentCount(player.getUniqueId(), "deep_archive_alpha");
        int cartographerFrags = VestigiumLib.getLoreRegistry()
                .getFragmentCount(player.getUniqueId(), "cartographer");

        if (deepArchiveFrags > 0 && cartographerFrags > 0) {
            player.getPersistentDataContainer()
                    .set(RECOGNITION_KEY, PersistentDataType.BYTE, (byte) 1);
            VestigiumLib.getLoreRegistry()
                    .grantFragment(player.getUniqueId(), "archive_of_descent_recognition");
            player.sendMessage("");
            player.sendMessage("§b§l[ Archive Recognition ]");
            player.sendMessage("§7The Archive identifies prior cartographic exposure in your record.");
            player.sendMessage("§7You are not anonymous here.");
            player.sendMessage("");
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        }
    }

    private void spawnDiscoveryParticles(Player player, Location center, Particle type, int count, double radius) {
        if (center == null) return;
        World world = center.getWorld();
        if (world == null) return;
        Random rng = new Random();
        for (int i = 0; i < count; i++) {
            double dx = (rng.nextDouble() * 2 - 1) * radius;
            double dy = rng.nextDouble() * 3;
            double dz = (rng.nextDouble() * 2 - 1) * radius;
            world.spawnParticle(type, center.clone().add(dx, dy, dz), 1, 0, 0, 0, 0);
        }
    }

    // ── NPC Interaction ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Entity entity = event.getRightClicked();
        String npcType = entity.getPersistentDataContainer()
                .get(NPC_TYPE_KEY, PersistentDataType.STRING);
        if (npcType == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        String siteId = entity.getPersistentDataContainer()
                .getOrDefault(NPC_SITE_KEY, PersistentDataType.STRING, "unknown");

        switch (npcType) {
            case NPC_REFUGEE  -> handleRefugee(player, entity, siteId);
            case NPC_CRAFTER  -> handleCrafter(player);
            case NPC_ARCHIVIST -> handleArchivist(player, siteId);
            case NPC_WARDEN   -> handleWarden(player);
        }
    }

    private void handleRefugee(Player player, Entity entity, String siteId) {
        String countKey = "refugee_" + siteId + "_count";
        NamespacedKey ck = new NamespacedKey("vestigium", countKey);
        int count = player.getPersistentDataContainer()
                .getOrDefault(ck, PersistentDataType.INTEGER, 0);

        String[] lines = REFUGEE_DIALOGUE[Math.min(count, REFUGEE_DIALOGUE.length - 1)];
        for (String line : lines) player.sendMessage(line);

        player.getPersistentDataContainer()
                .set(ck, PersistentDataType.INTEGER, Math.min(count + 1, REFUGEE_DIALOGUE.length - 1));
        player.playSound(player.getLocation(), Sound.ENTITY_DROWNED_AMBIENT_WATER, 0.7f, 0.9f);
    }

    private void handleCrafter(Player player) {
        Merchant merchant = plugin.getServer().createMerchant("§3Drowned Crafter");
        List<MerchantRecipe> recipes = new ArrayList<>();

        // kelp → dried kelp block
        MerchantRecipe r1 = new MerchantRecipe(new ItemStack(Material.DRIED_KELP_BLOCK), 64);
        r1.addIngredient(new ItemStack(Material.KELP, 9));
        r1.setMaxUses(32);
        r1.setHasExperienceReward(false);
        recipes.add(r1);

        // prismarine shard + emerald → nautilus shell
        MerchantRecipe r2 = new MerchantRecipe(new ItemStack(Material.NAUTILUS_SHELL), 16);
        r2.addIngredient(new ItemStack(Material.PRISMARINE_SHARD, 8));
        r2.addIngredient(new ItemStack(Material.EMERALD, 2));
        r2.setMaxUses(16);
        r2.setHasExperienceReward(false);
        recipes.add(r2);

        // prismarine crystals + emerald → sea lantern
        MerchantRecipe r3 = new MerchantRecipe(new ItemStack(Material.SEA_LANTERN), 8);
        r3.addIngredient(new ItemStack(Material.PRISMARINE_CRYSTALS, 4));
        r3.addIngredient(new ItemStack(Material.EMERALD, 1));
        r3.setMaxUses(32);
        r3.setHasExperienceReward(false);
        recipes.add(r3);

        // rare: nautilus shells + emerald → conduit
        MerchantRecipe r4 = new MerchantRecipe(new ItemStack(Material.CONDUIT), 1);
        r4.addIngredient(new ItemStack(Material.NAUTILUS_SHELL, 8));
        r4.addIngredient(new ItemStack(Material.EMERALD, 16));
        r4.setMaxUses(1);
        r4.setHasExperienceReward(true);
        recipes.add(r4);

        merchant.setRecipes(recipes);
        player.openMerchant(merchant, false);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 0.8f, 1.0f);
    }

    private void handleArchivist(Player player, String siteId) {
        String lore = VestigiumLib.getLoreRegistry()
                .getLoreContent("drowned_civilization", siteId.isEmpty() ? "archive_intro" : siteId + "_archive");
        if (lore.isEmpty()) lore = "§8The archivist gestures at the wall. The inscriptions predate the flood.";
        else lore = "§8[Archive] §7" + lore;
        player.sendMessage(lore);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.6f, 1.2f);
    }

    private void handleWarden(Player player) {
        boolean unlocked = player.getPersistentDataContainer()
                .getOrDefault(T4_UNLOCKED_KEY, PersistentDataType.BYTE, (byte) 0) == 1;
        if (unlocked) {
            player.sendMessage("§8[Warden] §7The record is complete. You may proceed.");
        } else {
            player.sendMessage("§8[Warden] §7The second lock requires a condition. The record determines it, not the warden.");
        }
        player.playSound(player.getLocation(), Sound.ENTITY_DROWNED_AMBIENT, 0.8f, 0.7f);
    }

    // ── Visited helpers ────────────────────────────────────────────────────────

    private boolean hasVisited(Player player, String siteId) {
        String data = player.getPersistentDataContainer()
                .getOrDefault(VISITED_KEY, PersistentDataType.STRING, "");
        return Arrays.asList(data.split(",")).contains(siteId);
    }

    private void markVisited(Player player, String siteId) {
        String data = player.getPersistentDataContainer()
                .getOrDefault(VISITED_KEY, PersistentDataType.STRING, "");
        Set<String> set = new HashSet<>(Arrays.asList(data.split(",")));
        set.remove("");
        set.add(siteId);
        player.getPersistentDataContainer()
                .set(VISITED_KEY, PersistentDataType.STRING, String.join(",", set));
    }

    // ── Admin command /vcivil ──────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vestigium.ocean.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length == 0) { printHelp(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "site" -> handleSiteCommand(sender, args);
            case "npc"  -> handleNpcCommand(sender, args);
            case "unlock" -> handleUnlock(sender, args);
            case "progress" -> handleProgress(sender, args);
            default -> printHelp(sender);
        }
        return true;
    }

    private void handleSiteCommand(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /vcivil site <add|remove|list>"); return; }
        switch (args[1].toLowerCase()) {
            case "add" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cMust be a player."); return; }
                if (args.length < 4) { sender.sendMessage("§cUsage: /vcivil site add <tier> <id> [name...]"); return; }
                int tier;
                try { tier = Integer.parseInt(args[2]); } catch (NumberFormatException e) {
                    sender.sendMessage("§cTier must be 1-4."); return;
                }
                if (tier < 1 || tier > 4) { sender.sendMessage("§cTier must be 1-4."); return; }
                String id = args[3].toLowerCase();
                String name = args.length > 4
                        ? String.join(" ", Arrays.copyOfRange(args, 4, args.length))
                        : id;
                int radius = tier == 1 ? 32 : tier == 2 ? 48 : tier == 3 ? 64 : 80;
                CivSite site = new CivSite(id, name, tier, p.getLocation().clone(), radius);
                sites.put(id, site);
                saveSites();
                sender.sendMessage("§aSite '" + id + "' (T" + tier + ", r=" + radius + ") registered at your location.");
            }
            case "remove" -> {
                if (args.length < 3) { sender.sendMessage("§cUsage: /vcivil site remove <id>"); return; }
                String id = args[2].toLowerCase();
                if (sites.remove(id) != null) { saveSites(); sender.sendMessage("§aSite removed."); }
                else sender.sendMessage("§cSite not found: " + id);
            }
            case "list" -> {
                if (sites.isEmpty()) { sender.sendMessage("§7No sites registered."); return; }
                sender.sendMessage("§8=== Drowned Civilization Sites ===");
                sites.values().forEach(s -> sender.sendMessage(
                        "§3" + s.id() + " §8(T" + s.tier() + ", r=" + s.radius() + ") — §7" + s.displayName()
                ));
            }
            default -> sender.sendMessage("§cUnknown site sub-command.");
        }
    }

    private void handleNpcCommand(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /vcivil npc <spawn|remove> ..."); return; }
        switch (args[1].toLowerCase()) {
            case "spawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cMust be a player."); return; }
                if (args.length < 4) { sender.sendMessage("§cUsage: /vcivil npc spawn <type> <siteId>"); return; }
                String type   = args[2].toLowerCase();
                String siteId = args[3].toLowerCase();
                if (!Set.of(NPC_REFUGEE, NPC_CRAFTER, NPC_ARCHIVIST, NPC_WARDEN).contains(type)) {
                    sender.sendMessage("§cUnknown NPC type. Use: refugee, crafter, archivist, warden"); return;
                }
                spawnNpc(p.getLocation(), type, siteId);
                sender.sendMessage("§aSpawned " + type + " NPC at your location (site: " + siteId + ").");
            }
            case "remove" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cMust be a player."); return; }
                p.getNearbyEntities(5, 5, 5).stream()
                        .filter(e -> e.getPersistentDataContainer().has(NPC_TYPE_KEY, PersistentDataType.STRING))
                        .findFirst().ifPresentOrElse(e -> { e.remove(); sender.sendMessage("§aNPC removed."); },
                                () -> sender.sendMessage("§cNo civilization NPC within 5 blocks."));
            }
            default -> sender.sendMessage("§cUnknown npc sub-command.");
        }
    }

    private void handleUnlock(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("t4")) {
            sender.sendMessage("§cUsage: /vcivil unlock t4 <player>"); return;
        }
        Player target = plugin.getServer().getPlayer(args[2]);
        if (target == null) { sender.sendMessage("§cPlayer not found or offline."); return; }
        target.getPersistentDataContainer().set(T4_UNLOCKED_KEY, PersistentDataType.BYTE, (byte) 1);
        sender.sendMessage("§aT4 access unlocked for " + target.getName() + ".");
        target.sendMessage("§d§lCivilization Access §r§8— §7The fourth depth is open to you.");
        target.playSound(target.getLocation(), Sound.BLOCK_CONDUIT_ACTIVATE, 1.0f, 0.7f);
    }

    private void handleProgress(CommandSender sender, String[] args) {
        Player target = (args.length >= 2)
                ? plugin.getServer().getPlayer(args[1])
                : (sender instanceof Player p ? p : null);
        if (target == null) { sender.sendMessage("§cPlayer not found."); return; }

        String visited = target.getPersistentDataContainer()
                .getOrDefault(VISITED_KEY, PersistentDataType.STRING, "");
        boolean t4 = target.getPersistentDataContainer()
                .getOrDefault(T4_UNLOCKED_KEY, PersistentDataType.BYTE, (byte) 0) == 1;
        boolean recognised = target.getPersistentDataContainer()
                .getOrDefault(RECOGNITION_KEY, PersistentDataType.BYTE, (byte) 0) == 1;

        sender.sendMessage("§8=== Civ Progress: " + target.getName() + " ===");
        sender.sendMessage("§7Sites visited: §f" + (visited.isEmpty() ? "none" : visited));
        sender.sendMessage("§7T4 unlocked: " + (t4 ? "§ayes" : "§cno"));
        sender.sendMessage("§7Archive recognition: " + (recognised ? "§ayes" : "§cno"));
    }

    private void printHelp(CommandSender sender) {
        sender.sendMessage("§8=== /vcivil ===");
        sender.sendMessage("§7site add <tier> <id> [name...] §8— register site at location");
        sender.sendMessage("§7site remove <id> §8— remove site");
        sender.sendMessage("§7site list §8— list registered sites");
        sender.sendMessage("§7npc spawn <type> <siteId> §8— spawn refugee/crafter/archivist/warden");
        sender.sendMessage("§7npc remove §8— remove nearest civ NPC");
        sender.sendMessage("§7unlock t4 <player> §8— grant T4 access");
        sender.sendMessage("§7progress [player] §8— show player progress");
    }

    // ── NPC Spawn ──────────────────────────────────────────────────────────────

    private void spawnNpc(Location loc, String type, String siteId) {
        World world = loc.getWorld();
        if (world == null) return;

        switch (type) {
            case NPC_REFUGEE -> world.spawn(loc, Drowned.class, drowned -> {
                drowned.setAI(false);
                drowned.setInvulnerable(true);
                drowned.setSilent(false);
                drowned.setPersistent(true);
                drowned.setRemoveWhenFarAway(false);
                drowned.customName(net.kyori.adventure.text.Component.text("§8Refugee"));
                drowned.setCustomNameVisible(true);
                tagNpc(drowned, NPC_REFUGEE, siteId);
            });
            case NPC_CRAFTER -> world.spawn(loc, Villager.class, v -> {
                v.setAI(false);
                v.setInvulnerable(true);
                v.setPersistent(true);
                v.setRemoveWhenFarAway(false);
                v.setProfession(Villager.Profession.TOOLSMITH);
                v.customName(net.kyori.adventure.text.Component.text("§3Drowned Crafter"));
                v.setCustomNameVisible(true);
                tagNpc(v, NPC_CRAFTER, siteId);
            });
            case NPC_ARCHIVIST -> world.spawn(loc, Villager.class, v -> {
                v.setAI(false);
                v.setInvulnerable(true);
                v.setPersistent(true);
                v.setRemoveWhenFarAway(false);
                v.setProfession(Villager.Profession.LIBRARIAN);
                v.customName(net.kyori.adventure.text.Component.text("§bArchivist"));
                v.setCustomNameVisible(true);
                tagNpc(v, NPC_ARCHIVIST, siteId);
            });
            case NPC_WARDEN -> world.spawn(loc, Drowned.class, drowned -> {
                drowned.setAI(false);
                drowned.setInvulnerable(true);
                drowned.setPersistent(true);
                drowned.setRemoveWhenFarAway(false);
                drowned.customName(net.kyori.adventure.text.Component.text("§4Door Warden"));
                drowned.setCustomNameVisible(true);
                tagNpc(drowned, NPC_WARDEN, siteId);
            });
        }
    }

    private void tagNpc(Entity e, String type, String siteId) {
        e.getPersistentDataContainer().set(NPC_TYPE_KEY, PersistentDataType.STRING, type);
        e.getPersistentDataContainer().set(NPC_SITE_KEY, PersistentDataType.STRING, siteId);
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    private void loadSites() {
        if (!dataFile.exists()) return;
        org.bukkit.configuration.file.YamlConfiguration cfg =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile);
        for (String id : cfg.getKeys(false)) {
            String display = cfg.getString(id + ".display", id);
            int tier       = cfg.getInt(id + ".tier", 1);
            int radius     = cfg.getInt(id + ".radius", 32);
            String worldName = cfg.getString(id + ".world", "world");
            double x = cfg.getDouble(id + ".x");
            double y = cfg.getDouble(id + ".y");
            double z = cfg.getDouble(id + ".z");
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) continue;
            sites.put(id, new CivSite(id, display, tier, new Location(world, x, y, z), radius));
        }
        plugin.getLogger().info("[DrownedCivilization] Loaded " + sites.size() + " site(s).");
    }

    private void saveSites() {
        org.bukkit.configuration.file.YamlConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        for (CivSite site : sites.values()) {
            cfg.set(site.id() + ".display", site.displayName());
            cfg.set(site.id() + ".tier", site.tier());
            cfg.set(site.id() + ".radius", site.radius());
            if (site.center() != null && site.center().getWorld() != null) {
                cfg.set(site.id() + ".world", site.center().getWorld().getName());
                cfg.set(site.id() + ".x", site.center().getX());
                cfg.set(site.id() + ".y", site.center().getY());
                cfg.set(site.id() + ".z", site.center().getZ());
            }
        }
        try { cfg.save(dataFile); } catch (IOException e) {
            plugin.getLogger().warning("[DrownedCivilization] Could not save drowned_civ.yml: " + e.getMessage());
        }
    }

    // ── TabCompleter ───────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("vestigium.ocean.admin")) return List.of();
        if (args.length == 1) return filter(List.of("site", "npc", "unlock", "progress"), args[0]);
        if (args[0].equalsIgnoreCase("site")) {
            if (args.length == 2) return filter(List.of("add", "remove", "list"), args[1]);
            if (args[0].equalsIgnoreCase("site") && args[1].equalsIgnoreCase("remove") && args.length == 3)
                return filter(new ArrayList<>(sites.keySet()), args[2]);
            if (args[1].equalsIgnoreCase("add") && args.length == 3)
                return filter(List.of("1", "2", "3", "4"), args[2]);
        }
        if (args[0].equalsIgnoreCase("npc")) {
            if (args.length == 2) return filter(List.of("spawn", "remove"), args[1]);
            if (args[1].equalsIgnoreCase("spawn") && args.length == 3)
                return filter(List.of(NPC_REFUGEE, NPC_CRAFTER, NPC_ARCHIVIST, NPC_WARDEN), args[2]);
            if (args[1].equalsIgnoreCase("spawn") && args.length == 4)
                return filter(new ArrayList<>(sites.keySet()), args[3]);
        }
        if (args[0].equalsIgnoreCase("unlock") && args.length == 2)
            return filter(List.of("t4"), args[1]);
        return List.of();
    }

    private List<String> filter(List<String> options, String partial) {
        String p = partial.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(p)).collect(Collectors.toList());
    }
}
