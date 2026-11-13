package com.vestigium.vestigiummobs.hostile;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiummobs.VestigiumMobs;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tide Lurker — 2% of Drowned in deep_ocean during storms at high tide.
 * +75% HP; damages nearby boats every second; despawns when tide recedes.
 */
public class TideLurkerManager implements Listener {

    private static final NamespacedKey VARIANT_KEY =
            new NamespacedKey("vestigium", "tide_lurker_variant");

    private final VestigiumMobs plugin;
    private final Set<UUID> lurkerIds = new HashSet<>();
    private BukkitRunnable tideTask;

    public TideLurkerManager(VestigiumMobs plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startTideTask();
        plugin.getLogger().info("[TideLurkerManager] Initialized.");
    }

    public void shutdown() {
        if (tideTask != null) tideTask.cancel();
    }

    private void startTideTask() {
        tideTask = new BukkitRunnable() {
            @Override
            public void run() {
                boolean highTide = VestigiumLib.getSeasonAPI().isHighTide();
                Iterator<UUID> it = lurkerIds.iterator();
                while (it.hasNext()) {
                    Entity entity = plugin.getServer().getEntity(it.next());
                    if (!(entity instanceof Drowned drowned) || !drowned.isValid()) {
                        it.remove();
                        continue;
                    }
                    if (!highTide && !drowned.getWorld().hasStorm()) {
                        drowned.remove();
                        it.remove();
                        continue;
                    }
                    drowned.getWorld()
                            .getNearbyEntities(drowned.getLocation(), 2, 2, 2).stream()
                            .filter(e -> e instanceof Boat)
                            .forEach(e -> {
                                if (e instanceof Damageable d) d.damage(3.0, drowned);
                                else e.remove();
                            });
                }
            }
        };
        tideTask.runTaskTimer(plugin, 20L, 20L);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) return;

        Entity entity = event.getEntity();
        if (entity.getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL) return;
        if (!(entity instanceof Drowned)) return;

        String biome = entity.getLocation().getBlock().getBiome().getKey().getKey();
        if (!biome.contains("deep_ocean")) return;
        if (!entity.getWorld().hasStorm()) return;
        if (!VestigiumLib.getSeasonAPI().isHighTide()) return;
        if (ThreadLocalRandom.current().nextInt(100) >= 2) return;

        Mob mob = (Mob) entity;
        mob.getPersistentDataContainer().set(VARIANT_KEY, PersistentDataType.STRING, "tide_lurker");

        AttributeInstance hp = mob.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(hp.getBaseValue() * 1.75);
        mob.setHealth(mob.getAttribute(Attribute.MAX_HEALTH).getValue());

        mob.setCustomName("§bTide Lurker");
        mob.setCustomNameVisible(true);
        lurkerIds.add(mob.getUniqueId());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        String variant = mob.getPersistentDataContainer()
                .get(VARIANT_KEY, PersistentDataType.STRING);
        if (!"tide_lurker".equals(variant)) return;
        lurkerIds.remove(mob.getUniqueId());
    }
}
