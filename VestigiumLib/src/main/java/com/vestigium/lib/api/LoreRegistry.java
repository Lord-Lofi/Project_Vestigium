package com.vestigium.lib.api;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.LoreFragmentGrantedEvent;
import com.vestigium.lib.util.Keys;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Central store for all lore content and player fragment tracking.
 *
 * Lore content is loaded from YAML files placed in:
 *   plugins/VestigiumLib/lore/{structureId}.yml
 *
 * Each YAML file has tablet entries keyed by tabletId:
 *   tablet_01: "The first who descended did not come willingly..."
 *
 * Player fragment tracking uses per-player PDC tags:
 *   vestigium:lore_fragment_{fragmentId} → Boolean
 *
 * Chain completion is tracked by counting fragments matching a chain prefix.
 */
public class LoreRegistry {

    private final Plugin plugin;
    // structureId -> (tabletId -> content)
    private final Map<String, Map<String, String>> loreContent = new HashMap<>();

    public LoreRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    private static final String[] BUNDLED_LORE = {
        "cartographer_waystone_1", "cartographer_terminus",
        "ancient_guardian_chamber", "antecedent_vault", "deep_archive_alpha"
    };

    /** Loads all lore YAML files from the lore/ data directory. */
    public void loadAll() {
        File loreDir = new File(plugin.getDataFolder(), "lore");
        if (!loreDir.exists()) {
            loreDir.mkdirs();
            for (String name : BUNDLED_LORE) {
                try {
                    plugin.saveResource("lore/" + name + ".yml", false);
                } catch (Exception e) {
                    plugin.getLogger().warning("[LoreRegistry] Could not extract default: " + name + ".yml");
                }
            }
        }

        File[] files = loreDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            plugin.getLogger().info("[LoreRegistry] No lore files found in lore/.");
            return;
        }

        for (File file : files) {
            String structureId = file.getName().replace(".yml", "");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            Map<String, String> tablets = new HashMap<>();
            for (String key : config.getKeys(false)) {
                tablets.put(key, config.getString(key, ""));
            }
            loreContent.put(structureId, tablets);
        }

        plugin.getLogger().info("[LoreRegistry] Loaded lore for " + loreContent.size() + " structure(s).");
    }

    /**
     * Returns the lore content for a specific tablet in a structure.
     * Returns an empty string if the structure or tablet is not found.
     *
     * @param structureId the structure's PDC ID (vestigium:structure_id value)
     * @param tabletId    the tablet identifier within that structure
     */
    public String getLoreContent(String structureId, String tabletId) {
        Map<String, String> tablets = loreContent.get(structureId);
        if (tablets == null) return "";
        return tablets.getOrDefault(tabletId, "");
    }

    /**
     * Returns true if the player has collected the given lore fragment.
     *
     * @param playerUUID the player's UUID
     * @param fragmentId the fragment identifier
     */
    public boolean hasFragment(UUID playerUUID, String fragmentId) {
        Player player = plugin.getServer().getPlayer(playerUUID);
        if (player == null) return false;
        return player.getPersistentDataContainer()
                .has(Keys.loreFragmentKey(plugin, fragmentId), PersistentDataType.BOOLEAN);
    }

    /**
     * Grants a lore fragment to a player.
     *
     * @param playerUUID the player's UUID
     * @param fragmentId the fragment identifier
     */
    public void grantFragment(UUID playerUUID, String fragmentId) {
        Player player = plugin.getServer().getPlayer(playerUUID);
        if (player == null) {
            plugin.getLogger().warning("[LoreRegistry] grantFragment called for offline player " + playerUUID);
            return;
        }
        boolean alreadyHad = player.getPersistentDataContainer()
                .has(Keys.loreFragmentKey(plugin, fragmentId), PersistentDataType.BOOLEAN);
        player.getPersistentDataContainer()
                .set(Keys.loreFragmentKey(plugin, fragmentId), PersistentDataType.BOOLEAN, true);
        if (!alreadyHad) {
            VestigiumLib.getEventBus().fire(new LoreFragmentGrantedEvent(playerUUID, fragmentId));
        }
    }

    /**
     * Counts how many fragments a player has collected for a given chain.
     * Fragments in the chain must be named {chainId}_{step} (e.g. "cartographer_01").
     *
     * @param playerUUID the player's UUID
     * @param chainId    the chain prefix to count
     * @return number of collected fragments in this chain
     */
    public int getFragmentCount(UUID playerUUID, String chainId) {
        Player player = plugin.getServer().getPlayer(playerUUID);
        if (player == null) return 0;

        int count = 0;
        // Scan PDC keys for this chain — iterate over all known lore files for chain entries
        for (Map.Entry<String, Map<String, String>> entry : loreContent.entrySet()) {
            for (String tabletId : entry.getValue().keySet()) {
                String fragmentId = entry.getKey() + "_" + tabletId;
                if (fragmentId.startsWith(chainId) && hasFragment(playerUUID, fragmentId)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Reloads all lore content from disk. */
    public void reload() {
        loreContent.clear();
        loadAll();
    }

    /** Returns how many structures have lore loaded. */
    public int getLoadedStructureCount() {
        return loreContent.size();
    }
}
