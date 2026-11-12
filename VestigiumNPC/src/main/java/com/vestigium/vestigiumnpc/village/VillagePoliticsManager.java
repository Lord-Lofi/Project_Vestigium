package com.vestigium.vestigiumnpc.village;

import com.vestigium.vestigiumnpc.VestigiumNPC;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Village Politics — proximity-based rivalry between registered villages.
 *
 * Rivalry: any two villages within RIVAL_RANGE blocks in the same world are rivals.
 * Rivalry is computed inline from current positions — never cached.
 *
 * Effects:
 *   Iron Golem Patrol Sharing — idle golems near a village are periodically nudged
 *   toward rival territory (contested deterrence patrolling).
 *
 *   Trade Standing — trading 5+ times at a village confers loyalty. Trading at a
 *   rival while loyal elsewhere marks the player as an enemy at the loyal village;
 *   VillagerMemoryManager treats enemies as strangers (dialogue tier 0).
 *
 * PDC keys (on Player):
 *   vestigium:vp_tc_{villageId}    INTEGER — trade count at this village
 *   vestigium:vp_loyal             STRING  — comma-delimited loyal village IDs
 *   vestigium:vp_enemy_{villageId} BYTE    — present = enemy at this village
 */
public class VillagePoliticsManager implements Listener, CommandExecutor, TabCompleter {

    static final int RIVAL_RANGE    = 500; // blocks — within this distance = rivals
    public static final int VILLAGE_RADIUS = 100; // blocks — radius to attribute a villager to a village
    private static final int GOLEM_SCAN     = 64;  // scan radius for village iron golems
    private static final int PATROL_RANGE   = 200; // max distance to rival for nudge to fire
    private static final int LOYALTY_TRADES = 5;   // trades to become loyal

    private final VestigiumNPC plugin;
    private final Map<String, VillageRecord> villages = new LinkedHashMap<>();
    private BukkitTask patrolTask;
    private File dataFile;
    private YamlConfiguration data;

    public VillagePoliticsManager(VestigiumNPC plugin) {
        this.plugin = plugin;
    }

    public void init() {
        dataFile = new File(plugin.getDataFolder(), "village_politics.yml");
        data = dataFile.exists()
                ? YamlConfiguration.loadConfiguration(dataFile)
                : new YamlConfiguration();
        loadVillages();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startPatrolTask();
        plugin.getLogger().info("[VillagePoliticsManager] Initialized — " + villages.size() + " village(s).");
    }

    public void shutdown() {
        if (patrolTask != null) patrolTask.cancel();
        saveVillages();
    }

    // ── Registry ──────────────────────────────────────────────────────────────

    private void loadVillages() {
        if (!data.contains("villages")) return;
        for (String id : Objects.requireNonNull(data.getConfigurationSection("villages")).getKeys(false)) {
            String name  = data.getString("villages." + id + ".name", id);
            String world = data.getString("villages." + id + ".world");
            double x = data.getDouble("villages." + id + ".x");
            double y = data.getDouble("villages." + id + ".y");
            double z = data.getDouble("villages." + id + ".z");
            World w = plugin.getServer().getWorld(Objects.requireNonNull(world));
            if (w == null) { plugin.getLogger().warning("[VillagePoliticsManager] World '" + world + "' not found for village '" + id + "', skipping."); continue; }
            villages.put(id, new VillageRecord(id, name, new Location(w, x, y, z)));
        }
    }

