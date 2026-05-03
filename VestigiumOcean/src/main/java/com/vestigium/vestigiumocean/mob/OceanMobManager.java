package com.vestigium.vestigiumocean.mob;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumocean.VestigiumOcean;
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

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Custom ocean mob variants applied on natural spawn.
 *
 * Variants:
 *   abyssal_guardian    — 1% guardians in ocean biomes; +75% HP, Mining Fatigue II on hit
 *   brine_drowned       — 2% drowned in ocean biomes; Slowness II on hit, drops prismarine shard
 *   lurking_elder       — 5% elder guardians; +100% HP, Blindness on spawn to nearby players
 *   phantom_squid       — 2% squids; Blindness on the player who hits it, drops glowstone dust
 *   dolphin_pod_leader  — 10% dolphins; on taking damage calls nearby dolphins and grants them Speed II
 *
 * Variant stored as "ocean_mob_variant" PDC STRING on entity.
 */
public class OceanMobManager implements Listener {

    private static final NamespacedKey VARIANT_KEY =
            new NamespacedKey("vestigium", "ocean_mob_variant");

    private static final Set<String> OCEAN_BIOMES = Set.of(
            "ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean",
            "frozen_ocean", "deep_frozen_ocean", "lukewarm_ocean",
            "deep_lukewarm_ocean", "warm_ocean");

    private final VestigiumOcean plugin;

    public OceanMobManager(VestigiumOcean plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[OceanMobManager] Initialized.");
    }

    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) return;

        Entity entity = event.getEntity();
        if (!isOceanContext(entity)) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (entity instanceof Guardian && !(entity instanceof ElderGuardian)
                && rng.nextInt(100) < 1) {
            applyVariant((Mob) entity, "abyssal_guardian");
        } else if (entity instanceof Drowned && rng.nextInt(100) < 2) {
            applyVariant((Mob) entity, "brine_drowned");
        } else if (entity instanceof ElderGuardian && rng.nextInt(100) < 5) {
            applyVariant((Mob) entity, "lurking_elder");
        } else if (entity instanceof Squid && !(entity instanceof GlowSquid)
                && rng.nextInt(100) < 2) {
            applyVariant((Mob) entity, "phantom_squid");
        } else if (entity instanceof Dolphin && rng.nextInt(10) < 1) {
            applyVariant((Mob) entity, "dolphin_pod_leader");
        }
    }

    private void applyVariant(Mob mob, String variant) {
        mob.getPersistentDataContainer().set(VARIANT_KEY, PersistentDataType.STRING, variant);

        switch (variant) {
            case "abyssal_guardian" -> {
                AttributeInstance hp = mob.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null) hp.setBaseValue(hp.getBaseValue() * 1.75);
                mob.setHealth(mob.getAttribute(Attribute.MAX_HEALTH).getValue());
                mob.setCustomName("§9Abyssal Guardian");
                mob.setCustomNameVisible(true);
            }
            case "brine_drowned" -> {
                mob.setCustomName("§3Brine Drowned");
                mob.setCustomNameVisible(true);
            }
            case "lurking_elder" -> {
                AttributeInstance hp = mob.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null) hp.setBaseValue(hp.getBaseValue() * 2.0);
                mob.setHealth(mob.getAttribute(Attribute.MAX_HEALTH).getValue());
                mob.setCustomName("§1Lurking Elder");
                mob.setCustomNameVisible(true);
                mob.getWorld().getPlayers().stream()
                        .filter(p -> p.getLocation().distanceSquared(mob.getLocation()) < 400)
                        .forEach(p -> {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0));
                            p.sendMessage("§9Something vast opens its eye beneath you.");
                        });
            }
            case "phantom_squid" -> {
                mob.setCustomName("§5Phantom Squid");
                mob.setCustomNameVisible(true);
            }
            case "dolphin_pod_leader" -> {
                mob.setCustomName("§bPod Leader");
                mob.setCustomNameVisible(true);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Mob-as-attacker: existing variants that damage players

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Mob attacker)) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        String variant = attacker.getPersistentDataContainer()
                .get(VARIANT_KEY, PersistentDataType.STRING);
        if (variant == null) return;

        switch (variant) {
            case "abyssal_guardian" ->
                victim.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 100, 1));
            case "brine_drowned" ->
                victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1));
        }
    }

    // Mob-as-victim: phantom_squid and dolphin_pod_leader react to being struck

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMobDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        String variant = mob.getPersistentDataContainer()
                .get(VARIANT_KEY, PersistentDataType.STRING);
        if (variant == null) return;

        switch (variant) {
            case "phantom_squid" -> {
                attacker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
                attacker.sendMessage("§5The ink burns your eyes.");
            }
            case "dolphin_pod_leader" -> {
                mob.getWorld().getNearbyEntities(mob.getLocation(), 15, 15, 15).stream()
                        .filter(e -> e instanceof Dolphin && e != mob)
                        .forEach(e -> {
                            ((LivingEntity) e).addPotionEffect(
                                    new PotionEffect(PotionEffectType.SPEED, 200, 1));
                        });
                mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_DOLPHIN_HURT, 1.0f, 1.2f);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        String variant = mob.getPersistentDataContainer()
                .get(VARIANT_KEY, PersistentDataType.STRING);
        if (variant == null) return;

        switch (variant) {
            case "abyssal_guardian" ->
                event.getDrops().add(new ItemStack(Material.PRISMARINE_CRYSTALS, 4));
            case "brine_drowned" ->
                event.getDrops().add(new ItemStack(Material.PRISMARINE_SHARD, 2));
            case "lurking_elder" -> {
                event.getDrops().add(new ItemStack(Material.HEART_OF_THE_SEA));
                VestigiumLib.getOmenAPI().addOmen(8);
            }
            case "phantom_squid" ->
                event.getDrops().add(new ItemStack(Material.GLOWSTONE_DUST, 2));
        }
    }

    // -------------------------------------------------------------------------

    private static boolean isOceanContext(Entity entity) {
        org.bukkit.Location loc = entity.getLocation();
        if (isOceanBiome(loc.getBlock().getBiome().getKey().getKey())) return true;
        if (loc.getBlockY() < 62) {
            String surfaceBiome = loc.getWorld()
                    .getBiome(loc.getBlockX(), 62, loc.getBlockZ()).getKey().getKey();
            return isOceanBiome(surfaceBiome);
        }
        return false;
    }

    private static boolean isOceanBiome(String biomeKey) {
        return OCEAN_BIOMES.contains(biomeKey);
    }
}
