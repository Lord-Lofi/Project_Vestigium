package com.vestigium.vestigiumatmosphere.city;

import com.vestigium.vestigiumatmosphere.VestigiumAtmosphere;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ancient City atmospheric events — all deep_dark biome at Y ≤ −20.
 *
 * Systems:
 *   Sculk Memory Pulses    — SCULK_SOUL particle shimmer + quiet sculk click every 2 s
 *   The Recording          — SOUL particles replay at stored death locations (max 3 per player)
 *   Resonant Frequency     — low heartbeat pulse every 90–150 s
 *   Sculk Bloom Events     — particle burst every 60–180 s
 *   The Choir              — three-part staggered haunting sound sequence every 30–90 s
 *   Echo of the Last Night — one-time lore sequence on first entry per city cell (512×512 grid)
 *   The Warden's Shadow    — directional SOUL stream + actionbar every 3–7 min (tier ≥ 2)
 *   The Aware              — 5-tier escalation tracking continuous city presence up to 20 min
 *
 * PDC keys (on Player):
 *   vestigium:city_visits     STRING  "CX~CZ;CX~CZ;..."  — visited 512×512 grid cells
 *   vestigium:city_death_locs STRING  "X~Y~Z;X~Y~Z;..."  — up to 3 ancient city death points
 */
public class AncientCityEventManager implements Listener {

    private static final NamespacedKey CITY_VISITS_KEY = new NamespacedKey("vestigium", "city_visits");
    private static final NamespacedKey CITY_DEATHS_KEY = new NamespacedKey("vestigium", "city_death_locs");

    private static final int  CITY_GRID          = 512;
    private static final int  CITY_MAX_Y         = -20;
    private static final int  MAX_DEATH_LOCS     = 3;
    private static final int  RECORDING_RADIUS   = 64;
    private static final long RECORDING_INTERVAL_MS = 10_000L;

    private static final long TIER1_MS =  4 * 60_000L;
    private static final long TIER2_MS =  8 * 60_000L;
    private static final long TIER3_MS = 12 * 60_000L;
    private static final long TIER4_MS = 16 * 60_000L;
    private static final long TIER5_MS = 20 * 60_000L;

    private static final long CHOIR_MIN_MS    = 30_000L;
    private static final long CHOIR_MAX_MS    = 90_000L;
    private static final long BLOOM_MIN_MS    = 60_000L;
    private static final long BLOOM_MAX_MS    = 180_000L;
    private static final long RESONANT_MIN_MS = 90_000L;
    private static final long RESONANT_MAX_MS = 150_000L;
    private static final long SHADOW_MIN_MS   = 3 * 60_000L;
    private static final long SHADOW_MAX_MS   = 7 * 60_000L;

    private static final String[] ECHO_SEQUENCE = {
        "§8A silence older than stone descends around you.",
        "§8You feel the weight of thousands of years of dark.",
        "§8This city does not sleep. It simply waits.",
        "§8Shapes at the edge of perception resolve and vanish.",
        "§8You have the distinct sense of being counted."
    };

    private static final String[] AWARE_MESSAGES = {
        null,
        "§7The dark is very still here.",
        "§7Something registers your presence.",
        "§7The city has noticed you.",
        "§6Do not linger here.",
        "§4You have been here too long."
    };

    // Entry timestamp. Null/absent = not in city.
    private final Map<UUID, Long>    cityEntryMs    = new HashMap<>();
    // Last tier for which message was sent, to avoid repeats.
    private final Map<UUID, Integer> appliedTier    = new HashMap<>();
    // Cooldown timestamps for periodic events.
    private final Map<UUID, Long>    nextChoirMs    = new HashMap<>();
    private final Map<UUID, Long>    nextBloomMs    = new HashMap<>();
    private final Map<UUID, Long>    nextResonantMs = new HashMap<>();
    private final Map<UUID, Long>    nextShadowMs   = new HashMap<>();
    private final Map<UUID, Long>    lastRecordingMs = new HashMap<>();

    private final VestigiumAtmosphere plugin;
    private BukkitRunnable pulseTask;
    private BukkitRunnable tickTask;

    public AncientCityEventManager(VestigiumAtmosphere plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startPulseTask();
        startTickTask();
        plugin.getLogger().info("[AncientCityEventManager] Initialized.");
    }

    public void shutdown() {
        if (pulseTask != null) pulseTask.cancel();
        if (tickTask  != null) tickTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!isInAncientCity(player)) return;

