package com.vestigium.vestigiumstructures.wandering;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.SeasonChangeEvent;
import com.vestigium.lib.util.BlockStructureTag;
import com.vestigium.vestigiumstructures.VestigiumStructures;
import com.vestigium.vestigiumstructures.registry.StructureDefinition;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wandering Dungeons — structures that relocate between seasons.
 *
 * Each wandering structure has one active "anchor block" in the world, tagged with
 * vestigium:structure_id PDC STRING. On each season change the anchor migrates
 * by 200-800 blocks in a random cardinal direction, placing a new anchor block and
 * removing the old one (protection-gated).
 *
 * Wandering dungeon locations are persisted to plugins/VestigiumStructures/wandering.yml.
 *
 * The system listens for SeasonChangeEvent via VestigiumLib EventBus (not Bukkit events)
 * since VestigiumStructures must not import VestigiumWorld classes directly.
 */
public class WanderingDungeonManager {

    private static final NamespacedKey STRUCTURE_ID_KEY =
            new NamespacedKey("vestigium", "structure_id");

    private static final int MIN_MIGRATE = 200;
    private static final int MAX_MIGRATE = 800;

    private final VestigiumStructures plugin;
    private final Map<String, WanderingEntry> activeEntries = new LinkedHashMap<>();
    private File persistFile;

    public WanderingDungeonManager(VestigiumStructures plugin) {
        this.plugin = plugin;
    }

    public void init() {
        persistFile = new File(plugin.getDataFolder(), "wandering.yml");
        loadEntries();

        // Subscribe to SeasonChangeEvent via EventBus — no direct VestigiumWorld import
        VestigiumLib.getEventBus().subscribe(SeasonChangeEvent.class,
                event -> plugin.getServer().getScheduler().runTask(plugin, this::migrateAll));

        plugin.getLogger().info("[WanderingDungeonManager] Initialized — "
                + activeEntries.size() + " active wandering dungeons.");
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        activeEntries.forEach((id, entry) -> {
            cfg.set(id + ".world", entry.world);
            cfg.set(id + ".x", entry.x);
            cfg.set(id + ".y", entry.y);
            cfg.set(id + ".z", entry.z);
        });
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(persistFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[WanderingDungeonManager] Save failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Migration
    // -------------------------------------------------------------------------

    public void migrateAll() {
        List<StructureDefinition> wanderers =
                plugin.getStructureRegistry().getWandering();
        if (wanderers.isEmpty()) return;

        plugin.getServer().getWorlds().stream()
                .filter(w -> w.getEnvironment() == org.bukkit.World.Environment.NORMAL)
                .forEach(world -> {
                    for (StructureDefinition def : wanderers) {
                        WanderingEntry entry = activeEntries.get(def.id());
                        if (entry == null || !entry.world.equals(world.getName())) continue;
                        migrateEntry(def, entry, world);
                    }
                });
        save();
    }

    /** Places a new wandering dungeon anchor in the given world (initial placement). */
    public void placeInitial(StructureDefinition def, org.bukkit.World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z) + 1;
        Location loc = new Location(world, x, y, z);
        if (VestigiumLib.getProtectionAPI().isProtected(loc)) return;

        Block anchor = world.getBlockAt(x, y, z);
        BlockStructureTag.set(anchor, def.id());

        activeEntries.put(def.id(), new WanderingEntry(world.getName(), x, y, z));
        broadcast(world, "§6A wandering structure has arrived: §e" + def.id()
                + " §6near §e" + x + ", " + z + "§6.");
        save();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void migrateEntry(StructureDefinition def, WanderingEntry entry, org.bukkit.World world) {
        // Remove old anchor tag
        Block old = world.getBlockAt(entry.x, entry.y, entry.z);
        BlockStructureTag.remove(old);

        // New location
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int dist = rng.nextInt(MIN_MIGRATE, MAX_MIGRATE + 1);
        int dx = rng.nextBoolean() ? dist : -dist;
        int dz = rng.nextBoolean() ? dist : -dist;

        int nx = entry.x + (rng.nextBoolean() ? dx : 0);
        int nz = entry.z + (rng.nextBoolean() ? dz : 0);
        int ny = world.getHighestBlockYAt(nx, nz) + 1;

        Location newLoc = new Location(world, nx, ny, nz);
        if (VestigiumLib.getProtectionAPI().isProtected(newLoc)) {
            plugin.getLogger().info("[WanderingDungeonManager] Migration blocked for "
                    + def.id() + " — protected area.");
            return;
        }

        Block newAnchor = world.getBlockAt(nx, ny, nz);
        BlockStructureTag.set(newAnchor, def.id());

        activeEntries.put(def.id(), new WanderingEntry(world.getName(), nx, ny, nz));
        broadcast(world, "§6The wandering structure §e" + def.id()
                + " §6has moved. New location near §e" + nx + ", " + nz + "§6.");

        plugin.getLogger().info("[WanderingDungeonManager] Migrated " + def.id()
                + " to " + nx + "," + ny + "," + nz);
    }

    private void broadcast(org.bukkit.World world, String message) {
        world.getPlayers().forEach(p -> p.sendMessage(message));
    }

    private void loadEntries() {
        if (!persistFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(persistFile);
        for (String id : cfg.getKeys(false)) {
            String worldName = cfg.getString(id + ".world", "world");
            int x = cfg.getInt(id + ".x");
            int y = cfg.getInt(id + ".y");
            int z = cfg.getInt(id + ".z");
            activeEntries.put(id, new WanderingEntry(worldName, x, y, z));
        }
    }

    private record WanderingEntry(String world, int x, int y, int z) {}
}
