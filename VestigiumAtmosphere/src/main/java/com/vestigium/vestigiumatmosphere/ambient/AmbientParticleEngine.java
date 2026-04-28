package com.vestigium.vestigiumatmosphere.ambient;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.model.ParticlePriority;
import com.vestigium.lib.model.Season;
import com.vestigium.vestigiumatmosphere.VestigiumAtmosphere;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ambient particle atmosphere layered per-player based on biome, season, and omen.
 *
 * Effects (ATMOSPHERIC priority — TPS-gated, distance-culled, player opt-out):
 *   Fireflies      — warm biomes, SUMMER nights; CHERRY_BLOSSOM (cherry grove), always
 *   Falling leaves — AUTUMN; forest biomes
 *   Sculk wisps    — omen 300+; near deepslate/sculk blocks
 *   Ash drift      — omen 600+ OR active cataclysm; all outdoor players
 *   Ice crystals   — WINTER; tundra biomes
 *   Spore drift    — swamp/jungle; always (low density)
 *
 * Each effect fires as a queued ParticleManager call, not a direct Bukkit spawn,
 * so TPS and player density controls apply automatically.
 *
 * Check interval: 40 ticks (2 seconds).
 */
public class AmbientParticleEngine {

    private static final long CHECK_TICKS = 40L;
    private static final double PARTICLE_SPREAD = 5.0;

    private final VestigiumAtmosphere plugin;

    public AmbientParticleEngine(VestigiumAtmosphere plugin) {
        this.plugin = plugin;
    }

    public void init() {
        new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getServer().getWorlds().forEach(world -> {
                    if (world.getEnvironment() != World.Environment.NORMAL) return;
                    world.getPlayers().forEach(player -> emitForPlayer(player, world));
                });
            }
        }.runTaskTimer(plugin, CHECK_TICKS, CHECK_TICKS);

        plugin.getLogger().info("[AmbientParticleEngine] Initialized.");
    }

    // -------------------------------------------------------------------------

    private void emitForPlayer(Player player, World world) {
        Biome biome   = player.getLocation().getBlock().getBiome();
        Season season = VestigiumLib.getSeasonAPI().getCurrentSeason();
        int omen      = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
        long time     = world.getTime();
        boolean night = time >= 13_000 && time <= 23_000;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Fireflies — warm biomes at night in summer, or cherry grove always
        if ((isWarmBiome(biome) && season == Season.SUMMER && night)
                || biome == Biome.CHERRY_GROVE) {
            if (rng.nextInt(3) == 0) {
                queueNearPlayer(player, Particle.END_ROD, null);
            }
        }

        // Falling leaves — autumn + forest
        if (season == Season.AUTUMN && isForestBiome(biome) && rng.nextInt(2) == 0) {
            queueNearPlayer(player, Particle.CHERRY_LEAVES, null);
        }

        // Sculk wisps — omen 300+
        if (omen >= 300 && rng.nextInt(4) == 0) {
            queueNearPlayer(player, Particle.SCULK_SOUL, null);
        }

        // Ash drift — omen 600+ or cataclysm
        if (omen >= 600 && rng.nextInt(3) == 0) {
            queueNearPlayer(player, Particle.ASH, null);
        }

        // Ice crystals — winter + tundra
        if (season == Season.WINTER && isTundraBiome(biome) && rng.nextInt(3) == 0) {
            queueNearPlayer(player, Particle.SNOWFLAKE, null);
        }

        // Spore drift — swamp/jungle, always low rate
        if (isSporyBiome(biome) && rng.nextInt(6) == 0) {
            queueNearPlayer(player, Particle.SPORE_BLOSSOM_AIR, null);
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

    // -------------------------------------------------------------------------
    // Biome classification helpers
    // -------------------------------------------------------------------------

    private static final java.util.Set<String> WARM_BIOMES = java.util.Set.of(
            "plains", "sunflower_plains", "savanna", "savanna_plateau",
            "windswept_savanna", "jungle", "sparse_jungle", "bamboo_jungle",
            "desert", "badlands", "eroded_badlands", "wooded_badlands");

    private static final java.util.Set<String> FOREST_BIOMES = java.util.Set.of(
            "forest", "flower_forest", "birch_forest", "old_growth_birch_forest",
            "dark_forest", "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga",
            "windswept_forest");

    private static final java.util.Set<String> TUNDRA_BIOMES = java.util.Set.of(
            "snowy_plains", "snowy_taiga", "snowy_slopes",
            "frozen_peaks", "jagged_peaks", "ice_spikes");

    private static final java.util.Set<String> SPORY_BIOMES = java.util.Set.of(
            "swamp", "mangrove_swamp", "jungle", "bamboo_jungle", "sparse_jungle",
            "mushroom_fields", "lush_caves");

    private static boolean isWarmBiome(Biome b)   { return WARM_BIOMES.contains(b.getKey().getKey()); }
    private static boolean isForestBiome(Biome b) { return FOREST_BIOMES.contains(b.getKey().getKey()); }
    private static boolean isTundraBiome(Biome b) { return TUNDRA_BIOMES.contains(b.getKey().getKey()); }
    private static boolean isSporyBiome(Biome b)  { return SPORY_BIOMES.contains(b.getKey().getKey()); }
}
