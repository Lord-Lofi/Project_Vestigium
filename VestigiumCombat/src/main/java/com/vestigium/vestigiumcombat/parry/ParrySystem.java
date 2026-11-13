package com.vestigium.vestigiumcombat.parry;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.model.ParticlePriority;
import com.vestigium.vestigiumcombat.VestigiumCombat;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Parry system — raising a shield within PARRY_WINDOW_MS before an incoming hit
 * negates all damage and staggers the attacker (Slowness I + knockback).
 *
 * Window  : 350 ms from first block-raise to the incoming hit
 * Cooldown: 2 000 ms between successful parries (prevents spam)
 *
 * Shield raise is detected via PlayerInteractEvent RIGHT_CLICK while holding a
 * shield in either hand. Any right-click with a shield resets the window.
 */
public class ParrySystem implements Listener {

    public static final long PARRY_WINDOW_MS   = 350L;
    public static final long PARRY_COOLDOWN_MS = 2_000L;

    private final VestigiumCombat plugin;
    private final Map<UUID, Long> blockStartMillis   = new HashMap<>();
    private final Map<UUID, Long> parryCooldownUntil = new HashMap<>();

    public ParrySystem(VestigiumCombat plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[ParrySystem] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Shield raise tracking
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!holdingShield(p)) return;
        blockStartMillis.put(p.getUniqueId(), System.currentTimeMillis());
    }

    // -------------------------------------------------------------------------
    // Parry check — HIGH priority so we cancel before damage is applied
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!victim.isBlocking()) return;

        long now = System.currentTimeMillis();

        long cooldownUntil = parryCooldownUntil.getOrDefault(victim.getUniqueId(), 0L);
        if (now < cooldownUntil) return;

        long blockStart = blockStartMillis.getOrDefault(victim.getUniqueId(), 0L);
        if (blockStart == 0 || now - blockStart > PARRY_WINDOW_MS) return;

        // Successful parry
        event.setDamage(0.0);
        event.setCancelled(true);
        parryCooldownUntil.put(victim.getUniqueId(), now + PARRY_COOLDOWN_MS);
        blockStartMillis.remove(victim.getUniqueId());

        victim.sendActionBar("§a⚔ PARRY!");
        victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.5f);
        VestigiumLib.getParticleManager().queueParticle(
                victim.getLocation().add(0, 1, 0), Particle.CRIT, null, ParticlePriority.GAMEPLAY);

        staggerAttacker(event.getDamager(), victim);
    }

    // -------------------------------------------------------------------------

    private void staggerAttacker(Entity attacker, Player victim) {
        if (!(attacker instanceof LivingEntity le)) return;

        le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, false, false));

        var knockback = victim.getLocation().getDirection().normalize().multiply(0.9).setY(0.25);
        le.setVelocity(knockback);

        attacker.getWorld().playSound(
                attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 0.6f);

        if (attacker instanceof Player pa) {
            pa.sendActionBar("§c⚔ Parried!");
        }
    }

    private boolean holdingShield(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        ItemStack off  = p.getInventory().getItemInOffHand();
        return main.getType() == Material.SHIELD || off.getType() == Material.SHIELD;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        blockStartMillis.remove(id);
        parryCooldownUntil.remove(id);
    }
}
