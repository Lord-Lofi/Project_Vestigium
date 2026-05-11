package com.vestigium.vestigiumeconomy.runic;

import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Drops rune items from specific mobs when killed by a player.
 *
 * Drop chances:
 *   Skeleton        5%  → Rune of Warding
 *   Spider / Cave   8%  → Rune of Venom
 *   Zombie          6%  → Rune of Resilience
 *   Creeper         10% → Rune of Thunder
 *   Drowned         8%  → Rune of the Deep
 *   Blaze           7%  → Rune of Swiftness
 *   Phantom         12% → Rune of Sight
 *   Warden          3%  → Rune of Sculk
 */
public class RuneDropListener implements Listener {

    private final RuneManager manager;

    public RuneDropListener(RuneManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        RuneManager.RuneType rune = switch (event.getEntityType()) {
            case SKELETON              -> rng.nextInt(100) < 5  ? RuneManager.RuneType.WARDING    : null;
            case SPIDER, CAVE_SPIDER   -> rng.nextInt(100) < 8  ? RuneManager.RuneType.VENOM      : null;
            case ZOMBIE                -> rng.nextInt(100) < 6  ? RuneManager.RuneType.RESILIENCE : null;
            case CREEPER               -> rng.nextInt(100) < 10 ? RuneManager.RuneType.THUNDER    : null;
            case DROWNED               -> rng.nextInt(100) < 8  ? RuneManager.RuneType.DEEP       : null;
            case BLAZE                 -> rng.nextInt(100) < 7  ? RuneManager.RuneType.SWIFTNESS  : null;
            case PHANTOM               -> rng.nextInt(100) < 12 ? RuneManager.RuneType.SIGHT      : null;
            case WARDEN                -> rng.nextInt(100) < 3  ? RuneManager.RuneType.SCULK      : null;
            default                    -> null;
        };

        if (rune != null) {
            event.getDrops().add(manager.createRune(rune));
        }
    }
}
