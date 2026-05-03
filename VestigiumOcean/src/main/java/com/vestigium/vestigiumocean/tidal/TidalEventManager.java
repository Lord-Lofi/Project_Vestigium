package com.vestigium.vestigiumocean.tidal;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.TidalChangeEvent;
import com.vestigium.vestigiumocean.VestigiumOcean;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Set;

/**
 * Tidal phase event system, driven by TidalChangeEvents from VestigiumLib.
 *
 * Phase 5 (high tide): players in ocean biomes near the surface get Strength I (20s)
 *                      + "The tide swells" message.
 * Phase 11 (low tide): players underwater in ocean biomes get Haste I (20s)
 *                      + "The tide retreats" message.
 * Phases 4,6 (shoulder): flavor message only, no buff.
 *
 * Tidal phases 0-11; 0-5 rising, 6-11 falling (see SeasonAPI).
 */
public class TidalEventManager {

    private static final Set<String> OCEAN_BIOMES = Set.of(
            "ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean",
            "frozen_ocean", "deep_frozen_ocean", "lukewarm_ocean",
            "deep_lukewarm_ocean", "warm_ocean");

    private final VestigiumOcean plugin;

    public TidalEventManager(VestigiumOcean plugin) {
        this.plugin = plugin;
    }

    public void init() {
        VestigiumLib.getEventBus().subscribe(TidalChangeEvent.class, this::onTidalChange);
        plugin.getLogger().info("[TidalEventManager] Initialized.");
    }

    // -------------------------------------------------------------------------

    private void onTidalChange(TidalChangeEvent event) {
        int phase = event.getNewPhase();

        plugin.getServer().getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                .forEach(world -> handlePhase(world, phase));
    }

    private void handlePhase(World world, int phase) {
        switch (phase) {
            case 4 -> broadcast(world,
                    "§3The sea shifts. A deep current stirs beneath the surface.");
            case 5 -> {
                broadcast(world, "§9The tide swells. The ocean remembers its reach.");
                world.getPlayers().stream()
                        .filter(p -> isInOcean(p) && p.getLocation().getBlockY() < 65)
                        .forEach(p -> p.addPotionEffect(
                                new PotionEffect(PotionEffectType.STRENGTH, 400, 0)));
            }
            case 6 -> broadcast(world,
                    "§3The peak has passed. The water begins to remember the shore.");
            case 11 -> {
                broadcast(world, "§3The tide retreats. What it leaves behind is older than the map.");
                world.getPlayers().stream()
                        .filter(p -> isInOcean(p) && p.isInWater())
                        .forEach(p -> p.addPotionEffect(
                                new PotionEffect(PotionEffectType.HASTE, 400, 0)));
            }
        }
    }

    private static void broadcast(World world, String message) {
        world.getPlayers().forEach(p -> p.sendMessage(message));
    }

    private static boolean isInOcean(Player player) {
        return OCEAN_BIOMES.contains(
                player.getLocation().getBlock().getBiome().getKey().getKey());
    }
}
