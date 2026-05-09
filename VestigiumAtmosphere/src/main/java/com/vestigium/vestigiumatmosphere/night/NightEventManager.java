package com.vestigium.vestigiumatmosphere.night;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumatmosphere.VestigiumAtmosphere;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Overworld night atmospheric events. All effects are per-player (client-side only).
 * Active only in NORMAL-environment worlds between game-time 13 000 and 23 000.
 *
 * Systems:
 *   The Watching Dark   — omen 300+, outdoors: paired SOUL_FIRE_FLAME "eyes" in the dark,
 *                         faint phantom sound, every 5–12 s per player.
 *   Night Fog Banks     — fog biomes: CLOUD particles hugging the ground every 2 s.
 *   Witch Lights        — forest/swamp biomes: floating SOUL_FIRE_FLAME wisps every 2 s.
 *   The Pilgrim's Road  — omen < 300, clear, outdoors: SOUL particle trail in a consistent
 *                         direction assigned at dusk, reset at dawn.
 *   Memory Bleed        — omen 200+, outdoors: random unsettling message + cave sound
 *                         every 90–150 s per player.
 *   Foxfire Fields      — warm/forest biomes, outdoors: END_ROD sparks near the ground.
 *   Auroral Cascade     — omen-responsive curtains high above all outdoor night players;
 *                         particle type changes with omen level.
 *   The Midnight Toll   — fires once per in-game night at game-time 18 000 (±200 ticks):
 *                         BLOCK_BELL_USE sound + message to all outdoor players.
 *
 * Does not duplicate SkyEventManager's AURORA (winter/low-omen) or STARFALL (cataclysm-end).
 */
public class NightEventManager {

    // -------------------------------------------------------------------------
    // Biome sets
    // -------------------------------------------------------------------------

    private static final Set<String> FOG_BIOMES = Set.of(
            "plains", "sunflower_plains", "meadow", "swamp", "mangrove_swamp",
            "river", "beach", "stony_shore");

    private static final Set<String> WITCH_BIOMES = Set.of(
            "forest", "flower_forest", "birch_forest", "old_growth_birch_forest",
            "dark_forest", "swamp", "mangrove_swamp", "windswept_forest");

    private static final Set<String> FOXFIRE_BIOMES = Set.of(
            "plains", "sunflower_plains", "meadow", "cherry_grove",
            "forest", "flower_forest", "birch_forest", "old_growth_birch_forest",
            "savanna", "windswept_savanna");

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final long NIGHT_START = 13_000L;
    private static final long NIGHT_END   = 23_000L;
    private static final long MIDNIGHT    = 18_000L;
    private static final long MIDNIGHT_WINDOW = 200L;

    private static final String[] MEMORY_BLEED_LINES = {
        "§8Something was standing here before you.",
        "§8You catch the sound of breathing — not yours.",
        "§8The dark moves differently when it believes you are not watching.",
        "§8A familiar face at the edge of sight. Then nothing.",
        "§8This ground has been walked before. Many times.",
        "§8The night is not empty. It has never been empty.",
        "§8You remember something you have never seen."
    };

    // -------------------------------------------------------------------------
    // Per-player state
    // -------------------------------------------------------------------------

    /** When the next Watching Dark "eyes" should appear. */
    private final Map<UUID, Long> nextWatchingDarkMs = new HashMap<>();
    /** When the next Memory Bleed message should fire. */
    private final Map<UUID, Long> nextMemoryBleedMs  = new HashMap<>();
    /** Pilgrim's Road angle (radians) assigned at dusk; absent = no road tonight. */
    private final Map<UUID, Double> pilgrimAngle     = new HashMap<>();

    // -------------------------------------------------------------------------
    // Per-world state
    // -------------------------------------------------------------------------

    /** Last in-game day (fullTime / 24 000) on which Midnight Toll fired. */
    private final Map<String, Long> lastTollDay = new HashMap<>();

    private final VestigiumAtmosphere plugin;
    private BukkitRunnable worldTask;
    private BukkitRunnable playerTask;

    public NightEventManager(VestigiumAtmosphere plugin) {
        this.plugin = plugin;
    }

    public void init() {
        startWorldTask();
        startPlayerTask();
        plugin.getLogger().info("[NightEventManager] Initialized.");
    }

