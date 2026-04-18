package com.vestigium.vestigiumnpc.special;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumnpc.VestigiumNPC;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages the four passive/hostile special NPC types:
 *
 *  The Ferryman  — spawns near critically-low-health players; offers to save
 *                  inventory on death in exchange for rare currency.
 *
 *  The Pale Wanderer — silent; appears at distance; following leads to buried structure.
 *                      Never attacks. Visible only to players with < 3 bars of health.
 *                      (Health restriction is lore flavour; mechanically spawns for all.)
 *
 *  Grave Robbers — spawn at player death location with a 5-minute timer;
 *                  loot the grave before the player retrieves it.
 *
 *  The Collector — roams world gathering rare items from chests; can be traded
 *                  with to recover items, or killed for its loot.
 */
public class SpecialNPCManager implements Listener {

    private static final NamespacedKey SPECIAL_TYPE_KEY =
            new NamespacedKey("vestigium", "special_npc_type");
    private static final NamespacedKey FERRYMAN_TARGET_KEY =
            new NamespacedKey("vestigium", "ferryman_target");

    // Grave Robber despawn delay after player death (5 minutes)
    private static final long GRAVE_ROBBER_TIMER_TICKS = 20L * 60 * 5;
    // Ferryman health threshold (less than 4 hearts = 8 HP)
    private static final double FERRYMAN_HEALTH_THRESHOLD = 8.0;
    // Ferryman check interval
    private static final long FERRYMAN_CHECK_TICKS = 100L;
    // Collector patrol check
    private static final long COLLECTOR_CHECK_TICKS = 36_000L;

    private final VestigiumNPC plugin;
    private final Set<UUID> activeFerrymen = new HashSet<>();
    private final UUID collectorUUID[] = {null}; // single Collector per server
    private BukkitRunnable ferrymanTask;
    private BukkitRunnable collectorTask;

    public SpecialNPCManager(VestigiumNPC plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startFerrymanTask();
        startCollectorTask();
        plugin.getLogger().info("[SpecialNPCManager] Initialized.");
    }

    public void shutdown() {
        if (ferrymanTask  != null) ferrymanTask.cancel();
        if (collectorTask != null) collectorTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Grave Robbers — spawn on player death
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Location deathLoc = player.getLocation();

        if (VestigiumLib.getProtectionAPI().isProtected(deathLoc)) return;

        // Spawn 2-3 Grave Robbers near the death location
        int count = 2 + ThreadLocalRandom.current().nextInt(2);
        for (int i = 0; i < count; i++) {
            spawnGraveRobber(deathLoc, player.getUniqueId());
        }
    }

