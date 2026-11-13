package com.vestigium.vestigiumend.biome;

import com.vestigium.lib.VestigiumLib;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * End biome zone atmospherics and Enderman behaviour.
 *
 * Zone classification reads the vanilla biome directly whenever needed —
 * no cached zone map. The lore fragment acts as the first-visit guard.
 *
 * Enderman suppression (VERDANT_REMNANT, RESONANCE_FIELDS) is event-driven via
 * EntityTargetLivingEntityEvent — no spatial scan required.
 * Enderman provocation (ASHEN_WASTES, PETRIFIED_ARCHIVE) uses a low-frequency
 * random guard in the 40-tick ambient timer.
 */
public class EndBiomeManager implements Listener {

    public enum Zone {
        THE_CRUCIBLE, RESONANCE_FIELDS, VERDANT_REMNANT,
        ASHEN_WASTES, MOURNING_SHALLOWS, DRIFTING_ISLANDS, PETRIFIED_ARCHIVE
    }

    private static final NamespacedKey VISITED_KEY = new NamespacedKey("vestigium", "end_biome_visited");

    private static final String[] ASHEN_LINES = {
        "§8The stone here predates any record of its formation.",
        "§8This part of the End was not cultivated. It was left.",
        "§8The ash has no source. It has been here since the Wastes were first mapped.",
        "§8Older than the outer islands. Possibly older than the main."
    };
    private static final String[] VERDANT_LINES = {
        "§8The Endermen are tending something. You cannot tell what.",
        "§8The attention here is not hostile. It is archival.",
        "§8They move through this place as if the geometry matters to them."
    };
    private static final String[] RESONANCE_LINES = {
        "§bThe resonance has a frequency. It is not sound.",
        "§bThe crystals are not the source. They are the output.",
        "§bThe field predates the formation above it."
    };
    private static final String[] CRUCIBLE_LINES = {
        "§5This ground is older than the island around it.",
        "§5The Shattering was documented here. It was not caused here.",
        "§5The Antecedents came after. To record. Not to explain."
    };
    private static final String[] ARCHIVE_LINES = {
        "§8The cataloguing stopped mid-entry.",
        "§8Whatever interrupted the Archive, it did not announce itself.",
        "§8The Endermen maintain the incompleteness. They do not resolve it.",
        "§8You are being assessed. The metric is not clear."
    };
    private static final String[] SHALLOWS_LINES = {
        "§8Below the cloud level the quality of the void is different.",
        "§8The Endermen do not descend here. Avoidance or indifference.",
        "§8The Shallows continue downward. We have not measured how far.",
        "§8Something in the descent feels like recognition."
    };
    private static final String[] DRIFTING_LINES = {
        "§8The drift is geological. Not measurable in a single lifetime.",
        "§8The island is anchored to something other than where it appears to be.",
        "§8The Antecedents considered this an anomaly. They did not resolve it either."
    };

    private static final Map<Zone, String> FIRST_VISIT_MESSAGES = Map.of(
        Zone.ASHEN_WASTES,      "§8You have entered the Ashen Wastes.",
        Zone.VERDANT_REMNANT,   "§8You have entered the Verdant Remnant.",
        Zone.RESONANCE_FIELDS,  "§bYou have entered the Resonance Fields.",
        Zone.THE_CRUCIBLE,      "§5You have entered the Crucible.",
        Zone.PETRIFIED_ARCHIVE, "§8You have entered the Petrified Archive.",
        Zone.MOURNING_SHALLOWS, "§8You have descended into the Mourning Shallows.",
        Zone.DRIFTING_ISLANDS,  "§8You have reached the Drifting Islands."
    );

    private static final Map<Zone, String> FRAGMENT_IDS = Map.of(
        Zone.ASHEN_WASTES,      "ashen_wastes_visitor",
        Zone.VERDANT_REMNANT,   "verdant_remnant_observer",
        Zone.RESONANCE_FIELDS,  "resonance_fields_witness",
        Zone.THE_CRUCIBLE,      "the_crucible_presence",
        Zone.PETRIFIED_ARCHIVE, "petrified_archive_exposure",
        Zone.MOURNING_SHALLOWS, "mourning_shallows_descent",
        Zone.DRIFTING_ISLANDS,  "drifting_islands_passage"
    );