    public void shutdown() {
        if (worldTask  != null) worldTask.cancel();
        if (playerTask != null) playerTask.cancel();
    }

    // -------------------------------------------------------------------------
    // World-level task — every 100 ticks (5 s)
    // -------------------------------------------------------------------------

    private void startWorldTask() {
        worldTask = new BukkitRunnable() {
            @Override public void run() {
                for (World world : plugin.getServer().getWorlds()) {
                    if (world.getEnvironment() != World.Environment.NORMAL) continue;
                    long time = world.getTime();
                    if (time < NIGHT_START || time > NIGHT_END) continue;
                    tickMidnightToll(world);
                }
            }
        };
        worldTask.runTaskTimer(plugin, 100L, 100L);
    }

    private void tickMidnightToll(World world) {
        long time = world.getTime();
        if (Math.abs(time - MIDNIGHT) > MIDNIGHT_WINDOW) return;

        long today = world.getFullTime() / 24_000;
        if (lastTollDay.getOrDefault(world.getName(), -1L) == today) return;
        lastTollDay.put(world.getName(), today);

        for (Player p : world.getPlayers()) {
            if (!isOutdoors(p)) continue;
            world.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, SoundCategory.AMBIENT, 0.5f, 0.6f);
            p.sendMessage("§8Somewhere in the dark, a bell marks the hour. No tower visible. No wind to carry it.");
        }
    }

    // -------------------------------------------------------------------------
    // Player-level task — every 40 ticks (2 s)
    // -------------------------------------------------------------------------

    private void startPlayerTask() {
        playerTask = new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (p.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
                    long time = p.getWorld().getTime();
                    boolean night = time >= NIGHT_START && time <= NIGHT_END;

                    if (!night) {
                        pilgrimAngle.remove(p.getUniqueId());
                        continue;
                    }

                    tickPlayer(p, now);
                }
            }
        };
        playerTask.runTaskTimer(plugin, 40L, 40L);
    }

    private void tickPlayer(Player player, long now) {
        int omen    = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
        Biome biome = player.getLocation().getBlock().getBiome();
        String biomeKey = biome.getKey().getKey();
        boolean outdoors = isOutdoors(player);

        // The Watching Dark
        if (omen >= 300 && outdoors) {
            Long next = nextWatchingDarkMs.get(player.getUniqueId());
            if (next == null || now >= next) {
                fireWatchingDark(player);
                long interval = 5_000L + ThreadLocalRandom.current().nextLong(7_000L);
                nextWatchingDarkMs.put(player.getUniqueId(), now + interval);
            }
        }

        // Night Fog Banks
        if (FOG_BIOMES.contains(biomeKey)) {
            fireNightFog(player);
        }

        // Witch Lights
        if (WITCH_BIOMES.contains(biomeKey)) {
            fireWitchLights(player);
        }

        // The Pilgrim's Road
        if (omen < 300 && outdoors && !player.getWorld().hasStorm()) {
            if (!pilgrimAngle.containsKey(player.getUniqueId())) {
                pilgrimAngle.put(player.getUniqueId(),
                        ThreadLocalRandom.current().nextDouble(Math.PI * 2));
            }
            firePilgrimsRoad(player, pilgrimAngle.get(player.getUniqueId()));
        } else {
            pilgrimAngle.remove(player.getUniqueId());
        }

        // Memory Bleed
        if (omen >= 200 && outdoors) {
            Long next = nextMemoryBleedMs.get(player.getUniqueId());
            if (next == null || now >= next) {
                fireMemoryBleed(player);
                long interval = 90_000L + ThreadLocalRandom.current().nextLong(60_000L);
                nextMemoryBleedMs.put(player.getUniqueId(), now + interval);
            }
        }

        // Foxfire Fields
        if (FOXFIRE_BIOMES.contains(biomeKey) && outdoors) {
            fireFoxfire(player);
        }

        // Auroral Cascade
        if (outdoors && omen >= 200) {
            fireAuroralCascade(player, omen);
        }
    }

    // -------------------------------------------------------------------------
    // Effect implementations
    // -------------------------------------------------------------------------

    private void fireWatchingDark(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = player.getWorld();
        Location origin = player.getLocation();

        double angle = rng.nextDouble(Math.PI * 2);
        double dist  = 12 + rng.nextDouble(8);
        double ex    = Math.cos(angle) * dist;
        double ez    = Math.sin(angle) * dist;
        // Perpendicular offset for the two "eye" particles
        double px = -Math.sin(angle) * 0.4;
        double pz =  Math.cos(angle) * 0.4;

        Location eyeCenter = origin.clone().add(ex, rng.nextDouble(-0.3, 0.3), ez);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, eyeCenter.clone().add( px, 0,  pz), 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, eyeCenter.clone().add(-px, 0, -pz), 1, 0, 0, 0, 0);

        if (rng.nextInt(3) == 0) {
            world.playSound(origin, Sound.ENTITY_PHANTOM_AMBIENT,
                    SoundCategory.AMBIENT, 0.15f, rng.nextFloat(0.6f, 1.0f));
        }
    }

    private void fireNightFog(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location base = player.getLocation();
        World world = player.getWorld();
        for (int i = 0; i < 8; i++) {
            world.spawnParticle(Particle.CLOUD,
                    base.clone().add(
                            rng.nextDouble(-5, 5),
                            rng.nextDouble(-0.5, 1.5),
                            rng.nextDouble(-5, 5)),
                    1, 0.3, 0.05, 0.3, 0.001);
        }
    }

    private void fireWitchLights(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location base = player.getLocation();
        World world = player.getWorld();
        int count = 3 + rng.nextInt(3);
        for (int i = 0; i < count; i++) {
            world.spawnParticle(Particle.SOUL_FIRE_FLAME,
                    base.clone().add(
                            rng.nextDouble(-8, 8),
                            1.5 + rng.nextDouble(2),
                            rng.nextDouble(-8, 8)),
                    1, 0, 0, 0, 0.01);
        }
        if (rng.nextInt(8) == 0) {
            world.playSound(base, Sound.AMBIENT_CAVE,
                    SoundCategory.AMBIENT, 0.1f, rng.nextFloat(0.8f, 1.2f));
        }
    }

    private void firePilgrimsRoad(Player player, double angle) {
        World world = player.getWorld();
        Location base = player.getLocation();
        double dx = Math.cos(angle);
        double dz = Math.sin(angle);
        for (int step = 2; step <= 20; step += 2) {
            double groundY = world.getHighestBlockYAt(
                    base.getBlockX() + (int)(dx * step),
                    base.getBlockZ() + (int)(dz * step));
            world.spawnParticle(Particle.SOUL,
                    new Location(world,
                            base.getX() + dx * step,
                            groundY + 0.15,
                            base.getZ() + dz * step),
                    1, 0.05, 0.02, 0.05, 0.005);
        }
    }

    private void fireMemoryBleed(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String line = MEMORY_BLEED_LINES[rng.nextInt(MEMORY_BLEED_LINES.length)];
        player.sendMessage(line);
        player.getWorld().playSound(player.getLocation(),
                Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.AMBIENT, 0.25f, 0.9f);
    }

    private void fireFoxfire(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location base = player.getLocation();
        World world = player.getWorld();
        int count = 4 + rng.nextInt(5);
        for (int i = 0; i < count; i++) {
            world.spawnParticle(Particle.END_ROD,
                    base.clone().add(
                            rng.nextDouble(-8, 8),
                            rng.nextDouble(0, 0.5),
                            rng.nextDouble(-8, 8)),
                    1, 0, 0, 0, 0.002);
        }
    }

    private void fireAuroralCascade(Player player, int omen) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location base = player.getLocation();
        World world = player.getWorld();

        Particle particle;
        int count;
        if (omen >= 600) {
            particle = Particle.FLAME;
            count = 6;
        } else if (omen >= 400) {
            particle = Particle.CRIT;
            count = 5;
        } else {
            particle = Particle.DRAGON_BREATH;
            count = 4;
        }

        for (int i = 0; i < count; i++) {
            world.spawnParticle(particle,
                    base.clone().add(
                            rng.nextDouble(-15, 15),
                            30 + rng.nextDouble(20),
                            rng.nextDouble(-15, 15)),
                    1, 0, 0, 0, 0.005);
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private boolean isOutdoors(Player player) {
        return player.getWorld().getHighestBlockYAt(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockZ())
                <= player.getLocation().getBlockY() + 1;
    }
}
