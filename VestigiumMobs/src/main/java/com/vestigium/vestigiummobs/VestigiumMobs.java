package com.vestigium.vestigiummobs;

import com.vestigium.vestigiummobs.hostile.CustomHostileMobManager;
import com.vestigium.vestigiummobs.minion.MinionSystem;
import com.vestigium.vestigiummobs.passive.PassiveMobManager;
import com.vestigium.vestigiummobs.warden.NamedWardenManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumMobs — custom mob definitions, rare variants, territorial wildlife,
 * the minion system, and named Wardens.
 * Depends only on VestigiumLib.
 */
public class VestigiumMobs extends JavaPlugin {

    private static VestigiumMobs instance;

    private CustomHostileMobManager hostileMobManager;
    private PassiveMobManager       passiveMobManager;
    private MinionSystem            minionSystem;
    private NamedWardenManager      namedWardenManager;

    @Override
    public void onEnable() {
        instance = this;

        hostileMobManager  = new CustomHostileMobManager(this);
        passiveMobManager  = new PassiveMobManager(this);
        minionSystem       = new MinionSystem(this);
        namedWardenManager = new NamedWardenManager(this);

        hostileMobManager.init();
        passiveMobManager.init();
        minionSystem.init();
        namedWardenManager.init();

        getLogger().info("VestigiumMobs enabled.");
    }

    @Override
    public void onDisable() {
        if (passiveMobManager  != null) passiveMobManager.shutdown();
        if (namedWardenManager != null) namedWardenManager.shutdown();
        if (minionSystem       != null) minionSystem.saveAll();
        getLogger().info("VestigiumMobs disabled.");
    }

    public static VestigiumMobs getInstance()                  { return instance; }
    public CustomHostileMobManager getHostileMobManager()      { return hostileMobManager; }
    public PassiveMobManager getPassiveMobManager()            { return passiveMobManager; }
    public MinionSystem getMinionSystem()                      { return minionSystem; }
    public NamedWardenManager getNamedWardenManager()          { return namedWardenManager; }
}
