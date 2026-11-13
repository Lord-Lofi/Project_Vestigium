package com.vestigium.vestigiummobs.hostile;

import com.vestigium.vestigiummobs.VestigiumMobs;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Thornback — 2% of overworld Zombies spawning above Y=40.
 * Retaliates by firing 2 arrows at any player who attacks it.
 * Drops a Spine Fragment on death.
 */
public class ThornbackManager implements Listener {

    private static final NamespacedKey VARIANT_KEY =
            new NamespacedKey("vestigium", "thornback_variant");

    private final VestigiumMobs plugin;

    public ThornbackManager(VestigiumMobs plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[ThornbackManager] Initialized.");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) return;

        Entity entity = event.getEntity();
        if (entity.getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL) return;
        if (!(entity instanceof Zombie)) return;
        if (entity.getLocation().getY() < 40) return;
        if (ThreadLocalRandom.current().nextInt(100) >= 2) return;

        Mob mob = (Mob) entity;
        mob.getPersistentDataContainer().set(VARIANT_KEY, PersistentDataType.STRING, "thornback");
        mob.setCustomName("§2Thornback");
        mob.setCustomNameVisible(true);
    }

    // Fire 2 arrows toward the attacker when a player hits this mob
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        String variant = mob.getPersistentDataContainer()
                .get(VARIANT_KEY, PersistentDataType.STRING);
        if (!"thornback".equals(variant)) return;

        org.bukkit.Location shootFrom = mob.getEyeLocation();
        Vector dir = attacker.getEyeLocation().subtract(shootFrom).toVector().normalize();

        for (int i = 0; i < 2; i++) {
            Vector spread = dir.clone().add(new Vector(
                    ThreadLocalRandom.current().nextDouble(-0.05, 0.05),
                    ThreadLocalRandom.current().nextDouble(-0.05, 0.05),
                    ThreadLocalRandom.current().nextDouble(-0.05, 0.05)));
            Arrow arrow = mob.getWorld().spawnArrow(shootFrom, spread, 1.5f, 2.0f);
            arrow.setShooter(mob);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        String variant = mob.getPersistentDataContainer()
                .get(VARIANT_KEY, PersistentDataType.STRING);
        if (!"thornback".equals(variant)) return;

        ItemStack spine = new ItemStack(Material.PRISMARINE_SHARD);
        ItemMeta meta = spine.getItemMeta();
        meta.setDisplayName("§7Spine Fragment");
        meta.setLore(List.of("§8A barbed spine from a Thornback's hide."));
        spine.setItemMeta(meta);
        event.getDrops().add(spine);
    }
}
