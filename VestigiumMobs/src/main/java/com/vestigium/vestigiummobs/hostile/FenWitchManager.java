package com.vestigium.vestigiummobs.hostile;

import com.vestigium.vestigiummobs.VestigiumMobs;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Fen Witch — 2% of Witches in swamp biomes.
 * Phase 1 (>50% HP): inflicts Wither I + Slowness II on hit.
 * Phase 2 (<50% HP): speed boost + summons 1–2 Vex.
 */
public class FenWitchManager implements Listener {

    private static final NamespacedKey VARIANT_KEY =
            new NamespacedKey("vestigium", "fen_witch_variant");
    private static final NamespacedKey PHASE_KEY =
            new NamespacedKey("vestigium", "fen_witch_phase");

    private final VestigiumMobs plugin;

    public FenWitchManager(VestigiumMobs plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[FenWitchManager] Initialized.");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) return;

        Entity entity = event.getEntity();
        if (entity.getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL) return;
        if (!(entity instanceof Witch)) return;

        String biome = entity.getLocation().getBlock().getBiome().getKey().getKey();
        if (!biome.contains("swamp")) return;
        if (ThreadLocalRandom.current().nextInt(100) >= 2) return;

        Mob mob = (Mob) entity;
        mob.getPersistentDataContainer().set(VARIANT_KEY, PersistentDataType.STRING, "fen_witch");
        mob.getPersistentDataContainer().set(PHASE_KEY, PersistentDataType.INTEGER, 1);
        mob.setCustomName("§aFen Witch");
        mob.setCustomNameVisible(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        // Phase 1: curse the player on attack
        if (event.getDamager() instanceof Mob attacker && event.getEntity() instanceof Player victim) {
            String variant = attacker.getPersistentDataContainer()
                    .get(VARIANT_KEY, PersistentDataType.STRING);
            if ("fen_witch".equals(variant)) {
                victim.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 0));
                victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1));
            }
        }

        // Phase 2 check: FenWitch took damage — evaluate post-damage HP next tick
        if (event.getEntity() instanceof Witch witch && event.getDamager() instanceof Player) {
            String variant = witch.getPersistentDataContainer()
                    .get(VARIANT_KEY, PersistentDataType.STRING);
            if (!"fen_witch".equals(variant)) return;

            Integer phase = witch.getPersistentDataContainer()
                    .get(PHASE_KEY, PersistentDataType.INTEGER);
            if (phase == null || phase != 1) return;

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!witch.isValid()) return;
                double maxHp = witch.getAttribute(Attribute.MAX_HEALTH).getValue();
                if (witch.getHealth() < maxHp * 0.5) triggerPhaseTwo(witch);
            });
        }
    }

    private void triggerPhaseTwo(Witch witch) {
        witch.getPersistentDataContainer().set(PHASE_KEY, PersistentDataType.INTEGER, 2);
        witch.setCustomName("§cFen Witch §4[Enraged]");

        org.bukkit.attribute.AttributeInstance speed = witch.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(speed.getBaseValue() * 1.4);

        witch.getWorld().playSound(witch.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 1.0f, 0.5f);

        int count = ThreadLocalRandom.current().nextInt(2) + 1;
        for (int i = 0; i < count; i++) {
            Vex vex = (Vex) witch.getWorld().spawnEntity(witch.getLocation(), EntityType.VEX);
            vex.setCustomName("§aBound Vex");
            vex.setCustomNameVisible(true);
        }
    }
}
