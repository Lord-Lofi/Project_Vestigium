package com.vestigium.vestigiumstructures.wandering;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.SeasonChangeEvent;
import com.vestigium.lib.util.BlockStructureTag;
import com.vestigium.vestigiumstructures.VestigiumStructures;
import com.vestigium.vestigiumstructures.registry.StructureDefinition;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wandering Dungeons — structures that drift through the world.
 *
 * Two migration modes:
 *   Daily drift  — 1 chunk (16 blocks) per real-world day in a slowly-shifting heading.
 *                  Players inside receive a 30-second warning; any remaining players are
 *                  teleported to the new anchor on execution. Impassable terrain (cliff
 *                  height diff > 30 blocks) or protected areas cancel that day's drift.
 *
 *   Season jump  — large jump (200–800 blocks) when SeasonChangeEvent fires via EventBus.
 *                  Players at the old anchor are teleported immediately.
 *
 * State persisted to plugins/VestigiumStructures/wandering.yml:
 *   <id>.world, x, y, z, heading (0–359 degrees), last_daily_migrate (Unix ms)
 */
public class WanderingDungeonManager {

    private static final NamespacedKey STRUCTURE_ID_KEY = new NamespacedKey("vestigium", "structure_id");

    private static final int  DAILY_DRIFT_BLOCKS  = 16;     // 1 chunk per day
    private static final int  PLAYER_WARN_RADIUS  = 50;     // blocks around anchor counted as "inside"
    private static final int  MAX_HEIGHT_DIFF     = 30;     // terrain cliff rejection threshold
    private static final long DAILY_MS            = 24L * 60 * 60 * 1000;
    private static final long WARN_TICKS          = 600L;   // 30 seconds warning before drift
    private static final long CHECK_INTERVAL      = 36_000L; // check every 30 minutes

    private static final int MIN_SEASON_MIGRATE   = 200;
    private static final int MAX_SEASON_MIGRATE   = 800;

    private final VestigiumStructures plugin;
    private final Map<String, WanderingEntry> activeEntries = new LinkedHashMap<>();
    private File persistFile;
    private BukkitRunnable checkTask;

    public WanderingDungeonManager(VestigiumStructures plugin) {
        this.plugin = plugin;
    }

    public void init() {
        persistFile = new File(plugin.getDataFolder(), "wandering.yml");
        loadEntries();

        VestigiumLib.getEventBus().subscribe(SeasonChangeEvent.class,
                event -> plugin.getServer().getScheduler().runTask(plugin, this::seasonMigrateAll));

        checkTask = new BukkitRunnable() {
            @Override public void run() { checkDailyDrift(); }
        };
        checkTask.runTaskTimer(plugin, CHECK_INTERVAL, CHECK_INTERVAL);

        plugin.getLogger().info("[WanderingDungeonManager] Initialized — "
                + activeEntries.size() + " active wandering dungeons.");
    }

    public void shutdown() {
        if (checkTask != null) checkTask.cancel();
    }

