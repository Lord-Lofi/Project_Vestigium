package com.vestigium.vestigiumcaves;

import com.vestigium.vestigiumcaves.atmosphere.CaveAtmosphereManager;
import com.vestigium.vestigiumcaves.event.CaveEventManager;
import com.vestigium.vestigiumcaves.mob.CaveMobManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumCaves — deep dark atmosphere, cave-biome mob variants,
 * and omen-driven underground events.
 */
public class VestigiumCaves extends JavaPlugin {

    private static VestigiumCaves instance;

    private CaveAtmosphereManager caveAtmosphereManager;
    private CaveMobManager        caveMobManager;
    private CaveEventManager      caveEventManager;

    @Override
    public void onEnable() {
        instance = this;

        caveAtmosphereManager = new CaveAtmosphereManager(this);
        caveMobManager        = new CaveMobManager(this);
        caveEventManager      = new CaveEventManager(this);

        caveAtmosphereManager.init();
        caveMobManager.init();
        caveEventManager.init();

        getLogger().info("VestigiumCaves enabled.");
    }

    @Override
    public void onDisable() {
        if (caveAtmosphereManager != null) caveAtmosphereManager.shutdown();
        if (caveEventManager      != null) caveEventManager.shutdown();
        getLogger().info("VestigiumCaves disabled.");
    }

    public static VestigiumCaves getInstance()               { return instance; }
    public CaveAtmosphereManager getCaveAtmosphereManager()  { return caveAtmosphereManager; }
    public CaveMobManager getCaveMobManager()                { return caveMobManager; }
    public CaveEventManager getCaveEventManager()            { return caveEventManager; }
}
