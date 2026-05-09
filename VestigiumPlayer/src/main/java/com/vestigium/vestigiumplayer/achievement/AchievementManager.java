package com.vestigium.vestigiumplayer.achievement;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.CataclysmEndEvent;
import com.vestigium.lib.event.LoreFragmentGrantedEvent;
import com.vestigium.lib.event.PlayerReputationChangeEvent;
import com.vestigium.lib.model.Faction;
import com.vestigium.vestigiumplayer.VestigiumPlayer;
import com.vestigium.vestigiumplayer.data.PlayerDataStore;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Achievement Tree — 24 achievements spanning exploration, lore, combat,
 * factions, cataclysms, and End-dimension milestones.
 *
 * Achievements are checked on: join (catch-up), lore fragment grants,
 * cataclysm survival, reputation changes, and entity death events.
 *
 * Unlocked achievements are stored in player PDC under
 * vestigium:vp_achievements (STRING, comma-separated keys) and backed up
 * to the per-player YAML via PlayerDataStore.
 *
 * On unlock: sends announcement, gives the reward item (drops at feet if
 * inventory full), and notifies all online players for legendary tier (T4).
 *
 * /vpachievements [list | info <key>]
 */
public class AchievementManager implements Listener, CommandExecutor {

    // PDC keys read from other modules — cross-module by convention, not import
    private static final NamespacedKey APEX_FIRST_KEY          = new NamespacedKey("vestigium", "apex_first_kill");
    private static final NamespacedKey APEX_TERRITORIES_KEY    = new NamespacedKey("vestigium", "apex_territories");
    private static final NamespacedKey CITY_VISITS_KEY         = new NamespacedKey("vestigium", "city_visits");
    private static final NamespacedKey CONVERGENCE_KEY         = new NamespacedKey("vestigium", "convergence_witnessed");
    private static final NamespacedKey WITNESS_COMPLETE_KEY    = new NamespacedKey("vestigium", "witness_complete");
    private static final NamespacedKey DRAGON_SEAL_KEY         = new NamespacedKey("vestigium", "dragon_seal_count");
    private static final NamespacedKey PIGLIN_HISTORIAN_KEY    = new NamespacedKey("vestigium", "piglin_historian_count");
    private static final NamespacedKey NAMED_WARDEN_KEY        = new NamespacedKey("vestigium", "named_warden_type");

    // Legendary tier keys — broadcast to all players on unlock
    private static final Set<String> LEGENDARY_KEYS = Set.of(
            "territory_master", "grand_archivist", "warden_bane",
            "seal_collector", "the_terminus", "void_surveyor");

    private final List<AchievementDefinition> ALL_ACHIEVEMENTS;
    private final VestigiumPlayer plugin;
    private final PlayerDataStore dataStore;

    public AchievementManager(VestigiumPlayer plugin, PlayerDataStore dataStore) {
        this.plugin    = plugin;
        this.dataStore = dataStore;
        ALL_ACHIEVEMENTS = buildDefinitions();
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        VestigiumLib.getEventBus().subscribe(LoreFragmentGrantedEvent.class, event -> {
            Player p = plugin.getServer().getPlayer(event.getPlayerUUID());
            if (p != null) checkAchievements(p);
        });

        VestigiumLib.getEventBus().subscribe(CataclysmEndEvent.class, event ->
                plugin.getServer().getOnlinePlayers().forEach(this::checkAchievements));

        VestigiumLib.getEventBus().subscribe(PlayerReputationChangeEvent.class, event -> {
            Player p = plugin.getServer().getPlayer(event.getPlayerUUID());
            if (p != null) checkAchievements(p);
        });

        var cmd = plugin.getCommand("vpachievements");
        if (cmd != null) cmd.setExecutor(this);

        plugin.getLogger().info("[AchievementManager] Initialized — "
                + ALL_ACHIEVEMENTS.size() + " achievements.");
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Delay 1 tick to ensure PlayerDataStore has finished loading backup
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> checkAchievements(player), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        boolean isApex  = event.getEntity().getPersistentDataContainer()
                .has(new NamespacedKey("vestigium", "apex_predator"), PersistentDataType.STRING);
        boolean isWarden = event.getEntity() instanceof Warden
                && event.getEntity().getPersistentDataContainer()
                        .has(NAMED_WARDEN_KEY, PersistentDataType.STRING);

        if (isApex || isWarden) checkAchievements(killer);
    }