    public void save() {
        if (persistFile == null) return;
        YamlConfiguration cfg = new YamlConfiguration();
        activeEntries.forEach((id, e) -> {
            cfg.set(id + ".world",             e.world);
            cfg.set(id + ".x",                 e.x);
            cfg.set(id + ".y",                 e.y);
            cfg.set(id + ".z",                 e.z);
            cfg.set(id + ".heading",           e.heading);
            cfg.set(id + ".last_daily_migrate",e.lastDailyMs);
        });
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(persistFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("[WanderingDungeonManager] Save failed: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Daily drift
    // -------------------------------------------------------------------------

    private void checkDailyDrift() {
        long now = System.currentTimeMillis();
        for (StructureDefinition def : plugin.getStructureRegistry().getWandering()) {
            WanderingEntry entry = activeEntries.get(def.id());
            if (entry == null || now - entry.lastDailyMs < DAILY_MS) continue;
            World world = plugin.getServer().getWorld(entry.world);
            if (world == null) continue;
            scheduleDrift(def, entry, world);
        }
    }

    private void scheduleDrift(StructureDefinition def, WanderingEntry entry, World world) {
        List<Player> inside = playersNear(world, entry.x, entry.y, entry.z);
        if (!inside.isEmpty()) {
            String warn = "§6⚠ The wandering dungeon is about to move! You have §e30 seconds §6to leave.";
            inside.forEach(p -> {
                p.sendMessage(warn);
                p.sendActionBar(Component.text(warn));
            });
        }

        new BukkitRunnable() {
            @Override public void run() {
                WanderingEntry current = activeEntries.get(def.id());
                if (current == null) return;

                Location target = driftTarget(world, current);
                if (target == null) {
                    plugin.getLogger().info("[WanderingDungeonManager] Drift blocked for "
                            + def.id() + " — impassable terrain or protection.");
                    activeEntries.put(def.id(), current.withLastDailyMs(System.currentTimeMillis()));
                    save();
                    return;
                }

                // Teleport any players still inside
                playersNear(world, current.x, current.y, current.z)
                        .forEach(p -> p.teleport(target.clone().add(2, 0, 2)));

                applyMove(def.id(), world, current.x, current.y, current.z,
                        target.getBlockX(), target.getBlockY(), target.getBlockZ());

                // Drift heading ±45 degrees each day for organic feel
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                int newHeading = (current.heading + rng.nextInt(-45, 46) + 360) % 360;
                activeEntries.put(def.id(), new WanderingEntry(
                        world.getName(),
                        target.getBlockX(), target.getBlockY(), target.getBlockZ(),
                        newHeading, System.currentTimeMillis()));
                save();
                plugin.getLogger().info("[WanderingDungeonManager] Daily drift: " + def.id()
                        + " → " + target.getBlockX() + "," + target.getBlockY() + "," + target.getBlockZ());
            }
        }.runTaskLater(plugin, WARN_TICKS);
    }

    /** Returns null if target terrain is too steep or protected. */
    private Location driftTarget(World world, WanderingEntry entry) {
        double rad = Math.toRadians(entry.heading);
        int nx = entry.x + (int) Math.round(Math.sin(rad) * DAILY_DRIFT_BLOCKS);
        int nz = entry.z + (int) Math.round(-Math.cos(rad) * DAILY_DRIFT_BLOCKS);
        int ny = world.getHighestBlockYAt(nx, nz) + 1;
        if (Math.abs(ny - entry.y) > MAX_HEIGHT_DIFF) return null;
        Location loc = new Location(world, nx, ny, nz);
        return VestigiumLib.getProtectionAPI().isProtected(loc) ? null : loc;
    }

    // -------------------------------------------------------------------------
    // Seasonal migration (big jump on SeasonChangeEvent)
    // -------------------------------------------------------------------------

    public void seasonMigrateAll() {
        List<StructureDefinition> wanderers = plugin.getStructureRegistry().getWandering();
        if (wanderers.isEmpty()) return;
        plugin.getServer().getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                .forEach(world -> {
                    for (StructureDefinition def : wanderers) {
                        WanderingEntry entry = activeEntries.get(def.id());
                        if (entry == null || !entry.world.equals(world.getName())) continue;
                        seasonMigrate(def, entry, world);
                    }
                });
        save();
    }

    private void seasonMigrate(StructureDefinition def, WanderingEntry entry, World world) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int dist = rng.nextInt(MIN_SEASON_MIGRATE, MAX_SEASON_MIGRATE + 1);
        int nx = entry.x + (rng.nextBoolean() ? dist : -dist);
        int nz = entry.z + (rng.nextBoolean() ? dist : -dist);
        int ny = world.getHighestBlockYAt(nx, nz) + 1;
        Location newLoc = new Location(world, nx, ny, nz);

        if (VestigiumLib.getProtectionAPI().isProtected(newLoc)) {
            plugin.getLogger().info("[WanderingDungeonManager] Season jump blocked for " + def.id());
            return;
        }

        playersNear(world, entry.x, entry.y, entry.z).forEach(p -> {
            p.sendMessage("§6The wandering dungeon has vanished with the season!");
            p.teleport(newLoc.clone().add(2, 0, 2));
        });

        applyMove(def.id(), world, entry.x, entry.y, entry.z, nx, ny, nz);
        activeEntries.put(def.id(), new WanderingEntry(
                world.getName(), nx, ny, nz, rng.nextInt(360), entry.lastDailyMs));

        broadcast(world, "§6The wandering structure §e" + def.id()
                + " §6has shifted with the season. New location near §e" + nx + ", " + nz + "§6.");
        plugin.getLogger().info("[WanderingDungeonManager] Season jump: " + def.id()
                + " → " + nx + "," + ny + "," + nz);
    }

    // -------------------------------------------------------------------------
    // Initial placement
    // -------------------------------------------------------------------------

    public void placeInitial(StructureDefinition def, World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z) + 1;
        Location loc = new Location(world, x, y, z);
        if (VestigiumLib.getProtectionAPI().isProtected(loc)) return;
        BlockStructureTag.set(world.getBlockAt(x, y, z), def.id());
        activeEntries.put(def.id(), new WanderingEntry(
                world.getName(), x, y, z, ThreadLocalRandom.current().nextInt(360), 0L));
        broadcast(world, "§6A wandering structure has arrived: §e" + def.id()
                + " §6near §e" + x + ", " + z + "§6.");
        save();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void applyMove(String id, World world, int ox, int oy, int oz, int nx, int ny, int nz) {
        BlockStructureTag.remove(world.getBlockAt(ox, oy, oz));
        BlockStructureTag.set(world.getBlockAt(nx, ny, nz), id);
    }

    private List<Player> playersNear(World world, int x, int y, int z) {
        Location centre = new Location(world, x, y, z);
        List<Player> result = new ArrayList<>();
        for (Entity e : world.getNearbyEntities(centre, PLAYER_WARN_RADIUS, PLAYER_WARN_RADIUS, PLAYER_WARN_RADIUS)) {
            if (e instanceof Player p) result.add(p);
        }
        return result;
    }

    private void broadcast(World world, String message) {
        world.getPlayers().forEach(p -> p.sendMessage(message));
    }

    private void loadEntries() {
        if (!persistFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(persistFile);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (String id : cfg.getKeys(false)) {
            activeEntries.put(id, new WanderingEntry(
                    cfg.getString(id + ".world", "world"),
                    cfg.getInt(id + ".x"), cfg.getInt(id + ".y"), cfg.getInt(id + ".z"),
                    cfg.getInt(id + ".heading", rng.nextInt(360)),
                    cfg.getLong(id + ".last_daily_migrate", 0L)));
        }
    }

    private record WanderingEntry(String world, int x, int y, int z, int heading, long lastDailyMs) {
        WanderingEntry withLastDailyMs(long ms) {
            return new WanderingEntry(world, x, y, z, heading, ms);
        }
    }
}
