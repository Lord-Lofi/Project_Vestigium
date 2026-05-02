package com.vestigium.vestigiumnether.mob;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumnether.VestigiumNether;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Custom nether mob variants applied on natural spawn.
 *
 * Variants:
 *   ancient_ghast      — 0.5% ghasts; triple HP, drops blaze rod + nether brick, adds omen 10
 *   corrupted_piglin   — 2% piglins; Wither I on hit, drops soul sand, ignores gold bartering
 *   wither_remnant     — 1% wither skeletons; +50% HP, on death releases wither effect cloud, 10% skull drop
 *
 * Variant stored as "nether_mob_variant" PDC STRING on entity.
 */
public class NetherMobManager implements Listener {

    private static final NamespacedKey VARIANT_KEY =
            new NamespacedKey("vestigium", "nether_mob_variant");

    private final VestigiumNether plugin;

    public NetherMobManager(VestigiumNether plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[NetherMobManager] Initialized.");
    }

    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) return;

        Entity entity = event.getEntity();
        if (entity.getWorld().getEnvironment() != org.bukkit.World.Environment.NETHER) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (entity instanceof Ghast && rng.nextInt(200) < 1) {
            applyVariant((Mob) entity, "ancient_ghast");
        } else if (entity instanceof Piglin && rng.nextInt(100) < 2) {
            applyVariant((Mob) entity, "corrupted_piglin");
        } else if (entity instanceof WitherSkeleton && rng.nextInt(100) < 1) {
            applyVariant((Mob) entity, "wither_remnant");
        }
    }

    private void applyVariant(Mob mob, String variant) {
        mob.getPersistentDataContainer().set(VARIANT_KEY, PersistentDataType.STRING, variant);

        switch (variant) {
            case "ancient_ghast" -> {
                AttributeInstance hp = mob.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null) hp.setBaseValue(hp.getBaseValue() * 3.0);
                mob.setHealth(mob.getAttribute(Attribute.MAX_HEALTH).getValue());
                mob.setCustomName("§4Ancient Ghast");
                mob.setCustomNameVisible(true);
                mob.setGlowing(true);
            }
            case "corrupted_piglin" -> {
                mob.setCustomName("§5Corrupted Piglin");
                mob.setCustomNameVisible(true);
            }
            case "wither_remnant" -> {
                AttributeInstance hp = mob.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null) hp.setBaseValue(hp.getBaseValue() * 1.5);
                mob.setHealth(mob.getAttribute(Attribute.MAX_HEALTH).getValue());
                mob.setCustomName("§8Wither Remnant");
                mob.setCustomNameVisible(true);
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

        if (variant.equals("corrupted_piglin")) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 80, 0));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        String variant = mob.getPersistentDataContainer()
                .get(VARIANT_KEY, PersistentDataType.STRING);
        if (variant == null) return;

        switch (variant) {
            case "ancient_ghast" -> {
                event.getDrops().add(new ItemStack(Material.BLAZE_ROD, 2));
                event.getDrops().add(new ItemStack(Material.NETHER_BRICK, 4));
                VestigiumLib.getOmenAPI().addOmen(10);
            }
            case "corrupted_piglin" ->
                event.getDrops().add(new ItemStack(Material.SOUL_SAND));
            case "wither_remnant" -> {
                // Release wither cloud on death
                mob.getWorld().spawnParticle(
                        org.bukkit.Particle.SMOKE, mob.getLocation(), 30, 1, 1, 1, 0.05);
                mob.getWorld().getPlayers().stream()
                        .filter(p -> p.getLocation().distanceSquared(mob.getLocation()) < 64)
                        .forEach(p -> p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 100, 0)));
                if (ThreadLocalRandom.current().nextInt(10) == 0) {
                    event.getDrops().add(new ItemStack(Material.WITHER_SKELETON_SKULL));
                }
            }
        }
    }
}
