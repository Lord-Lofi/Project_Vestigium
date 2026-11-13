package com.vestigium.vestigiummobs.hostile;

import com.vestigium.vestigiummobs.VestigiumMobs;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Echo Beast — 0.5% of Zombies spawning below Y=-20 in the overworld.
 * Attracted by player sounds: block breaks, chest opens, fall damage.
 * Pathfinds toward the last detected sound within 32 blocks.
 */
public class EchoBeastManager implements Listener {

    private static final NamespacedKey VARIANT_KEY =
            new NamespacedKey("vestigium", "echo_beast_variant");
    private static final double SOUND_RADIUS = 32.0;

    private final VestigiumMobs plugin;
    private final Map<UUID, Location> soundTargets = new HashMap<>();
    private BukkitRunnable navigationTask;

    public EchoBeastManager(VestigiumMobs plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startNavigationTask();
        plugin.getLogger().info("[EchoBeastManager] Initialized.");
    }

    public void shutdown() {
        if (navigationTask != null) navigationTask.cancel();
    }

    private void startNavigationTask() {
        navigationTask = new BukkitRunnable() {
            @Override
            public void run() {
                Iterator<Map.Entry<UUID, Location>> it = soundTargets.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, Location> entry = it.next();
                    Entity entity = plugin.getServer().getEntity(entry.getKey());
                    if (!(entity instanceof Mob beast) || !beast.isValid()) {
                        it.remove();
                        continue;
                    }
                    Location target = entry.getValue();
                    if (beast.getLocation().distanceSquared(target) < 4.0) {
                        it.remove();
                        continue;
                    }
                    beast.getPathfinder().moveTo(target, 1.2);
                }
            }
        };
        navigationTask.runTaskTimer(plugin, 40L, 40L);
    }

    private void registerSound(Location soundLoc) {
        if (soundLoc.getWorld() == null) return;
        soundLoc.getWorld()
                .getNearbyEntities(soundLoc, SOUND_RADIUS, SOUND_RADIUS, SOUND_RADIUS,
                        e -> e instanceof Mob m && "echo_beast".equals(
                                m.getPersistentDataContainer().get(VARIANT_KEY, PersistentDataType.STRING)))
                .forEach(e -> soundTargets.put(e.getUniqueId(), soundLoc.clone()));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) return;

        Entity entity = event.getEntity();
        if (entity.getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL) return;
        if (!(entity instanceof Zombie)) return;
        if (entity.getLocation().getY() > -20) return;
        if (ThreadLocalRandom.current().nextInt(200) >= 1) return;

        Mob mob = (Mob) entity;
        mob.getPersistentDataContainer().set(VARIANT_KEY, PersistentDataType.STRING, "echo_beast");

        AttributeInstance hp = mob.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(hp.getBaseValue() * 1.5);
        mob.setHealth(mob.getAttribute(Attribute.MAX_HEALTH).getValue());

        mob.setCustomName("§8Echo Beast");
        mob.setCustomNameVisible(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        registerSound(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() != null) {
            registerSound(event.getClickedBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        registerSound(event.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        String variant = mob.getPersistentDataContainer()
                .get(VARIANT_KEY, PersistentDataType.STRING);
        if (!"echo_beast".equals(variant)) return;
        soundTargets.remove(mob.getUniqueId());
    }
}
