package com.vestigium.vestigiumocean;

import com.vestigium.vestigiumocean.depth.OceanDepthManager;
import com.vestigium.vestigiumocean.mob.OceanMobManager;
import com.vestigium.vestigiumocean.tidal.TidalEventManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumOcean — ocean biome mob variants, tidal phase events,
 * and depth-pressure effects.
 */
public class VestigiumOcean extends JavaPlugin {

    private static VestigiumOcean instance;

    private OceanMobManager   oceanMobManager;
    private TidalEventManager tidalEventManager;
    private OceanDepthManager oceanDepthManager;

    @Override
    public void onEnable() {
        instance = this;

        oceanMobManager   = new OceanMobManager(this);
        tidalEventManager = new TidalEventManager(this);
        oceanDepthManager = new OceanDepthManager(this);

        oceanMobManager.init();
        tidalEventManager.init();
        oceanDepthManager.init();

        getLogger().info("VestigiumOcean enabled.");
    }

    @Override
    public void onDisable() {
        if (oceanDepthManager != null) oceanDepthManager.shutdown();
        getLogger().info("VestigiumOcean disabled.");
    }

    public static VestigiumOcean getInstance()           { return instance; }
    public OceanMobManager getOceanMobManager()          { return oceanMobManager; }
    public TidalEventManager getTidalEventManager()      { return tidalEventManager; }
    public OceanDepthManager getOceanDepthManager()      { return oceanDepthManager; }
}
