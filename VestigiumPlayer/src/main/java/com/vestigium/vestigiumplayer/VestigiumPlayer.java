package com.vestigium.vestigiumplayer;

import com.vestigium.vestigiumplayer.data.PlayerDataStore;
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

    @Override
    public void onEnable() {
        instance = this;

        playerDataStore   = new PlayerDataStore(this);
        playerStatTracker = new PlayerStatTracker(this, playerDataStore);
        titleManager      = new TitleManager(this, playerDataStore);

        playerDataStore.init();
        playerStatTracker.init();
        titleManager.init();

        getLogger().info("VestigiumPlayer enabled.");
    }

    @Override
    public void onDisable() {
        if (playerDataStore != null) playerDataStore.saveAll();
        getLogger().info("VestigiumPlayer disabled.");
    }

    public static VestigiumPlayer getInstance()          { return instance; }
    public PlayerDataStore getPlayerDataStore()          { return playerDataStore; }
    public PlayerStatTracker getPlayerStatTracker()      { return playerStatTracker; }
    public TitleManager getTitleManager()                { return titleManager; }
}
