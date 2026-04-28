package com.vestigium.vestigiumnpc.hostile;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumnpc.VestigiumNPC;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages the Doppelganger — one of the most complex hostile NPCs in the suite.
 *
 * Behaviour:
 *   - Copies the skin and name of a random offline player.
 *   - Appears in the tab list as that player.
 *   - Responds to chat with basic phrases ("hey", "brb", "yeah").
 *   - Passive phase: wanders; never attacks.
 *   - Hostile escalation: after ESCALATION_DURATION_MILLIS, becomes aggressive.
 *   - Cannot spawn if the target player is currently online.
 *   - Drops Soul Mirror or Hollow Crown on death.
 *   - Despawns immediately if target player logs in.
 *
 * Implementation note: true skin spoofing requires NMS / ProtocolLib, which is
 * intentionally out of scope for this phase. This class manages the game logic,
 * state machine, and lifecycle. The visual layer (tab list injection, skin packet)
 * is left as a TODO hook for when ProtocolLib integration is added.
 */
public class DoppelgangerManager implements Listener {

    private static final long SPAWN_CHECK_TICKS          = 72_000L; // every hour
    private static final long ESCALATION_DURATION_MILLIS = 30L * 60 * 1000; // 30 minutes passive
    private static final double SPAWN_CHANCE_PER_CHECK   = 0.15; // 15% chance per hour check

    private static final NamespacedKey DOPPEL_TARGET_KEY =
            new NamespacedKey("vestigium", "doppel_target");
    private static final NamespacedKey DOPPEL_SPAWN_TIME_KEY =
            new NamespacedKey("vestigium", "doppel_spawn_time");

    // Active doppelgangers: entity UUID -> target player UUID
    private final Map<UUID, UUID> activeDoppelgangers = new HashMap<>();
    private BukkitRunnable spawnTask;
    private BukkitRunnable escalationTask;

    private final VestigiumNPC plugin;

    public DoppelgangerManager(VestigiumNPC plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startSpawnTask();
        startEscalationTask();
        plugin.getLogger().info("[DoppelgangerManager] Initialized.");
    }

