package com.vestigium.vestigiumplayer;

import com.vestigium.lib.api.PlaceholderAPIHook;
import com.vestigium.vestigiumplayer.data.PlayerDataStore;
import com.vestigium.vestigiumplayer.notoriety.NotorietyManager;
import com.vestigium.vestigiumplayer.stats.PlayerStatTracker;
import com.vestigium.vestigiumplayer.title.TitleManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumPlayer — player progression, custom stat tracking, unlockable titles,
 * and persistent player data storage.
 * Depends only on VestigiumLib.
 */
public class VestigiumPlayer extends JavaPlugin {

    private static VestigiumPlayer instance;

    private PlayerDataStore   playerDataStore;
    private PlayerStatTracker playerStatTracker;
    private TitleManager      titleManager;
    private NotorietyManager  notorietyManager;

    @Override
    public void onEnable() {
        instance = this;

        playerDataStore   = new PlayerDataStore(this);
        playerStatTracker = new PlayerStatTracker(this, playerDataStore);
        titleManager      = new TitleManager(this, playerDataStore);
        notorietyManager  = new NotorietyManager(this, playerDataStore);

        playerDataStore.init();
        playerStatTracker.init();
        titleManager.init();
        notorietyManager.init();

        registerPlaceholders();

        getLogger().info("VestigiumPlayer enabled.");
    }

    @Override
    public void onDisable() {
        if (notorietyManager != null) notorietyManager.shutdown();
        if (playerDataStore  != null) playerDataStore.saveAll();
        getLogger().info("VestigiumPlayer disabled.");
    }

    public static VestigiumPlayer getInstance()          { return instance; }
    public PlayerDataStore getPlayerDataStore()          { return playerDataStore; }
    public PlayerStatTracker getPlayerStatTracker()      { return playerStatTracker; }
    public TitleManager getTitleManager()                { return titleManager; }
    public NotorietyManager getNotorietyManager()        { return notorietyManager; }

    private void registerPlaceholders() {
        // %vestigium_title%        — active title display string (e.g. §e[Cartographer])
        PlaceholderAPIHook.register("title", p -> {
            var online = p.getPlayer();
            if (online == null) return "";
            String key = playerDataStore.getActiveTitle(online);
            if (key.isBlank()) return "";
            return TitleManager.getAllTitles().stream()
                    .filter(t -> t.key().equals(key))
                    .map(t -> t.display())
                    .findFirst().orElse(key);
        });

        // %vestigium_title_key%    — raw title key (e.g. cartographer)
        PlaceholderAPIHook.register("title_key", p -> {
            var online = p.getPlayer();
            return online == null ? "" : playerDataStore.getActiveTitle(online);
        });

        // %vestigium_structures%   — unique structures discovered
        PlaceholderAPIHook.register("structures", p -> {
            var online = p.getPlayer();
            return online == null ? "0"
                    : String.valueOf(playerDataStore.getInt(online, PlayerDataStore.KEY_STRUCTURES));
        });

        // %vestigium_cataclysms%   — cataclysms survived
        PlaceholderAPIHook.register("cataclysms", p -> {
            var online = p.getPlayer();
            return online == null ? "0"
                    : String.valueOf(playerDataStore.getInt(online, PlayerDataStore.KEY_CATACLYSMS));
        });

        // %vestigium_boss_kills%   — named boss kills
        PlaceholderAPIHook.register("boss_kills", p -> {
            var online = p.getPlayer();
            return online == null ? "0"
                    : String.valueOf(playerDataStore.getInt(online, PlayerDataStore.KEY_BOSS_KILLS));
        });

        // %vestigium_lore_frags%   — lore fragments collected
        PlaceholderAPIHook.register("lore_frags", p -> {
            var online = p.getPlayer();
            return online == null ? "0"
                    : String.valueOf(playerDataStore.getInt(online, PlayerDataStore.KEY_LORE_FRAGS));
        });

        // %vestigium_playtime%     — playtime in hours (rounded)
        PlaceholderAPIHook.register("playtime", p -> {
            var online = p.getPlayer();
            if (online == null) return "0h";
            long minutes = online.getPersistentDataContainer()
                    .getOrDefault(PlayerDataStore.KEY_PLAYTIME,
                            org.bukkit.persistence.PersistentDataType.LONG, 0L);
            return (minutes / 60) + "h";
        });
    }
}
