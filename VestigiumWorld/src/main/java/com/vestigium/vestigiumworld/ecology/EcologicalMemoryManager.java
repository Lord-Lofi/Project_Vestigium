package com.vestigium.vestigiumworld.ecology;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.CataclysmEndEvent;
import com.vestigium.lib.util.Keys;
import com.vestigium.vestigiumworld.VestigiumWorld;
import com.vestigium.vestigiumworld.cataclysm.CataclysmType;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Manages ecological memory and tectonic drift.
 *
 * Biome Health — each chunk carries a vestigium:biome_health score (0-100).
 *   Post-cataclysm chunks are permanently degraded (score drops).
 *   Score feeds VestigiumAtmosphere's ambiance intensity.
 *
 * Tectonic Drift — vestigium:chunk_pressure PDC accumulates over real time.
 *   When a chunk's pressure exceeds PRESSURE_THRESHOLD, it triggers a Sinkhole
 *   cataclysm via CataclysmManager. Pressure accumulates on loaded chunks only.
 *
 * World Scars — cataclysm endpoints are recorded as hex zones via
 *   vestigium:chunk_hex_zone PDC tag on relevant chunks.
 */
public class EcologicalMemoryManager {

    private static final int  PRESSURE_THRESHOLD  = 1000;
    private static final int  PRESSURE_PER_TICK   = 1;       // per chunk per check
    private static final long PRESSURE_TICK_TICKS = 72_000L; // once per real-world hour

    // Biome health reduction applied to chunks affected by cataclysms
    private static final int CATACLYSM_HEALTH_PENALTY = 15;

    private final VestigiumWorld plugin;
    private BukkitRunnable pressureTask;

    public EcologicalMemoryManager(VestigiumWorld plugin) {
        this.plugin = plugin;
    }

    public void init() {
        // Listen for cataclysm endings to scar affected chunks
        VestigiumLib.getEventBus().subscribe(CataclysmEndEvent.class, this::onCataclysmEnd);
        startPressureTask();
        plugin.getLogger().info("[EcologicalMemoryManager] Initialized.");
    }

    public void shutdown() {
        if (pressureTask != null) pressureTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the biome health score (0-100) for a chunk.
     * 100 = pristine; lower = ecologically degraded.
     */
    public int getBiomeHealth(Chunk chunk) {
        return chunk.getPersistentDataContainer()
                .getOrDefault(Keys.BIOME_HEALTH, PersistentDataType.INTEGER, 100);
    }

    /**
     * Marks a chunk as a world scar from a specific cataclysm.
     * Sets vestigium:chunk_hex_zone with the cataclysm type string.
     */
    public void markWorldScar(Chunk chunk, String cataclysmType) {
        chunk.getPersistentDataContainer()
                .set(Keys.CHUNK_HEX_ZONE, PersistentDataType.STRING, cataclysmType);
        degradeBiomeHealth(chunk, CATACLYSM_HEALTH_PENALTY);
    }

    /**
     * Returns the hex zone type active in this chunk, or null if none.
     */
    public String getHexZone(Chunk chunk) {
        return chunk.getPersistentDataContainer()
                .get(Keys.CHUNK_HEX_ZONE, PersistentDataType.STRING);
    }

    // -------------------------------------------------------------------------

    private void onCataclysmEnd(CataclysmEndEvent event) {
        Location epicenter = event.getEpicenter();
        if (epicenter == null || epicenter.getWorld() == null) return;

        // Scar the epicenter chunk and its immediate neighbours
        Chunk center = epicenter.getChunk();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Chunk chunk = epicenter.getWorld().getChunkAt(
                        center.getX() + dx, center.getZ() + dz);
                markWorldScar(chunk, event.getCataclysmType());
            }
        }
    }

    private void degradeBiomeHealth(Chunk chunk, int amount) {
        int current = getBiomeHealth(chunk);
        int updated = Math.max(0, current - amount);
        chunk.getPersistentDataContainer()
                .set(Keys.BIOME_HEALTH, PersistentDataType.INTEGER, updated);
    }

    private void startPressureTask() {
        pressureTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickTectonicPressure();
            }
        };
        pressureTask.runTaskTimerAsynchronously(plugin, PRESSURE_TICK_TICKS, PRESSURE_TICK_TICKS);
    }

    private void tickTectonicPressure() {
        for (World world : plugin.getServer().getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) continue;

            for (Chunk chunk : world.getLoadedChunks()) {
                int pressure = chunk.getPersistentDataContainer()
                        .getOrDefault(Keys.CHUNK_PRESSURE, PersistentDataType.INTEGER, 0);
                pressure += PRESSURE_PER_TICK;

                if (pressure >= PRESSURE_THRESHOLD) {
                    // Reset pressure and trigger a sinkhole at this chunk
                    chunk.getPersistentDataContainer()
                            .set(Keys.CHUNK_PRESSURE, PersistentDataType.INTEGER, 0);
                    final Location loc = new Location(world,
                            chunk.getX() * 16 + 8,
                            world.getHighestBlockYAt(chunk.getX() * 16 + 8, chunk.getZ() * 16 + 8),
                            chunk.getZ() * 16 + 8);
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> plugin.getCataclysmManager().start(
                                    CataclysmType.SINKHOLE, loc, -1));
                } else {
                    chunk.getPersistentDataContainer()
                            .set(Keys.CHUNK_PRESSURE, PersistentDataType.INTEGER, pressure);
                }
            }
        }
    }
}
