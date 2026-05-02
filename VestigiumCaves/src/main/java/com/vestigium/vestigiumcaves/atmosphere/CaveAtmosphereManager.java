package com.vestigium.vestigiumcaves.atmosphere;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.model.ParticlePriority;
import com.vestigium.vestigiumcaves.VestigiumCaves;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Ambient atmosphere for underground players.
 *
 * Effects:
 *   Sculk wisps    — omen 300+, underground (Y < 50); SCULK_SOUL particles + cave sound
 *   Deep dark pulse — omen 500+, deep underground (Y < -20); SCULK_CHARGE_POP + heartbeat sound
 *   Echo shimmer   — omen < 100, underground; END_ROD trickle, "the cave breathes" sound
 *
 * Uses ParticleManager at ATMOSPHERIC priority so TPS-gating applies automatically.
 * Check interval: 60 ticks (3 seconds).
 */
public class CaveAtmosphereManager {

    private static final long   CHECK_TICKS     = 60L;
    private static final int    UNDERGROUND_Y   = 50;
    private static final int    DEEP_DARK_Y     = -20;
    private static final double PARTICLE_SPREAD = 4.0;

    private final VestigiumCaves plugin;
    private BukkitRunnable task;

    public CaveAtmosphereManager(VestigiumCaves plugin) {
        this.plugin = plugin;
    }

    public void init() {
        task = new BukkitRunnable() {
            @Override public void run() {
                plugin.getServer().getWorlds().forEach(world -> {
                    if (world.getEnvironment() != World.Environment.NORMAL) return;
                    world.getPlayers().forEach(p -> emitForPlayer(p));
                });
            }
        };
        task.runTaskTimer(plugin, CHECK_TICKS, CHECK_TICKS);

        plugin.getLogger().info("[CaveAtmosphereManager] Initialized.");
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    // -------------------------------------------------------------------------

    private void emitForPlayer(Player player) {
        int y    = player.getLocation().getBlockY();
        int omen = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (y >= UNDERGROUND_Y) return; // above ground, nothing to do

        // Sculk wisps — omen 300+, underground
        if (omen >= 300 && rng.nextInt(3) == 0) {
            queueNearPlayer(player, Particle.SCULK_SOUL, null);
            if (rng.nextInt(8) == 0) {
                player.playSound(player.getLocation(), Sound.AMBIENT_CAVE, 0.3f, 0.8f);
            }
        }

        // Deep dark pulse — omen 500+, deep underground
        if (omen >= 500 && y < DEEP_DARK_Y && rng.nextInt(4) == 0) {
            queueNearPlayer(player, Particle.SCULK_CHARGE_POP, null);
            if (rng.nextInt(6) == 0) {
                player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.4f, 0.7f);
            }
        }

        // Echo shimmer — low omen, gentle cave life
        if (omen < 100 && rng.nextInt(5) == 0) {
            queueNearPlayer(player, Particle.END_ROD, null);
            if (rng.nextInt(12) == 0) {
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.2f, 1.2f);
            }
        }
    }

    private <T> void queueNearPlayer(Player player, Particle particle, T data) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location base = player.getLocation();
        Location loc = base.clone().add(
                rng.nextDouble(-PARTICLE_SPREAD, PARTICLE_SPREAD),
                rng.nextDouble(0, PARTICLE_SPREAD),
                rng.nextDouble(-PARTICLE_SPREAD, PARTICLE_SPREAD));
        VestigiumLib.getParticleManager().queueParticle(loc, particle, data, ParticlePriority.ATMOSPHERIC);
    }
}
