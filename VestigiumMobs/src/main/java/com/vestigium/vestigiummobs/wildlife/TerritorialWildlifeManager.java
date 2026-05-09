package com.vestigium.vestigiummobs.wildlife;

import com.vestigium.vestigiummobs.VestigiumMobs;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Territorial Wildlife system.
 *
 * Five biome-specific apex predators with a 3-tier escalation response:
 *   Tier 1 (Warn)   — predator looks at player, warning sound + particle puff,
 *                     actionbar "watches you from its territory." (0–10 s)
 *   Tier 2 (Patrol) — predator begins slow pursuit (speed 0.6), actionbar
 *                     "begins to circle you." (10–20 s)
 *   Tier 3 (Pursue) — setTarget(player), aggressive sound, actionbar
 *                     "is pursuing you!" (20 s+, or immediately if within 8 blocks)
 *
 * Apex predators are tagged at natural spawn with a 2% chance in their home biomes.
 * On death: killer receives the predator's unique drop item and a permanent territory
 * marker appended to vestigium:apex_territories STRING PDC. First kill sets
 * vestigium:apex_first_kill BYTE.
 *
 * Apex types and home biomes:
 *   ARCTIC_SOVEREIGN   POLAR_BEAR  frozen/snowy biomes
 *   PACK_SOVEREIGN     WOLF        taiga biomes
 *   JUNGLE_STALKER     OCELOT      jungle biomes
 *   SWAMP_BROODMOTHER  SPIDER      swamp + dark_forest
 *   MOUNTAIN_SOVEREIGN GOAT        mountain / windswept biomes
 */
public class TerritorialWildlifeManager implements Listener {

    private static final NamespacedKey APEX_TAG_KEY   = new NamespacedKey("vestigium", "apex_predator");
    private static final NamespacedKey TERRITORY_KEY  = new NamespacedKey("vestigium", "apex_territories");
    private static final NamespacedKey APEX_FIRST_KEY = new NamespacedKey("vestigium", "apex_first_kill");

    private static final int  SPAWN_CHANCE       = 20;   // out of 1000 → 2%
    private static final int  OUTER_RADIUS       = 24;   // enter territory
    private static final int  INNER_RADIUS       =  8;   // immediate pursue
    private static final long WARN_DURATION_MS   = 10_000L;
    private static final long PATROL_DURATION_MS = 10_000L;

    // -------------------------------------------------------------------------
    // Apex predator definitions
    // -------------------------------------------------------------------------

    enum ApexType {
        ARCTIC_SOVEREIGN(EntityType.POLAR_BEAR, "§bArctic Sovereign",
            Set.of("SNOWY_PLAINS", "SNOWY_TAIGA", "FROZEN_RIVER",
                   "FROZEN_OCEAN", "DEEP_FROZEN_OCEAN", "ICE_SPIKES",
                   "FROZEN_PEAKS", "JAGGED_PEAKS", "GROVE"),
            Material.PACKED_ICE, "Arctic Pelt", 40.0,
            Sound.ENTITY_POLAR_BEAR_WARNING, Sound.ENTITY_POLAR_BEAR_HURT),

        PACK_SOVEREIGN(EntityType.WOLF, "§aPack Sovereign",
            Set.of("TAIGA", "OLD_GROWTH_SPRUCE_TAIGA", "OLD_GROWTH_PINE_TAIGA"),
            Material.BONE, "Wolf Fang", 30.0,
            Sound.ENTITY_WOLF_GROWL, Sound.ENTITY_WOLF_HURT),

        JUNGLE_STALKER(EntityType.OCELOT, "§2Jungle Stalker",
            Set.of("JUNGLE", "SPARSE_JUNGLE", "BAMBOO_JUNGLE"),
            Material.FLINT, "Ocelot Claw", 24.0,
            Sound.ENTITY_CAT_HISS, Sound.ENTITY_OCELOT_HURT),

        SWAMP_BROODMOTHER(EntityType.SPIDER, "§8Swamp Broodmother",
            Set.of("SWAMP", "MANGROVE_SWAMP", "DARK_FOREST"),
            Material.FERMENTED_SPIDER_EYE, "Brood Sac", 35.0,
            Sound.ENTITY_SPIDER_AMBIENT, Sound.ENTITY_SPIDER_HURT),

        MOUNTAIN_SOVEREIGN(EntityType.GOAT, "§7Mountain Sovereign",
            Set.of("STONY_PEAKS", "WINDSWEPT_HILLS",
                   "WINDSWEPT_GRAVELLY_HILLS", "WINDSWEPT_FOREST"),
            Material.QUARTZ, "Summit Crystal", 32.0,
            Sound.ENTITY_GOAT_AMBIENT, Sound.ENTITY_GOAT_HURT);

        final EntityType entityType;
        final String     displayName;
        final Set<String> biomes;
        final Material   dropMaterial;
        final String     dropName;
        final double     maxHp;
        final Sound      warnSound;
        final Sound      pursueSound;

