package com.vestigium.vestigiummobs.passive;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiummobs.VestigiumMobs;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Worm — purely atmospheric world event.
 *
 * Every ~15 seconds there is a 5% chance per server tick that a worm event
 * fires near a random overworld player. The worm carves a short underground
 * tunnel (12 blocks of air at Y = surface − 8 to − 20), exposing any natural
 * ores adjacent to the carved path, and erupts rising CRIT particles at the
 * surface above the trail.
 *
 * Only natural stone, deepslate, dirt, gravel, and their variants are removed.
 * Protection-checked blocks are skipped silently.
 */
public class TheWormManager {

    private static final int   STEPS        = 12;
    private static final int   CHECK_TICKS  = 300; // 15 s
    private static final float CHANCE       = 0.05f;
    private static final int   SEARCH_RANGE = 80;

    private static final Set<Material> CARVEABLE = Set.of(
            Material.STONE, Material.DEEPSLATE, Material.DIRT, Material.GRAVEL,
            Material.ANDESITE, Material.DIORITE, Material.GRANITE,
            Material.TUFF, Material.CALCITE, Material.COBBLESTONE,
            Material.COBBLED_DEEPSLATE, Material.DIRT_PATH, Material.COARSE_DIRT
    );

    private final VestigiumMobs plugin;
    private BukkitRunnable task;

    public TheWormManager(VestigiumMobs plugin) {
        this.plugin = plugin;
    }

    public void init() {
        task = new BukkitRunnable() {
            @Override public void run() {
                if (ThreadLocalRandom.current().nextFloat() >= CHANCE) return;
                List<Player> candidates = new ArrayList<Player>(plugin.getServer().getOnlinePlayers())
                        .stream()
                        .filter(p -> p.getWorld().getEnvironment() == World.Environment.NORMAL)
                        .toList();
                if (candidates.isEmpty()) return;
                Player target = candidates.get(
                        ThreadLocalRandom.current().nextInt(candidates.size()));
                fireWormEvent(target);
            }
        };
        task.runTaskTimer(plugin, CHECK_TICKS, CHECK_TICKS);
        plugin.getLogger().info("[TheWormManager] Initialized.");
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    // -------------------------------------------------------------------------

    private void fireWormEvent(Player near) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = near.getWorld();

        // Pick a random surface location within SEARCH_RANGE of the player
        int ox = rng.nextInt(-SEARCH_RANGE, SEARCH_RANGE + 1);
        int oz = rng.nextInt(-SEARCH_RANGE, SEARCH_RANGE + 1);
        int bx = near.getLocation().getBlockX() + ox;
        int bz = near.getLocation().getBlockZ() + oz;

        int surfaceY = world.getHighestBlockYAt(bx, bz);
        int startY   = surfaceY - 8 - rng.nextInt(12); // 8–20 below surface
        if (startY < world.getMinHeight() + 5) return;

        // Choose a random horizontal direction
        int dx = rng.nextBoolean() ? 1 : -1;
        int dz = rng.nextBoolean() ? 1 : -1;
        if (rng.nextBoolean()) dx = 0; else dz = 0; // cardinal only

        int cx = bx, cy = startY, cz = bz;

        for (int step = 0; step < STEPS; step++) {
            Block b = world.getBlockAt(cx, cy, cz);

            // Carve if it's a natural tunnelable block and not protected
            if (CARVEABLE.contains(b.getType())
                    && !VestigiumLib.getProtectionAPI().isProtected(b.getLocation())) {
                b.setType(Material.AIR);
            }

            // Particles at the surface directly above each step
            Location surface = new Location(world, cx + 0.5, surfaceY + 1, cz + 0.5);
            world.spawnParticle(Particle.CRIT, surface, 6, 0.3, 0.5, 0.3, 0.05);

            // Play a muffled rumble sound at each step (audible only close by)
            world.playSound(surface, Sound.BLOCK_GRAVEL_BREAK, 0.5f, 0.3f + step * 0.04f);

            cx += dx;
            cz += dz;
            // Slight Y variation to simulate organic movement
            if (step % 3 == 0 && rng.nextBoolean()) cy--;
            if (cy < world.getMinHeight() + 2) break;
        }
    }
}
