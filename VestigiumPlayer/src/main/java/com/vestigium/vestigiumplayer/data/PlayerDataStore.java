package com.vestigium.vestigiumplayer.data;

import com.vestigium.vestigiumplayer.VestigiumPlayer;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Central persistence layer for VestigiumPlayer data.
 *
 * Design:
 *   - Primary store: Player entity PDC (online players) — instant read/write, no I/O
 *   - Backup store: plugins/VestigiumPlayer/players/{uuid}.yml — written on logout and shutdown
 *   - On join: loads yml backup into PDC for any keys that are missing (handles restarts)
 *
 * Tracked keys (all under namespace "vestigium"):
 *   vp_playtime        → Long   (minutes online)
 *   vp_active_title    → String (current display title key)
 *   vp_unlocked_titles → String (comma-separated title keys)
 *   vp_cataclysms      → Integer (cataclysms survived)
 *   vp_structures      → Integer (unique structures discovered)
 *   vp_boss_kills      → Integer (total named boss kills)
 *   vp_lore_frags      → Integer (lore fragments collected)
 */
public class PlayerDataStore implements Listener {

    public static final NamespacedKey KEY_PLAYTIME   = new NamespacedKey("vestigium", "vp_playtime");
    public static final NamespacedKey KEY_TITLE      = new NamespacedKey("vestigium", "vp_active_title");
    public static final NamespacedKey KEY_TITLES     = new NamespacedKey("vestigium", "vp_unlocked_titles");
    public static final NamespacedKey KEY_CATACLYSMS = new NamespacedKey("vestigium", "vp_cataclysms");
    public static final NamespacedKey KEY_STRUCTURES = new NamespacedKey("vestigium", "vp_structures");
    public static final NamespacedKey KEY_BOSS_KILLS = new NamespacedKey("vestigium", "vp_boss_kills");
    public static final NamespacedKey KEY_LORE_FRAGS = new NamespacedKey("vestigium", "vp_lore_frags");

    private final VestigiumPlayer plugin;
    private final Map<UUID, Long> joinTimes = new HashMap<>();

    public PlayerDataStore(VestigiumPlayer plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[PlayerDataStore] Initialized.");
    }

    public void saveAll() {
        File dir = new File(plugin.getDataFolder(), "players");
        dir.mkdirs();
        plugin.getServer().getOnlinePlayers().forEach(p -> savePlayer(p, dir));
    }

    // -------------------------------------------------------------------------
    // Convenience accessors
    // -------------------------------------------------------------------------

    public String getActiveTitle(Player player) {
        return player.getPersistentDataContainer()
                .getOrDefault(KEY_TITLE, PersistentDataType.STRING, "");
    }

    public void setActiveTitle(Player player, String titleKey) {
        player.getPersistentDataContainer()
                .set(KEY_TITLE, PersistentDataType.STRING, titleKey);
    }

    public List<String> getUnlockedTitles(Player player) {
        String raw = player.getPersistentDataContainer()
                .getOrDefault(KEY_TITLES, PersistentDataType.STRING, "");
        if (raw.isBlank()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(raw.split(",")));
    }

    public void unlockTitle(Player player, String titleKey) {
        List<String> titles = getUnlockedTitles(player);
        if (!titles.contains(titleKey)) {
            titles.add(titleKey);
            player.getPersistentDataContainer()
                    .set(KEY_TITLES, PersistentDataType.STRING, String.join(",", titles));
        }
    }

    public int getInt(Player player, NamespacedKey key) {
        return player.getPersistentDataContainer()
                .getOrDefault(key, PersistentDataType.INTEGER, 0);
    }

    public void addInt(Player player, NamespacedKey key, int delta) {
        int current = getInt(player, key);
        player.getPersistentDataContainer()
                .set(key, PersistentDataType.INTEGER, current + delta);
    }

    // -------------------------------------------------------------------------
    // Join/quit lifecycle
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        joinTimes.put(player.getUniqueId(), System.currentTimeMillis());
        loadPlayerBackup(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        accumulatePlaytime(player);
        joinTimes.remove(player.getUniqueId());
        savePlayer(player, new File(plugin.getDataFolder(), "players"));
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void savePlayer(Player player, File dir) {
        dir.mkdirs();
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("active_title",      getActiveTitle(player));
        cfg.set("unlocked_titles",   getUnlockedTitles(player));
        cfg.set("playtime_minutes",  getPlaytimeMinutes(player));
        cfg.set("cataclysms",        getInt(player, KEY_CATACLYSMS));
        cfg.set("structures",        getInt(player, KEY_STRUCTURES));
        cfg.set("boss_kills",        getInt(player, KEY_BOSS_KILLS));
        cfg.set("lore_frags",        getInt(player, KEY_LORE_FRAGS));
        try {
            cfg.save(new File(dir, player.getUniqueId() + ".yml"));
        } catch (IOException e) {
            plugin.getLogger().warning("[PlayerDataStore] Save failed for "
                    + player.getName() + ": " + e.getMessage());
        }
    }

    private void loadPlayerBackup(Player player) {
        File f = new File(plugin.getDataFolder(), "players/" + player.getUniqueId() + ".yml");
        if (!f.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);

        if (!player.getPersistentDataContainer().has(KEY_TITLE, PersistentDataType.STRING)) {
            String title = cfg.getString("active_title", "");
            if (!title.isBlank())
                player.getPersistentDataContainer().set(KEY_TITLE, PersistentDataType.STRING, title);
        }
        if (!player.getPersistentDataContainer().has(KEY_TITLES, PersistentDataType.STRING)) {
            List<String> titles = cfg.getStringList("unlocked_titles");
            if (!titles.isEmpty())
                player.getPersistentDataContainer()
                        .set(KEY_TITLES, PersistentDataType.STRING, String.join(",", titles));
        }
        restoreInt(player, KEY_CATACLYSMS, cfg, "cataclysms");
        restoreInt(player, KEY_STRUCTURES, cfg, "structures");
        restoreInt(player, KEY_BOSS_KILLS, cfg, "boss_kills");
        restoreInt(player, KEY_LORE_FRAGS, cfg, "lore_frags");
    }

    private void restoreInt(Player player, NamespacedKey key, YamlConfiguration cfg, String cfgKey) {
        if (!player.getPersistentDataContainer().has(key, PersistentDataType.INTEGER)) {
            int val = cfg.getInt(cfgKey, 0);
            if (val > 0) player.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, val);
        }
    }

    private void accumulatePlaytime(Player player) {
        Long joinTime = joinTimes.get(player.getUniqueId());
        if (joinTime == null) return;
        long minutes = (System.currentTimeMillis() - joinTime) / 60_000L;
        long current = player.getPersistentDataContainer()
                .getOrDefault(KEY_PLAYTIME, PersistentDataType.LONG, 0L);
        player.getPersistentDataContainer()
                .set(KEY_PLAYTIME, PersistentDataType.LONG, current + minutes);
    }

    private long getPlaytimeMinutes(Player player) {
        return player.getPersistentDataContainer()
                .getOrDefault(KEY_PLAYTIME, PersistentDataType.LONG, 0L);
    }
}
