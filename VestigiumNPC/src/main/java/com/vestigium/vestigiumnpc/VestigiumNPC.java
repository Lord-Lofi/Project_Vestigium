package com.vestigium.vestigiumnpc;

import com.vestigium.vestigiumnpc.hostile.DoppelgangerManager;
import com.vestigium.vestigiumnpc.special.SpecialNPCManager;
import com.vestigium.vestigiumnpc.traveling.TravelingNPCManager;
import com.vestigium.vestigiumnpc.villager.VillagerMemoryManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumNPC — villager memory, traveling NPCs, faction NPCs, hostile special NPCs.
 * Depends only on VestigiumLib. All cross-plugin communication via EventBus.
 */
public class VestigiumNPC extends JavaPlugin {

    private static VestigiumNPC instance;

    private VillagerMemoryManager villagerMemoryManager;
    private TravelingNPCManager   travelingNPCManager;
    private DoppelgangerManager   doppelgangerManager;
    private SpecialNPCManager     specialNPCManager;

    @Override
    public void onEnable() {
        instance = this;

        villagerMemoryManager = new VillagerMemoryManager(this);
        travelingNPCManager   = new TravelingNPCManager(this);
        doppelgangerManager   = new DoppelgangerManager(this);
        specialNPCManager     = new SpecialNPCManager(this);

        villagerMemoryManager.init();
        travelingNPCManager.init();
        doppelgangerManager.init();
        specialNPCManager.init();

        getLogger().info("VestigiumNPC enabled.");
    }

    @Override
    public void onDisable() {
        if (travelingNPCManager != null) travelingNPCManager.shutdown();
        if (doppelgangerManager != null) doppelgangerManager.shutdown();
        if (specialNPCManager   != null) specialNPCManager.shutdown();
        getLogger().info("VestigiumNPC disabled.");
    }

    public static VestigiumNPC getInstance()                   { return instance; }
    public VillagerMemoryManager getVillagerMemoryManager()    { return villagerMemoryManager; }
    public TravelingNPCManager getTravelingNPCManager()        { return travelingNPCManager; }
    public DoppelgangerManager getDoppelgangerManager()        { return doppelgangerManager; }
    public SpecialNPCManager getSpecialNPCManager()            { return specialNPCManager; }
}