    // -------------------------------------------------------------------------
    // Core check
    // -------------------------------------------------------------------------

    public void checkAchievements(Player player) {
        List<String> unlocked = dataStore.getUnlockedAchievements(player);
        for (AchievementDefinition def : ALL_ACHIEVEMENTS) {
            if (unlocked.contains(def.key())) continue;
            try {
                if (!def.condition().test(player)) continue;
            } catch (Exception e) {
                continue;
            }
            grantAchievement(player, def);
        }
    }

    private void grantAchievement(Player player, AchievementDefinition def) {
        dataStore.unlockAchievement(player, def.key());

        player.sendMessage("§6§l[Achievement Unlocked] §r§f" + def.displayName());
        player.sendMessage("  §7" + def.description());

        ItemStack reward = buildReward(def);
        if (player.getInventory().firstEmpty() == -1) {
            player.getWorld().dropItemNaturally(player.getLocation(), reward);
            player.sendMessage("  §7Reward dropped at your feet: §f" + def.rewardName());
        } else {
            player.getInventory().addItem(reward);
            player.sendMessage("  §7Reward added to inventory: §f" + def.rewardName());
        }

        player.getWorld().playSound(player.getLocation(),
                Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1.0f, 1.0f);

        if (LEGENDARY_KEYS.contains(def.key())) {
            String broadcast = "§5§l[Vestigium] §r§d" + player.getName()
                    + " §7has achieved §5§l" + def.displayName() + "§r§7.";
            plugin.getServer().getOnlinePlayers().forEach(p -> p.sendMessage(broadcast));
        }
    }