    private void saveVillages() {
        data.set("villages", null);
        for (VillageRecord v : villages.values()) {
            String p = "villages." + v.id();
            data.set(p + ".name",  v.displayName());
            data.set(p + ".world", v.center().getWorld().getName());
            data.set(p + ".x",     v.center().getX());
            data.set(p + ".y",     v.center().getY());
            data.set(p + ".z",     v.center().getZ());
        }
        try { data.save(dataFile); } catch (IOException e) {
            plugin.getLogger().warning("[VillagePoliticsManager] Could not save: " + e.getMessage());
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Returns the nearest registered village within the given radius, or null. */
    public VillageRecord getVillageAt(Location loc, int radius) {
        VillageRecord nearest = null;
        double best = radius + 1;
        for (VillageRecord v : villages.values()) {
            if (!Objects.equals(v.center().getWorld(), loc.getWorld())) continue;
            double d = v.center().distance(loc);
            if (d < best) { best = d; nearest = v; }
        }
        return nearest;
    }

    /** Returns all villages that are rivals of the given village. */
    public List<VillageRecord> getRivals(VillageRecord village) {
        List<VillageRecord> rivals = new ArrayList<>();
        for (VillageRecord other : villages.values()) {
            if (other.id().equals(village.id())) continue;
            if (!Objects.equals(other.center().getWorld(), village.center().getWorld())) continue;
            if (village.center().distance(other.center()) <= RIVAL_RANGE) rivals.add(other);
        }
        return rivals;
    }

    /** True if the player is marked as an enemy at the given village. */
    public boolean isEnemy(UUID playerUUID, String villageId) {
        Player player = plugin.getServer().getPlayer(playerUUID);
        if (player == null) return false;
        return player.getPersistentDataContainer().has(enemyKey(villageId), PersistentDataType.BYTE);
    }

    /** True if the player has reached loyalty threshold at the given village. */
    public boolean isLoyal(UUID playerUUID, String villageId) {
        Player player = plugin.getServer().getPlayer(playerUUID);
        if (player == null) return false;
        String loyal = player.getPersistentDataContainer()
                .getOrDefault(loyalKey(), PersistentDataType.STRING, "");
        if (loyal.isBlank()) return false;
        for (String id : loyal.split(",")) {
            if (id.equals(villageId)) return true;
        }
        return false;
    }

    // ── Trade hook ─────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager)) return;
        if (villages.isEmpty()) return;
        Player player = event.getPlayer();

        VillageRecord here = getVillageAt(event.getRightClicked().getLocation(), VILLAGE_RADIUS);
        if (here == null) return;

        // Increment trade count for this village
        NamespacedKey tcKey = tradeCountKey(here.id());
        int count = player.getPersistentDataContainer().getOrDefault(tcKey, PersistentDataType.INTEGER, 0) + 1;
        player.getPersistentDataContainer().set(tcKey, PersistentDataType.INTEGER, count);

        // Reach loyalty threshold
        if (count == LOYALTY_TRADES && !isLoyal(player.getUniqueId(), here.id())) {
            addLoyal(player, here.id());
            player.sendMessage("§7The merchants of §f" + here.displayName() + " §7have come to trust you.");
        }

        // If player is loyal to any rival, mark as enemy here
        List<VillageRecord> rivals = getRivals(here);
        for (VillageRecord rival : rivals) {
            if (!isLoyal(player.getUniqueId(), rival.id())) continue;
            if (!isEnemy(player.getUniqueId(), here.id())) {
                player.getPersistentDataContainer().set(enemyKey(here.id()), PersistentDataType.BYTE, (byte) 1);
                player.sendMessage("§8The merchants of §7" + here.displayName() +
                        " §8distrust you. You have done business with their rivals.");
            }
            return;
        }
    }

    // ── Patrol task ────────────────────────────────────────────────────────────

    private void startPatrolTask() {
        patrolTask = new BukkitRunnable() {
            @Override public void run() { tickPatrols(); }
        }.runTaskTimer(plugin, 200L, 200L);
    }

    private void tickPatrols() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (VillageRecord village : villages.values()) {
            List<VillageRecord> rivals = getRivals(village);
            if (rivals.isEmpty()) continue;

            Location center = village.center();
            World world = center.getWorld();
            if (world == null) continue;

            for (Entity entity : world.getNearbyEntities(center, GOLEM_SCAN, GOLEM_SCAN, GOLEM_SCAN)) {
                if (!(entity instanceof IronGolem golem)) continue;
                if (golem.getTarget() != null) continue;   // busy
                if (rng.nextInt(100) >= 15) continue;      // 15% chance

                VillageRecord rival = rivals.get(rng.nextInt(rivals.size()));
                Location rivalCenter = rival.center();
                double dist = center.distance(rivalCenter);
                if (dist > PATROL_RANGE) continue;

                // Nudge the golem 40% of the way toward rival territory, capped at 80 blocks
                double dx = (rivalCenter.getX() - center.getX()) / dist;
                double dz = (rivalCenter.getZ() - center.getZ()) / dist;
                double nudge = Math.min(dist * 0.4, 80);
                Location target = golem.getLocation().clone().add(dx * nudge, 0, dz * nudge);
                target.setY(world.getHighestBlockYAt(target.getBlockX(), target.getBlockZ()));
                golem.getPathfinder().moveTo(target, 0.5);
            }
        }
    }

