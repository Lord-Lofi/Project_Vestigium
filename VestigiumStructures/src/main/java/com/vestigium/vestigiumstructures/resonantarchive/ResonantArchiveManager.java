package com.vestigium.vestigiumstructures.resonantarchive;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumstructures.VestigiumStructures;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks players inside Resonant Archive structures and delivers a lore sequence
 * after 60 continuous seconds of silence (no block movement, no damage taken).
 *
 * Archive anchors are registered by ChunkStructureInjector (on worldgen injection)
 * and StructureAdminCommand (on manual paste). Each call to registerAnchor() adds
 * the location to the watched set; the 20-tick proximity task evaluates whether
 * any online player is within DETECTION_RADIUS blocks of any anchor.
 *
 * The silence sequence fires once per player per archive (gated by per-archive PDC).
 */
public class ResonantArchiveManager implements Listener {

    private static final int DETECTION_RADIUS = 20;
    private static final long SILENCE_MS = 60_000L;

    private final VestigiumStructures plugin;

    record ArchiveAnchor(Location loc, String structureId) {}

    private final CopyOnWriteArrayList<ArchiveAnchor> archives = new CopyOnWriteArrayList<>();

    // UUID -> ms timestamp when player last moved a block (or entered archive)
    private final Map<UUID, Long> lastMoveTime = new ConcurrentHashMap<>();
    // UUID -> archive ID player is currently inside
    private final Map<UUID, String> playerArchive = new ConcurrentHashMap<>();
    // UUID -> whether the silence trigger has already fired this visit
    private final Map<UUID, Boolean> triggered = new ConcurrentHashMap<>();

    public ResonantArchiveManager(VestigiumStructures plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startProximityTask();
        plugin.getLogger().info("[ResonantArchiveManager] Initialized.");
    }

    public void registerAnchor(Block anchor, String structureId) {
        archives.add(new ArchiveAnchor(anchor.getLocation(), structureId));
        plugin.getLogger().info("[ResonantArchiveManager] Registered anchor for "
                + structureId + " at " + anchor.getWorld().getName()
                + " " + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ());
    }

    // -------------------------------------------------------------------------
    // Proximity task — runs every 20 ticks (1 second)
    // -------------------------------------------------------------------------

    private void startProximityTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (archives.isEmpty()) return;

                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    Location pLoc = player.getLocation();

                    String nearbyArchive = null;
                    for (ArchiveAnchor archive : archives) {
                        if (!archive.loc().getWorld().equals(pLoc.getWorld())) continue;
                        if (archive.loc().distanceSquared(pLoc)
                                <= DETECTION_RADIUS * DETECTION_RADIUS) {
                            nearbyArchive = archive.structureId();
                            break;
                        }
                    }

                    if (nearbyArchive != null) {
                        if (!playerArchive.containsKey(uuid)) {
                            // Player just entered — start tracking
                            playerArchive.put(uuid, nearbyArchive);
                            lastMoveTime.put(uuid, System.currentTimeMillis());
                            triggered.put(uuid, false);
                            player.sendMessage("§8§o— A deep resonance fills the chamber. Stand still. —");
                        }

                        // Check silence threshold (only fires once per visit per archive)
                        if (!triggered.getOrDefault(uuid, true)) {
                            long elapsed = System.currentTimeMillis() - lastMoveTime.get(uuid);
                            if (elapsed >= SILENCE_MS) {
                                triggered.put(uuid, true);
                                triggerSilenceEvent(player, playerArchive.get(uuid));
                            }
                        }
                    } else {
                        exitArchive(uuid);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // -------------------------------------------------------------------------
    // Event listeners — reset silence timer on movement or damage
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!playerArchive.containsKey(uuid)) return;
        if (triggered.getOrDefault(uuid, true)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;

        lastMoveTime.put(uuid, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (!playerArchive.containsKey(uuid)) return;
        lastMoveTime.put(uuid, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        exitArchive(event.getPlayer().getUniqueId());
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void exitArchive(UUID uuid) {
        playerArchive.remove(uuid);
        lastMoveTime.remove(uuid);
        triggered.remove(uuid);
    }

    private void triggerSilenceEvent(Player player, String archiveId) {
        // Gate: only once per player per archive
        NamespacedKey gateKey = new NamespacedKey("vestigium",
                "resonant_archive_silence_" + archiveId.replace(":", "_"));
        if (player.getPersistentDataContainer()
                .has(gateKey, PersistentDataType.BOOLEAN)) return;
        player.getPersistentDataContainer()
                .set(gateKey, PersistentDataType.BOOLEAN, true);

        String[] lines = {
            "§8§o— The resonance deepens. Something stirs in the silence. —",
            "§7§o\"The last who listened were never found. Their words remain.\"",
            "§7§o\"We encoded everything. The frequency holds memory.\"",
            "§7§o\"If you can hear this, the archive has not yet fallen silent.\"",
            "§8§o— The resonance fades. The archive yields its fragment. —"
        };

        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) player.sendMessage(line);
            }, (long) i * 60L);
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                VestigiumLib.getLoreRegistry().grantFragment(
                        player.getUniqueId(), "resonant_archive_silence");
                player.sendMessage("§5[Fragment] §7Resonant Archive — Silence Record");
            }
        }, (long) lines.length * 60L + 20L);
    }
}
