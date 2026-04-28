package com.vestigium.vestigiumcombat.status;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumcombat.VestigiumCombat;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Custom status effects beyond vanilla, applied by specific mob types and
 * weapon/artifact interactions.
 *
 * Effects (stored as PDC on the affected entity):
 *
 *   CORRUPTED    — applied by sculk-infested mobs and cultist weapons
 *                  Deals 0.5 damage/2s; glowing; cleared by milk
 *                  PDC: "vc_corrupted_until" LONG (expire timestamp ms)
 *
 *   PHASED       — applied by void_walker mobs; random teleport every 5s
 *                  PDC: "vc_phased_until" LONG
 *
 *   BRANDED      — applied by cultist fire sources; burning + weakness I
 *                  PDC: "vc_branded_until" LONG
 *
 *   RESONANT     — applied by Resonant Terminal overload (standing too long)
 *                  Grants brief night-vision but applies confusion
 *                  PDC: "vc_resonant_until" LONG
 *
 * Tick interval: 40 ticks (2 seconds).
 */
public class CustomStatusEffectManager implements Listener {

    public static final NamespacedKey CORRUPTED_KEY = new NamespacedKey("vestigium", "vc_corrupted_until");
    public static final NamespacedKey PHASED_KEY    = new NamespacedKey("vestigium", "vc_phased_until");
    public static final NamespacedKey BRANDED_KEY   = new NamespacedKey("vestigium", "vc_branded_until");
    public static final NamespacedKey RESONANT_KEY  = new NamespacedKey("vestigium", "vc_resonant_until");

    private static final long TICK_INTERVAL = 40L;

    private final VestigiumCombat plugin;

    public CustomStatusEffectManager(VestigiumCombat plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                plugin.getServer().getWorlds().forEach(world ->
                        world.getLivingEntities().forEach(e -> tickEffects(e, now)));
            }
        }.runTaskTimer(plugin, TICK_INTERVAL, TICK_INTERVAL);

        plugin.getLogger().info("[CustomStatusEffectManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Public API — apply effects
    // -------------------------------------------------------------------------

    public void applyCorrupted(LivingEntity entity, long durationMs) {
        long until = System.currentTimeMillis() + durationMs;
        entity.getPersistentDataContainer().set(CORRUPTED_KEY, PersistentDataType.LONG, until);
        entity.setGlowing(true);
        if (entity instanceof Player p)
            p.sendMessage("§8You feel the sculk spreading through you.");
    }

    public void applyPhased(LivingEntity entity, long durationMs) {
        long until = System.currentTimeMillis() + durationMs;
        entity.getPersistentDataContainer().set(PHASED_KEY, PersistentDataType.LONG, until);
        if (entity instanceof Player p)
            p.sendMessage("§5Reality becomes unreliable.");
    }

    public void applyBranded(LivingEntity entity, long durationMs) {
        long until = System.currentTimeMillis() + durationMs;
        entity.getPersistentDataContainer().set(BRANDED_KEY, PersistentDataType.LONG, until);
        entity.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, (int)(durationMs/50), 0));
        if (entity instanceof Player p)
            p.sendMessage("§cThe brand burns. You feel weakened.");
    }

    public void applyResonant(LivingEntity entity, long durationMs) {
        long until = System.currentTimeMillis() + durationMs;
        entity.getPersistentDataContainer().set(RESONANT_KEY, PersistentDataType.LONG, until);
        entity.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, (int)(durationMs/50), 0));
        entity.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, (int)(durationMs/50), 0));
        if (entity instanceof Player p)
            p.sendMessage("§bThe resonance overwhelms your senses.");
    }

    public boolean hasEffect(LivingEntity entity, NamespacedKey key) {
        long until = entity.getPersistentDataContainer()
                .getOrDefault(key, PersistentDataType.LONG, 0L);
        return System.currentTimeMillis() < until;
    }

    // -------------------------------------------------------------------------
    // Mob application triggers
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        String variant = getDamagerVariant(event.getDamager());
        if (variant == null) return;

        switch (variant) {
            case "sculk_infested" -> applyCorrupted(victim, 8_000);
            case "void_walker"    -> applyPhased(victim, 10_000);
        }
    }

    // -------------------------------------------------------------------------
    // Tick effects
    // -------------------------------------------------------------------------

    private void tickEffects(LivingEntity entity, long now) {
        var pdc = entity.getPersistentDataContainer();

        // CORRUPTED — 0.5 damage every tick (2s)
        long corruptedUntil = pdc.getOrDefault(CORRUPTED_KEY, PersistentDataType.LONG, 0L);
        if (now < corruptedUntil) {
            entity.damage(0.5);
            VestigiumLib.getParticleManager().queueParticle(
                    entity.getLocation().add(0, 1, 0), Particle.SCULK_SOUL, null,
                    com.vestigium.lib.model.ParticlePriority.ATMOSPHERIC);
        } else if (corruptedUntil > 0 && now >= corruptedUntil) {
            pdc.remove(CORRUPTED_KEY);
            entity.setGlowing(false);
        }

        // PHASED — random teleport every tick check (low probability)
        long phasedUntil = pdc.getOrDefault(PHASED_KEY, PersistentDataType.LONG, 0L);
        if (now < phasedUntil && ThreadLocalRandom.current().nextInt(5) == 0) {
            var loc = entity.getLocation();
            double dx = ThreadLocalRandom.current().nextDouble(-4, 4);
            double dz = ThreadLocalRandom.current().nextDouble(-4, 4);
            entity.teleport(loc.add(dx, 0, dz));
        } else if (phasedUntil > 0 && now >= phasedUntil) {
            pdc.remove(PHASED_KEY);
        }

        // BRANDED — fire tick maintenance
        long brandedUntil = pdc.getOrDefault(BRANDED_KEY, PersistentDataType.LONG, 0L);
        if (now < brandedUntil) {
            entity.setFireTicks(60);
        } else if (brandedUntil > 0 && now >= brandedUntil) {
            pdc.remove(BRANDED_KEY);
        }

        // RESONANT — expires naturally via potion effects; just remove key
        long resonantUntil = pdc.getOrDefault(RESONANT_KEY, PersistentDataType.LONG, 0L);
        if (resonantUntil > 0 && now >= resonantUntil) {
            pdc.remove(RESONANT_KEY);
        }
    }

    // -------------------------------------------------------------------------

    private static String getDamagerVariant(Entity damager) {
        if (!(damager instanceof LivingEntity le)) return null;
        return le.getPersistentDataContainer()
                .get(new NamespacedKey("vestigium", "mob_variant"), PersistentDataType.STRING);
    }
}