    // -------------------------------------------------------------------------
    // /vpachievements command
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cPlayer only.");
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("info")) {
            String key = args[1].toLowerCase();
            ALL_ACHIEVEMENTS.stream()
                    .filter(a -> a.key().equals(key))
                    .findFirst()
                    .ifPresentOrElse(
                            a -> {
                                boolean has = dataStore.getUnlockedAchievements(player).contains(key);
                                player.sendMessage("§6" + a.displayName() + (has ? " §a[Unlocked]" : " §8[Locked]"));
                                player.sendMessage("  §7" + a.description());
                                player.sendMessage("  §7Reward: §f" + a.rewardName());
                            },
                            () -> player.sendMessage("§cUnknown achievement: " + key));
            return true;
        }

        // Default: list all
        List<String> unlocked = dataStore.getUnlockedAchievements(player);
        int total = ALL_ACHIEVEMENTS.size();
        int count = unlocked.size();
        player.sendMessage("§6§lAchievements §r§8(" + count + "/" + total + ")");
        for (AchievementDefinition def : ALL_ACHIEVEMENTS) {
            boolean has = unlocked.contains(def.key());
            String color = has ? "§a" : "§8";
            player.sendMessage("  " + color + def.displayName()
                    + (has ? "" : " §8— /vpachievements info " + def.key()));
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Achievement definitions
    // -------------------------------------------------------------------------

    private List<AchievementDefinition> buildDefinitions() {
        return List.of(

            // ── Exploration ────────────────────────────────────────────────────
            new AchievementDefinition(
                "first_structure", "Ruins Found",
                "Discover your first ancient structure.",
                Material.GOLDEN_SWORD, "§6Explorer's Blade",
                p -> dataStore.getInt(p, PlayerDataStore.KEY_STRUCTURES) >= 1),

            new AchievementDefinition(
                "trail_mapper", "Trail Mapper",
                "Discover 10 ancient structures.",
                Material.COMPASS, "§eMapper's Compass",
                p -> dataStore.getInt(p, PlayerDataStore.KEY_STRUCTURES) >= 10),

            new AchievementDefinition(
                "deep_cartographer", "Deep Cartographer",
                "Discover 25 ancient structures.",
                Material.MAP, "§bAntecedent Survey",
                p -> dataStore.getInt(p, PlayerDataStore.KEY_STRUCTURES) >= 25),

            new AchievementDefinition(
                "city_echo", "Echo of the First Hour",
                "Enter an ancient city for the first time.",
                Material.SCULK, "§8Sculk Fragment",
                p -> !p.getPersistentDataContainer()
                        .getOrDefault(CITY_VISITS_KEY, PersistentDataType.STRING, "").isBlank()),

            new AchievementDefinition(
                "city_elder", "City Elder",
                "Visit three distinct ancient cities.",
                Material.SCULK_VEIN, "§8Sculk Crystal",
                p -> countCityVisits(p) >= 3),

            // ── Lore ───────────────────────────────────────────────────────────
            new AchievementDefinition(
                "lore_initiate", "Lore Initiate",
                "Collect 10 lore fragments.",
                Material.BOOK, "§bLore Primer",
                p -> dataStore.getInt(p, PlayerDataStore.KEY_LORE_FRAGS) >= 10),

            new AchievementDefinition(
                "lore_scholar", "Scholar of the Antecedent",
                "Collect 50 lore fragments.",
                Material.WRITTEN_BOOK, "§dScholar's Tome",
                p -> dataStore.getInt(p, PlayerDataStore.KEY_LORE_FRAGS) >= 50),

            new AchievementDefinition(
                "grand_archivist", "Grand Archivist",
                "Collect 100 lore fragments.",
                Material.ENCHANTED_BOOK, "§5Archive Master's Key",
                p -> dataStore.getInt(p, PlayerDataStore.KEY_LORE_FRAGS) >= 100),

            new AchievementDefinition(
                "convergence_witness", "The Convergence",
                "Witness the Convergence Point in the End.",
                Material.AMETHYST_SHARD, "§9Convergence Shard",
                p -> p.getPersistentDataContainer().has(CONVERGENCE_KEY, PersistentDataType.BYTE)),

            new AchievementDefinition(
                "enderman_witness_complete", "Void Witness",
                "Complete the Enderman Witness Chain.",
                Material.ENDER_EYE, "§5Witness Eye",
                p -> p.getPersistentDataContainer().has(WITNESS_COMPLETE_KEY, PersistentDataType.BYTE)),

            // ── Combat ─────────────────────────────────────────────────────────
            new AchievementDefinition(
                "apex_hunter", "Apex Hunter",
                "Slay an apex predator.",
                Material.FLINT, "§7Hunter's Mark",
                p -> p.getPersistentDataContainer().has(APEX_FIRST_KEY, PersistentDataType.BYTE)),

            new AchievementDefinition(
                "apex_sovereign", "Apex Sovereign",
                "Defeat three apex predators.",
                Material.DIAMOND, "§bSovereign's Mark",
                p -> countApexKills(p) >= 3),

            new AchievementDefinition(
                "territory_master", "Territory Master",
                "Defeat one of each apex predator type.",
                Material.NETHERITE_INGOT, "§8§lApex Crown",
                p -> hasAllApexTypes(p)),

            new AchievementDefinition(
                "warden_challenger", "Warden Challenger",
                "Slay a Named Warden.",
                Material.BONE, "§cWarden Bone",
                p -> dataStore.getInt(p, PlayerDataStore.KEY_BOSS_KILLS) >= 1),

            new AchievementDefinition(
                "warden_bane", "Five Wardens Fallen",
                "Slay five Named Wardens.",
                Material.NETHER_STAR, "§4§lWarden's Crest",
                p -> dataStore.getInt(p, PlayerDataStore.KEY_BOSS_KILLS) >= 5),

            // ── Cataclysm ──────────────────────────────────────────────────────
            new AchievementDefinition(
                "first_cataclysm", "Unbroken",
                "Survive your first cataclysm.",
                Material.GLASS, "§7Cataclysm Shard",
                p -> dataStore.getInt(p, PlayerDataStore.KEY_CATACLYSMS) >= 1),

            new AchievementDefinition(
                "cataclysm_veteran", "Chaos Endured",
                "Survive three cataclysms.",
                Material.OBSIDIAN, "§8Chaos Stone",
                p -> dataStore.getInt(p, PlayerDataStore.KEY_CATACLYSMS) >= 3),

            // ── Dragon and End ─────────────────────────────────────────────────
            new AchievementDefinition(
                "dragon_present", "Dragon Witness",
                "Be present when the Ender Dragon falls.",
                Material.FIRE_CHARGE, "§5Dragon Witness Mark",
                p -> p.getPersistentDataContainer()
                        .getOrDefault(DRAGON_SEAL_KEY, PersistentDataType.INTEGER, 0) >= 1),

            new AchievementDefinition(
                "seal_collector", "Dragonbound",
                "Collect all five Dragon Seal Fragments.",
                Material.DRAGON_BREATH, "§5§lDragon's Essence",
                p -> p.getPersistentDataContainer()
                        .getOrDefault(DRAGON_SEAL_KEY, PersistentDataType.INTEGER, 0) >= 5),

            // ── Factions ───────────────────────────────────────────────────────
            new AchievementDefinition(
                "faction_friend", "Faction Friend",
                "Reach 200 reputation with any faction.",
                Material.GOLD_INGOT, "§6Faction Token",
                p -> hasMinRepWithAnyFaction(p, 200)),

            new AchievementDefinition(
                "faction_champion", "Faction Champion",
                "Reach 500 reputation with any faction.",
                Material.EMERALD, "§aFaction Crest",
                p -> hasMinRepWithAnyFaction(p, 500)),

            new AchievementDefinition(
                "piglin_historian", "Piglin Historian",
                "Earn the trust of the Piglins through prolonged study.",
                Material.GILDED_BLACKSTONE, "§6Gilded Honor",
                p -> p.getPersistentDataContainer()
                        .getOrDefault(PIGLIN_HISTORIAN_KEY, PersistentDataType.INTEGER, 0) >= 1),

            // ── Legendary / Endgame ────────────────────────────────────────────
            new AchievementDefinition(
                "the_terminus", "The Terminus",
                "Complete the Final Cartographer Chain.",
                Material.FILLED_MAP, "§5§lCartographer's Final Survey",
                p -> VestigiumLib.getLoreRegistry()
                        .hasFragment(p.getUniqueId(), "cartographer_terminus_main")),

            new AchievementDefinition(
                "void_surveyor", "Void Surveyor",
                "Witness the Convergence Point and complete the Enderman Witness Chain.",
                Material.CHORUS_FRUIT, "§5§lVoid Surveyor's Charm",
                p -> p.getPersistentDataContainer().has(CONVERGENCE_KEY, PersistentDataType.BYTE)
                        && p.getPersistentDataContainer().has(WITNESS_COMPLETE_KEY, PersistentDataType.BYTE))
        );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int countCityVisits(Player p) {
        String raw = p.getPersistentDataContainer()
                .getOrDefault(CITY_VISITS_KEY, PersistentDataType.STRING, "");
        if (raw.isBlank()) return 0;
        return raw.split(";").length;
    }

    private int countApexKills(Player p) {
        String raw = p.getPersistentDataContainer()
                .getOrDefault(APEX_TERRITORIES_KEY, PersistentDataType.STRING, "");
        if (raw.isBlank()) return 0;
        return raw.split(";").length;
    }

    private boolean hasAllApexTypes(Player p) {
        String raw = p.getPersistentDataContainer()
                .getOrDefault(APEX_TERRITORIES_KEY, PersistentDataType.STRING, "");
        if (raw.isBlank()) return false;
        Set<String> types = new HashSet<>();
        for (String entry : raw.split(";")) {
            String[] parts = entry.split("~");
            if (parts.length >= 1) types.add(parts[0]);
        }
        return types.containsAll(Set.of(
                "ARCTIC_SOVEREIGN", "PACK_SOVEREIGN", "JUNGLE_STALKER",
                "SWAMP_BROODMOTHER", "MOUNTAIN_SOVEREIGN"));
    }

    private boolean hasMinRepWithAnyFaction(Player p, int minRep) {
        for (Faction faction : Faction.values()) {
            try {
                if (VestigiumLib.getReputationAPI()
                        .getReputation(p.getUniqueId(), faction) >= minRep) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private ItemStack buildReward(AchievementDefinition def) {
        ItemStack item = new ItemStack(def.rewardMaterial());
        ItemMeta meta  = item.getItemMeta();
        meta.setDisplayName(def.rewardName());
        meta.setLore(List.of(
                "§7Achievement: §f" + def.displayName(),
                "§8" + def.description()));
        item.setItemMeta(meta);
        return item;
    }

    public List<AchievementDefinition> getAllAchievements() { return ALL_ACHIEVEMENTS; }
}