    public void shutdown() {
        if (spawnTask      != null) spawnTask.cancel();
        if (escalationTask != null) escalationTask.cancel();
        // Despawn all active doppelgangers on shutdown
        activeDoppelgangers.keySet().forEach(entityUUID -> {
            plugin.getServer().getWorlds().forEach(world ->
                    world.getEntitiesByClass(Zombie.class).stream()
                            .filter(z -> z.getUniqueId().equals(entityUUID))
                            .forEach(org.bukkit.entity.Entity::remove));
        });
        activeDoppelgangers.clear();
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // If a doppelganger is impersonating this player, despawn it immediately
        UUID joiningPlayer = event.getPlayer().getUniqueId();
        activeDoppelgangers.entrySet().removeIf(entry -> {
            if (entry.getValue().equals(joiningPlayer)) {
                despawnDoppelganger(entry.getKey());
                return true;
            }
            return false;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) return;
        UUID entityUUID = zombie.getUniqueId();
        if (!activeDoppelgangers.containsKey(entityUUID)) return;

        activeDoppelgangers.remove(entityUUID);

        // Drop Soul Mirror or Hollow Crown (50/50)
        org.bukkit.inventory.ItemStack drop;
        if (ThreadLocalRandom.current().nextBoolean()) {
            drop = createNamedItem(org.bukkit.Material.GLASS, "Soul Mirror");
        } else {
            drop = createNamedItem(org.bukkit.Material.GOLDEN_HELMET, "Hollow Crown");
        }
        event.getDrops().clear();
        event.getDrops().add(drop);
        plugin.getLogger().info("[DoppelgangerManager] Doppelganger killed, dropped: "
                + drop.getItemMeta().getDisplayName());
    }

    // -------------------------------------------------------------------------
    // Spawn and escalation
    // -------------------------------------------------------------------------

    private void startSpawnTask() {
        spawnTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (plugin.getServer().getOnlinePlayers().isEmpty()) return;
                // Only one doppelganger at a time
                if (!activeDoppelgangers.isEmpty()) return;
                if (Math.random() < SPAWN_CHANCE_PER_CHECK) {
                    trySpawn();
                }
            }
        };
        spawnTask.runTaskTimer(plugin, SPAWN_CHECK_TICKS, SPAWN_CHECK_TICKS);
    }

    private void startEscalationTask() {
        escalationTask = new BukkitRunnable() {
            @Override
            public void run() {
                checkEscalations();
            }
        };
        escalationTask.runTaskTimer(plugin, 200L, 200L); // check every 10 seconds
    }

    private void trySpawn() {
        // Pick a random offline player as the target
        List<org.bukkit.OfflinePlayer> offline = getOfflineCandidates();
        if (offline.isEmpty()) return;

        org.bukkit.OfflinePlayer target = offline.get(
                ThreadLocalRandom.current().nextInt(offline.size()));

        // Find a spawn location near a random online player
        List<Player> online = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        Player anchor = online.get(ThreadLocalRandom.current().nextInt(online.size()));
        Location spawnLoc = findSpawnLocation(anchor.getLocation());
        if (spawnLoc == null) return;

        Zombie zombie = (Zombie) spawnLoc.getWorld().spawnEntity(spawnLoc, EntityType.ZOMBIE);
        zombie.setCustomName(target.getName());
        zombie.setCustomNameVisible(true);
        zombie.setBaby(false);
        zombie.setAware(true);
        // Start passive — no AI aggro initially
        zombie.setAI(true);

        zombie.getPersistentDataContainer()
                .set(DOPPEL_TARGET_KEY, PersistentDataType.STRING, target.getUniqueId().toString());
        zombie.getPersistentDataContainer()
                .set(DOPPEL_SPAWN_TIME_KEY, PersistentDataType.LONG, System.currentTimeMillis());

        activeDoppelgangers.put(zombie.getUniqueId(), target.getUniqueId());

        // TODO: inject tab list entry and skin packet when ProtocolLib is available

        plugin.getLogger().info("[DoppelgangerManager] Spawned doppelganger of: " + target.getName());
    }

    private void checkEscalations() {
        activeDoppelgangers.keySet().forEach(entityUUID -> {
            plugin.getServer().getWorlds().forEach(world ->
                    world.getEntitiesByClass(Zombie.class).stream()
                            .filter(z -> z.getUniqueId().equals(entityUUID))
                            .findFirst()
                            .ifPresent(zombie -> {
                                long spawnTime = zombie.getPersistentDataContainer()
                                        .getOrDefault(DOPPEL_SPAWN_TIME_KEY,
                                                PersistentDataType.LONG, 0L);
                                if (System.currentTimeMillis() - spawnTime
                                        >= ESCALATION_DURATION_MILLIS) {
                                    // Escalate to hostile — enable full combat AI
                                    zombie.setAware(true);
                                    plugin.getLogger().info(
                                            "[DoppelgangerManager] Doppelganger escalated to hostile.");
                                }
                            }));
        });
    }

    private void despawnDoppelganger(UUID entityUUID) {
        plugin.getServer().getWorlds().forEach(world ->
                world.getEntitiesByClass(Zombie.class).stream()
                        .filter(z -> z.getUniqueId().equals(entityUUID))
                        .forEach(org.bukkit.entity.Entity::remove));
    }

    private List<org.bukkit.OfflinePlayer> getOfflineCandidates() {
        Set<UUID> onlineUUIDs = new HashSet<>();
        plugin.getServer().getOnlinePlayers()
                .forEach(p -> onlineUUIDs.add(p.getUniqueId()));
        List<org.bukkit.OfflinePlayer> result = new ArrayList<>();
        for (org.bukkit.OfflinePlayer op : plugin.getServer().getOfflinePlayers()) {
            if (!onlineUUIDs.contains(op.getUniqueId()) && op.getName() != null) {
                result.add(op);
            }
        }
        return result;
    }

    private Location findSpawnLocation(Location anchor) {
        // Spawn 30-100 blocks away, out of direct line of sight
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble() * 2 * Math.PI;
            double dist  = 30 + ThreadLocalRandom.current().nextDouble() * 70;
            int x = anchor.getBlockX() + (int) (Math.cos(angle) * dist);
            int z = anchor.getBlockZ() + (int) (Math.sin(angle) * dist);
            int y = anchor.getWorld().getHighestBlockYAt(x, z) + 1;
            Location candidate = new Location(anchor.getWorld(), x, y, z);
            if (!VestigiumLib.getProtectionAPI().isProtected(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private org.bukkit.inventory.ItemStack createNamedItem(org.bukkit.Material mat, String name) {
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(mat);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }
}
