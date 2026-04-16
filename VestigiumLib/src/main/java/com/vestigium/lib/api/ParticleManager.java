package com.vestigium.lib.api;

import com.vestigium.lib.model.ParticleDensity;
import com.vestigium.lib.model.ParticlePriority;
import com.vestigium.lib.util.Keys;
import com.vestigium.lib.util.TPSMonitor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * All atmospheric particle calls for the Vestigium suite must go through this manager.
 * Never call Bukkit particle APIs directly from atmosphere plugins.
 *
 * Responsibilities:
 * - Distance culling: atmospheric particles > 24 blocks from any player do not fire.
 * - TPS scaling: auto-reduces density at < 18 TPS, further at < 15, suspends at < 12.
 * - Player opt-out: /vestigium particles [full|reduced|minimal] stored in player PDC.
 * - Priority gating: GAMEPLAY particles bypass all budget checks; ATMOSPHERIC do not.
 */
public class ParticleManager {

    /** Atmospheric particles are culled beyond this distance (in blocks) from the nearest player. */
    public static final double CULL_DISTANCE = 24.0;

    private final Plugin plugin;
    private final TPSMonitor tpsMonitor;

    public ParticleManager(Plugin plugin, TPSMonitor tpsMonitor) {
        this.plugin = plugin;
        this.tpsMonitor = tpsMonitor;
    }

    /**
     * Queues and fires a particle effect, applying all applicable budget rules.
     *
     * @param location  where to spawn the particle
     * @param type      the Bukkit Particle type
     * @param data      extra data for the particle (pass null if none required)
     * @param priority  GAMEPLAY bypasses budget; ATMOSPHERIC is subject to all rules
     * @param <T>       the particle data type
     */
    public <T> void queueParticle(Location location, Particle type, T data, ParticlePriority priority) {
        if (location == null || location.getWorld() == null) return;

        if (priority == ParticlePriority.ATMOSPHERIC) {
            // TPS gate — suspend entirely if critically low
            if (tpsMonitor.isCriticallyLow()) return;

            // Distance culling — skip if no players are nearby
            if (!isPlayerNearby(location)) return;
        }

        // Find the nearest player to send the packet to (within cull distance)
        Player nearest = getNearestPlayer(location);
        if (nearest == null) return;

        // Respect player opt-out density setting
        double playerDensity = getPlayerDensityMultiplier(nearest);
        if (priority == ParticlePriority.ATMOSPHERIC && playerDensity == 0.0) return;

        // Apply combined density (TPS multiplier × player preference)
        double density = priority == ParticlePriority.GAMEPLAY
                ? 1.0
                : tpsMonitor.getDensityMultiplier() * playerDensity;

        // Stochastic culling: skip proportionally based on density < 1.0
        if (density < 1.0 && Math.random() > density) return;

        spawnParticle(location, type, data);
    }

    /**
     * Convenience overload for particles with no extra data.
     */
    public void queueParticle(Location location, Particle type, ParticlePriority priority) {
        queueParticle(location, type, null, priority);
    }

    /**
     * Sets a player's preferred particle density, stored in their PDC.
     * Called by the /vestigium particles command.
     *
     * @param playerUUID the player's UUID
     * @param density    the desired density setting
     */
    public void setPlayerParticleDensity(UUID playerUUID, ParticleDensity density) {
        Player player = plugin.getServer().getPlayer(playerUUID);
        if (player == null) return;
        player.getPersistentDataContainer()
                .set(Keys.PARTICLE_DENSITY, PersistentDataType.STRING, density.name());
    }

    /**
     * Returns a player's current particle density preference.
     * Defaults to FULL if not set.
     */
    public ParticleDensity getPlayerParticleDensity(UUID playerUUID) {
        Player player = plugin.getServer().getPlayer(playerUUID);
        if (player == null) return ParticleDensity.FULL;
        String stored = player.getPersistentDataContainer()
                .getOrDefault(Keys.PARTICLE_DENSITY, PersistentDataType.STRING, ParticleDensity.FULL.name());
        try {
            return ParticleDensity.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return ParticleDensity.FULL;
        }
    }

    // -------------------------------------------------------------------------

    private boolean isPlayerNearby(Location location) {
        return location.getWorld().getPlayers().stream()
                .anyMatch(p -> p.getLocation().distanceSquared(location) <= CULL_DISTANCE * CULL_DISTANCE);
    }

    private Player getNearestPlayer(Location location) {
        return location.getWorld().getPlayers().stream()
                .filter(p -> p.getLocation().distanceSquared(location) <= CULL_DISTANCE * CULL_DISTANCE)
                .min((a, b) -> Double.compare(
                        a.getLocation().distanceSquared(location),
                        b.getLocation().distanceSquared(location)))
                .orElse(null);
    }

    private double getPlayerDensityMultiplier(Player player) {
        String stored = player.getPersistentDataContainer()
                .getOrDefault(Keys.PARTICLE_DENSITY, PersistentDataType.STRING, ParticleDensity.FULL.name());
        try {
            return ParticleDensity.valueOf(stored).getMultiplier();
        } catch (IllegalArgumentException e) {
            return ParticleDensity.FULL.getMultiplier();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void spawnParticle(Location location, Particle type, T data) {
        if (data != null) {
            location.getWorld().spawnParticle(type, location, 1, 0, 0, 0, 0, (T) data);
        } else {
            location.getWorld().spawnParticle(type, location, 1, 0, 0, 0, 0);
        }
    }
}
