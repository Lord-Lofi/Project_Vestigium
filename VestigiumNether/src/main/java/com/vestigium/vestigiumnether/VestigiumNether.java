package com.vestigium.vestigiumnether;

import com.vestigium.vestigiumnether.atmosphere.NetherAtmosphereManager;
import com.vestigium.vestigiumnether.mob.NetherMobManager;
import com.vestigium.vestigiumnether.mob.WitherSkeletonBehaviorManager;
import com.vestigium.vestigiumnether.storm.SoulStormManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumNether — nether mob variants, omen-driven nether atmosphere,
 * and soul sand valley storm events.
 */
public class VestigiumNether extends JavaPlugin {

    private static VestigiumNether instance;

    private NetherMobManager              netherMobManager;
    private NetherAtmosphereManager       netherAtmosphereManager;
    private SoulStormManager              soulStormManager;
    private WitherSkeletonBehaviorManager witherBehaviorManager;

    @Override
    public void onEnable() {
        instance = this;

        netherMobManager       = new NetherMobManager(this);
        netherAtmosphereManager = new NetherAtmosphereManager(this);
        soulStormManager        = new SoulStormManager(this);
        witherBehaviorManager   = new WitherSkeletonBehaviorManager(this);

        netherMobManager.init();
        netherAtmosphereManager.init();
        soulStormManager.init();
        witherBehaviorManager.init();

        getLogger().info("VestigiumNether enabled.");
    }

    @Override
    public void onDisable() {
        if (witherBehaviorManager   != null) witherBehaviorManager.shutdown();
        if (netherAtmosphereManager != null) netherAtmosphereManager.shutdown();
        if (soulStormManager        != null) soulStormManager.shutdown();
        getLogger().info("VestigiumNether disabled.");
    }

    public static VestigiumNether getInstance()                    { return instance; }
    public NetherMobManager getNetherMobManager()                  { return netherMobManager; }
    public NetherAtmosphereManager getNetherAtmosphereManager()    { return netherAtmosphereManager; }
    public SoulStormManager getSoulStormManager()                  { return soulStormManager; }
}
