package com.vestigium.vestigiumnether.navigation;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumnether.VestigiumNether;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Soul Road Navigation — Antecedent navigation infrastructure.
 *
 * Soul roads are ancient paths of Soul Sand and Soul Soil laid through the
 * Nether toward expedition camps and The Breach Point. Admins register named
 * destinations via /vnroad; from then on the system:
 *
 *   - detects players walking on Soul Sand or Soul Soil in the Nether
 *   - shows a player-relative compass arrow in the actionbar pointing toward
 *     the nearest unvisited registered destination (updated every 10 ticks)
 *   - increments vestigium:soul_road_progress (INTEGER) on each block step
 *   - grants lore fragments at milestones (100, 500, 2000 steps) and on
 *     first arrival within 16 blocks of each destination
 *   - delivers atmospheric Antecedent messages every 2–4 minutes while on road
 *
 * Destinations persisted to plugins/VestigiumNether/soul_roads.yml.
 * Visited destinations stored as comma-delimited STRING in player PDC.
 *
 * Admin: /vnroad add <id> [display name...] | remove <id> | list | progress [player]
 * Permission: vestigium.nether.admin
 */
public class SoulRoadManager implements Listener, CommandExecutor, TabCompleter {

    private static final NamespacedKey PROGRESS_KEY = new NamespacedKey("vestigium", "soul_road_progress");
    private static final NamespacedKey VISITED_KEY  = new NamespacedKey("vestigium", "soul_road_visited");

    private static final Set<Material> ROAD_SURFACE = Set.of(
            Material.SOUL_SAND, Material.SOUL_SOIL);

    private static final int  ARRIVAL_RADIUS    = 16;
    private static final int  MILESTONE_STEPS_1 = 100;
    private static final int  MILESTONE_STEPS_2 = 500;
    private static final int  MILESTONE_STEPS_3 = 2000;
    private static final long AMBIENT_MIN_MS    = 2L * 60_000;
    private static final long AMBIENT_MAX_MS    = 4L * 60_000;

    private static final String[] AMBIENT_MESSAGES = {
        "§8The road remembers your weight.",
        "§8This path was walked before memory begins.",
        "§8The survey markers are still here. Follow them.",
        "§8Something watched the first walkers of this road. Something still does.",
        "§8The Antecedents moved quickly. You should too.",
        "§8Ahead. Always ahead on this road.",
        "§8The pavement does not allow for hesitation.",
        "§8You are not the first. The road has held more desperate feet than yours.",
        "§8The road does not care whether you finish it.",
        "§8Keep walking. The destination is its own answer."
    };

    private static final String[] COMPASS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private final VestigiumNether plugin;
    private final Map<String, Destination> destinations = new LinkedHashMap<>();
    private final Set<UUID> onRoad        = new HashSet<>();
    private final Map<UUID, Long> nextAmbientMs = new HashMap<>();

    private File saveFile;
    private BukkitRunnable tickTask;

    public record Destination(String id, String displayName, Location location) {}

    public SoulRoadManager(VestigiumNether plugin) {
        this.plugin = plugin;
    }

    public void init() {
        saveFile = new File(plugin.getDataFolder(), "soul_roads.yml");
        load();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("vnroad");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        startTickTask();
        plugin.getLogger().info("[SoulRoadManager] Initialized — "
                + destinations.size() + " destination(s) loaded.");
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Movement — road surface detection and step counting
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo().getWorld().getEnvironment() != World.Environment.NETHER) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Player player = event.getPlayer();
        Location to = event.getTo();
        Material underFoot = to.getWorld().getBlockAt(
                to.getBlockX(), to.getBlockY() - 1, to.getBlockZ()).getType();

        if (ROAD_SURFACE.contains(underFoot)) {
            boolean wasOnRoad = onRoad.add(player.getUniqueId());
            incrementProgress(player, !wasOnRoad);
        } else {
            onRoad.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        onRoad.remove(id);
        nextAmbientMs.remove(id);
    }

