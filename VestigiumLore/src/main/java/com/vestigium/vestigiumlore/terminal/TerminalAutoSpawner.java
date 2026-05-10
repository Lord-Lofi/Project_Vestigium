package com.vestigium.vestigiumlore.terminal;

import com.vestigium.vestigiumlore.VestigiumLore;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Auto-spawns Resonant and End Archive terminals whenever a chunk containing
 * an Ancient City or End City loads for the first time this session.
 *
 * Uses ChunkLoadEvent — no polling, no periodic task. Cost per event is one
 * world.getStructures() call (only in NORMAL/THE_END), which is trivial
 * alongside the chunk load itself. The processedKeys gate ensures the actual
 * block scan and placement run at most once per structure per session.
 *
 * Per unique structure (world + BB min-X/Z):
 *   1. Scan ±16 blocks of centre for an already-tagged terminal — done.
 *   2. Tag the nearest untagged vanilla lectern in range — done.
 *   3. No lectern: find the highest solid surface at centre and place one.
 *
 * Lore keys:
 *   Ancient City → RESONANT,     "resonant_archive"
 *   End City     → END_ARCHIVE,  "end_archive"
 */
public class TerminalAutoSpawner implements Listener {

    private static final NamespacedKey TERMINAL_TYPE_KEY =
            new NamespacedKey("vestigium", "terminal_type");
    private static final NamespacedKey TERMINAL_LORE_KEY =
            new NamespacedKey("vestigium", "terminal_lore");

    private static final int SCAN_RADIUS = 16;

    private final Set<String> processedKeys = new HashSet<>();
    private final VestigiumLore plugin;

    public TerminalAutoSpawner(VestigiumLore plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[TerminalAutoSpawner] Initialized.");
    }

    public void shutdown() {
        // Bukkit unregisters all listeners automatically on plugin disable.
    }

    // -------------------------------------------------------------------------

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        World.Environment env = world.getEnvironment();
        if (env != World.Environment.NORMAL && env != World.Environment.THE_END) return;

        Chunk chunk = event.getChunk();
        int cx = chunk.getX() * 16 + 8;
        int cz = chunk.getZ() * 16 + 8;

        Collection<GeneratedStructure> structures = world.getStructures(cx, cz);
        for (GeneratedStructure gs : structures) {
            Structure type = gs.getStructure();
            if (env == World.Environment.NORMAL && type == Structure.ANCIENT_CITY) {
                maybeEnsure(world, gs, TerminalManager.TerminalType.RESONANT, "resonant_archive");
            } else if (env == World.Environment.THE_END && type == Structure.END_CITY) {
                maybeEnsure(world, gs, TerminalManager.TerminalType.END_ARCHIVE, "end_archive");
            }
        }
    }

    private void maybeEnsure(World world, GeneratedStructure gs,
                              TerminalManager.TerminalType type, String loreKey) {
        BoundingBox bb = gs.getBoundingBox();
        String key = world.getName()
                + ":" + (int) bb.getMinX()
                + ":" + (int) bb.getMinZ();
        if (!processedKeys.add(key)) return;

        ensureTerminal(world, bb, type, loreKey);
    }

    private void ensureTerminal(World world, BoundingBox bb,
                                 TerminalManager.TerminalType type, String loreKey) {
        int cx   = (int) bb.getCenterX();
        int cz   = (int) bb.getCenterZ();
        int minY = Math.max((int) bb.getMinY(), world.getMinHeight());
        int maxY = Math.min((int) bb.getMaxY(), world.getMaxHeight() - 1);

        // 1. Already-tagged terminal present?
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx += 4) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz += 4) {
                for (int y = minY; y <= maxY; y++) {
                    Block b = world.getBlockAt(cx + dx, y, cz + dz);
                    if (b.getType() == Material.LECTERN) {
                        Lectern ls = (Lectern) b.getState();
                        if (ls.getPersistentDataContainer()
                                .has(TERMINAL_TYPE_KEY, PersistentDataType.STRING)) {
                            return;
                        }
                    }
                }
            }
        }

        // 2. Tag the nearest untagged vanilla lectern
        Block nearest = findNearestLectern(world, cx, cz, minY, maxY);
        if (nearest != null) {
            tagBlock(nearest, type, loreKey);
            plugin.getLogger().info("[TerminalAutoSpawner] Tagged existing lectern for "
                    + type.displayName() + " at " + nearest.getLocation().toVector()
                    + " (lore: " + loreKey + ")");
            return;
        }

        // 3. Place a new lectern on the highest solid surface at centre
        Block placed = placeAtCentre(world, cx, cz, minY, maxY);
        if (placed != null) {
            tagBlock(placed, type, loreKey);
            plugin.getLogger().info("[TerminalAutoSpawner] Placed " + type.displayName()
                    + " at " + placed.getLocation().toVector() + " (lore: " + loreKey + ")");
        } else {
            plugin.getLogger().warning("[TerminalAutoSpawner] No surface found for "
                    + type.displayName() + " near " + cx + "," + cz);
        }
    }

    // -------------------------------------------------------------------------

    private Block findNearestLectern(World world, int cx, int cz, int minY, int maxY) {
        Block best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx += 2) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz += 2) {
                for (int y = minY; y <= maxY; y++) {
                    Block b = world.getBlockAt(cx + dx, y, cz + dz);
                    if (b.getType() != Material.LECTERN) continue;
                    double dist = dx * dx + dz * dz;
                    if (dist < bestDist) { bestDist = dist; best = b; }
                }
            }
        }
        return best;
    }

    private Block placeAtCentre(World world, int cx, int cz, int minY, int maxY) {
        for (int y = maxY; y >= minY; y--) {
            Block ground = world.getBlockAt(cx, y, cz);
            if (!ground.getType().isSolid()) continue;
            Block above = world.getBlockAt(cx, y + 1, cz);
            if (above.getType() == Material.AIR || above.getType() == Material.CAVE_AIR) {
                above.setType(Material.LECTERN);
                return above;
            }
        }
        return null;
    }

    private void tagBlock(Block block, TerminalManager.TerminalType type, String loreKey) {
        if (!(block.getState() instanceof Lectern lectern)) return;
        lectern.getPersistentDataContainer()
                .set(TERMINAL_TYPE_KEY, PersistentDataType.STRING, type.key());
        lectern.getPersistentDataContainer()
                .set(TERMINAL_LORE_KEY, PersistentDataType.STRING, loreKey);
        lectern.update();
    }
}
