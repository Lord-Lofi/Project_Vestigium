package com.vestigium.vestigiumplayer.epitaph;

import com.vestigium.vestigiumplayer.VestigiumPlayer;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Legacy Markers — when a legendary achievement is earned server-first, a permanent
 * glowing ArmorStand marker is placed at that player's location and a server-wide
 * announcement fires. Records persist to plugins/VestigiumPlayer/legacy_markers.yml.
 *
 * /vplegacy — lists all server-first legacy records.
 */
public class LegacyMarkerManager implements CommandExecutor {

    private static final NamespacedKey LEGACY_KEY = new NamespacedKey("vestigium", "legacy_marker_key");

    private final VestigiumPlayer plugin;
    private File              dataFile;
    private YamlConfiguration data;
    private final Set<String> claimedKeys = new HashSet<>();

    public LegacyMarkerManager(VestigiumPlayer plugin) {
        this.plugin = plugin;
    }

    public void init() {
        dataFile = new File(plugin.getDataFolder(), "legacy_markers.yml");
        data = dataFile.exists()
                ? YamlConfiguration.loadConfiguration(dataFile)
                : new YamlConfiguration();
        claimedKeys.addAll(data.getKeys(false));

        var cmd = plugin.getCommand("vplegacy");
        if (cmd != null) cmd.setExecutor(this);

        plugin.getLogger().info("[LegacyMarkerManager] Initialized — "
                + claimedKeys.size() + " server-first records.");
    }

    // -------------------------------------------------------------------------
    // Public API — called by AchievementManager on legendary unlock
    // -------------------------------------------------------------------------

    public void notifyLegendary(Player player, String achievementKey, String displayName) {
        if (claimedKeys.contains(achievementKey)) return;
        claimedKeys.add(achievementKey);

        Location loc = player.getLocation();
        spawnMarker(loc, player.getName(), displayName, achievementKey);
        saveRecord(achievementKey, player.getName(), displayName, loc);

        String broadcast = "§5§l[Vestigium] §r§d" + player.getName()
                + " §7is the first to achieve §5" + displayName + "§7."
                + " §8A Legacy Marker stands where they stood.";
        plugin.getServer().getOnlinePlayers().forEach(p -> p.sendMessage(broadcast));
    }

    // -------------------------------------------------------------------------
    // Command — /vplegacy
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (claimedKeys.isEmpty()) {
            sender.sendMessage("§7No server-first legacies have been recorded yet.");
            return true;
        }
        sender.sendMessage("§5§l--- Server Legacies ---");
        for (String key : claimedKeys) {
            String playerName = data.getString(key + ".player", "Unknown");
            String display    = data.getString(key + ".display", key);
            String locStr     = data.getString(key + ".location", "unknown location");
            sender.sendMessage("§d" + display + " §8— §7" + playerName + " §8@ §7" + locStr);
        }
        return true;
    }

    // -------------------------------------------------------------------------

    private void spawnMarker(Location loc, String playerName, String displayName, String key) {
        ArmorStand stand = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setInvulnerable(true);
        stand.setGravity(false);
        stand.setVisible(false);
        stand.setPersistent(true);
        stand.setGlowing(true);
        stand.setCustomName("§5✦ §d" + playerName + " §5— §d" + displayName);
        stand.setCustomNameVisible(true);
        stand.getPersistentDataContainer()
                .set(LEGACY_KEY, PersistentDataType.STRING, key);

        // Slow ambient particle halo
        new BukkitRunnable() {
            @Override public void run() {
                if (!stand.isValid()) { cancel(); return; }
                stand.getWorld().spawnParticle(
                        Particle.END_ROD,
                        stand.getLocation().add(0, 2.2, 0),
                        4, 0.25, 0.25, 0.25, 0.01);
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void saveRecord(String key, String playerName, String displayName, Location loc) {
        String locStr = loc.getWorld().getName()
                + " " + loc.getBlockX()
                + " " + loc.getBlockY()
                + " " + loc.getBlockZ();
        data.set(key + ".player",   playerName);
        data.set(key + ".display",  displayName);
        data.set(key + ".location", locStr);
        try {
            plugin.getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[LegacyMarkerManager] Failed to save: " + e.getMessage());
        }
    }
}