        Location loc = player.getLocation();
        String entry = loc.getBlockX() + "~" + loc.getBlockY() + "~" + loc.getBlockZ();
        String existing = player.getPersistentDataContainer()
                .getOrDefault(CITY_DEATHS_KEY, PersistentDataType.STRING, "");
        List<String> parts = new ArrayList<>(
                existing.isBlank() ? Collections.emptyList() : Arrays.asList(existing.split(";")));
        parts.add(entry);
        while (parts.size() > MAX_DEATH_LOCS) parts.remove(0);
        player.getPersistentDataContainer().set(CITY_DEATHS_KEY, PersistentDataType.STRING,
                String.join(";", parts));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cityEntryMs.remove(id);
        appliedTier.remove(id);
        nextChoirMs.remove(id);
        nextBloomMs.remove(id);
        nextResonantMs.remove(id);
        nextShadowMs.remove(id);
        lastRecordingMs.remove(id);
    }

    // -------------------------------------------------------------------------
    // Pulse task — every 40 ticks: sculk memory pulses + recording replay
    // -------------------------------------------------------------------------

    private void startPulseTask() {
        pulseTask = new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (!isInAncientCity(p)) continue;
                    fireSculkPulse(p);
                    maybeReplayRecording(p, now);
                }
            }
        };
        pulseTask.runTaskTimer(plugin, 40L, 40L);
    }

    private void fireSculkPulse(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location base = player.getLocation();
        World world = player.getWorld();
        for (int i = 0; i < 6; i++) {
            world.spawnParticle(Particle.SCULK_SOUL,
                    base.clone().add(
                            rng.nextDouble(-4, 4),
                            rng.nextDouble(0, 2),
                            rng.nextDouble(-4, 4)),
                    1, 0, 0, 0, 0.01);
        }
        if (rng.nextInt(3) == 0) {
            world.playSound(base, Sound.BLOCK_SCULK_SENSOR_CLICKING,
                    SoundCategory.BLOCKS, 0.15f, rng.nextFloat(0.6f, 1.2f));
        }
    }

    private void maybeReplayRecording(Player player, long now) {
        Long last = lastRecordingMs.get(player.getUniqueId());
        if (last != null && now - last < RECORDING_INTERVAL_MS) return;

        String stored = player.getPersistentDataContainer()
                .getOrDefault(CITY_DEATHS_KEY, PersistentDataType.STRING, "");
        if (stored.isBlank()) return;

        lastRecordingMs.put(player.getUniqueId(), now);
        World world = player.getWorld();
        Location pLoc = player.getLocation();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (String entry : stored.split(";")) {
            String[] parts = entry.split("~");
            if (parts.length != 3) continue;
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                if (!world.getName().equals(player.getWorld().getName())) continue;
                Location deathLoc = new Location(world, x + 0.5, y, z + 0.5);
                if (pLoc.distanceSquared(deathLoc) > (double) RECORDING_RADIUS * RECORDING_RADIUS) continue;
                // Ghost silhouette — scattered SOUL particles forming a figure
                for (int i = 0; i < 12; i++) {
                    world.spawnParticle(Particle.SOUL,
                            deathLoc.clone().add(
                                    rng.nextDouble(-0.3, 0.3),
                                    rng.nextDouble(0, 1.8),
                                    rng.nextDouble(-0.3, 0.3)),
                            1, 0, 0.01, 0, 0.01);
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Tick task — every 20 ticks: city entry/exit, The Aware, timed events
    // -------------------------------------------------------------------------

    private void startTickTask() {
        tickTask = new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    boolean inCity = isInAncientCity(p);
                    UUID id = p.getUniqueId();

                    if (!inCity) {
                        if (cityEntryMs.containsKey(id)) {
                            cityEntryMs.remove(id);
                            appliedTier.remove(id);
                        }
                        continue;
                    }

                    if (!cityEntryMs.containsKey(id)) {
                        cityEntryMs.put(id, now);
                        appliedTier.put(id, 0);
                        scheduleNextEvents(id, now);
                        maybeFireEcho(p);
                    }

                    tickAware(p, now);
                    tickTimedEvents(p, now);
                }
            }
        };
        tickTask.runTaskTimer(plugin, 20L, 20L);
    }

    private void scheduleNextEvents(UUID id, long now) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        nextChoirMs.put(id,    now + rng.nextLong(CHOIR_MIN_MS,    CHOIR_MAX_MS));
        nextBloomMs.put(id,    now + rng.nextLong(BLOOM_MIN_MS,    BLOOM_MAX_MS));
        nextResonantMs.put(id, now + rng.nextLong(RESONANT_MIN_MS, RESONANT_MAX_MS));
        nextShadowMs.put(id,   now + rng.nextLong(SHADOW_MIN_MS,   SHADOW_MAX_MS));
    }

    private void tickAware(Player player, long now) {
        long elapsed = now - cityEntryMs.get(player.getUniqueId());
        int tier = elapsed >= TIER5_MS ? 5
                 : elapsed >= TIER4_MS ? 4
                 : elapsed >= TIER3_MS ? 3
                 : elapsed >= TIER2_MS ? 2
                 : elapsed >= TIER1_MS ? 1
                 : 0;

        int last = appliedTier.getOrDefault(player.getUniqueId(), 0);

        if (tier != last) {
            appliedTier.put(player.getUniqueId(), tier);
            applyAwareTier(player, tier);
        }

        if (tier == 5) {
            PotionEffect existing = player.getPotionEffect(PotionEffectType.DARKNESS);
            if (existing == null || existing.getDuration() < 100) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 200, 0, true, false, false));
            }
        }
    }

    private void applyAwareTier(Player player, int tier) {
        if (tier == 0) return;
        String msg = AWARE_MESSAGES[tier];
        if (msg != null) player.sendActionBar(Component.text(msg));

        World world = player.getWorld();
        Location loc = player.getLocation();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        switch (tier) {
            case 1 -> {
                for (int i = 0; i < 8; i++) {
                    world.spawnParticle(Particle.SCULK_SOUL,
                            loc.clone().add(rng.nextDouble(-5, 5), rng.nextDouble(0, 3), rng.nextDouble(-5, 5)),
                            1, 0, 0, 0, 0.02);
                }
            }
            case 2 -> {
                world.playSound(loc, Sound.ENTITY_WARDEN_LISTENING_ANGRY, SoundCategory.AMBIENT, 0.25f, 1.2f);
                for (int i = 0; i < 12; i++) {
                    world.spawnParticle(Particle.SCULK_SOUL,
                            loc.clone().add(rng.nextDouble(-6, 6), rng.nextDouble(0, 3), rng.nextDouble(-6, 6)),
                            1, 0, 0, 0, 0.03);
                }
            }
            case 3 -> {
                world.playSound(loc, Sound.ENTITY_WARDEN_NEARBY_CLOSE, SoundCategory.AMBIENT, 0.35f, 0.9f);
                for (int i = 0; i < 16; i++) {
                    world.spawnParticle(Particle.SOUL,
                            loc.clone().add(rng.nextDouble(-6, 6), rng.nextDouble(0, 3), rng.nextDouble(-6, 6)),
                            1, 0, 0, 0, 0.04);
                }
            }
            case 4 -> {
                world.playSound(loc, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.AMBIENT, 0.5f, 0.6f);
                for (int i = 0; i < 20; i++) {
                    world.spawnParticle(Particle.SOUL,
                            loc.clone().add(rng.nextDouble(-7, 7), rng.nextDouble(0, 3), rng.nextDouble(-7, 7)),
                            1, 0, 0, 0, 0.05);
                }
            }
            case 5 -> {
                world.playSound(loc, Sound.ENTITY_WARDEN_AMBIENT, SoundCategory.AMBIENT, 0.8f, 0.5f);
            }
        }
    }

    private void tickTimedEvents(Player player, long now) {
        UUID id = player.getUniqueId();
        int tier = appliedTier.getOrDefault(id, 0);

        if (now >= nextChoirMs.getOrDefault(id, Long.MAX_VALUE)) {
            fireChoir(player);
            nextChoirMs.put(id, now + ThreadLocalRandom.current().nextLong(CHOIR_MIN_MS, CHOIR_MAX_MS));
        }
        if (now >= nextBloomMs.getOrDefault(id, Long.MAX_VALUE)) {
            fireSculkBloom(player);
            nextBloomMs.put(id, now + ThreadLocalRandom.current().nextLong(BLOOM_MIN_MS, BLOOM_MAX_MS));
        }
        if (now >= nextResonantMs.getOrDefault(id, Long.MAX_VALUE)) {
            fireResonance(player);
            nextResonantMs.put(id, now + ThreadLocalRandom.current().nextLong(RESONANT_MIN_MS, RESONANT_MAX_MS));
        }
        if (tier >= 2 && now >= nextShadowMs.getOrDefault(id, Long.MAX_VALUE)) {
            fireWardenShadow(player);
            nextShadowMs.put(id, now + ThreadLocalRandom.current().nextLong(SHADOW_MIN_MS, SHADOW_MAX_MS));
        }
    }

    // -------------------------------------------------------------------------
    // Echo of the Last Night
    // -------------------------------------------------------------------------

    private void maybeFireEcho(Player player) {
        String cell = cityCell(player.getLocation());
        String existing = player.getPersistentDataContainer()
                .getOrDefault(CITY_VISITS_KEY, PersistentDataType.STRING, "");
        if (!existing.isBlank()) {
            for (String v : existing.split(";")) {
                if (v.equals(cell)) return;
            }
        }
        String updated = existing.isBlank() ? cell : existing + ";" + cell;
        player.getPersistentDataContainer().set(CITY_VISITS_KEY, PersistentDataType.STRING, updated);
        scheduleEchoSequence(player);
    }

    private void scheduleEchoSequence(Player player) {
        for (int i = 0; i < ECHO_SEQUENCE.length; i++) {
            final String line = ECHO_SEQUENCE[i];
            new BukkitRunnable() {
                @Override public void run() {
                    if (player.isOnline() && isInAncientCity(player)) {
                        player.sendMessage(line);
                        player.getWorld().playSound(player.getLocation(),
                                Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.AMBIENT, 0.4f, 0.8f);
                    }
                }
            }.runTaskLater(plugin, 60L * i + 20L);
        }
    }

    // -------------------------------------------------------------------------
    // Periodic effects
    // -------------------------------------------------------------------------

    private void fireChoir(Player player) {
        Location loc = player.getLocation();
        World world = player.getWorld();
        world.playSound(loc, Sound.ENTITY_WARDEN_AMBIENT, SoundCategory.AMBIENT, 0.35f, 0.55f);
        new BukkitRunnable() {
            @Override public void run() {
                if (player.isOnline() && isInAncientCity(player)) {
                    player.getWorld().playSound(player.getLocation(),
                            Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.AMBIENT, 0.3f, 1.0f);
                }
            }
        }.runTaskLater(plugin, 40L);
        new BukkitRunnable() {
            @Override public void run() {
                if (player.isOnline() && isInAncientCity(player)) {
                    player.getWorld().playSound(player.getLocation(),
                            Sound.ENTITY_WARDEN_LISTENING_ANGRY, SoundCategory.AMBIENT, 0.2f, 0.7f);
                }
            }
        }.runTaskLater(plugin, 80L);
    }

    private void fireSculkBloom(Player player) {
        Location center = player.getLocation();
        World world = player.getWorld();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        world.playSound(center, Sound.BLOCK_SCULK_CATALYST_BLOOM, SoundCategory.BLOCKS, 0.8f, 1.0f);
        for (int i = 0; i < 30; i++) {
            world.spawnParticle(Particle.SCULK_SOUL,
                    center.clone().add(
                            rng.nextDouble(-4, 4),
                            rng.nextDouble(0, 2.5),
                            rng.nextDouble(-4, 4)),
                    1, 0, 0, 0, 0.02);
        }
    }

    private void fireResonance(Player player) {
        player.getWorld().playSound(player.getLocation(),
                Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.AMBIENT, 0.3f, 0.45f);
    }

    private void fireWardenShadow(Player player) {
        Location loc = player.getLocation();
        World world = player.getWorld();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        world.playSound(loc, Sound.ENTITY_WARDEN_NEARBY_CLOSEST, SoundCategory.HOSTILE, 0.6f, 0.8f);
        double angle = rng.nextDouble(Math.PI * 2);
        double dx = Math.cos(angle);
        double dz = Math.sin(angle);
        for (int i = 0; i < 10; i++) {
            double t = i / 9.0;
            world.spawnParticle(Particle.SOUL,
                    loc.clone().add(dx * t * 5 - 2.5, rng.nextDouble(-0.5, 1.5), dz * t * 5 - 2.5),
                    2, 0.05, 0.15, 0.05, 0.03);
        }
        player.sendActionBar(Component.text("§8Something moves in the dark."));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isInAncientCity(Player p) {
        if (p.getWorld().getEnvironment() != World.Environment.NORMAL) return false;
        if (p.getLocation().getY() > CITY_MAX_Y) return false;
        return p.getLocation().getBlock().getBiome() == Biome.DEEP_DARK;
    }

    private String cityCell(Location loc) {
        int cx = Math.floorDiv(loc.getBlockX(), CITY_GRID);
        int cz = Math.floorDiv(loc.getBlockZ(), CITY_GRID);
        return cx + "~" + cz;
    }
}
