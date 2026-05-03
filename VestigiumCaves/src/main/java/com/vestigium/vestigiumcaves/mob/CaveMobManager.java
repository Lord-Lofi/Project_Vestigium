package com.vestigium.vestigiumcaves.mob;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumcaves.VestigiumCaves;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Custom cave mob variants applied on natural spawn.
 *
 * Variants:
 *   crystalback_spider  — 3% cave spiders; applies Poison II on hit, drops amethyst shard
 *   deep_sentinel       — 1% zombies below Y=0; +50% HP, fire resistant, drops iron nugget
 *   echo_shrieker       — 0.5% skeletons below Y=-40; on death plays warden sound + adds omen
 *
 * Variant stored as "cave_mob_variant" PDC STRING on entity.
 */
public class CaveMobManager implements Listener {

    private static final NamespacedKey VARIANT_KEY =
            new NamespacedKey("vestigium", "cave_mob_variant");

    private final VestigiumCaves plugin;

    public CaveMobManager(VestigiumCaves plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[CaveMobManager] Initialized.");
    }

    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) return;

        Entity entity = event.getEntity();
        int y = entity.getLocation().getBlockY();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (entity instanceof CaveSpider && rng.nextInt(100) < 3) {
            applyVariant((Mob) entity, "crystalback_spider");
        } else if (entity instanceof Zombie && !(entity instanceof Drowned)
                && y < 0 && rng.nextInt(100) < 1) {
            applyVariant((Mob) entity, "deep_sentinel");
        } else if (entity instanceof Skeleton && y < -40 && rng.nextInt(200) < 1) {
            applyVariant((Mob) entity, "echo_shrieker");
        }
    }

    private void applyVariant(Mob mob, String variant) {
        mob.getPersistentDataContainer().set(VARIANT_KEY, PersistentDataType.STRING, variant);

        switch (variant) {
            case "crystalback_spider" -> {
                mob.setCustomName("§dCrystalback Spider");
                mob.setCustomNameVisible(true);
            }
            case "deep_sentinel" -> {
                AttributeInstance hp = mob.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null) hp.setBaseValue(hp.getBaseValue() * 1.5);
                mob.setHealth(mob.getAttribute(Attribute.MAX_HEALTH).getValue());
                mob.setCustomName("§8Deep Sentinel");
                mob.setCustomNameVisible(true);
            }
            case "echo_shrieker" -> {
                mob.setCustomName("§2Echo Shrieker");
                mob.setCustomNameVisible(true);
                mob.setGlowing(true);
            }
        }
    }

    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Mob attacker)) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        String variant = attacker.getPersistentDataContainer()
                .get(VARIANT_KEY, PersistentDataType.STRING);
        if (variant == null) return;

        if (variant.equals("crystalback_spider")) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 1));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        String variant = mob.getPersistentDataContainer()
                .get(VARIANT_KEY, PersistentDataType.STRING);
        if (variant == null) return;

        switch (variant) {
            case "crystalback_spider" ->
                event.getDrops().add(new ItemStack(Material.AMETHYST_SHARD, 2));
            case "deep_sentinel" ->
                event.getDrops().add(new ItemStack(Material.IRON_NUGGET, 3));
            case "echo_shrieker" -> {
                mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_WARDEN_DEATH, 1.0f, 0.8f);
                VestigiumLib.getOmenAPI().addOmen(3);
            }
        }
    }

    public String getVariant(Entity entity) {
        return entity.getPersistentDataContainer()
                .get(VARIANT_KEY, PersistentDataType.STRING);
    }
}
