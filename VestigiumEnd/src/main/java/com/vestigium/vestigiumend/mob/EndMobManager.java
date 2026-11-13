package com.vestigium.vestigiumend.mob;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumend.VestigiumEnd;
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
 * Custom End mob variants applied on natural spawn.
 *
 * End dimension variants:
 *   shulker_sentinel      — 2% shulkers; +100% HP, guaranteed shulker shell drop
 *   void_touched_enderman — 1% endermen; on death spawns 2 Vex, drops 3 ender pearls, adds omen 5
 *   chorus_phantom_end    — 0.3% endermen; applies Levitation I on hit, drops 2 chorus fruit
 *   corrupted_endermite   — 5% endermites; Blindness 2s on hit, drops nether quartz
 *
 * Overworld variant (handled in separate listener):
 *   wailing_phantom       — 3% phantoms (any world); Fear effect on death — Slowness II + Weakness I
 *                           to all players within 10 blocks for 5 seconds
 *
 * Variant stored as "end_mob_variant" PDC STRING on entity.
 */
public class EndMobManager implements Listener {

    private static final NamespacedKey VARIANT_KEY =
            new NamespacedKey("vestigium", "end_mob_variant");

    private final VestigiumEnd plugin;

    public EndMobManager(VestigiumEnd plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[EndMobManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // End dimension spawns

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEndSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) return;

        Entity entity = event.getEntity();
        if (entity.getWorld().getEnvironment() != org.bukkit.World.Environment.THE_END) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (entity instanceof Shulker && rng.nextInt(100) < 2) {
            applyVariant((Mob) entity, "shulker_sentinel");
        } else if (entity instanceof Enderman) {
            if (rng.nextInt(300) < 1) {
                applyVariant((Mob) entity, "chorus_phantom_end");
            } else if (rng.nextInt(100) < 1) {
                applyVariant((Mob) entity, "void_touched_enderman");
            }
        } else if (entity instanceof Endermite && rng.nextInt(20) < 1) {
            applyVariant((Mob) entity, "corrupted_endermite");
        }
    }

    // Wailing Phantom — any world, natural phantom spawns only

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPhantomSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        if (!(event.getEntity() instanceof Phantom)) return;
        if (ThreadLocalRandom.current().nextInt(100) < 3) {
            applyVariant((Mob) event.getEntity(), "wailing_phantom");
        }
    }

    // -------------------------------------------------------------------------

    private void applyVariant(Mob mob, String variant) {
        mob.getPersistentDataContainer().set(VARIANT_KEY, PersistentDataType.STRING, variant);

        switch (variant) {
            case "shulker_sentinel" -> {
                AttributeInstance hp = mob.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null) hp.setBaseValue(hp.getBaseValue() * 2.0);
                mob.setHealth(mob.getAttribute(Attribute.MAX_HEALTH).getValue());
                mob.setCustomName("§5Shulker Sentinel");
                mob.setCustomNameVisible(true);
            }
            case "void_touched_enderman" -> {
                mob.setCustomName("§8Void-Touched Enderman");
                mob.setCustomNameVisible(true);
                mob.setGlowing(true);
            }
            case "chorus_phantom_end" -> {
                mob.setCustomName("§dChorus Phantom");
                mob.setCustomNameVisible(true);
            }
            case "corrupted_endermite" -> {
                mob.setCustomName("§5Corrupted Endermite");
                mob.setCustomNameVisible(true);
                mob.setGlowing(true);
            }
            case "wailing_phantom" -> {
                mob.setCustomName("§8Wailing Phantom");
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

        switch (variant) {
            case "chorus_phantom_end" -> {
                victim.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 40, 0));
                victim.sendMessage("§dThe chorus sings. The ground retreats.");
            }
            case "corrupted_endermite" ->
                victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        String variant = mob.getPersistentDataContainer()
                .get(VARIANT_KEY, PersistentDataType.STRING);
        if (variant == null) return;

        switch (variant) {
            case "shulker_sentinel" ->
                event.getDrops().add(new ItemStack(Material.SHULKER_SHELL));
            case "void_touched_enderman" -> {
                event.getDrops().add(new ItemStack(Material.ENDER_PEARL, 3));
                for (int i = 0; i < 2; i++) {
                    mob.getWorld().spawnEntity(mob.getLocation(), EntityType.VEX);
                }
                VestigiumLib.getOmenAPI().addOmen(5);
                mob.getWorld().getPlayers().stream()
                        .filter(p -> p.getLocation().distanceSquared(mob.getLocation()) < 100)
                        .forEach(p -> p.sendMessage("§8The void leaks through where it fell."));
            }
            case "chorus_phantom_end" ->
                event.getDrops().add(new ItemStack(Material.CHORUS_FRUIT, 2));
            case "corrupted_endermite" ->
                event.getDrops().add(new ItemStack(Material.QUARTZ));
            case "wailing_phantom" -> {
                mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_PHANTOM_DEATH, 1.0f, 0.6f);
                mob.getWorld().getPlayers().stream()
                        .filter(p -> p.getLocation().distanceSquared(mob.getLocation()) < 100)
                        .forEach(p -> {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 1));
                            p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 100, 0));
                            p.sendMessage("§8Its death cry tears through you.");
                        });
            }
        }
    }

    public String getVariant(Entity entity) {
        return entity.getPersistentDataContainer()
                .get(VARIANT_KEY, PersistentDataType.STRING);
    }
}
