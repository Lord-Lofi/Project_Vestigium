package com.vestigium.vestigiumnether.event;

import com.vestigium.vestigiumnether.VestigiumNether;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Nether-specific atmospheric events. All active in NETHER environment only.
 *
 * Systems:
 *   Soul Resonance         — player's own name whispered back every 5–8 min.
 *   Expedition Echoes      — Antecedent expedition log fragments every 90–150 s.
 *   The Breathing Ceiling  — CLOUD particles falling from above every 2 s.
 *   Wither Skeleton Pause  — nearby Wither Skeletons turn to face the Breach Point
 *                            (origin) with WITHER_AMBIENT sound every 5–7 min.
 *   Crimson Pulse          — LAVA particle ring radiating from the player every 60–90 s.
 *   Nether Choir           — staggered three-part haunting sound sequence every 60–120 s.
 */
public class NetherEventManager {

    private static final String[] EXPEDITION_ECHO_LINES = {
        "§8Equipment logged. Last waystone: three days north.",
        "§8The survey team did not return from the lower passage.",
        "§8Notation: the Piglins were restless. Do not make eye contact.",
        "§8Personal log, day 41. We have stopped counting.",
        "§8The heat here is structural. It is part of the stone.",
        "§8Field note: cartography is impossible here. The tunnels rearrange.",
        "§8We found the road. We are not sure we should follow it."
    };

    private static final long RESONANCE_MIN_MS  = 5 * 60_000L;
    private static final long RESONANCE_MAX_MS  = 8 * 60_000L;
    private static final long ECHO_MIN_MS       = 90_000L;
    private static final long ECHO_MAX_MS       = 150_000L;
    private static final long PAUSE_MIN_MS      = 5 * 60_000L;
    private static final long PAUSE_MAX_MS      = 7 * 60_000L;
    private static final long PULSE_MIN_MS      = 60_000L;
    private static final long PULSE_MAX_MS      = 90_000L;
    private static final long CHOIR_MIN_MS      = 60_000L;
    private static final long CHOIR_MAX_MS      = 120_000L;
    private static final int  PAUSE_MOB_RADIUS  = 20;

    private final Map<UUID, Long> nextResonanceMs  = new HashMap<>();
    private final Map<UUID, Long> nextEchoMs       = new HashMap<>();
    private final Map<UUID, Long> nextPauseMs      = new HashMap<>();
    private final Map<UUID, Long> nextPulseMs      = new HashMap<>();
    private final Map<UUID, Long> nextChoirMs      = new HashMap<>();

    private final VestigiumNether plugin;
    private BukkitRunnable tickTask;

    public NetherEventManager(VestigiumNether plugin) {
        this.plugin = plugin;
    }

