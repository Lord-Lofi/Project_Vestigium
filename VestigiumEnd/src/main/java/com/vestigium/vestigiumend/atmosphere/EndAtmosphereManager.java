package com.vestigium.vestigiumend.atmosphere;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumend.VestigiumEnd;
import org.bukkit.*;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * End-specific atmospheric events. All active in THE_END environment only.
 *
 * Systems:
 *   Shattering Echoes       — END_CRYSTAL_HURT sound + CRIT burst every 60–120 s per player.
 *   Void Whispers           — ENDERMAN_STARE at very low volume + whisper message every 90–150 s.
 *   Enderman Convergence    — 5+ Endermen within 20 blocks triggers DRAGON_BREATH burst + sound,
 *                             30 s per-player cooldown.
 *   Dragon's Breath as Lament — DRAGON_BREATH particles drifting down from above while the
 *                               dragon has been killed; fires every 80 ticks per player.
 *   Void Tide               — world-level wave every 3–5 min: particles + sound + message + omen.
 *   Star Map Ceiling        — END_ROD sparks high above every 60–90 s per player.
 */
public class EndAtmosphereManager {

    private static final String[] VOID_WHISPER_LINES = {
        "§8You should not be here.",
        "§8The void has a sound. You are hearing it now.",
        "§8Everything that enters the End becomes part of the End.",
        "§8The silence between heartbeats is not empty.",
        "§8The island is watching. It has always been watching.",
        "§8You are very small, and the void is very patient."
    };

    private static final long SHATTERING_MIN_MS  = 60_000L;
    private static final long SHATTERING_MAX_MS  = 120_000L;
    private static final long WHISPER_MIN_MS      = 90_000L;
    private static final long WHISPER_MAX_MS      = 150_000L;
    private static final long CONVERGENCE_CD_MS   = 30_000L;
    private static final long LAMENT_TICKS        = 80L;
    private static final long TIDE_MIN_MS         = 3 * 60_000L;
    private static final long TIDE_MAX_MS         = 5 * 60_000L;
    private static final long STAR_MIN_MS         = 60_000L;
    private static final long STAR_MAX_MS         = 90_000L;
    private static final int  CONVERGENCE_RADIUS  = 20;
    private static final int  CONVERGENCE_COUNT   = 5;

    private final Map<UUID, Long> nextShatteringMs  = new HashMap<>();
    private final Map<UUID, Long> nextWhisperMs     = new HashMap<>();
    private final Map<UUID, Long> nextConvergenceMs = new HashMap<>();
    private final Map<UUID, Long> nextStarMs        = new HashMap<>();
    private final Map<String, Long> nextTideMs      = new HashMap<>();

    private long lamentTickCounter = 0;

    private final VestigiumEnd plugin;
    private BukkitRunnable tickTask;

    public EndAtmosphereManager(VestigiumEnd plugin) {
        this.plugin = plugin;
    }

    public void init() {
        tickTask = new BukkitRunnable() {
            @Override public void run() {
                lamentTickCounter++;
                long now = System.currentTimeMillis();
                for (World world : plugin.getServer().getWorlds()) {
                    if (world.getEnvironment() != World.Environment.THE_END) continue;
                    tickVoidTide(world, now);
                    for (Player p : world.getPlayers()) {
                        tickPlayer(p, world, now);
                    }
                }
            }
        };
        tickTask.runTaskTimer(plugin, 40L, 40L);
        plugin.getLogger().info("[EndAtmosphereManager] Initialized.");
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
    }

    // -------------------------------------------------------------------------

    private void tickVoidTide(World world, long now) {
        if (world.getPlayers().isEmpty()) return;
        if (now < nextTideMs.getOrDefault(world.getName(), 0L)) return;
        nextTideMs.put(world.getName(),
                now + ThreadLocalRandom.current().nextLong(TIDE_MIN_MS, TIDE_MAX_MS));
        fireVoidTide(world);
    }

