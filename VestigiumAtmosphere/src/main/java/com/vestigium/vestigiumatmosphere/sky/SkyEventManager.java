package com.vestigium.vestigiumatmosphere.sky;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.OmenThresholdEvent;
import com.vestigium.lib.model.ParticlePriority;
import com.vestigium.lib.model.Season;
import com.vestigium.vestigiumatmosphere.VestigiumAtmosphere;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sky-level events: meteors, aurora, eclipse, starfall vigil.
 *
 * METEOR_SHOWER   — omen 500+; random nights; GAMEPLAY particle streak + sound + omen add
 * AURORA          — WINTER nights, omen < 300; ATMOSPHERIC colored beacon beams via particles
 * ECLIPSE         — omen 700+ at dawn (ticks 23000-24000); warning message + omen spike
 * STARFALL_VIGIL  — recovery cataclysm type fires this; persistent falling star particles for 10 min
 *
 * Each sky event is checked once per minute (1200 ticks).
 * Active sky events are stored in a Set and ticked separately at 20t intervals.
 */
public class SkyEventManager {

    private static final long CHECK_TICKS  = 1_200L;
    private static final long TICK_TICKS   = 20L;

    // Duration ticks for starfall vigil
    private static final long STARFALL_DURATION = 12_000L;

    private final VestigiumAtmosphere plugin;
    private final Map<String, Long> activeEvents = new HashMap<>(); // eventKey → expiry tick
    private BukkitRunnable checkTask;
    private BukkitRunnable tickTask;

    public SkyEventManager(VestigiumAtmosphere plugin) {
        this.plugin = plugin;
    }

