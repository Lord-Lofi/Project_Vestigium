package com.vestigium.vestigiummobs.minion;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiummobs.VestigiumMobs;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Minion system — allows boss/named mobs to spawn bound minions.
 *
 * A minion is a Zombie or Skeleton tagged with:
 *   vestigium:minion_owner → String (owner entity UUID)
 *   vestigium:minion_tier  → Integer (1-3, affects stats)
 *
 * When the owner dies all bound minions are also killed.
 * Max minions per owner: tier 1 = 3, tier 2 = 6, tier 3 = 12.
 *
 * Active minion ownership is tracked in-memory by owner UUID → Set<Entity UUID>.
 * Persisted to plugins/VestigiumMobs/minions.yml on shutdown (clears stale on load).
 */
public class MinionSystem implements Listener {

    private static final NamespacedKey MINION_OWNER_KEY =
            new NamespacedKey("vestigium", "minion_owner");
    private static final NamespacedKey MINION_TIER_KEY =
            new NamespacedKey("vestigium", "minion_tier");

    private static final int[] MAX_MINIONS = {0, 3, 6, 12};

    private final VestigiumMobs plugin;
    private final Map<UUID, Set<UUID>> ownerMinions = new HashMap<>();
    private File persistFile;

    public MinionSystem(VestigiumMobs plugin) {
        this.plugin = plugin;
    }

    public void init() {
        persistFile = new File(plugin.getDataFolder(), "minions.yml");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[MinionSystem] Initialized.");
    }

    public void saveAll() {
        YamlConfiguration cfg = new YamlConfiguration();
        ownerMinions.forEach((owner, minions) ->
                cfg.set(owner.toString(), new ArrayList<>(minions.stream()
                        .map(UUID::toString).toList())));
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(persistFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[MinionSystem] Save failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Spawns a minion bound to the given owner entity at the specified location.
     * Returns the spawned entity, or null if the owner has reached the minion cap.
     */
    public Mob spawnMinion(LivingEntity owner, Location loc, int tier) {
        if (tier < 1 || tier > 3) tier = 1;
        UUID ownerUUID = owner.getUniqueId();

        Set<UUID> existing = ownerMinions.computeIfAbsent(ownerUUID, k -> new HashSet<>());
        // Prune dead
        existing.removeIf(uid -> {
            var e = plugin.getServer().getEntity(uid);
            return e == null || !e.isValid();
        });

        if (existing.size() >= MAX_MINIONS[tier]) return null;

        EntityType type = tier == 3 ? EntityType.SKELETON : EntityType.ZOMBIE;
        Mob minion = (Mob) loc.getWorld().spawnEntity(loc, type);

        minion.getPersistentDataContainer()
                .set(MINION_OWNER_KEY, PersistentDataType.STRING, ownerUUID.toString());
        minion.getPersistentDataContainer()
                .set(MINION_TIER_KEY, PersistentDataType.INTEGER, tier);

        applyTierStats(minion, tier);
        existing.add(minion.getUniqueId());

        // Register omen activity — minion spawns are world events
        VestigiumLib.getOmenAPI().markPlayerActivity();
        return minion;
    }

    /** Returns all live minions owned by the given entity. */
    public List<LivingEntity> getMinions(LivingEntity owner) {
        Set<UUID> uuids = ownerMinions.getOrDefault(owner.getUniqueId(), Collections.emptySet());
        List<LivingEntity> result = new ArrayList<>();
        for (UUID uid : uuids) {
            var e = plugin.getServer().getEntity(uid);
            if (e instanceof LivingEntity le && le.isValid()) result.add(le);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Owner death — kill bound minions
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerDeath(EntityDeathEvent event) {
        UUID ownerUUID = event.getEntity().getUniqueId();
        Set<UUID> minions = ownerMinions.remove(ownerUUID);
        if (minions == null) return;

        for (UUID uid : minions) {
            var e = plugin.getServer().getEntity(uid);
            if (e instanceof LivingEntity le && le.isValid()) {
                le.setHealth(0);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Minion death — remove from tracking
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMinionDeath(EntityDeathEvent event) {
        String ownerStr = event.getEntity().getPersistentDataContainer()
                .get(MINION_OWNER_KEY, PersistentDataType.STRING);
        if (ownerStr == null) return;

        try {
            UUID ownerUUID = UUID.fromString(ownerStr);
            Set<UUID> set = ownerMinions.get(ownerUUID);
            if (set != null) set.remove(event.getEntity().getUniqueId());
        } catch (IllegalArgumentException ignored) {}
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void applyTierStats(Mob minion, int tier) {
        double hpMult   = 1.0 + (tier - 1) * 0.5;  // 1x / 1.5x / 2x
        double dmgMult  = 1.0 + (tier - 1) * 0.25;

        var hp = minion.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(hp.getBaseValue() * hpMult);
            minion.setHealth(hp.getValue());
        }
        var atk = minion.getAttribute(Attribute.ATTACK_DAMAGE);
        if (atk != null) atk.setBaseValue(atk.getBaseValue() * dmgMult);

        minion.setCustomName("§cMinion §7(T" + tier + ")");
        minion.setCustomNameVisible(true);
    }
}
