package com.vestigium.vestigiumocean.depth;

import com.vestigium.vestigiumocean.VestigiumOcean;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Depth-pressure system for ocean players.
 *
 * Applied every 100 ticks to players in ocean biomes:
 *   Y < 0   → Slowness I (pressure begins)
 *   Y < -32 → Slowness I + Mining Fatigue I (structural strain)
 *   Y < -48 → Slowness I + Mining Fatigue I + rare pressure-crack messages
 *
 * Effects refresh each cycle while the player remains at depth;
 * they expire naturally when the player ascends.
 */
public class OceanDepthManager {

    private static final long CHECK_TICKS = 100L;

    private static final Set<String> OCEAN_BIOMES = Set.of(
            "ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean",
            "frozen_ocean", "deep_frozen_ocean", "lukewarm_ocean",
            "deep_lukewarm_ocean", "warm_ocean");

    private static final String[] PRESSURE_MESSAGES = {
        "§7The water weighs more than it should.",
        "§7Your ears ache. The dark below is absolute.",
        "§7Something groans in the rock. Not the rock.",
        "§7The light from above is a memory now."
    };

    private final VestigiumOcean plugin;
    private BukkitRunnable task;

    public OceanDepthManager(VestigiumOcean plugin) {
        this.plugin = plugin;
    }

    public void init() {
        task = new BukkitRunnable() {
            @Override public void run() {
                plugin.getServer().getWorlds().forEach(world -> {
                    if (world.getEnvironment() != World.Environment.NORMAL) return;
                    world.getPlayers().forEach(OceanDepthManager.this::applyDepthEffects);
                });
            }
        };
        task.runTaskTimer(plugin, CHECK_TICKS, CHECK_TICKS);

        plugin.getLogger().info("[OceanDepthManager] Initialized.");
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    // -------------------------------------------------------------------------

    private void applyDepthEffects(Player player) {
        String biome = player.getLocation().getBlock().getBiome().getKey().getKey();
        if (!OCEAN_BIOMES.contains(biome)) return;

        int y = player.getLocation().getBlockY();

        if (y >= 0) return;

        // Slowness I at any ocean depth below Y=0
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 140, 0, false, false));

        if (y < -32) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 140, 0, false, false));
        }

        if (y < -48 && ThreadLocalRandom.current().nextInt(8) == 0) {
            String msg = PRESSURE_MESSAGES[ThreadLocalRandom.current()
                    .nextInt(PRESSURE_MESSAGES.length)];
            player.sendMessage(msg);
        }
    }
}
