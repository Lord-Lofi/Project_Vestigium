package com.vestigium.vestigiummagic.spell;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.model.ParticlePriority;
import com.vestigium.vestigiummagic.VestigiumMagic;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Spell casting — right-click air/block with a spell's cast item triggers the spell.
 *
 * Mana is stored as Player PDC "vestigium:vm_mana" INTEGER (max 100, regenerates 1/10s).
 * Cooldowns are tracked in memory: UUID → Map<spellId, expireMillis>.
 */
public class SpellCaster implements Listener, CommandExecutor {

    public static final NamespacedKey MANA_KEY  = new NamespacedKey("vestigium", "vm_mana");
    public static final int MAX_MANA            = 100;
    private static final long MANA_REGEN_TICKS  = 200L; // 10s
    private static final int  MANA_REGEN_AMOUNT = 5;

    private final VestigiumMagic plugin;
    private final SpellRegistry spellRegistry;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    public SpellCaster(VestigiumMagic plugin, SpellRegistry spellRegistry) {
        this.plugin = plugin;
        this.spellRegistry = spellRegistry;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Mana regen
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                plugin.getServer().getOnlinePlayers().forEach(p -> {
                    int mana = getMana(p);
                    if (mana < MAX_MANA) setMana(p, Math.min(MAX_MANA, mana + MANA_REGEN_AMOUNT));
                });
            }
        }.runTaskTimer(plugin, MANA_REGEN_TICKS, MANA_REGEN_TICKS);

        plugin.getLogger().info("[SpellCaster] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Casting trigger
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getItem() == null) return;

        Player player = event.getPlayer();
        String matName = event.getItem().getType().name();

        spellRegistry.getByItemMaterial(matName).ifPresent(spell -> {
            event.setCancelled(true);
            tryCast(player, spell);
        });
    }

    public void tryCast(Player player, SpellDefinition spell) {
        int omen = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
        if (omen < spell.omenRequired()) {
            player.sendMessage("§8The omen is not strong enough to cast " + spell.name() + ".");
            return;
        }

        if (isOnCooldown(player, spell.id())) {
            long remaining = (getCooldownExpiry(player, spell.id()) - System.currentTimeMillis()) / 1000;
            player.sendMessage("§7" + spell.name() + " is on cooldown (" + remaining + "s).");
            return;
        }

        int mana = getMana(player);
        if (mana < spell.manaCost()) {
            player.sendMessage("§9Not enough mana. Need §b" + spell.manaCost() + "§9, have §b" + mana + "§9.");
            return;
        }

        setMana(player, mana - spell.manaCost());
        setCooldown(player, spell.id(), spell.cooldownMs());
        executeSpell(player, spell);

        player.sendMessage("§b" + spell.name() + " §7— mana: §b" + getMana(player) + "§7/§b" + MAX_MANA);
    }

    // -------------------------------------------------------------------------
    // Effect execution
    // -------------------------------------------------------------------------

    private void executeSpell(Player player, SpellDefinition spell) {
        switch (spell.effectType()) {
            case BOLT   -> castBolt(player, spell);
            case BURST  -> castBurst(player, spell);
            case WARD   -> castWard(player, spell);
            case PULL   -> castPull(player, spell);
            case BLINK  -> castBlink(player, spell);
            case REVEAL -> castReveal(player, spell);
        }
    }

    private void castBolt(Player player, SpellDefinition spell) {
        Vector dir = player.getLocation().getDirection().normalize();
        Location loc = player.getEyeLocation();
        for (int i = 0; i < (int)(spell.range() * 2); i++) {
            loc = loc.clone().add(dir.clone().multiply(0.5));
            VestigiumLib.getParticleManager().queueParticle(loc, Particle.SCULK_SOUL, null, ParticlePriority.GAMEPLAY);
            for (Entity e : loc.getWorld().getNearbyEntities(loc, 0.8, 0.8, 0.8)) {
                if (e == player || !(e instanceof LivingEntity le)) continue;
                le.damage(spell.power() * 4, player);
                player.getWorld().playSound(loc, Sound.BLOCK_SCULK_SENSOR_CLICKING, 1f, 1.5f);
                return;
            }
            if (loc.getBlock().getType().isSolid()) break;
        }
    }

    private void castBurst(Player player, SpellDefinition spell) {
        player.getWorld().getNearbyEntities(player.getLocation(), spell.range(), spell.range(), spell.range())
                .stream()
                .filter(e -> e instanceof LivingEntity && e != player)
                .forEach(e -> {
                    ((LivingEntity) e).damage(spell.power() * 3, player);
                    VestigiumLib.getParticleManager().queueParticle(
                            e.getLocation(), Particle.EXPLOSION, null, ParticlePriority.GAMEPLAY);
                });
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.8f);
    }

    private void castWard(Player player, SpellDefinition spell) {
        int ticks = (int)(spell.power() * 100);
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, ticks, 0));
        VestigiumLib.getParticleManager().queueParticle(
                player.getLocation(), Particle.END_ROD, null, ParticlePriority.GAMEPLAY);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
    }

    private void castPull(Player player, SpellDefinition spell) {
        player.getWorld().getNearbyEntities(player.getLocation(), spell.range(), spell.range(), spell.range())
                .stream()
                .filter(e -> e instanceof LivingEntity && e != player)
                .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(player.getLocation())))
                .ifPresent(target -> {
                    Vector toward = player.getLocation().toVector()
                            .subtract(target.getLocation().toVector()).normalize().multiply(spell.power());
                    target.setVelocity(toward.setY(0.5));
                    VestigiumLib.getParticleManager().queueParticle(
                            target.getLocation(), Particle.DRAGON_BREATH, null, ParticlePriority.GAMEPLAY);
                });
    }

    private void castBlink(Player player, SpellDefinition spell) {
        Vector dir = player.getLocation().getDirection().normalize();
        Location dest = player.getLocation().clone();
        for (int i = 0; i < (int)(spell.range() * 2); i++) {
            Location next = dest.clone().add(dir.clone().multiply(0.5));
            if (next.getBlock().getType().isSolid()) break;
            dest = next;
        }
        dest.setYaw(player.getLocation().getYaw());
        dest.setPitch(player.getLocation().getPitch());
        VestigiumLib.getParticleManager().queueParticle(
                player.getLocation(), Particle.PORTAL, null, ParticlePriority.GAMEPLAY);
        player.teleport(dest);
        VestigiumLib.getParticleManager().queueParticle(dest, Particle.PORTAL, null, ParticlePriority.GAMEPLAY);
        player.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1f);
    }

    private void castReveal(Player player, SpellDefinition spell) {
        Location eye = player.getEyeLocation();
        player.getWorld().getNearbyEntities(eye, spell.range(), spell.range(), spell.range())
                .stream()
                .filter(e -> e instanceof LivingEntity && e != player)
                .forEach(e -> {
                    ((LivingEntity) e).setGlowing(true);
                    plugin.getServer().getScheduler().runTaskLater(plugin,
                            () -> e.setGlowing(false), 200L);
                    VestigiumLib.getParticleManager().queueParticle(
                            e.getLocation().add(0,1,0), Particle.END_ROD, null, ParticlePriority.ATMOSPHERIC);
                });
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 2f);
    }

    // -------------------------------------------------------------------------
    // Mana / cooldown helpers
    // -------------------------------------------------------------------------

    public int getMana(Player player) {
        return player.getPersistentDataContainer()
                .getOrDefault(MANA_KEY, PersistentDataType.INTEGER, MAX_MANA);
    }

    public void setMana(Player player, int mana) {
        player.getPersistentDataContainer()
                .set(MANA_KEY, PersistentDataType.INTEGER, Math.max(0, Math.min(MAX_MANA, mana)));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cPlayer only.");
            return true;
        }
        int mana = getMana(player);
        player.sendMessage("§9Mana: §b" + mana + "§9/§b" + MAX_MANA);
        return true;
    }

    private boolean isOnCooldown(Player player, String spellId) {
        return System.currentTimeMillis() < getCooldownExpiry(player, spellId);
    }

    private long getCooldownExpiry(Player player, String spellId) {
        return cooldowns.getOrDefault(player.getUniqueId(), Collections.emptyMap())
                .getOrDefault(spellId, 0L);
    }

    private void setCooldown(Player player, String spellId, long durationMs) {
        cooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .put(spellId, System.currentTimeMillis() + durationMs);
    }
}