    private void spawnGraveRobber(Location deathLoc, UUID targetPlayer) {
        Location spawnLoc = deathLoc.clone().add(
                ThreadLocalRandom.current().nextInt(10) - 5,
                0,
                ThreadLocalRandom.current().nextInt(10) - 5);

        Zombie robber = (Zombie) deathLoc.getWorld().spawnEntity(spawnLoc, EntityType.ZOMBIE);
        robber.setCustomName("Grave Robber");
        robber.setCustomNameVisible(true);
        robber.setBaby(false);
        robber.getPersistentDataContainer()
                .set(SPECIAL_TYPE_KEY, PersistentDataType.STRING, "GRAVE_ROBBER");

        // Auto-despawn after timer
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (robber.isValid()) robber.remove();
        }, GRAVE_ROBBER_TIMER_TICKS);
    }

    // -------------------------------------------------------------------------
    // The Ferryman — watches for critically low health
    // -------------------------------------------------------------------------

    private void startFerrymanTask() {
        ferrymanTask = new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getServer().getOnlinePlayers().forEach(player -> {
                    if (player.getHealth() <= FERRYMAN_HEALTH_THRESHOLD
                            && !hasFerrymanNearby(player)) {
                        trySpawnFerryman(player);
                    }
                });
                // Despawn ferrymen whose target has recovered
                activeFerrymen.removeIf(ferrymanUUID -> {
                    Entity e = findEntityByUUID(ferrymanUUID);
                    if (e == null || !e.isValid()) return true;
                    return false;
                });
            }
        };
        ferrymanTask.runTaskTimer(plugin, FERRYMAN_CHECK_TICKS, FERRYMAN_CHECK_TICKS);
    }

    private void trySpawnFerryman(Player player) {
        Location loc = player.getLocation().clone().add(
                ThreadLocalRandom.current().nextInt(6) - 3,
                0,
                ThreadLocalRandom.current().nextInt(6) - 3);

        if (VestigiumLib.getProtectionAPI().isProtected(loc)) return;

        Villager ferryman = (Villager) loc.getWorld().spawnEntity(loc, EntityType.VILLAGER);
        ferryman.setCustomName("The Ferryman");
        ferryman.setCustomNameVisible(true);
        ferryman.setAI(false); // stationary until interacted with
        ferryman.getPersistentDataContainer()
                .set(SPECIAL_TYPE_KEY, PersistentDataType.STRING, "FERRYMAN");
        ferryman.getPersistentDataContainer()
                .set(FERRYMAN_TARGET_KEY, PersistentDataType.STRING,
                        player.getUniqueId().toString());

        activeFerrymen.add(ferryman.getUniqueId());

        // Despawn if player recovers (checked every 5 seconds)
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!ferryman.isValid()) return;
            if (player.getHealth() > FERRYMAN_HEALTH_THRESHOLD + 4) {
                ferryman.remove();
                activeFerrymen.remove(ferryman.getUniqueId());
            }
        }, 100L, 100L);
    }

    private boolean hasFerrymanNearby(Player player) {
        return player.getNearbyEntities(10, 10, 10).stream()
                .anyMatch(e -> e instanceof Villager v
                        && "FERRYMAN".equals(v.getPersistentDataContainer()
                        .get(SPECIAL_TYPE_KEY, PersistentDataType.STRING)));
    }

    // -------------------------------------------------------------------------
    // The Pale Wanderer — spawns near unexplored structures
    // -------------------------------------------------------------------------

    /**
     * Spawns a Pale Wanderer near the given location (typically near a buried structure).
     * Called by VestigiumStructures via EventBus when generating an undiscovered structure.
     */
    public void spawnPaleWanderer(Location near) {
        if (VestigiumLib.getProtectionAPI().isProtected(near)) return;

        Location spawnLoc = near.clone().add(
                20 + ThreadLocalRandom.current().nextInt(30),
                0,
                20 + ThreadLocalRandom.current().nextInt(30));

        Villager wanderer = (Villager) near.getWorld().spawnEntity(spawnLoc, EntityType.VILLAGER);
        wanderer.setCustomName("???");
        wanderer.setCustomNameVisible(false); // name only shows when very close
        wanderer.setAI(true);
        wanderer.setInvulnerable(true); // never takes damage — always leads, never fights
        wanderer.getPersistentDataContainer()
                .set(SPECIAL_TYPE_KEY, PersistentDataType.STRING, "PALE_WANDERER");
    }

    // -------------------------------------------------------------------------
    // The Collector — single persistent entity patrolling the world
    // -------------------------------------------------------------------------

    private void startCollectorTask() {
        collectorTask = new BukkitRunnable() {
            @Override
            public void run() {
                maintainCollector();
            }
        };
        collectorTask.runTaskTimer(plugin, COLLECTOR_CHECK_TICKS, COLLECTOR_CHECK_TICKS);
    }

    private void maintainCollector() {
        // Ensure Collector is still alive; respawn if dead
        if (collectorUUID[0] != null) {
            Entity e = findEntityByUUID(collectorUUID[0]);
            if (e != null && e.isValid()) return; // still alive
        }

        if (plugin.getServer().getOnlinePlayers().isEmpty()) return;

        List<org.bukkit.entity.Player> players = new ArrayList<>(
                plugin.getServer().getOnlinePlayers());
        Player anchor = players.get(ThreadLocalRandom.current().nextInt(players.size()));
        Location spawnLoc = anchor.getLocation().clone().add(
                200 + ThreadLocalRandom.current().nextInt(400),
                0,
                200 + ThreadLocalRandom.current().nextInt(400));

        if (VestigiumLib.getProtectionAPI().isProtected(spawnLoc)) return;

        Villager collector = (Villager) spawnLoc.getWorld()
                .spawnEntity(spawnLoc, EntityType.VILLAGER);
        collector.setCustomName("The Collector");
        collector.setCustomNameVisible(true);
        collector.setAI(true);
        collector.getPersistentDataContainer()
                .set(SPECIAL_TYPE_KEY, PersistentDataType.STRING, "COLLECTOR");

        collectorUUID[0] = collector.getUniqueId();
        plugin.getLogger().info("[SpecialNPCManager] The Collector spawned.");
    }

    // -------------------------------------------------------------------------

    private Entity findEntityByUUID(UUID uuid) {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e.getUniqueId().equals(uuid)) return e;
            }
        }
        return null;
    }
}
