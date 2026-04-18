package com.vestigium.vestigiummobs.hostile;

import com.vestigium.lib.VestigiumLib;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Applies custom hostile mob variants on natural spawn.
 *
 * Rare variant system:
 *   ANCIENT        — 2% of zombies; +50% max HP, glowing, drops ancient debris fragment
 *   SCULK_INFESTED — 1% of skeletons; on hit applies 4s wither II to attacker
 *   TIDE_TOUCHED   — 3% of drowned; buffed in water, drops tidal inscription shard
 *   VOID_WALKER    — 0.5% of endermen; teleports nearby players on hit
 *
 * Variant key stored as "mob_variant" PDC STRING on the entity.
 */
public class CustomHostileMobManager implements Listener {

    private static final NamespacedKey MOB_VARIANT_KEY =
            new NamespacedKey("vestigium", "mob_variant");

    private final VestigiumMobs plugin;

    public CustomHostileMobManager(VestigiumMobs plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[CustomHostileMobManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Spawn hook
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) return;

        Entity entity = event.getEntity();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (entity instanceof Zombie && rng.nextInt(100) < 2) {
            applyVariant((Mob) entity, "ancient");
        } else if (entity instanceof Skeleton && rng.nextInt(100) < 1) {
            applyVariant((Mob) entity, "sculk_infested");
        } else if (entity instanceof Drowned && rng.nextInt(100) < 3) {
            applyVariant((Mob) entity, "tide_touched");
        } else if (entity instanceof Enderman && rng.nextInt(200) < 1) {
            applyVariant((Mob) entity, "void_walker");
        }
    }

    private void applyVariant(Mob mob, String variant) {
        mob.getPersistentDataContainer()
                .set(MOB_VARIANT_KEY, PersistentDataType.STRING, variant);

        switch (variant) {
            case "ancient" -> {
                AttributeInstance maxHp = mob.getAttribute(Attribute.MAX_HEALTH);
                if (maxHp != null) maxHp.setBaseValue(maxHp.getBaseValue() * 1.5);
                mob.setHealth(mob.getAttribute(Attribute.MAX_HEALTH).getValue());
                mob.setGlowing(true);
                mob.setCustomName("§6Ancient " + capitalize(mob.getType().name().replace("_", " ")));
                mob.setCustomNameVisible(true);
            }
            case "sculk_infested" -> {
                mob.setCustomName("§2Sculk-Infested " + capitalize(mob.getType().name().replace("_", " ")));
                mob.setCustomNameVisible(true);
            }
            case "tide_touched" -> {
                mob.setCustomName("§bTide-Touched Drowned");
                mob.setCustomNameVisible(true);
            }
            case "void_walker" -> {
                mob.setCustomName("§5Void Walker");
                mob.setCustomNameVisible(true);
                AttributeInstance speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
                if (speed != null) speed.setBaseValue(speed.getBaseValue() * 1.3);
            }
        }
    }

    // -------------------------------------------------------------------------
    // On-hit effects
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Mob attacker)) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        String variant = attacker.getPersistentDataContainer()
                .get(MOB_VARIANT_KEY, PersistentDataType.STRING);
        if (variant == null) return;

        switch (variant) {
            case "sculk_infested" ->
                victim.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.WITHER, 80, 1));
            case "void_walker" -> {
                // Teleport victim a short random distance
                org.bukkit.Location loc = victim.getLocation();
                double dx = ThreadLocalRandom.current().nextDouble(-8, 8);
                double dz = ThreadLocalRandom.current().nextDouble(-8, 8);
                victim.teleport(loc.add(dx, 0, dz));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Death drops
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        String variant = mob.getPersistentDataContainer()
                .get(MOB_VARIANT_KEY, PersistentDataType.STRING);
        if (variant == null) return;

        switch (variant) {
            case "ancient" -> {
                event.getDrops().add(new ItemStack(Material.ANCIENT_DEBRIS));
                VestigiumLib.getOmenAPI().addOmen(5);
            }
            case "tide_touched" ->
                event.getDrops().add(new ItemStack(Material.NAUTILUS_SHELL));
            case "void_walker" ->
                event.getDrops().add(new ItemStack(Material.ENDER_PEARL, 2));
        }
    }

    // -------------------------------------------------------------------------

    public String getVariant(Entity entity) {
        return entity.getPersistentDataContainer()
                .get(MOB_VARIANT_KEY, PersistentDataType.STRING);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        String[] words = s.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty())
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase()).append(" ");
        }
        return sb.toString().trim();
    }
}
