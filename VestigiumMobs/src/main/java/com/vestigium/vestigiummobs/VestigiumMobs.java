package com.vestigium.vestigiummobs;

import com.vestigium.vestigiummobs.hostile.CustomHostileMobManager;
import com.vestigium.vestigiummobs.hostile.EchoBeastManager;
import com.vestigium.vestigiummobs.hostile.FenWitchManager;
import com.vestigium.vestigiummobs.hostile.HollowKnightManager;
import com.vestigium.vestigiummobs.hostile.ThornbackManager;
import com.vestigium.vestigiummobs.hostile.TideLurkerManager;
import com.vestigium.vestigiummobs.minion.MinionSystem;
import com.vestigium.vestigiummobs.minion.PlayerMinionManager;
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
    private HollowKnightManager     hollowKnightManager;
    private FenWitchManager         fenWitchManager;
    private EchoBeastManager        echoBeastManager;
    private TideLurkerManager       tideLurkerManager;
    private ThornbackManager        thornbackManager;
    private PassiveMobManager       passiveMobManager;
    private MinionSystem            minionSystem;
    private PlayerMinionManager     playerMinionManager;
    private NamedWardenManager      namedWardenManager;

    @Override
    public void onEnable() {
        instance = this;

        hostileMobManager  = new CustomHostileMobManager(this);
        hollowKnightManager = new HollowKnightManager(this);
        fenWitchManager    = new FenWitchManager(this);
        echoBeastManager   = new EchoBeastManager(this);
        tideLurkerManager  = new TideLurkerManager(this);
        thornbackManager   = new ThornbackManager(this);
        passiveMobManager  = new PassiveMobManager(this);
        minionSystem        = new MinionSystem(this);
        playerMinionManager = new PlayerMinionManager(this);
        namedWardenManager  = new NamedWardenManager(this);

        hostileMobManager.init();
        hollowKnightManager.init();
        fenWitchManager.init();
        echoBeastManager.init();
        tideLurkerManager.init();
        thornbackManager.init();
        passiveMobManager.init();
        minionSystem.init();
        playerMinionManager.init();
        namedWardenManager.init();

        getLogger().info("VestigiumMobs enabled.");
    }

    @Override
    public void onDisable() {
        if (echoBeastManager   != null) echoBeastManager.shutdown();
        if (tideLurkerManager  != null) tideLurkerManager.shutdown();
        if (passiveMobManager  != null) passiveMobManager.shutdown();
        if (namedWardenManager != null) namedWardenManager.shutdown();
        if (playerMinionManager != null) playerMinionManager.shutdown();
        if (minionSystem        != null) minionSystem.saveAll();
        getLogger().info("VestigiumMobs disabled.");
    }

    public static VestigiumMobs getInstance()                  { return instance; }
    public CustomHostileMobManager getHostileMobManager()      { return hostileMobManager; }
    public PassiveMobManager getPassiveMobManager()            { return passiveMobManager; }
    public MinionSystem getMinionSystem()                      { return minionSystem; }
    public NamedWardenManager getNamedWardenManager()          { return namedWardenManager; }
}
