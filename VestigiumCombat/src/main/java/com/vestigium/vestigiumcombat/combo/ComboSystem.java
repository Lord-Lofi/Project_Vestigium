package com.vestigium.vestigiumcombat.combo;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.model.ParticlePriority;
import com.vestigium.vestigiumcombat.VestigiumCombat;
import com.vestigium.vestigiumcombat.tracker.CombatTracker;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Combo system — consecutive hits within COMBO_WINDOW_MS build a multiplier.
 *
 * Thresholds and effects:
 *   3  hits  → §e[x2]   damage multiplier 1.2x; CRIT particle burst
 *   6  hits  → §6[x3]   damage multiplier 1.4x; CRIT + SWEEP_ATTACK particles; knockback bonus
 *   10 hits  → §c[FURY] damage multiplier 1.75x; EXPLOSION particle; AOE 1.5 damage to nearby mobs
 *
 * On combo break (hit missed or combat timeout) the combo resets to 0.
 * The damage event is modified directly via setDamage().
 */
public class ComboSystem implements Listener {

    private final VestigiumCombat plugin;
    private final CombatTracker combatTracker;

    public ComboSystem(VestigiumCombat plugin, CombatTracker combatTracker) {
        this.plugin = plugin;
        this.combatTracker = combatTracker;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[ComboSystem] Initialized.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        int combo = combatTracker.getComboCount(attacker);

        if (combo >= 10) {
            applyFuryCombo(attacker, event);
        } else if (combo >= 6) {
            applyHeavyCombo(attacker, event);
        } else if (combo >= 3) {
            applyLightCombo(attacker, event);
        }
    }

    // -------------------------------------------------------------------------

    private void applyLightCombo(Player attacker, EntityDamageByEntityEvent event) {
        event.setDamage(event.getDamage() * 1.2);
        attacker.sendActionBar("§e✦ COMBO x" + combatTracker.getComboCount(attacker));
        VestigiumLib.getParticleManager().queueParticle(
                event.getEntity().getLocation(), Particle.CRIT, null, ParticlePriority.GAMEPLAY);
    }

    private void applyHeavyCombo(Player attacker, EntityDamageByEntityEvent event) {
        event.setDamage(event.getDamage() * 1.4);
        attacker.sendActionBar("§6✦✦ COMBO x" + combatTracker.getComboCount(attacker));
        VestigiumLib.getParticleManager().queueParticle(
                event.getEntity().getLocation(), Particle.CRIT, null, ParticlePriority.GAMEPLAY);
        VestigiumLib.getParticleManager().queueParticle(
                event.getEntity().getLocation(), Particle.SWEEP_ATTACK, null, ParticlePriority.GAMEPLAY);
        // Bonus knockback via velocity
        if (event.getEntity() instanceof LivingEntity le) {
            var dir = attacker.getLocation().getDirection().normalize().multiply(0.5).setY(0.3);
            le.setVelocity(le.getVelocity().add(dir));
        }
        attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.2f);
    }

    private void applyFuryCombo(Player attacker, EntityDamageByEntityEvent event) {
        event.setDamage(event.getDamage() * 1.75);
        attacker.sendActionBar("§c⚡ FURY ⚡");
        VestigiumLib.getParticleManager().queueParticle(
                event.getEntity().getLocation(), Particle.EXPLOSION, null, ParticlePriority.GAMEPLAY);

        // AOE — 1.5 damage to mobs within 2.5 blocks
        entity(event.getEntity()).getWorld()
                .getNearbyEntities(event.getEntity().getLocation(), 2.5, 2.5, 2.5)
                .stream()
                .filter(e -> e instanceof LivingEntity && e != event.getEntity() && e != attacker)
                .forEach(e -> ((LivingEntity) e).damage(1.5, attacker));

        attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 0.5f, 1.5f);
        combatTracker.resetCombo(attacker);
    }

    private static Entity entity(Entity e) { return e; }
}