    // -------------------------------------------------------------------------
    // Tick task — compass, arrival, ambient (every 10 ticks)
    // -------------------------------------------------------------------------

    private void startTickTask() {
        tickTask = new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                for (UUID uid : List.copyOf(onRoad)) {
                    Player p = plugin.getServer().getPlayer(uid);
                    if (p == null) { onRoad.remove(uid); continue; }
                    tickOnRoadPlayer(p, now);
                }
            }
        };
        tickTask.runTaskTimer(plugin, 10L, 10L);
    }

    private void tickOnRoadPlayer(Player player, long now) {
        showCompassActionbar(player);
        checkArrival(player);

        if (now >= nextAmbientMs.getOrDefault(player.getUniqueId(), 0L)) {
            fireAmbientMessage(player);
            long delay = ThreadLocalRandom.current().nextLong(AMBIENT_MIN_MS, AMBIENT_MAX_MS);
            nextAmbientMs.put(player.getUniqueId(), now + delay);
        }
    }

    // -------------------------------------------------------------------------
    // Progress tracking
    // -------------------------------------------------------------------------

    private void incrementProgress(Player player, boolean justEnteredRoad) {
        var pdc = player.getPersistentDataContainer();
        int prev = pdc.getOrDefault(PROGRESS_KEY, PersistentDataType.INTEGER, 0);
        int next = prev + 1;
        pdc.set(PROGRESS_KEY, PersistentDataType.INTEGER, next);

        if (prev == 0) {
            onFirstRoadStep(player);
            return;
        }
        if (prev < MILESTONE_STEPS_1 && next >= MILESTONE_STEPS_1) {
            player.sendActionBar(Component.text(
                    "§7You have walked §f100 §7steps of the Antecedent road."));
            player.getWorld().playSound(player.getLocation(),
                    Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.AMBIENT, 0.4f, 0.7f);
        }
        if (prev < MILESTONE_STEPS_2 && next >= MILESTONE_STEPS_2) {
            VestigiumLib.getLoreRegistry().grantFragment(
                    player.getUniqueId(), "soul_road_expedition_log");
            player.sendMessage(
                    "§8[Soul Roads] §7The road remembers you. An expedition log surfaces from memory.");
            player.getWorld().playSound(player.getLocation(),
                    Sound.BLOCK_ANCIENT_DEBRIS_STEP, SoundCategory.AMBIENT, 0.5f, 0.5f);
        }
        if (prev < MILESTONE_STEPS_3 && next >= MILESTONE_STEPS_3) {
            VestigiumLib.getLoreRegistry().grantFragment(
                    player.getUniqueId(), "soul_road_veteran");
            player.sendMessage(
                    "§8[Soul Roads] §7You have walked far enough to understand what was here before.");
            player.getWorld().playSound(player.getLocation(),
                    Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.AMBIENT, 0.8f, 0.6f);
        }
    }

    private void onFirstRoadStep(Player player) {
        VestigiumLib.getLoreRegistry().grantFragment(
                player.getUniqueId(), "soul_road_first_steps");
        player.sendMessage(
                "§8[Soul Roads] §7The path is not natural. Stone does not arrange itself like this.");
        player.getWorld().playSound(player.getLocation(),
                Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.AMBIENT, 0.6f, 0.5f);
    }

    // -------------------------------------------------------------------------
    // Navigation compass
    // -------------------------------------------------------------------------

    private void showCompassActionbar(Player player) {
        Destination nearest = findNearestUnvisited(player);
        if (nearest == null) {
            if (!destinations.isEmpty()) {
                player.sendActionBar(Component.text("§7§o— all roads remembered —"));
            }
            return;
        }

        double distSq = player.getLocation().distanceSquared(nearest.location());
        int dist = (int) Math.sqrt(distSq);
        String arrow = compassArrow(player, nearest.location());
        player.sendActionBar(Component.text(
                "§8Road §7" + arrow + " §f" + nearest.displayName() + " §8(" + dist + "m)"));
    }

    private String compassArrow(Player player, Location dest) {
        Location from = player.getLocation();
        double dx = dest.getX() - from.getX();
        double dz = dest.getZ() - from.getZ();

        // Compass bearing to destination (0=N, 90=E, 180=S, 270=W)
        double bearingToDest = Math.toDegrees(Math.atan2(dx, -dz));
        bearingToDest = ((bearingToDest % 360) + 360) % 360;

        // Minecraft yaw 0=south → compass bearing 180
        double playerBearing = ((from.getYaw() + 180.0) % 360 + 360) % 360;

        // Angle from player's facing to the destination (clockwise positive)
        double rel = (bearingToDest - playerBearing + 360) % 360;
        int sector = (int) Math.round(rel / 45.0) % 8;
        return COMPASS[sector];
    }

    // -------------------------------------------------------------------------
    // Arrival check
    // -------------------------------------------------------------------------

    private void checkArrival(Player player) {
        int rSq = ARRIVAL_RADIUS * ARRIVAL_RADIUS;
        for (Destination dest : destinations.values()) {
            if (!dest.location().getWorld().equals(player.getWorld())) continue;
            if (player.getLocation().distanceSquared(dest.location()) > rSq) continue;
            if (hasVisited(player, dest.id())) continue;

            markVisited(player, dest.id());
            VestigiumLib.getLoreRegistry().grantFragment(
                    player.getUniqueId(), "soul_road_" + dest.id());
            player.sendMessage("§8[Soul Roads] §7§o" + dest.displayName()
                    + "§r§8. §7The road brought you here for a reason.");
            player.getWorld().playSound(player.getLocation(),
                    Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.AMBIENT, 0.8f, 0.55f);
            player.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,
                    player.getLocation().add(0, 1, 0), 24, 0.6, 0.8, 0.6, 0.02);
        }
    }

    // -------------------------------------------------------------------------
    // Ambient road messages
    // -------------------------------------------------------------------------

    private void fireAmbientMessage(Player player) {
        String msg = AMBIENT_MESSAGES[
                ThreadLocalRandom.current().nextInt(AMBIENT_MESSAGES.length)];
        player.sendMessage(msg);
        player.getWorld().playSound(player.getLocation(),
                Sound.BLOCK_SOUL_SAND_STEP, SoundCategory.AMBIENT, 0.2f, 0.6f);
    }

    // -------------------------------------------------------------------------
    // Visited tracking
    // -------------------------------------------------------------------------

    private boolean hasVisited(Player player, String id) {
        String raw = player.getPersistentDataContainer()
                .getOrDefault(VISITED_KEY, PersistentDataType.STRING, "");
        if (raw.isEmpty()) return false;
        for (String v : raw.split(",")) {
            if (v.equals(id)) return true;
        }
        return false;
    }

    private void markVisited(Player player, String id) {
        String raw = player.getPersistentDataContainer()
                .getOrDefault(VISITED_KEY, PersistentDataType.STRING, "");
        String updated = raw.isEmpty() ? id : raw + "," + id;
        player.getPersistentDataContainer()
                .set(VISITED_KEY, PersistentDataType.STRING, updated);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private Destination findNearestUnvisited(Player player) {
        Destination nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Destination dest : destinations.values()) {
            if (!dest.location().getWorld().equals(player.getWorld())) continue;
            if (hasVisited(player, dest.id())) continue;
            double d = player.getLocation().distanceSquared(dest.location());
            if (d < nearestDist) {
                nearestDist = d;
                nearest = dest;
            }
        }
        return nearest;
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void load() {
        if (!saveFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(saveFile);
        for (String id : cfg.getKeys(false)) {
            String worldName = cfg.getString(id + ".world", "");
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("[SoulRoadManager] Unknown world '" + worldName
                        + "' for destination '" + id + "' — skipped.");
                continue;
            }
            String display = cfg.getString(id + ".display", formatId(id));
            Location loc = new Location(world,
                    cfg.getDouble(id + ".x"),
                    cfg.getDouble(id + ".y"),
                    cfg.getDouble(id + ".z"));
            destinations.put(id, new Destination(id, display, loc));
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Destination dest : destinations.values()) {
            cfg.set(dest.id() + ".display", dest.displayName());
            cfg.set(dest.id() + ".world",   dest.location().getWorld().getName());
            cfg.set(dest.id() + ".x",       dest.location().getX());
            cfg.set(dest.id() + ".y",       dest.location().getY());
            cfg.set(dest.id() + ".z",       dest.location().getZ());
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(saveFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[SoulRoadManager] Save failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // /vnroad command
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vestigium.nether.admin")) {
            sender.sendMessage("§cNo permission."); return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§7/vnroad <add <id> [display] | remove <id> | list | progress [player]>");
            return true;
        }
        switch (args[0].toLowerCase()) {

            case "add" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cIn-game only."); return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§7/vnroad add <id> [display name...]"); return true;
                }
                if (player.getWorld().getEnvironment() != World.Environment.NETHER) {
                    sender.sendMessage("§cMust be in the Nether."); return true;
                }
                String id = args[1].toLowerCase();
                String display = args.length > 2
                        ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                        : formatId(id);
                Location loc = player.getLocation();
                destinations.put(id, new Destination(id, display, loc));
                save();
                sender.sendMessage("§aDestination §f" + display
                        + " §a(§7" + id + "§a) registered at §e"
                        + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "§a.");
            }

            case "remove" -> {
                if (args.length < 2) {
                    sender.sendMessage("§7/vnroad remove <id>"); return true;
                }
                Destination removed = destinations.remove(args[1].toLowerCase());
                if (removed != null) {
                    save();
                    sender.sendMessage("§aDestination §f" + removed.displayName() + " §aremoved.");
                } else {
                    sender.sendMessage("§cNo destination with id '§f" + args[1] + "§c'.");
                }
            }

            case "list" -> {
                if (destinations.isEmpty()) {
                    sender.sendMessage("§7No destinations registered."); return true;
                }
                sender.sendMessage("§6Soul Road Destinations (" + destinations.size() + "):");
                for (Destination dest : destinations.values()) {
                    Location l = dest.location();
                    sender.sendMessage("§7  §f" + dest.displayName()
                            + " §8(§7" + dest.id() + "§8) — "
                            + l.getWorld().getName() + " "
                            + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ());
                }
            }

            case "progress" -> {
                Player target;
                if (args.length >= 2) {
                    target = plugin.getServer().getPlayer(args[1]);
                    if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    sender.sendMessage("§7/vnroad progress <player>"); return true;
                }
                int steps = target.getPersistentDataContainer()
                        .getOrDefault(PROGRESS_KEY, PersistentDataType.INTEGER, 0);
                String visited = target.getPersistentDataContainer()
                        .getOrDefault(VISITED_KEY, PersistentDataType.STRING, "");
                int visitCount = visited.isEmpty() ? 0 : visited.split(",").length;
                sender.sendMessage("§6" + target.getName() + "§7 — Road steps: §f" + steps
                        + "§7, Destinations reached: §f" + visitCount + "§7/§f" + destinations.size());
                if (!visited.isEmpty()) {
                    sender.sendMessage("§8  Visited: " + visited.replace(",", "§8, §7"));
                }
            }

            default -> sender.sendMessage(
                    "§7/vnroad <add <id> [display] | remove <id> | list | progress [player]>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("add", "remove", "list", "progress");
        if (args.length == 2 && args[0].equalsIgnoreCase("remove"))
            return new ArrayList<>(destinations.keySet());
        return List.of();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String formatId(String id) {
        return Arrays.stream(id.split("_"))
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
    }
}