        ApexType(EntityType entityType, String displayName, Set<String> biomes,
                 Material dropMaterial, String dropName, double maxHp,
                 Sound warnSound, Sound pursueSound) {
            this.entityType   = entityType;
            this.displayName  = displayName;
            this.biomes       = biomes;
            this.dropMaterial = dropMaterial;
            this.dropName     = dropName;
            this.maxHp        = maxHp;
            this.warnSound    = warnSound;
            this.pursueSound  = pursueSound;
        }

        static ApexType fromBiome(String biomeName) {
            for (ApexType t : values()) {
                if (t.biomes.contains(biomeName)) return t;
            }
            return null;
        }

        static ApexType fromEntityType(EntityType type) {
            for (ApexType t : values()) {
                if (t.entityType == type) return t;
            }
            return null;
        }

        String strippedName() {
            return displayName.replaceAll("§.", "");
        }
    }

    // -------------------------------------------------------------------------
    // Per-player threat tracking
    // -------------------------------------------------------------------------

    record ThreatState(UUID predatorId, long tierStartMs, int tier) {}

    private final VestigiumMobs         plugin;
    private final Map<UUID, ThreatState> playerThreats = new HashMap<>();
    private BukkitRunnable tickTask;

    public TerritorialWildlifeManager(VestigiumMobs plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startTickTask();
        plugin.getLogger().info("[TerritorialWildlifeManager] Initialized.");
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) return;
        if (event.getEntity().getWorld().getEnvironment() != World.Environment.NORMAL) return;

        ApexType candidate = ApexType.fromEntityType(event.getEntityType());
        if (candidate == null) return;

        String biome = event.getEntity().getLocation().getBlock().getBiome().name();
        if (!candidate.biomes.contains(biome)) return;

        if (ThreadLocalRandom.current().nextInt(1000) >= SPAWN_CHANCE) return;

        tagAsApex((Mob) event.getEntity(), candidate);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        String typeName = mob.getPersistentDataContainer()
                .get(APEX_TAG_KEY, PersistentDataType.STRING);
        if (typeName == null) return;

        ApexType type;
        try { type = ApexType.valueOf(typeName); }
        catch (IllegalArgumentException e) { return; }

        Player killer = mob.getKiller();
        if (killer != null) {
            event.getDrops().removeIf(i -> i.getType() == type.dropMaterial);
            event.getDrops().add(buildDrop(type));
            recordTerritoryMarker(killer, type, mob.getLocation());

            if (!killer.getPersistentDataContainer().has(APEX_FIRST_KEY, PersistentDataType.BYTE)) {
                killer.getPersistentDataContainer()
                        .set(APEX_FIRST_KEY, PersistentDataType.BYTE, (byte) 1);
                killer.sendMessage("§8[§6Apex Predator§8] §7You have claimed the territory of a sovereign.");
            }
        }