    private static final Map<Zone, String> LORE_KEYS = Map.of(
        Zone.ASHEN_WASTES,      "ashen_wastes",
        Zone.VERDANT_REMNANT,   "verdant_remnant",
        Zone.RESONANCE_FIELDS,  "resonance_fields",
        Zone.THE_CRUCIBLE,      "the_crucible",
        Zone.PETRIFIED_ARCHIVE, "petrified_archive",
        Zone.MOURNING_SHALLOWS, "mourning_shallows",
        Zone.DRIFTING_ISLANDS,  "drifting_islands"
    );

    private final JavaPlugin plugin;
    private final Map<UUID, Long> lastAmbientMs = new HashMap<>();
    private final Map<UUID, Long> driftTickMs   = new HashMap<>();
    private BukkitTask tickTask;

    public EndBiomeManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        tickTask = new BukkitRunnable() {
            @Override public void run() { tick(); }
        }.runTaskTimer(plugin, 20L, 40L);
        plugin.getLogger().info("[EndBiomeManager] Initialized.");
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
    }

    // ── Enderman targeting — event-driven, no scan ────────────────────────────

    @EventHandler(priority = EventPriority.LOW)
    public void onEndermanTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Enderman enderman)) return;
        if (!(event.getTarget() instanceof Player player)) return;
        if (player.getWorld().getEnvironment() != World.Environment.THE_END) return;

        Zone zone = classify(player);
        if (zone == Zone.VERDANT_REMNANT) {
            event.setCancelled(true);
        } else if (zone == Zone.RESONANCE_FIELDS) {
            event.setCancelled(true);
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            enderman.teleport(enderman.getLocation().clone().add(
                    rng.nextDouble(-20, 20), 0, rng.nextDouble(-20, 20)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastAmbientMs.remove(id);
        driftTickMs.remove(id);
    }

    // ── Ambient tick ──────────────────────────────────────────────────────────

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() != World.Environment.THE_END) continue;
            Zone zone = classify(player);
            checkFirstVisit(player, zone);
            applyZoneEffects(player, zone, now);
        }
    }

    // Fragment is the first-visit guard — hasFragment() returns immediately once granted.
    private void checkFirstVisit(Player player, Zone zone) {
        String fragId = FRAGMENT_IDS.get(zone);
        if (VestigiumLib.getLoreRegistry().hasFragment(player.getUniqueId(), fragId)) return;
        VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), fragId);
        player.sendMessage(FIRST_VISIT_MESSAGES.get(zone));
        String lore = VestigiumLib.getLoreRegistry().getLoreContent("end_biomes", LORE_KEYS.get(zone));
        if (!lore.isEmpty()) player.sendMessage("§8§o" + lore);
    }

    private void applyZoneEffects(Player player, Zone zone, long now) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location loc = player.getLocation();

        switch (zone) {
            case ASHEN_WASTES -> {
                if (rng.nextInt(10) == 0) {
                    getNearbyEndermen(player, 12).stream()
                            .filter(e -> e.getTarget() == null)
                            .findAny()
                            .ifPresent(e -> e.setTarget(player));
                }
                if (rng.nextBoolean()) {
                    player.getWorld().spawnParticle(Particle.ASH,
                            loc.clone().add(rng.nextDouble(-6, 6), rng.nextDouble(0, 3), rng.nextDouble(-6, 6)),
                            3, 0.2, 0.1, 0.2, 0.02);
                }
                sendAmbient(player, ASHEN_LINES, 45_000L, 90_000L, now);
            }
            case VERDANT_REMNANT -> {
                if (rng.nextInt(4) == 0) {
                    player.getWorld().spawnParticle(Particle.COMPOSTER,
                            loc.clone().add(rng.nextDouble(-4, 4), rng.nextDouble(0, 2), rng.nextDouble(-4, 4)),
                            2, 0, 0, 0, 0);
                }
                sendAmbient(player, VERDANT_LINES, 60_000L, 120_000L, now);
            }
            case RESONANCE_FIELDS -> {
                if (rng.nextInt(5) == 0) {
                    player.getWorld().playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                            SoundCategory.AMBIENT, 0.3f, rng.nextFloat(0.5f, 1.4f));
                }
                player.getWorld().spawnParticle(Particle.END_ROD,
                        loc.clone().add(rng.nextDouble(-8, 8), rng.nextDouble(1, 4), rng.nextDouble(-8, 8)),
                        1, 0, 0, 0, 0.02);
                sendAmbient(player, RESONANCE_LINES, 40_000L, 80_000L, now);
            }
            case THE_CRUCIBLE -> {
                if (rng.nextInt(3) == 0) {
                    player.getWorld().spawnParticle(Particle.DRAGON_BREATH,
                            loc.clone().add(rng.nextDouble(-5, 5), rng.nextDouble(0, 2), rng.nextDouble(-5, 5)),
                            2, 0.2, 0.1, 0.2, 0.02);
                }
                sendAmbient(player, CRUCIBLE_LINES, 60_000L, 120_000L, now);
            }
            case PETRIFIED_ARCHIVE -> {
                if (rng.nextInt(8) == 0) {
                    getNearbyEndermen(player, 15).stream()
                            .filter(e -> e.getTarget() == null)
                            .forEach(e -> e.setTarget(player));
                }
                sendAmbient(player, ARCHIVE_LINES, 30_000L, 70_000L, now);
            }
            case MOURNING_SHALLOWS -> {
                if (rng.nextBoolean()) {
                    player.getWorld().spawnParticle(Particle.FALLING_WATER,
                            loc.clone().add(rng.nextDouble(-6, 6), rng.nextDouble(-2, 0), rng.nextDouble(-6, 6)),
                            2, 0.1, 0.05, 0.1, 0.01);
                }
                sendAmbient(player, SHALLOWS_LINES, 50_000L, 100_000L, now);
            }
            case DRIFTING_ISLANDS -> {
                long last = driftTickMs.getOrDefault(player.getUniqueId(), 0L);
                if (now - last > 4_000L) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, true, false));
                    driftTickMs.put(player.getUniqueId(), now);
                }
                if (rng.nextInt(3) == 0) {
                    player.getWorld().spawnParticle(Particle.END_ROD,
                            loc.clone().add(rng.nextDouble(-10, 10), rng.nextDouble(2, 8), rng.nextDouble(-10, 10)),
                            1, 0, 0, 0, 0.01);
                }
                sendAmbient(player, DRIFTING_LINES, 60_000L, 120_000L, now);
            }
        }
    }

    // ── Classification — reads vanilla biome directly, no cache ───────────────

    public Zone classify(Player player) {
        Location loc = player.getLocation();
        Biome biome = player.getWorld().getBiome(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        if (biome == Biome.THE_END || (Math.abs(loc.getX()) <= 200 && Math.abs(loc.getZ()) <= 200)) {
            return Zone.THE_CRUCIBLE;
        }
        if (biome == Biome.END_HIGHLANDS)    return Zone.RESONANCE_FIELDS;
        if (biome == Biome.END_MIDLANDS)     return Zone.VERDANT_REMNANT;
        if (biome == Biome.END_BARRENS)      return loc.getBlockY() >= 55 ? Zone.ASHEN_WASTES : Zone.MOURNING_SHALLOWS;
        if (biome == Biome.SMALL_END_ISLANDS) {
            int parity = (Math.abs(loc.getBlockX() >> 4) + Math.abs(loc.getBlockZ() >> 4)) & 1;
            return parity == 0 ? Zone.DRIFTING_ISLANDS : Zone.PETRIFIED_ARCHIVE;
        }
        return Zone.ASHEN_WASTES;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Enderman> getNearbyEndermen(Player player, double radius) {
        List<Enderman> result = new ArrayList<>();
        for (Entity e : player.getWorld().getNearbyEntities(player.getLocation(), radius, radius, radius)) {
            if (e instanceof Enderman em) result.add(em);
        }
        return result;
    }

    private void sendAmbient(Player player, String[] pool, long minMs, long maxMs, long now) {
        UUID id = player.getUniqueId();
        long next = lastAmbientMs.getOrDefault(id, 0L);
        if (now < next) return;
        lastAmbientMs.put(id, now + ThreadLocalRandom.current().nextLong(minMs, maxMs));
        player.sendMessage(pool[ThreadLocalRandom.current().nextInt(pool.length)]);
    }
}
