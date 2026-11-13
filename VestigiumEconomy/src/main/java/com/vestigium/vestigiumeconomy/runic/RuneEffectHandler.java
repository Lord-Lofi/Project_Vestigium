package com.vestigium.vestigiumeconomy.runic;

import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import org.bukkit.Sound;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles runtime effects for all inscribed runes.
 *
 * Effect summary:
 *   WARDING    — 15% chance to reflect 3 dmg to attacker when player is hit
 *   RESILIENCE — once per 60s: absorb a hit of 6+ final damage entirely
 *   VENOM      — 20% chance to apply Poison I (3s) on weapon hit
 *   DEEP       — 40% raw damage reduction while player is submerged
 *   THUNDER    — on killing blow: Slowness I (3s) to all nearby entities
 *   SWIFTNESS  — on kill: Speed I (4s) to the killer
 *   SIGHT      — Night Vision while health ≤ 10 HP (managed by RuneManager task)
 *   SCULK      — 50% chance to cancel Warden target acquisition
 */
public class RuneEffectHandler implements Listener {

    private final RuneManager      manager;
    private final VestigiumEconomy plugin;

    // Resilience: per-player cooldown (Unix ms)
    private final Map<UUID, Long> resilienceCooldown = new HashMap<>();
    // Warding: prevent recursive reflect damage loops
    private final Set<UUID>       reflecting         = new HashSet<>();

    public RuneEffectHandler(RuneManager manager, VestigiumEconomy plugin) {
        this.manager = manager;
        this.plugin  = plugin;
    }

    // -------------------------------------------------------------------------
    // Damage events — Warding, Venom, Deep, Resilience
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {

        // ── Venom: attacker poisons target on weapon hit ──
        if (event.getDamager() instanceof Player attacker
                && manager.playerHasRune(attacker, "venom")
                && event.getEntity() instanceof LivingEntity target
                && ThreadLocalRandom.current().nextInt(100) < 20) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0));
        }

        // ── Warding: reflect 3 damage to attacker when player is hit ──
        if (event.getEntity() instanceof Player player
                && !reflecting.contains(player.getUniqueId())
                && manager.playerHasRune(player, "warding")
                && ThreadLocalRandom.current().nextInt(100) < 15
                && event.getDamager() instanceof LivingEntity attacker) {
            reflecting.add(player.getUniqueId());
            attacker.damage(3.0, player);
            reflecting.remove(player.getUniqueId());
            player.getWorld().playSound(player.getLocation(),
                    Sound.ITEM_SHIELD_BLOCK, 0.6f, 1.4f);
        }

        // ── Deep: reduce raw damage by 40% while submerged ──
        if (event.getEntity() instanceof Player player
                && player.isInWater()
                && manager.playerHasRune(player, "deep")) {
            event.setDamage(event.getDamage() * 0.6);
        }

        // ── Resilience: absorb a hard hit once per 60 seconds ──
        // Checked after Deep so final damage reflects any Deep reduction.
        if (event.getEntity() instanceof Player player
                && event.getFinalDamage() >= 6.0
                && manager.playerHasRune(player, "resilience")) {
            long now = System.currentTimeMillis();
            if (now > resilienceCooldown.getOrDefault(player.getUniqueId(), 0L)) {
                resilienceCooldown.put(player.getUniqueId(), now + 60_000L);
                event.setDamage(0.0);
                player.sendActionBar("§c§lResilience absorbed the blow!");
                player.getWorld().playSound(player.getLocation(),
                        Sound.ITEM_SHIELD_BREAK, 0.5f, 1.6f);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Kill events — Thunder, Swiftness
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        // ── Thunder: Slowness I in 4-block radius on killing blow ──
        if (manager.playerHasRune(killer, "thunder")) {
            event.getEntity().getLocation().getNearbyLivingEntities(4).forEach(nearby -> {
                if (nearby != killer) {
                    nearby.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0));
                }
            });
        }

        // ── Swiftness: Speed I for 4 seconds on kill ──
        if (manager.playerHasRune(killer, "swiftness")) {
            killer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 0));
        }
    }

    // -------------------------------------------------------------------------
    // Warden targeting — Sculk
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onWardenTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Warden)) return;
        if (!(event.getTarget() instanceof Player player)) return;
        if (manager.playerHasRune(player, "sculk")
                && ThreadLocalRandom.current().nextBoolean()) {
            event.setCancelled(true);
        }
    }
}
