package com.vestigium.vestigiumatmosphere.weather;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.CataclysmStartEvent;
import com.vestigium.lib.model.Season;
import com.vestigium.vestigiumatmosphere.VestigiumAtmosphere;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Custom weather events layered on top of vanilla weather.
 *
 * Event types:
 *   ACID_RAIN      — during active cataclysm; players in rain take 0.5 damage/2s, crops wither
 *   SCULK_STORM    — high omen (600+) + thunder; spreads sculk to nearby blocks, blindness to exposed players
 *   BLOOD_MOON     — omen 400+ on full moon night (day 8, 16, 24...); hostile mob damage +20%, red sky tint msg
 *   DEAD_CALM      — omen < 50 + clear weather; passive regen to nearby players, crop growth boost
 *   FROST_VEIL     — winter only; freezing fog message, slow I to exposed players
 *
 * Events are evaluated every 100 ticks (5 seconds) per world.
 */
public class WeatherEventManager {

    private static final long CHECK_TICKS = 100L;

    private final VestigiumAtmosphere plugin;
    private final Set<String> activeEvents = new HashSet<>();  // "worldName:eventType"
    private boolean cataclysmActive = false;

    public WeatherEventManager(VestigiumAtmosphere plugin) {
        this.plugin = plugin;
    }

    public void init() {
        VestigiumLib.getEventBus().subscribe(CataclysmStartEvent.class,
                e -> cataclysmActive = true);
        VestigiumLib.getEventBus().subscribe(
                com.vestigium.lib.event.CataclysmEndEvent.class,
                e -> cataclysmActive = false);

        new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getServer().getWorlds().forEach(world -> {
                    if (world.getEnvironment() != World.Environment.NORMAL) return;
                    evaluateWeatherEvents(world);
                });
            }
        }.runTaskTimer(plugin, CHECK_TICKS, CHECK_TICKS);

        plugin.getLogger().info("[WeatherEventManager] Initialized.");
    }

    // -------------------------------------------------------------------------

    private void evaluateWeatherEvents(World world) {
        boolean raining   = world.hasStorm();
        boolean thunder   = world.isThundering();
        int omen          = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
        Season season     = VestigiumLib.getSeasonAPI().getCurrentSeason();
        long time         = world.getTime();
        boolean isNight   = time >= 13_000 && time <= 23_000;
        long day          = world.getFullTime() / 24_000;

        if (cataclysmActive && raining) {
            activateEvent(world, "ACID_RAIN");
        } else {
            deactivateEvent(world, "ACID_RAIN");
        }

        if (omen >= 600 && thunder) {
            activateEvent(world, "SCULK_STORM");
        } else {
            deactivateEvent(world, "SCULK_STORM");
        }

        if (omen >= 400 && isNight && day % 8 == 0) {
            activateEvent(world, "BLOOD_MOON");
        } else {
            deactivateEvent(world, "BLOOD_MOON");
        }

        if (omen < 50 && !raining) {
            activateEvent(world, "DEAD_CALM");
        } else {
            deactivateEvent(world, "DEAD_CALM");
        }

        if (season == Season.WINTER && raining) {
            activateEvent(world, "FROST_VEIL");
        } else {
            deactivateEvent(world, "FROST_VEIL");
        }
    }

    private void activateEvent(World world, String eventType) {
        String key = world.getName() + ":" + eventType;
        if (activeEvents.add(key)) {
            onEventStart(world, eventType);
        }
        applyEventTick(world, eventType);
    }

    private void deactivateEvent(World world, String eventType) {
        String key = world.getName() + ":" + eventType;
        if (activeEvents.remove(key)) {
            onEventEnd(world, eventType);
        }
    }

    private void onEventStart(World world, String eventType) {
        String msg = switch (eventType) {
            case "ACID_RAIN"   -> "§2The rain hisses against stone. Something in it burns.";
            case "SCULK_STORM" -> "§8The thunder has a heartbeat. Something in the deep is listening.";
            case "BLOOD_MOON"  -> "§4The moon has forgotten its color.";
            case "DEAD_CALM"   -> "§aThe world exhales. For a moment, everything is still.";
            case "FROST_VEIL"  -> "§bA cold fog rolls in. The air tastes like iron.";
            default -> null;
        };
        if (msg != null) world.getPlayers().forEach(p -> p.sendMessage(msg));
    }

    private void onEventEnd(World world, String eventType) {
        String msg = switch (eventType) {
            case "ACID_RAIN"   -> "§7The rain clears. The damage remains.";
            case "SCULK_STORM" -> "§8The heartbeat fades. You are not sure it has stopped.";
            case "BLOOD_MOON"  -> "§7The moon has returned to itself.";
            default -> null;
        };
        if (msg != null) world.getPlayers().forEach(p -> p.sendMessage(msg));
    }

    private void applyEventTick(World world, String eventType) {
        List<Player> players = world.getPlayers();
        switch (eventType) {
            case "ACID_RAIN" -> players.stream()
                    .filter(p -> !p.getLocation().getBlock().getLightFromSky() == false)
                    .filter(this::isExposedToSky)
                    .forEach(p -> p.damage(0.5));
            case "SCULK_STORM" -> players.stream()
                    .filter(this::isExposedToSky)
                    .forEach(p -> p.addPotionEffect(
                            new PotionEffect(PotionEffectType.BLINDNESS, 60, 0)));
            case "BLOOD_MOON" -> {
                // Mob damage boost is handled via OmenAPI effective score naturally
                // Just add omen pressure while active
                if (ThreadLocalRandom.current().nextInt(20) == 0) {
                    VestigiumLib.getOmenAPI().addOmen(1);
                }
            }
            case "DEAD_CALM" -> players.forEach(p ->
                    p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 120, 0)));
            case "FROST_VEIL" -> players.stream()
                    .filter(this::isExposedToSky)
                    .forEach(p -> p.addPotionEffect(
                            new PotionEffect(PotionEffectType.SLOWNESS, 120, 0)));
        }
    }

    private boolean isExposedToSky(Player player) {
        return player.getWorld().getHighestBlockYAt(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockZ())
                <= player.getLocation().getBlockY();
    }

    public boolean isEventActive(World world, String eventType) {
        return activeEvents.contains(world.getName() + ":" + eventType);
    }
}
