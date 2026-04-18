package com.vestigium.vestigiumworld;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumworld.cataclysm.CataclysmManager;
import com.vestigium.vestigiumworld.ecology.EcologicalMemoryManager;
import com.vestigium.vestigiumworld.living.LivingWorldManager;
import com.vestigium.vestigiumworld.village.VillageExpansionManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumWorld — cataclysmic events, living world behaviours, ecological memory.
 * Depends only on VestigiumLib. All world modification calls ProtectionAPI first.
 * All cross-plugin output fires through VestigiumLib.getEventBus().
 */
public class VestigiumWorld extends JavaPlugin {

    private static VestigiumWorld instance;

    private CataclysmManager cataclysmManager;
    private LivingWorldManager livingWorldManager;
    private EcologicalMemoryManager ecologicalMemoryManager;
    private VillageExpansionManager villageExpansionManager;

    @Override
    public void onEnable() {
        instance = this;

        cataclysmManager      = new CataclysmManager(this);
        livingWorldManager    = new LivingWorldManager(this);
        ecologicalMemoryManager = new EcologicalMemoryManager(this);
        villageExpansionManager = new VillageExpansionManager(this);

        cataclysmManager.init();
        livingWorldManager.init();
        ecologicalMemoryManager.init();
        villageExpansionManager.init();

        getLogger().info("VestigiumWorld enabled.");
    }

    @Override
    public void onDisable() {
        if (cataclysmManager      != null) cataclysmManager.shutdown();
        if (livingWorldManager    != null) livingWorldManager.shutdown();
        if (ecologicalMemoryManager != null) ecologicalMemoryManager.shutdown();
        if (villageExpansionManager != null) villageExpansionManager.shutdown();
        getLogger().info("VestigiumWorld disabled.");
    }

    public static VestigiumWorld getInstance()             { return instance; }
    public CataclysmManager getCataclysmManager()          { return cataclysmManager; }
    public LivingWorldManager getLivingWorldManager()      { return livingWorldManager; }
    public EcologicalMemoryManager getEcologicalMemoryManager() { return ecologicalMemoryManager; }
    public VillageExpansionManager getVillageExpansionManager() { return villageExpansionManager; }
}
