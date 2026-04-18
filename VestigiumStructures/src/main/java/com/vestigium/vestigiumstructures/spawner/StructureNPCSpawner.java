package com.vestigium.vestigiumstructures.spawner;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumstructures.VestigiumStructures;
import com.vestigium.vestigiumstructures.registry.StructureDefinition;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spawns structure-specific NPCs when a player first enters a structure's territory.
 *
 * Detection: PlayerMoveEvent checks if the block below the player has a
 * vestigium:structure_id PDC tag. On first entry per player per structure,
 * NPC types listed in the StructureDefinition are spawned nearby.
 *
 * Named Warden spawning is also triggered here if the structure definition
 * includes a warden_type and the structure has not yet had its warden spawned.
 *
 * Per-structure spawn state is tracked by:
 *   - player entry: "vs_entered_{structureId}" PDC BOOLEAN on Player (prevents re-trigger per player)
 *   - warden spawned: in-memory Set<String> of structure IDs that have had wardens placed
 *     (clears on restart — intentional, so wardens respawn if killed between restarts)
 */
public class StructureNPCSpawner implements Listener {

    private static final NamespacedKey STRUCTURE_ID_KEY =
            new NamespacedKey("vestigium", "structure_id");
    private static final NamespacedKey NPC_TYPE_KEY =
            new NamespacedKey("vestigium", "npc_type");

    private final VestigiumStructures plugin;
    // structure IDs that have had their warden spawned this server session
    private final Set<String> wardenSpawned = ConcurrentHashMap.newKeySet();

    public StructureNPCSpawner(VestigiumStructures plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[StructureNPCSpawner] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Entry detection
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Only check when crossing block boundaries
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Block underFoot = event.getTo().getBlock().getRelative(0, -1, 0);
        String structureId = underFoot.getPersistentDataContainer()
                .get(STRUCTURE_ID_KEY, PersistentDataType.STRING);
        if (structureId == null) return;

        var player = event.getPlayer();

        // Check if this player has already triggered this structure
        NamespacedKey enteredKey = new NamespacedKey("vestigium", "vs_entered_" + structureId);
        boolean alreadyEntered = player.getPersistentDataContainer()
                .getOrDefault(enteredKey, PersistentDataType.BOOLEAN, false);
        if (alreadyEntered) return;

        player.getPersistentDataContainer()
                .set(enteredKey, PersistentDataType.BOOLEAN, true);

        // Look up definition
        plugin.getStructureRegistry().getById(structureId).ifPresent(def -> {
            Location spawnLoc = event.getTo().clone().add(2, 0, 2);
            spawnStructureNPCs(def, spawnLoc);
            maybeSpawnWarden(def, spawnLoc);
        });
    }

    // -------------------------------------------------------------------------
    // NPC spawning
    // -------------------------------------------------------------------------

    private void spawnStructureNPCs(StructureDefinition def, Location loc) {
        List<String> npcTypes = def.npcTypes();
        if (npcTypes.isEmpty()) return;

        for (String npcType : npcTypes) {
            if (VestigiumLib.getProtectionAPI().isProtected(loc)) continue;

            Villager villager = (Villager) loc.getWorld().spawnEntity(loc, EntityType.VILLAGER);
            villager.setBreed(false);
            villager.setPersistent(true);
            villager.setAI(false);
            villager.setCustomName(formatNpcName(npcType));
            villager.setCustomNameVisible(true);
            villager.getPersistentDataContainer()
                    .set(NPC_TYPE_KEY, PersistentDataType.STRING, npcType);

            // Offset each NPC slightly
            loc = loc.clone().add(1.5, 0, 0);
        }
    }

    private void maybeSpawnWarden(StructureDefinition def, Location loc) {
        String wardenType = def.wardenType();
        if (wardenType == null || wardenType.isBlank()) return;
        if (wardenSpawned.contains(def.id())) return;

        wardenSpawned.add(def.id());

        // Fire warden spawn via EventBus — VestigiumMobs subscribes without us importing it
        VestigiumLib.getEventBus().fire(
                new com.vestigium.lib.event.WorldBossSpawnEvent(wardenType, null, loc));

        plugin.getLogger().info("[StructureNPCSpawner] Fired WorldBossSpawnEvent for "
                + wardenType + " at " + def.id());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String formatNpcName(String npcType) {
        return "§e" + npcType.replace("_", " ")
                .chars()
                .collect(StringBuilder::new,
                        (sb, c) -> {
                            if (sb.length() == 0 || sb.charAt(sb.length() - 1) == ' ')
                                sb.append(Character.toUpperCase(c));
                            else
                                sb.append((char) c);
                        },
                        StringBuilder::append)
                .toString();
    }
}