    public void init() {
        // Listen for omen threshold — eclipse trigger
        VestigiumLib.getEventBus().subscribe(OmenThresholdEvent.class, event -> {
            if (event.getThreshold() == 700 && event.isAscending()) {
                plugin.getServer().getWorlds().stream()
                        .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                        .forEach(this::triggerEclipse);
            }
        });

        // Listen for cataclysm end events — starfall vigil
        VestigiumLib.getEventBus().subscribe(
                com.vestigium.lib.event.CataclysmEndEvent.class, event -> {
            if ("STARFALL_VIGIL".equals(event.getCataclysmType()) || "THE_LONG_EXHALE".equals(event.getCataclysmType())) {
                plugin.getServer().getWorlds().stream()
                        .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                        .forEach(this::triggerStarfallVigil);
            }
        });

        // Periodic check for meteor/aurora
        checkTask = new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getServer().getWorlds().forEach(world -> {
                    if (world.getEnvironment() != World.Environment.NORMAL) return;
                    evaluateSkyEvents(world);
                });
            }
        };
        checkTask.runTaskTimer(plugin, CHECK_TICKS, CHECK_TICKS);

        // Tick loop for active events
        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                long now = plugin.getServer().getWorlds().get(0).getFullTime();
                activeEvents.entrySet().removeIf(e -> now > e.getValue());
                plugin.getServer().getWorlds().forEach(world -> {
                    if (world.getEnvironment() != World.Environment.NORMAL) return;
                    tickActiveEvents(world, now);
                });
            }
        };
        tickTask.runTaskTimer(plugin, TICK_TICKS, TICK_TICKS);

        plugin.getLogger().info("[SkyEventManager] Initialized.");
    }

    // -------------------------------------------------------------------------

    private void evaluateSkyEvents(World world) {
        int omen      = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
        Season season = VestigiumLib.getSeasonAPI().getCurrentSeason();
        long time     = world.getTime();
        boolean night = time >= 13_000 && time <= 23_000;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Meteor shower — omen 500+, night, 10% chance per minute
        if (omen >= 500 && night && rng.nextInt(10) == 0) {
            triggerMeteorShower(world);
        }

        // Aurora — winter nights, omen < 300, 20% chance per minute
        if (season == Season.WINTER && night && omen < 300 && rng.nextInt(5) == 0) {
            triggerAurora(world);
        }
    }

    private void triggerMeteorShower(World world) {
        String key = world.getName() + ":METEOR";
        if (activeEvents.containsKey(key)) return;
        long expiry = world.getFullTime() + 600; // 30 seconds of meteors
        activeEvents.put(key, expiry);
        world.getPlayers().forEach(p ->
                p.sendMessage("§6Streaks of fire cross the sky. The omen grows."));
        VestigiumLib.getOmenAPI().addOmen(10);
    }

    private void triggerAurora(World world) {
        String key = world.getName() + ":AURORA";
        if (activeEvents.containsKey(key)) return;
        long expiry = world.getFullTime() + 3_600; // 3 min
        activeEvents.put(key, expiry);
        world.getPlayers().forEach(p ->
                p.sendMessage("§bThe sky moves in curtains of light. The cold has a memory."));
    }

    private void triggerEclipse(World world) {
        String key = world.getName() + ":ECLIPSE";
        activeEvents.put(key, world.getFullTime() + 600);
        world.getPlayers().forEach(p ->
                p.sendMessage("§4The sun dims. Something passes between you and warmth."));
        VestigiumLib.getOmenAPI().addOmen(25);
    }

    private void triggerStarfallVigil(World world) {
        String key = world.getName() + ":STARFALL";
        activeEvents.put(key, world.getFullTime() + STARFALL_DURATION);
        world.getPlayers().forEach(p ->
                p.sendMessage("§eThe vigil begins. The stars fall slowly, and the world breathes."));
        VestigiumLib.getOmenAPI().subtractOmen(30);
    }

    private void tickActiveEvents(World world, long now) {
        List<Player> players = world.getPlayers();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (activeEvents.containsKey(world.getName() + ":METEOR")) {
            players.forEach(p -> {
                if (rng.nextInt(5) == 0) emitMeteorStreak(p);
            });
        }

        if (activeEvents.containsKey(world.getName() + ":AURORA")) {
            players.forEach(p -> {
                if (rng.nextInt(3) == 0) emitAuroraParticle(p, rng);
            });
        }

        if (activeEvents.containsKey(world.getName() + ":STARFALL")) {
            players.forEach(p -> {
                if (rng.nextInt(4) == 0) emitFallingStar(p, rng);
            });
        }
    }

    // -------------------------------------------------------------------------
    // Particle emitters
    // -------------------------------------------------------------------------

    private void emitMeteorStreak(Player player) {
        Location base = player.getLocation().clone().add(
                ThreadLocalRandom.current().nextInt(-20, 20), 30,
                ThreadLocalRandom.current().nextInt(-20, 20));
        VestigiumLib.getParticleManager().queueParticle(base, Particle.FLAME, null, ParticlePriority.GAMEPLAY);
    }

    private void emitAuroraParticle(Player player, ThreadLocalRandom rng) {
        Location loc = player.getLocation().clone().add(
                rng.nextDouble(-15, 15), 20 + rng.nextDouble(5), rng.nextDouble(-15, 15));
        VestigiumLib.getParticleManager().queueParticle(loc, Particle.DRAGON_BREATH, null, ParticlePriority.ATMOSPHERIC);
    }

    private void emitFallingStar(Player player, ThreadLocalRandom rng) {
        Location loc = player.getLocation().clone().add(
                rng.nextDouble(-10, 10), 25 + rng.nextDouble(5), rng.nextDouble(-10, 10));
        VestigiumLib.getParticleManager().queueParticle(loc, Particle.END_ROD, null, ParticlePriority.ATMOSPHERIC);
    }

    public void shutdown() {
        if (checkTask != null) checkTask.cancel();
        if (tickTask  != null) tickTask.cancel();
    }

    public boolean isEventActive(World world, String eventType) {
        return activeEvents.containsKey(world.getName() + ":" + eventType);
    }
}
