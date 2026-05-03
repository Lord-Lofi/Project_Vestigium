package com.vestigium.vestigiumcaves.event;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumcaves.VestigiumCaves;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;

/**
 * Omen-driven underground events evaluated every 120 ticks (6 seconds).
 *
 * Events:
 *   ECHO_CASCADE          — omen > 500, players underground (Y < 50);
 *                           Blindness + Wither I for 10s, message "The dark knows you're here."
 *   BIOLUMINESCENT_BLOOM  — omen < 100, players underground;
 *                           Night Vision for 15s, message about the cave glowing.
 *
 * Only the start/end transition message fires once; effects re-apply each tick the
 * condition holds (matching WeatherEventManager pattern).
 */
public class CaveEventManager {

    private static final long CHECK_TICKS  = 120L;
    private static final int  UNDERGROUND_Y = 50;

    private final VestigiumCaves plugin;
    private final Set<String> activeEvents = new HashSet<>(); // "worldName:eventType"
    private BukkitRunnable task;

    public CaveEventManager(VestigiumCaves plugin) {
        this.plugin = plugin;
    }

    public void init() {
        task = new BukkitRunnable() {
            @Override public void run() {
                plugin.getServer().getWorlds().forEach(world -> {
                    if (world.getEnvironment() != World.Environment.NORMAL) return;
                    evaluateCaveEvents(world);
                });
            }
        };
        task.runTaskTimer(plugin, CHECK_TICKS, CHECK_TICKS);

        plugin.getLogger().info("[CaveEventManager] Initialized.");
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    // -------------------------------------------------------------------------

    private void evaluateCaveEvents(World world) {
        int omen = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();

        if (omen > 500) {
            activateEvent(world, "ECHO_CASCADE");
        } else {
            deactivateEvent(world, "ECHO_CASCADE");
        }

        if (omen < 100) {
            activateEvent(world, "BIOLUMINESCENT_BLOOM");
        } else {
            deactivateEvent(world, "BIOLUMINESCENT_BLOOM");
        }
    }

    private void activateEvent(World world, String type) {
        String key = world.getName() + ":" + type;
        if (activeEvents.add(key)) onEventStart(world, type);
        applyEventTick(world, type);
    }

    private void deactivateEvent(World world, String type) {
        String key = world.getName() + ":" + type;
        if (activeEvents.remove(key)) onEventEnd(world, type);
    }

    private void onEventStart(World world, String type) {
        String msg = switch (type) {
            case "ECHO_CASCADE"         -> "§8The dark knows you're here. Something in it listens.";
            case "BIOLUMINESCENT_BLOOM" -> "§aThe cave stirs. The walls have begun to glow.";
            default -> null;
        };
        if (msg != null) {
            world.getPlayers().stream()
                    .filter(p -> p.getLocation().getBlockY() < UNDERGROUND_Y)
                    .forEach(p -> p.sendMessage(msg));
        }
    }

    private void onEventEnd(World world, String type) {
        String msg = switch (type) {
            case "ECHO_CASCADE" -> "§7The silence returns. You are no longer certain it was ever absent.";
            default -> null;
        };
        if (msg != null) {
            world.getPlayers().stream()
                    .filter(p -> p.getLocation().getBlockY() < UNDERGROUND_Y)
                    .forEach(p -> p.sendMessage(msg));
        }
    }

    private void applyEventTick(World world, String type) {
        world.getPlayers().stream()
                .filter(p -> p.getLocation().getBlockY() < UNDERGROUND_Y)
                .forEach(p -> applyToPlayer(p, type));
    }

    private void applyToPlayer(Player player, String type) {
        switch (type) {
            case "ECHO_CASCADE" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 200, 0));
                player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 200, 0));
            }
            case "BIOLUMINESCENT_BLOOM" ->
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 300, 0));
        }
    }

    public boolean isEventActive(World world, String type) {
        return activeEvents.contains(world.getName() + ":" + type);
    }
}
