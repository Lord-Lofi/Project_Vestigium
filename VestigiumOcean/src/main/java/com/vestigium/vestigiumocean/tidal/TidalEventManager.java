package com.vestigium.vestigiumocean.tidal;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.TidalChangeEvent;
import com.vestigium.lib.model.ParticlePriority;
import com.vestigium.vestigiumocean.VestigiumOcean;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tidal phase event system, driven by TidalChangeEvents from VestigiumLib.
 *
 * Tidal cycle phases 0-11 (0-5 rising, 6-11 falling):
 *   Phase 3  — SURGE:     velocity push + water particle stream for ocean surface players
 *   Phase 4  — shoulder:  atmospheric message
 *   Phase 5  — HIGH_TIDE: Strength I (20s); mob spawn boost flag set (read by OceanMobManager)
 *   Phase 6  — shoulder:  atmospheric message
 *   Phase 9  — NEAP:      bonus fishing drops (PlayerFishEvent); calm atmosphere
 *   Phase 11 — LOW_TIDE:  Haste I (20s) underwater; mob spawn suppression flag set
 *
 * No repeating task — all reactions are one-shot responses to TidalChangeEvent.
 */
public class TidalEventManager implements Listener {

    private static final Set<String> OCEAN_BIOMES = Set.of(
            "ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean",
            "frozen_ocean", "deep_frozen_ocean", "lukewarm_ocean",
            "deep_lukewarm_ocean", "warm_ocean");

    private static final ItemStack NEAP_BONUS_ITEM = new ItemStack(Material.PRISMARINE_SHARD, 1);

    private final VestigiumOcean plugin;

    // Tidal state flags — read by OceanMobManager to modify spawn rates
    private volatile boolean highTideActive = false;
    private volatile boolean lowTideActive  = false;

    public TidalEventManager(VestigiumOcean plugin) {
        this.plugin = plugin;
    }

    public void init() {
        VestigiumLib.getEventBus().subscribe(TidalChangeEvent.class, this::onTidalChange);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[TidalEventManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Tidal state accessors (for OceanMobManager)
    // -------------------------------------------------------------------------

    public boolean isHighTideActive() { return highTideActive; }
    public boolean isLowTideActive()  { return lowTideActive; }

    // -------------------------------------------------------------------------
    // Tidal phase dispatch
    // -------------------------------------------------------------------------

    private void onTidalChange(TidalChangeEvent event) {
        int phase = event.getNewPhase();

        // Clear tide flags on any phase transition
        if (!event.isHighTide()) highTideActive = false;
        if (!event.isLowTide())  lowTideActive  = false;

        plugin.getServer().getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                .forEach(world -> handlePhase(world, phase, event));
    }

    private void handlePhase(World world, int phase, TidalChangeEvent event) {
        switch (phase) {

            // ------------------------------------------------------------------
            // SURGE — steepest rising tide; strong current pushes ocean players
            // ------------------------------------------------------------------
            case 3 -> {
                broadcast(world, "§3A deep surge runs beneath the surface. The current fights back.");
                world.getPlayers().stream()
                        .filter(p -> isInOcean(p) && p.getLocation().getBlockY() < 70)
                        .forEach(this::applySurge);
            }

            // ------------------------------------------------------------------
            // Shoulder — approaching high tide
            // ------------------------------------------------------------------
            case 4 -> broadcast(world,
                    "§3The sea shifts. A deep current stirs beneath the surface.");

            // ------------------------------------------------------------------
            // HIGH TIDE — peak; mob spawns boosted, Strength I for ocean players
            // ------------------------------------------------------------------
            case 5 -> {
                highTideActive = true;
                broadcast(world, "§9The tide swells. The ocean remembers its reach.");
                world.getPlayers().stream()
                        .filter(p -> isInOcean(p) && p.getLocation().getBlockY() < 70)
                        .forEach(p -> {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 400, 0, false, false));
                            p.getWorld().playSound(p.getLocation(), Sound.AMBIENT_UNDERWATER_LOOP, 0.3f, 0.8f);
                        });
            }

            // ------------------------------------------------------------------
            // Shoulder — retreating from high tide
            // ------------------------------------------------------------------
            case 6 -> {
                highTideActive = false;
                broadcast(world, "§3The peak has passed. The water begins to remember the shore.");
            }

            // ------------------------------------------------------------------
            // NEAP — calm mid-tide; bonus fishing drops active
            // ------------------------------------------------------------------
            case 9 -> {
                broadcast(world, "§3The tide rests. The ocean is unusually still.");
                world.getPlayers().stream()
                        .filter(p -> isInOcean(p))
                        .forEach(p -> p.getWorld().playSound(
                                p.getLocation(), Sound.AMBIENT_UNDERWATER_LOOP, 0.15f, 1.2f));
            }

            // ------------------------------------------------------------------
            // LOW TIDE — trough; mob spawns suppressed, Haste I for underwater players
            // ------------------------------------------------------------------
            case 11 -> {
                lowTideActive = true;
                broadcast(world, "§3The tide retreats. What it leaves behind is older than the map.");
                world.getPlayers().stream()
                        .filter(p -> isInOcean(p) && p.isInWater())
                        .forEach(p -> p.addPotionEffect(
                                new PotionEffect(PotionEffectType.HASTE, 400, 0, false, false)));
            }
        }
    }

    // -------------------------------------------------------------------------
    // SURGE — apply current push + particle stream
    // -------------------------------------------------------------------------

    private void applySurge(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Random horizontal push of 0.3–0.6 in a randomised direction
        double angle = rng.nextDouble(0, 2 * Math.PI);
        double strength = rng.nextDouble(0.3, 0.6);
        Vector push = new Vector(Math.cos(angle) * strength, 0.1, Math.sin(angle) * strength);
        player.setVelocity(player.getVelocity().add(push));

        // Particle stream along the push direction
        for (int i = 1; i <= 6; i++) {
            VestigiumLib.getParticleManager().queueParticle(
                    player.getLocation().add(push.clone().multiply(i)),
                    Particle.SPLASH, null, ParticlePriority.ATMOSPHERIC);
        }

        player.sendActionBar("§3A surge current pushes you.");
        player.getWorld().playSound(player.getLocation(), Sound.AMBIENT_UNDERWATER_LOOP, 0.6f, 0.6f);
    }

    // -------------------------------------------------------------------------
    // NEAP fishing bonus — extra prismarine shard on any fish catch
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (!isInOcean(event.getPlayer())) return;

        // Only active during NEAP phase (9)
        int tidalPhase = VestigiumLib.getSeasonAPI().getTidalPhase();
        if (tidalPhase != 9) return;

        // Bonus drop: 1–2 prismarine shards
        int amount = ThreadLocalRandom.current().nextInt(1, 3);
        event.getPlayer().getWorld().dropItemNaturally(
                event.getPlayer().getLocation(),
                new ItemStack(Material.PRISMARINE_SHARD, amount));
        event.getPlayer().sendActionBar("§3The calm tide yields something extra.");
    }

    // -------------------------------------------------------------------------

    private static void broadcast(World world, String message) {
        world.getPlayers().forEach(p -> p.sendMessage(message));
    }

    private static boolean isInOcean(Player player) {
        return OCEAN_BIOMES.contains(
                player.getLocation().getBlock().getBiome().getKey().getKey());
    }
}
