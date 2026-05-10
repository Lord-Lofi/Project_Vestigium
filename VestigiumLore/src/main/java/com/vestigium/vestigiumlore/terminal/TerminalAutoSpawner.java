package com.vestigium.vestigiumlore.terminal;

import com.vestigium.vestigiumlore.VestigiumLore;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.util.BoundingBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Auto-spawns Resonant and End Archive terminals the first time a player
 * enters an Ancient City or End City respectively.
 *
 * Per structure (keyed by world + bounding box min-X/Z) — fires once per
 * server session. On first entry the spawner:
 *   1. Scans within ±16 blocks of the structure centre for an existing lectern.
 *   2. Tags the nearest untagged lectern it finds (preserves vanilla generation).
 *   3. If no lectern exists, places a new one on the first solid surface at centre.
 *
 * Lore key assigned:
 *   Ancient City → "resonant_archive"   (TerminalType.RESONANT)
 *   End City     → "end_archive"        (TerminalType.END_ARCHIVE)
 *
 * Nether camp terminals are tied to custom VestigiumStructures schematics and
 * are not auto-spawned here.
 */
public class TerminalAutoSpawner implements Listener {

    private static final NamespacedKey TERMINAL_TYPE_KEY =
            new NamespacedKey("vestigium", "terminal_type");
    private static final NamespacedKey TERMINAL_LORE_KEY =
            new NamespacedKey("vestigium", "terminal_lore");

    private static final int SCAN_RADIUS = 16;

    // Prevents re-processing the same structure across the session
    private final Set<String> processedKeys = new HashSet<>();

    private final VestigiumLore plugin;

    public TerminalAutoSpawner(VestigiumLore plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[TerminalAutoSpawner] Initialized.");
    }

    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Player player = event.getPlayer();
        Location loc  = player.getLocation();
        World world   = player.getWorld();

        Collection<GeneratedStructure> structures = world.getStructures(loc.getBlockX(), loc.getBlockZ());
        for (GeneratedStructure gs : structures) {
            Structure type = gs.getStructure();
            if (type == Structure.ANCIENT_CITY) {
                maybeEnsureTerminal(world, gs,
                        TerminalManager.TerminalType.RESONANT, "resonant_archive");
            } else if (type == Structure.END_CITY) {
                maybeEnsureTerminal(world, gs,
                        TerminalManager.TerminalType.END_ARCHIVE, "end_archive");
            }
        }
    }

    // -------------------------------------------------------------------------

    private void maybeEnsureTerminal(World world, GeneratedStructure gs,
                                     TerminalManager.TerminalType type, String loreKey) {
        BoundingBox bb = gs.getBoundingBox();
        String key = world.getName()
                + ":" + (int) bb.getMinX()
                + ":" + (int) bb.getMinZ();
        if (!processedKeys.add(key)) return;

        // Schedule one tick later so the move event isn't held up
        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                ensureTerminal(world, bb, type, loreKey), 1L);
    }

    private void ensureTerminal(World world, BoundingBox bb,
                                 TerminalManager.TerminalType type, String loreKey) {
        int cx = (int) bb.getCenterX();
        int cz = (int) bb.getCenterZ();
        int minY = Math.max((int) bb.getMinY(), world.getMinHeight());
        int maxY = Math.min((int) bb.getMaxY(), world.getMaxHeight() - 1);

        // 1. Check for an already-tagged terminal in scan radius
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx += 4) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz += 4) {
                for (int y = minY; y <= maxY; y++) {
                    Block b = world.getBlockAt(cx + dx, y, cz + dz);
                    if (b.getType() == Material.LECTERN) {
                        Lectern ls = (Lectern) b.getState();
                        if (ls.getPersistentDataContainer()
                                .has(TERMINAL_TYPE_KEY, PersistentDataType.STRING)) {
                            return; // terminal already present — nothing to do
                        }
                    }
                }
            }
        }

        // 2. Find nearest untagged vanilla lectern and tag it
        Block nearest = findNearestLectern(world, cx, cz, minY, maxY);
        if (nearest != null) {
            tagBlock(nearest, type, loreKey);
            plugin.getLogger().info("[TerminalAutoSpawner] Tagged existing lectern for "
                    + type.displayName() + " at " + nearest.getLocation().toVector()
                    + " (lore: " + loreKey + ")");
            return;
        }

        // 3. Place a new lectern on the first solid surface at centre
        Block placed = placeAtCentre(world, cx, cz, minY, maxY);
        if (placed != null) {
            tagBlock(placed, type, loreKey);
            plugin.getLogger().info("[TerminalAutoSpawner] Placed " + type.displayName()
                    + " at " + placed.getLocation().toVector() + " (lore: " + loreKey + ")");
        } else {
            plugin.getLogger().warning("[TerminalAutoSpawner] Could not place "
                    + type.displayName() + " near " + cx + "," + cz
                    + " — no suitable surface found.");
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