    private void tickPlayer(Player player, World world, long now) {
        UUID id = player.getUniqueId();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Shattering Echoes
        if (now >= nextShatteringMs.getOrDefault(id, 0L)) {
            fireShatteringEcho(player);
            nextShatteringMs.put(id, now + rng.nextLong(SHATTERING_MIN_MS, SHATTERING_MAX_MS));
        }

        // Void Whispers
        if (now >= nextWhisperMs.getOrDefault(id, 0L)) {
            fireVoidWhisper(player);
            nextWhisperMs.put(id, now + rng.nextLong(WHISPER_MIN_MS, WHISPER_MAX_MS));
        }

        // Enderman Convergence
        if (now >= nextConvergenceMs.getOrDefault(id, 0L)) {
            if (countNearbyEndermen(player) >= CONVERGENCE_COUNT) {
                fireEndermanConvergence(player);
                nextConvergenceMs.put(id, now + CONVERGENCE_CD_MS);
            }
        }

        // Dragon's Breath as Lament
        if (lamentTickCounter % LAMENT_TICKS == 0 && isDragonKilled(world)) {
            fireLament(player);
        }

        // Star Map Ceiling
        if (now >= nextStarMs.getOrDefault(id, 0L)) {
            fireStarMap(player);
            nextStarMs.put(id, now + rng.nextLong(STAR_MIN_MS, STAR_MAX_MS));
        }
    }

    // -------------------------------------------------------------------------
    // Effect implementations
    // -------------------------------------------------------------------------

    private void fireShatteringEcho(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location loc = player.getLocation().clone().add(
                rng.nextDouble(-15, 15), rng.nextDouble(-5, 5), rng.nextDouble(-15, 15));
        player.getWorld().playSound(loc, Sound.ENTITY_ENDER_DRAGON_HURT,
                SoundCategory.AMBIENT, 0.3f, rng.nextFloat(0.6f, 1.2f));
        player.getWorld().spawnParticle(Particle.CRIT, loc, 12, 0.8, 0.8, 0.8, 0.1);
        if (rng.nextInt(3) == 0) {
            player.sendMessage("§8Something shatters in the distance.");
        }
    }

    private void fireVoidWhisper(Player player) {
        player.getWorld().playSound(player.getLocation(),
                Sound.ENTITY_ENDERMAN_STARE, SoundCategory.AMBIENT, 0.1f, 1.2f);
        String line = VOID_WHISPER_LINES[ThreadLocalRandom.current().nextInt(VOID_WHISPER_LINES.length)];
        player.sendMessage(line);
    }

    private void fireEndermanConvergence(Player player) {
        Location loc = player.getLocation();
        World world = player.getWorld();
        world.playSound(loc, Sound.ENTITY_ENDERMAN_AMBIENT, SoundCategory.AMBIENT, 0.6f, 0.5f);
        world.spawnParticle(Particle.DRAGON_BREATH, loc.clone().add(0, 1, 0),
                30, 2, 1, 2, 0.03);
        player.sendMessage("§5The Endermen regard you with singular attention.");
    }

    private void fireLament(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location base = player.getLocation();
        World world = player.getWorld();
        for (int i = 0; i < 5; i++) {
            world.spawnParticle(Particle.DRAGON_BREATH,
                    base.clone().add(
                            rng.nextDouble(-8, 8),
                            20 + rng.nextDouble(10),
                            rng.nextDouble(-8, 8)),
                    1, 0.1, 0.05, 0.1, 0.02);
        }
    }

    private void fireVoidTide(World world) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        world.playSound(world.getSpawnLocation(),
                Sound.ENTITY_ENDERMAN_AMBIENT, SoundCategory.AMBIENT, 0.5f, 0.4f);
        for (Player p : world.getPlayers()) {
            Location loc = p.getLocation();
            world.spawnParticle(Particle.DRAGON_BREATH, loc.clone().add(0, 1, 0),
                    25, 12, 0.5, 12, 0.02);
            p.sendMessage("§5A tide passes through the End. Something shifted.");
        }
        VestigiumLib.getOmenAPI().addOmen(1);
    }

    private void fireStarMap(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location base = player.getLocation();
        World world = player.getWorld();
        for (int i = 0; i < 8; i++) {
            world.spawnParticle(Particle.END_ROD,
                    base.clone().add(
                            rng.nextDouble(-20, 20),
                            40 + rng.nextDouble(40),
                            rng.nextDouble(-20, 20)),
                    1, 0, 0, 0, 0);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int countNearbyEndermen(Player player) {
        int count = 0;
        for (Entity e : player.getWorld().getNearbyEntities(
                player.getLocation(), CONVERGENCE_RADIUS, CONVERGENCE_RADIUS, CONVERGENCE_RADIUS)) {
            if (e instanceof Enderman) count++;
        }
        return count;
    }

    private boolean isDragonKilled(World world) {
        var battle = world.getEnderDragonBattle();
        return battle != null && battle.hasBeenPreviouslyKilled();
    }
}
