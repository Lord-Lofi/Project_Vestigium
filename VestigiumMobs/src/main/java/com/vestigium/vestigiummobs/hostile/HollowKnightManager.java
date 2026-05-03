package com.vestigium.vestigiummobs.hostile;

import com.vestigium.vestigiummobs.VestigiumMobs;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Hollow Knight — 1% of overworld Skeletons spawning above Y=40.
 * +50% HP, full iron armour (no drop), parry on hit.
 */
public class HollowKnightManager implements Listener {

    private static final NamespacedKey VARIANT_KEY =
            new NamespacedKey("vestigium", "hollow_knight_variant");

    private final VestigiumMobs plugin;

    public HollowKnightManager(VestigiumMobs plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[HollowKnightManager] Initialized.");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) return;

        Entity entity = event.getEntity();
        if (entity.getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL) return;
        if (!(entity instanceof Skeleton)) return;
        if (entity.getLocation().getY() < 40) return;
        if (ThreadLocalRandom.current().nextInt(100) >= 1) return;

        Mob mob = (Mob) entity;
        mob.getPersistentDataContainer().set(VARIANT_KEY, PersistentDataType.STRING, "hollow_knight");

        AttributeInstance hp = mob.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(hp.getBaseValue() * 1.5);
        mob.setHealth(mob.getAttribute(Attribute.MAX_HEALTH).getValue());

        EntityEquipment eq = mob.getEquipment();
        if (eq != null) {
            eq.setHelmet(new ItemStack(Material.IRON_HELMET));
            eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
            eq.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
            eq.setBoots(new ItemStack(Material.IRON_BOOTS));
            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setLeggingsDropChance(0f);
            eq.setBootsDropChance(0f);
        }

        mob.setCustomName("§7Hollow Knight");
        mob.setCustomNameVisible(true);
    }

    // 30% chance to parry a player's attack: reduce damage by 75% and knock back the attacker
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Mob victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        String variant = victim.getPersistentDataContainer()
                .get(VARIANT_KEY, PersistentDataType.STRING);
        if (!"hollow_knight".equals(variant)) return;

        if (ThreadLocalRandom.current().nextInt(10) < 3) {
            event.setDamage(event.getDamage() * 0.25);
            org.bukkit.util.Vector dir = attacker.getLocation()
                    .subtract(victim.getLocation()).toVector().normalize().multiply(1.5);
            dir.setY(0.4);
            attacker.setVelocity(dir);
            attacker.sendMessage("§7The knight parries your blow!");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        String variant = mob.getPersistentDataContainer()
                .get(VARIANT_KEY, PersistentDataType.STRING);
        if (!"hollow_knight".equals(variant)) return;
        event.getDrops().add(new ItemStack(Material.IRON_SWORD));
    }
}