        UUID dead = mob.getUniqueId();
        playerThreats.entrySet().removeIf(e -> e.getValue().predatorId().equals(dead));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ThreatState state = playerThreats.remove(event.getPlayer().getUniqueId());
        if (state != null) resetPredator(state.predatorId());
    }

    // -------------------------------------------------------------------------
    // Tick task — every 20 ticks
    // -------------------------------------------------------------------------

    private void startTickTask() {
        tickTask = new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (p.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
                    tickPlayer(p, now);
                }
            }
        };
        tickTask.runTaskTimer(plugin, 20L, 20L);
    }

    private void tickPlayer(Player player, long now) {
        ThreatState state = playerThreats.get(player.getUniqueId());
        Mob nearest = findNearestApex(player, OUTER_RADIUS);

        if (nearest == null) {
            if (state != null) {
                resetPredator(state.predatorId());
                playerThreats.remove(player.getUniqueId());
            }
            return;
        }

        // Switched predators — reset the old one
        if (state != null && !nearest.getUniqueId().equals(state.predatorId())) {
            resetPredator(state.predatorId());
            state = null;
        }

        // Immediate pursue if player enters the inner radius
        double distSq = nearest.getLocation().distanceSquared(player.getLocation());
        if (distSq <= (long) INNER_RADIUS * INNER_RADIUS
                && (state == null || state.tier() < 3)) {
            state = new ThreatState(nearest.getUniqueId(), now, 3);
            playerThreats.put(player.getUniqueId(), state);
            applyTier3(player, nearest);
            return;
        }

        // First encounter — warn tier
        if (state == null) {
            state = new ThreatState(nearest.getUniqueId(), now, 1);
            playerThreats.put(player.getUniqueId(), state);
            applyTier1(player, nearest);
            return;
        }

        long elapsed = now - state.tierStartMs();

        if (state.tier() == 1 && elapsed >= WARN_DURATION_MS) {
            ThreatState next = new ThreatState(nearest.getUniqueId(), now, 2);
            playerThreats.put(player.getUniqueId(), next);
            applyTier2(player, nearest);
        } else if (state.tier() == 2) {
            if (elapsed >= PATROL_DURATION_MS) {
                ThreatState next = new ThreatState(nearest.getUniqueId(), now, 3);
                playerThreats.put(player.getUniqueId(), next);
                applyTier3(player, nearest);
            } else {
                nearest.getPathfinder().moveTo(player, 0.6);
            }
        }
        // Tier 3: vanilla AI handles chase after setTarget()
    }

    // -------------------------------------------------------------------------
    // Tier effects
    // -------------------------------------------------------------------------

    private void applyTier1(Player player, Mob predator) {
        ApexType type = getApexType(predator);
        predator.getWorld().playSound(predator.getLocation(),
                type != null ? type.warnSound : Sound.ENTITY_GENERIC_HURT, 1.0f, 0.9f);
        predator.getWorld().spawnParticle(Particle.SMOKE,
                predator.getLocation().add(0, 1, 0), 6, 0.3, 0.2, 0.3, 0.01);
        predator.lookAt(player);
        String name = predator.getCustomName() != null ? predator.getCustomName() : "§7A predator";
        player.sendActionBar(Component.text(name + " §7watches you from its territory."));
    }

    private void applyTier2(Player player, Mob predator) {
        ApexType type = getApexType(predator);
        predator.getWorld().playSound(predator.getLocation(),
                type != null ? type.warnSound : Sound.ENTITY_GENERIC_HURT, 1.0f, 0.7f);
        String name = predator.getCustomName() != null ? predator.getCustomName() : "§7A predator";
        player.sendActionBar(Component.text(name + " §6begins to circle you."));
        predator.getPathfinder().moveTo(player, 0.6);
    }

    private void applyTier3(Player player, Mob predator) {
        ApexType type = getApexType(predator);
        predator.getWorld().playSound(predator.getLocation(),
                type != null ? type.pursueSound : Sound.ENTITY_GENERIC_HURT, 1.2f, 0.7f);
        predator.setTarget(player);
        String name = predator.getCustomName() != null ? predator.getCustomName() : "§7A predator";
        player.sendActionBar(Component.text("§c" + name + " §7is pursuing you!"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void tagAsApex(Mob mob, ApexType type) {
        mob.getPersistentDataContainer().set(APEX_TAG_KEY, PersistentDataType.STRING, type.name());
        mob.setPersistent(true);
        mob.setRemoveWhenFarAway(false);
        var hp = mob.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(type.maxHp);
            mob.setHealth(type.maxHp);
        }
        mob.setCustomName(type.displayName);
        mob.setCustomNameVisible(true);
    }

    private Mob findNearestApex(Player player, int radius) {
        Mob nearest = null;
        double best = (double) radius * radius;
        for (Entity e : player.getWorld().getNearbyEntities(
                player.getLocation(), radius, radius, radius)) {
            if (!(e instanceof Mob mob)) continue;
            if (!mob.isValid()) continue;
            if (!mob.getPersistentDataContainer().has(APEX_TAG_KEY, PersistentDataType.STRING)) continue;
            double d = mob.getLocation().distanceSquared(player.getLocation());
            if (d < best) { best = d; nearest = mob; }
        }
        return nearest;
    }

    private void resetPredator(UUID id) {
        Entity e = plugin.getServer().getEntity(id);
        if (e instanceof Mob mob && mob.isValid()) mob.setTarget(null);
    }

    private ApexType getApexType(Mob mob) {
        String name = mob.getPersistentDataContainer().get(APEX_TAG_KEY, PersistentDataType.STRING);
        if (name == null) return null;
        try { return ApexType.valueOf(name); }
        catch (IllegalArgumentException e) { return null; }
    }

    private ItemStack buildDrop(ApexType type) {
        ItemStack item = new ItemStack(type.dropMaterial);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§6" + type.dropName);
        meta.setLore(List.of(
                "§7Claimed from a defeated " + type.strippedName() + ".",
                "§8A mark of territorial dominance."));
        item.setItemMeta(meta);
        return item;
    }

    private void recordTerritoryMarker(Player player, ApexType type, Location loc) {
        String existing = player.getPersistentDataContainer()
                .getOrDefault(TERRITORY_KEY, PersistentDataType.STRING, "");
        String marker = type.name() + "~" + loc.getBlockX() + "~" + loc.getBlockZ();
        String updated = existing.isBlank() ? marker : existing + ";" + marker;
        player.getPersistentDataContainer().set(TERRITORY_KEY, PersistentDataType.STRING, updated);
        player.sendMessage("§7Territory marked: §e" + type.strippedName()
                + " §7at §e" + loc.getBlockX() + "§7, §e" + loc.getBlockZ() + "§7.");
    }
}
