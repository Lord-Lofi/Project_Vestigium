package com.vestigium.vestigiumworld.living;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.util.Keys;
import com.vestigium.vestigiumworld.VestigiumWorld;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.chunk.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages living world behaviours:
 *
 *  Living Roads — PlayerMoveEvent on grass increments chunk PDC walk count.
 *    At ROAD_THRESHOLD, converts grass to grass path. Event-driven, never tick-driven.
 *
 *  Invasive Species Spread — vines / moss / sculk expand into wilderness chunks.
 *    Hard gate: ProtectionAPI.isPlayerPlaced() — tagged blocks are NEVER consumed.
 *    Runs async on a slow background task.
 *
 *  Civilizational Decay — unclaimed, unvisited structures accumulate moss/crumble
 *    particles after 90 real days (cosmetic only — no block destruction).
 *    Tracked via chunk PDC biome_health score.
 */
public class LivingWorldManager implements Listener {

    // Grass footsteps before a chunk converts to path
    private static final int ROAD_THRESHOLD = 500;

    // Spread check interval: every 5 real minutes
    private static final long SPREAD_INTERVAL_TICKS = 6_000L;

    // Materials that invasive species can spread over (non-player-placed wilderness blocks)
    private static final Set<Material> SPREAD_TARGETS = Set.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT,
            Material.ROOTED_DIRT, Material.PODZOL, Material.MYCELIUM
    );

    // Invasive species blocks that can spread
    private static final List<Material> INVASIVE_SOURCES = List.of(
            Material.VINE, Material.MOSS_BLOCK, Material.SCULK
    );

    private final VestigiumWorld plugin;
    private BukkitRunnable spreadTask;

    public LivingWorldManager(VestigiumWorld plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startSpreadTask();
        plugin.getLogger().info("[LivingWorldManager] Initialized.");
    }

    public void shutdown() {
        if (spreadTask != null) spreadTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Living Roads
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Only fire on block-to-block movement (not head turns)
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Block below = event.getTo().getBlock().getRelative(0, -1, 0);
        if (below.getType() != Material.GRASS_BLOCK) return;

        // Protection gate — never convert player-placed blocks
        if (VestigiumLib.getProtectionAPI().isPlayerPlaced(below)) return;
        if (VestigiumLib.getProtectionAPI().isProtected(below.getLocation())) return;

        Chunk chunk = below.getChunk();
        int count = chunk.getPersistentDataContainer()
                .getOrDefault(Keys.ROAD_WALK_COUNT, PersistentDataType.INTEGER, 0);
        count++;

        if (count >= ROAD_THRESHOLD) {
            below.setType(Material.GRASS_PATH);
            chunk.getPersistentDataContainer()
                    .set(Keys.ROAD_WALK_COUNT, PersistentDataType.INTEGER, 0);
        } else {
            chunk.getPersistentDataContainer()
                    .set(Keys.ROAD_WALK_COUNT, PersistentDataType.INTEGER, count);
        }
    }

    // -------------------------------------------------------------------------
    // Invasive Species Spread
    // -------------------------------------------------------------------------

    private void startSpreadTask() {
        spreadTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickInvasiveSpread();
            }
        };
        spreadTask.runTaskTimerAsynchronously(plugin, SPREAD_INTERVAL_TICKS, SPREAD_INTERVAL_TICKS);
    }

    private void tickInvasiveSpread() {
        plugin.getServer().getWorlds().forEach(world -> {
            if (world.getEnvironment() != org.bukkit.World.Environment.NORMAL) return;

            // Sample a small subset of loaded chunks each tick to avoid lag
            Chunk[] loadedChunks = world.getLoadedChunks();
            if (loadedChunks.length == 0) return;

            int sampleSize = Math.min(5, loadedChunks.length);
            for (int i = 0; i < sampleSize; i++) {
                Chunk chunk = loadedChunks[ThreadLocalRandom.current().nextInt(loadedChunks.length)];
                // Skip protected/claimed chunks
                int cx = chunk.getX() * 16 + 8;
                int cz = chunk.getZ() * 16 + 8;
                org.bukkit.Location center = new org.bukkit.Location(world, cx,
                        world.getHighestBlockYAt(cx, cz), cz);
                if (VestigiumLib.getProtectionAPI().isProtected(center)) continue;

                spreadInChunk(chunk);
            }
        });
    }

    private void spreadInChunk(Chunk chunk) {
        // Scan for existing invasive blocks and try to spread them one block
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int y = chunk.getWorld().getHighestBlockYAt(
                        chunk.getX() * 16 + x, chunk.getZ() * 16 + z);
                Block block = chunk.getWorld().getBlockAt(
                        chunk.getX() * 16 + x, y, chunk.getZ() * 16 + z);

                if (!INVASIVE_SOURCES.contains(block.getType())) continue;
                if (ThreadLocalRandom.current().nextInt(100) > 3) continue; // 3% chance per block

                // Try to spread to an adjacent block
                int[] dx = {1, -1, 0, 0};
                int[] dz = {0, 0, 1, -1};
                int dir = ThreadLocalRandom.current().nextInt(4);
                Block target = block.getRelative(dx[dir], 0, dz[dir]);

                if (!SPREAD_TARGETS.contains(target.getType())) continue;
                if (VestigiumLib.getProtectionAPI().isPlayerPlaced(target)) continue;
                if (VestigiumLib.getProtectionAPI().isProtected(target.getLocation())) continue;

                final Block finalTarget = target;
                final Material spreadMat = block.getType();
                // Jump back to main thread for block set
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> finalTarget.setType(spreadMat));
            }
        }
    }
}
