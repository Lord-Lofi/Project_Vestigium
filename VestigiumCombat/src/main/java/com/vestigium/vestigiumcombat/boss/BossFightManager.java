package com.vestigium.vestigiumcombat.boss;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.model.ParticlePriority;
import com.vestigium.vestigiumcombat.VestigiumCombat;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Boss fight phase transitions for Named Wardens and structure bosses.
 *
 * Boss entities are identified by PDC key vestigium:named_warden (STRING).
 * Current phase stored in vestigium:boss_phase (INTEGER):
 *   0 — full health, no phase effects active
 *   1 — ≤75% HP: speed +1 (SPEED 0), CRIT burst, WARDEN_AMBIENT
 *   2 — ≤50% HP: speed +2 (SPEED 1), EXPLOSION ring, WARDEN_SONIC_BOOM
 *   3 — ≤25% HP: speed +3 (SPEED 2), lava ring, dual sounds, +25% damage bonus
 *
 * Phase transitions are one-way (phase only increases) and broadcast to
 * all players within range.
 */
public class BossFightManager implements Listener {

    private static final NamespacedKey KEY_NAMED_WARDEN = new NamespacedKey("vestigium", "named_warden");
    private static final NamespacedKey KEY_BOSS_PHASE   = new NamespacedKey("vestigium", "boss_phase");

    private final VestigiumCombat plugin;

    public BossFightManager(VestigiumCombat plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[BossFightManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Phase transition — fires after damage is finalized (MONITOR reads newHp)
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity boss)) return;
        if (!isBoss(boss)) return;

        var maxHpAttr = boss.getAttribute(Attribute.MAX_HEALTH);
        if (maxHpAttr == null) return;

        double maxHp  = maxHpAttr.getValue();
        double newHp  = boss.getHealth() - event.getFinalDamage();
        if (newHp <= 0) return;

        double pct = newHp / maxHp;
        int currentPhase = getBossPhase(boss);

        if      (pct <= 0.25 && currentPhase < 3) triggerPhase(boss, 3);
        else if (pct <= 0.50 && currentPhase < 2) triggerPhase(boss, 2);
        else if (pct <= 0.75 && currentPhase < 1) triggerPhase(boss, 1);
    }

    // -------------------------------------------------------------------------
    // Phase 3 outgoing damage bonus (+25%)
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof LivingEntity boss)) return;
        if (!isBoss(boss)) return;
        if (getBossPhase(boss) < 3) return;
        event.setDamage(event.getDamage() * 1.25);
    }

    // -------------------------------------------------------------------------

    private void triggerPhase(LivingEntity boss, int phase) {
        setBossPhase(boss, phase);

        Location loc  = boss.getLocation();
        String   name = boss.getCustomName() != null ? boss.getCustomName() : "The Warden";

        switch (phase) {
            case 1 -> {
                applySpeed(boss, 0);
                burstParticles(loc, Particle.CRIT, 24, 2.0);
                loc.getWorld().playSound(loc, Sound.ENTITY_WARDEN_AMBIENT, 1.2f, 0.8f);
                broadcastNearby(loc, 50, "§e" + name + " §7begins to shift.");
            }
            case 2 -> {
                applySpeed(boss, 1);
                burstParticles(loc, Particle.EXPLOSION_EMITTER, 8, 1.5);
                loc.getWorld().playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 0.9f);
                broadcastNearby(loc, 50, "§6" + name + " §7enters a second phase!");
            }
            case 3 -> {
                applySpeed(boss, 2);
                lavaRing(loc);
                loc.getWorld().playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 0.7f);
                loc.getWorld().playSound(loc, Sound.ENTITY_WITHER_DEATH, 0.5f, 1.5f);
                broadcastNearby(loc, 80, "§4" + name + " §cunleashes its full power!");
            }
        }
    }

    private void applySpeed(LivingEntity boss, int amplifier) {
        boss.addPotionEffect(
                new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, amplifier, false, false));
    }

    private void burstParticles(Location loc, Particle particle, int count, double radius) {
        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI * i) / count;
            Location p = loc.clone().add(
                    Math.cos(angle) * radius, 1.0, Math.sin(angle) * radius);
            VestigiumLib.getParticleManager().queueParticle(p, particle, null, ParticlePriority.GAMEPLAY);
        }
    }

    private void lavaRing(Location loc) {
        for (int i = 0; i < 24; i++) {
            double angle = (2 * Math.PI * i) / 24;
            Location p = loc.clone().add(
                    Math.cos(angle) * 4.0, 0.2, Math.sin(angle) * 4.0);
            VestigiumLib.getParticleManager().queueParticle(p, Particle.LAVA, null, ParticlePriority.GAMEPLAY);
        }
    }

    private void broadcastNearby(Location loc, double radius, String message) {
        loc.getWorld().getNearbyPlayers(loc, radius).forEach(p -> p.sendMessage(message));
    }

    private boolean isBoss(LivingEntity entity) {
        return entity.getPersistentDataContainer()
                .has(KEY_NAMED_WARDEN, PersistentDataType.STRING);
    }

    private int getBossPhase(LivingEntity boss) {
        return boss.getPersistentDataContainer()
                .getOrDefault(KEY_BOSS_PHASE, PersistentDataType.INTEGER, 0);
    }

    private void setBossPhase(LivingEntity boss, int phase) {
        boss.getPersistentDataContainer()
                .set(KEY_BOSS_PHASE, PersistentDataType.INTEGER, phase);
    }
}
