package com.vestigium.vestigiumnether.atmosphere;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.model.ParticlePriority;
import com.vestigium.vestigiumnether.VestigiumNether;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Omen-driven atmospheric effects for Nether players.
 *
 * Effects (evaluated every 80 ticks, 4 seconds):
 *   omen 400+  — SOUL particles near players, distant ghast-moan sound
 *   omen 600+  — FLAME particles + lava-proximity warnings
 *   omen 700+  — LAVA_SPLASH burst + "Something stirs in the core" message
 *
 * Uses ParticleManager at ATMOSPHERIC priority.
 */
public class NetherAtmosphereManager {

    private static final long   CHECK_TICKS     = 80L;
    private static final double PARTICLE_SPREAD = 5.0;

    private final VestigiumNether plugin;
    private final Set<String> shownMessages = new HashSet<>();
    private BukkitRunnable task;

    public NetherAtmosphereManager(VestigiumNether plugin) {
        this.plugin = plugin;
    }

    public void init() {
        task = new BukkitRunnable() {
            @Override public void run() {
                plugin.getServer().getWorlds().forEach(world -> {
                    if (world.getEnvironment() != World.Environment.NETHER) return;
                    int omen = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
                    world.getPlayers().forEach(p -> emitForPlayer(p, omen));
                });
            }
        };
        task.runTaskTimer(plugin, CHECK_TICKS, CHECK_TICKS);

        plugin.getLogger().info("[NetherAtmosphereManager] Initialized.");
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    // -------------------------------------------------------------------------

    private void emitForPlayer(Player player, int omen) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (omen >= 400) {
            if (rng.nextInt(4) == 0) {
                queueNearPlayer(player, Particle.SOUL, null);
            }
            if (rng.nextInt(12) == 0) {
                player.playSound(player.getLocation(), Sound.ENTITY_GHAST_AMBIENT, 0.3f, 0.5f);
            }
        }

        if (omen >= 600) {
            if (rng.nextInt(3) == 0) {
                queueNearPlayer(player, Particle.FLAME, null);
            }
            if (rng.nextInt(10) == 0) {
                player.sendMessage("§c§oThe heat is thicker here. Something is burning deeper than lava.");
            }
        }

        if (omen >= 700) {
            if (rng.nextInt(5) == 0) {
                queueNearPlayer(player, Particle.LAVA, null);
            }
            String msgKey = player.getWorld().getName() + ":core_stirs";
            if (rng.nextInt(20) == 0 && shownMessages.add(msgKey)) {
                player.getWorld().getPlayers().forEach(p ->
                        p.sendMessage("§4Something stirs in the core. The nether shudders."));
                // Reset after broadcast so it can fire again later
                plugin.getServer().getScheduler().runTaskLater(plugin,
                        () -> shownMessages.remove(msgKey), 12000L);
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