    // ── Commands ───────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { sendUsage(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "village" -> {
                if (args.length < 2) { sendUsage(sender); return true; }
                switch (args[1].toLowerCase()) {
                    case "add" -> {
                        if (!(sender instanceof Player player)) { sender.sendMessage("§cMust be run by a player."); return true; }
                        if (args.length < 3) { sender.sendMessage("§cUsage: /vcpolitics village add <id> [name...]"); return true; }
                        String id = args[2].toLowerCase();
                        if (villages.containsKey(id)) { sender.sendMessage("§cVillage '" + id + "' already exists."); return true; }
                        String name = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : id;
                        VillageRecord v = new VillageRecord(id, name, player.getLocation());
                        villages.put(id, v);
                        saveVillages();
                        sender.sendMessage("§aVillage '§f" + name + "§a' registered at your location.");
                        List<VillageRecord> rivals = getRivals(v);
                        if (!rivals.isEmpty()) {
                            sender.sendMessage("§eRivals (within " + RIVAL_RANGE + " blocks): " +
                                rivals.stream().map(VillageRecord::displayName).reduce((a, b) -> a + ", " + b).orElse(""));
                        }
                    }
                    case "remove" -> {
                        if (args.length < 3) { sender.sendMessage("§cUsage: /vcpolitics village remove <id>"); return true; }
                        String id = args[2].toLowerCase();
                        if (villages.remove(id) == null) { sender.sendMessage("§cNo village with id '" + id + "'."); return true; }
                        saveVillages();
                        sender.sendMessage("§aVillage '" + id + "' removed.");
                    }
                    case "list" -> {
                        if (villages.isEmpty()) { sender.sendMessage("§7No villages registered."); return true; }
                        sender.sendMessage("§eRegistered villages:");
                        for (VillageRecord v : villages.values()) {
                            Location c = v.center();
                            List<VillageRecord> rivals = getRivals(v);
                            String rivalStr = rivals.isEmpty() ? "§7none"
                                    : "§c" + rivals.stream().map(VillageRecord::displayName).reduce((a, b) -> a + ", " + b).orElse("");
                            sender.sendMessage("§f  " + v.id() + " §8— §7" + v.displayName() +
                                    " §8[" + c.getWorld().getName() + " " + c.getBlockX() + "," + c.getBlockY() + "," + c.getBlockZ() + "]" +
                                    " §8rivals: " + rivalStr);
                        }
                    }
                    default -> sendUsage(sender);
                }
            }
            case "rivals" -> {
                if (villages.isEmpty()) { sender.sendMessage("§7No villages registered."); return true; }
                Set<String> shown = new HashSet<>();
                boolean any = false;
                for (VillageRecord v : villages.values()) {
                    List<VillageRecord> rivals = getRivals(v);
                    for (VillageRecord rival : rivals) {
                        String key = v.id().compareTo(rival.id()) < 0 ? v.id() + ":" + rival.id() : rival.id() + ":" + v.id();
                        if (!shown.add(key)) continue;
                        int d = (int) v.center().distance(rival.center());
                        sender.sendMessage("§c" + v.displayName() + " §8⟷ §c" + rival.displayName() + " §8(" + d + " blocks)");
                        any = true;
                    }
                }
                if (!any) sender.sendMessage("§7No rival relationships (no villages within " + RIVAL_RANGE + " blocks).");
            }
            case "status" -> {
                Player target;
                if (args.length >= 2) {
                    target = plugin.getServer().getPlayer(args[1]);
                    if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    sender.sendMessage("§cSpecify a player name."); return true;
                }
                if (villages.isEmpty()) { sender.sendMessage("§7No villages registered."); return true; }
                sender.sendMessage("§eVillage standing for §f" + target.getName() + "§e:");
                for (VillageRecord v : villages.values()) {
                    int count = target.getPersistentDataContainer()
                            .getOrDefault(tradeCountKey(v.id()), PersistentDataType.INTEGER, 0);
                    boolean loyal = isLoyal(target.getUniqueId(), v.id());
                    boolean enemy = isEnemy(target.getUniqueId(), v.id());
                    String status = enemy ? "§cENEMY" : loyal ? "§aLOYAL" : "§7neutral";
                    sender.sendMessage("  §f" + v.displayName() + " §8— " + status + " §8(" + count + " trades)");
                }
            }
            default -> sendUsage(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("village", "rivals", "status");
        if (args.length == 2 && args[0].equalsIgnoreCase("village")) return List.of("add", "remove", "list");
        if (args.length == 3 && args[0].equalsIgnoreCase("village") && args[1].equalsIgnoreCase("remove"))
            return new ArrayList<>(villages.keySet());
        return List.of();
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§eVillage Politics:");
        sender.sendMessage("§7  /vcpolitics village add <id> [display name]");
        sender.sendMessage("§7  /vcpolitics village remove <id>");
        sender.sendMessage("§7  /vcpolitics village list");
        sender.sendMessage("§7  /vcpolitics rivals");
        sender.sendMessage("§7  /vcpolitics status [player]");
    }

    // ── PDC helpers ────────────────────────────────────────────────────────────

    private NamespacedKey tradeCountKey(String villageId) { return new NamespacedKey(plugin, "vp_tc_" + villageId); }
    private NamespacedKey loyalKey()                       { return new NamespacedKey(plugin, "vp_loyal"); }
    private NamespacedKey enemyKey(String villageId)       { return new NamespacedKey(plugin, "vp_enemy_" + villageId); }

    private void addLoyal(Player player, String villageId) {
        String current = player.getPersistentDataContainer()
                .getOrDefault(loyalKey(), PersistentDataType.STRING, "");
        player.getPersistentDataContainer().set(loyalKey(), PersistentDataType.STRING,
                current.isBlank() ? villageId : current + "," + villageId);
    }
}
