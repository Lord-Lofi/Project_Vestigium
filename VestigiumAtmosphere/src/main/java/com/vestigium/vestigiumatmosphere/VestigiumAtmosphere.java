package com.vestigium.vestigiumatmosphere;

import com.vestigium.vestigiumatmosphere.ambient.AmbientParticleEngine;
import com.vestigium.vestigiumatmosphere.sky.SkyEventManager;
import com.vestigium.vestigiumatmosphere.weather.WeatherEventManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumAtmosphere — custom weather events, ambient particle atmosphere,
 * and sky events (meteors, aurora, eclipse, blood moon).
 * Depends only on VestigiumLib.
 */
public class VestigiumAtmosphere extends JavaPlugin {

    private static VestigiumAtmosphere instance;

    private WeatherEventManager  weatherEventManager;
    private AmbientParticleEngine ambientParticleEngine;
    private SkyEventManager       skyEventManager;

    @Override
    public void onEnable() {
        instance = this;

        weatherEventManager  = new WeatherEventManager(this);
        ambientParticleEngine = new AmbientParticleEngine(this);
        skyEventManager       = new SkyEventManager(this);

        weatherEventManager.init();
        ambientParticleEngine.init();
        skyEventManager.init();

        getLogger().info("VestigiumAtmosphere enabled.");
    }

    @Override
    public void onDisable() {
        if (weatherEventManager  != null) weatherEventManager.shutdown();
        if (ambientParticleEngine != null) ambientParticleEngine.shutdown();
        if (skyEventManager       != null) skyEventManager.shutdown();
        getLogger().info("VestigiumAtmosphere disabled.");
    }

    public static VestigiumAtmosphere getInstance()           { return instance; }
    public WeatherEventManager getWeatherEventManager()       { return weatherEventManager; }
    public AmbientParticleEngine getAmbientParticleEngine()   { return ambientParticleEngine; }
    public SkyEventManager getSkyEventManager()               { return skyEventManager; }
}