    public void init() {
        tickTask = new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                for (World world : plugin.getServer().getWorlds()) {
                    if (world.getEnvironment() != World.Environment.NETHER) continue;
                    for (Player p : world.getPlayers()) {
                        tickPlayer(p, now);
                    }
                }
            }
        };
        tickTask.runTaskTimer(plugin, 40L, 40L);
        plugin.getLogger().info("[NetherEventManager] Initialized.");
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
    }

    // -------------------------------------------------------------------------

    private void tickPlayer(Player player, long now) {
        UUID id = player.getUniqueId();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Soul Resonance
        if (now >= nextResonanceMs.getOrDefault(id, 0L)) {
            fireSoulResonance(player);
            nextResonanceMs.put(id, now + rng.nextLong(RESONANCE_MIN_MS, RESONANCE_MAX_MS));
        }

        // Expedition Echoes
        if (now >= nextEchoMs.getOrDefault(id, 0L)) {
            fireExpeditionEcho(player);
            nextEchoMs.put(id, now + rng.nextLong(ECHO_MIN_MS, ECHO_MAX_MS));
        }

        // The Breathing Ceiling (always active)
        fireBreathingCeiling(player);

        // Wither Skeleton Pause
        if (now >= nextPauseMs.getOrDefault(id, 0L)) {
            fireWitherPause(player);
            nextPauseMs.put(id, now + rng.nextLong(PAUSE_MIN_MS, PAUSE_MAX_MS));
        }

        // Crimson Pulse
        if (now >= nextPulseMs.getOrDefault(id, 0L)) {
            fireCrimsonPulse(player);
            nextPulseMs.put(id, now + rng.nextLong(PULSE_MIN_MS, PULSE_MAX_MS));
        }

        // Nether Choir
        if (now >= nextChoirMs.getOrDefault(id, 0L)) {
            fireNetherChoir(player);
            nextChoirMs.put(id, now + rng.nextLong(CHOIR_MIN_MS, CHOIR_MAX_MS));
        }
    }

    // -------------------------------------------------------------------------
    // Effect implementations
    // -------------------------------------------------------------------------

    private void fireSoulResonance(Player player) {
        player.getWorld().playSound(player.getLocation(),
                Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.AMBIENT, 0.2f, 0.7f);
        player.sendMessage("§8..." + player.getName() + "...");
    }

    private void fireExpeditionEcho(Player player) {
        String line = EXPEDITION_ECHO_LINES[
                ThreadLocalRandom.current().nextInt(EXPEDITION_ECHO_LINES.length)];
        player.sendMessage(line);
        player.getWorld().playSound(player.getLocation(),
                Sound.BLOCK_DEEPSLATE_STEP, SoundCategory.AMBIENT, 0.15f, 0.5f);
    }

    private void fireBreathingCeiling(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location base = player.getLocation();
        World world = player.getWorld();
        for (int i = 0; i < 5; i++) {
            world.spawnParticle(Particle.CLOUD,
                    base.clone().add(
                            rng.nextDouble(-6, 6),
                            10 + rng.nextDouble(5),
                            rng.nextDouble(-6, 6)),
                    1, 0.2, 0.05, 0.2, 0.005);
        }
        if (rng.nextInt(10) == 0) {
            world.playSound(base, Sound.ENTITY_GHAST_AMBIENT,
                    SoundCategory.AMBIENT, 0.08f, rng.nextFloat(0.4f, 0.8f));
        }
    }

    private void fireWitherPause(Player player) {
        Location breachPoint = new Location(player.getWorld(), 0,
                player.getLocation().getY(), 0);
        List<WitherSkeleton> nearby = new ArrayList<>();
        for (Entity e : player.getWorld().getNearbyEntities(
                player.getLocation(), PAUSE_MOB_RADIUS, PAUSE_MOB_RADIUS, PAUSE_MOB_RADIUS)) {
            if (e instanceof WitherSkeleton ws) nearby.add(ws);
        }
        if (nearby.isEmpty()) return;

        nearby.forEach(ws -> {
            ws.lookAt(breachPoint);
            ((Mob) ws).setTarget(null);
        });
        player.getWorld().playSound(player.getLocation(),
                Sound.ENTITY_WITHER_AMBIENT, SoundCategory.HOSTILE, 0.35f, 0.6f);
        player.sendActionBar(Component.text("§8The Wither Skeletons pause, and face somewhere distant."));
    }

    private void fireCrimsonPulse(Player player) {
        Location center = player.getLocation();
        World world = player.getWorld();
        world.playSound(center, Sound.BLOCK_LAVA_POP, SoundCategory.AMBIENT, 0.4f, 0.5f);
        for (int i = 0; i < 24; i++) {
            double angle = (Math.PI * 2 / 24) * i;
            world.spawnParticle(Particle.LAVA,
                    center.clone().add(Math.cos(angle) * 5, 0.1, Math.sin(angle) * 5),
                    1, 0, 0, 0, 0);
        }
    }

    private void fireNetherChoir(Player player) {
        Location loc = player.getLocation();
        World world = player.getWorld();
        world.playSound(loc, Sound.ENTITY_GHAST_AMBIENT, SoundCategory.AMBIENT, 0.4f, 0.5f);
        new BukkitRunnable() {
            @Override public void run() {
                if (player.isOnline() && player.getWorld().getEnvironment() == World.Environment.NETHER) {
                    player.getWorld().playSound(player.getLocation(),
                            Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.AMBIENT, 0.3f, 0.9f);
                }
            }
        }.runTaskLater(plugin, 40L);
        new BukkitRunnable() {
            @Override public void run() {
                if (player.isOnline() && player.getWorld().getEnvironment() == World.Environment.NETHER) {
                    player.getWorld().playSound(player.getLocation(),
                            Sound.ENTITY_WITHER_AMBIENT, SoundCategory.HOSTILE, 0.2f, 0.7f);
                }
            }
        }.runTaskLater(plugin, 80L);
    }
}
