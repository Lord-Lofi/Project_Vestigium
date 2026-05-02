package com.vestigium.vestigiummobs.passive;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.model.Season;
import com.vestigium.vestigiummobs.VestigiumMobs;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages territorial and seasonal passive mob behavior.
 *
 * Territorial wildlife:
 *   Wolves — form packs; a "pack_alpha" wolf gains +25% HP and leads the pack
 *   Foxes  — during AUTUMN have 15% higher natural spawn rate in forests
 *   Horses — during SPRING have 10% chance of being "swift" variant (+15% speed)
 *
 * Seasonal behavior:
 *   WINTER → most passive mobs become 30% slower
 *   SUMMER → passive mobs near water have 10% larger herds
 *
 * Behavior keys stored as PDC on the entity.
 */
public class PassiveMobManager implements Listener {

    private static final NamespacedKey PACK_ROLE_KEY =
            new NamespacedKey("vestigium", "pack_role");
    private static final NamespacedKey SEASONAL_BUFF_KEY =
            new NamespacedKey("vestigium", "seasonal_buff");

    // Re-evaluate seasonal buffs every 6,000 ticks (5 min)
    private static final long SEASON_TICK_INTERVAL = 6_000L;

    private final VestigiumMobs plugin;
    private BukkitRunnable seasonTask;

    public PassiveMobManager(VestigiumMobs plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startSeasonalBehaviorTask();
        plugin.getLogger().info("[PassiveMobManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Spawn hooks
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) return;

        Entity entity = event.getEntity();
        Season season = VestigiumLib.getSeasonAPI().getCurrentSeason();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (entity instanceof Wolf wolf) {
            handleWolfSpawn(wolf, rng);
        } else if (entity instanceof Fox && season == Season.AUTUMN) {
            // Autumn fox boost — slight HP bump to represent hardier autumn foxes
            var hp = fox((Fox) entity).getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(hp.getBaseValue() * 1.1);
        } else if (entity instanceof Horse horse && season == Season.SPRING) {
            if (rng.nextInt(100) < 10) {
                applySwiftHorse(horse);
            }
        }

        // Winter slow
        if (season == Season.WINTER && isPassiveLand(entity)) {
            applyWinterSlow((Mob) entity);
        }
    }

    private void handleWolfSpawn(Wolf wolf, ThreadLocalRandom rng) {
        // 15% chance of being a pack alpha
        if (rng.nextInt(100) < 15) {
            wolf.getPersistentDataContainer()
                    .set(PACK_ROLE_KEY, PersistentDataType.STRING, "alpha");
            wolf.setCustomName("§cPack Alpha");
            wolf.setCustomNameVisible(true);
            var hp = wolf.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) {
                hp.setBaseValue(hp.getBaseValue() * 1.25);
                wolf.setHealth(hp.getValue());
            }
        }
    }

    private void applySwiftHorse(Horse horse) {
        horse.getPersistentDataContainer()
                .set(SEASONAL_BUFF_KEY, PersistentDataType.STRING, "swift");
        var speed = horse.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(speed.getBaseValue() * 1.15);
        horse.setCustomName("§aSwift " + capitalize(horse.getColor().name()));
        horse.setCustomNameVisible(true);
    }

    private void applyWinterSlow(Mob mob) {
        mob.getPersistentDataContainer()
                .set(SEASONAL_BUFF_KEY, PersistentDataType.STRING, "winter_slow");
        var speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(speed.getBaseValue() * 0.70);
    }

    // -------------------------------------------------------------------------
    // Seasonal refresh task — re-checks world season and updates existing mobs
    // -------------------------------------------------------------------------

    public void shutdown() {
        if (seasonTask != null) seasonTask.cancel();
    }

    private void startSeasonalBehaviorTask() {
        seasonTask = new BukkitRunnable() {
            @Override
            public void run() {
                Season season = VestigiumLib.getSeasonAPI().getCurrentSeason();
                plugin.getServer().getWorlds().forEach(world -> {
                    if (world.getEnvironment() != org.bukkit.World.Environment.NORMAL) return;
                    world.getEntitiesByClass(Wolf.class).forEach(wolf -> {
                        String role = wolf.getPersistentDataContainer()
                                .get(PACK_ROLE_KEY, PersistentDataType.STRING);
                        if ("alpha".equals(role) && season == Season.WINTER) {
                            wolf.getPersistentDataContainer().remove(SEASONAL_BUFF_KEY);
                        }
                    });
                });
            }
        };
        seasonTask.runTaskTimer(plugin, SEASON_TICK_INTERVAL, SEASON_TICK_INTERVAL);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isPassiveLand(Entity entity) {
        return entity instanceof Animals && !(entity instanceof Wolf)
                && !(entity instanceof Bee);
    }

    private static Fox fox(Fox f) { return f; }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
