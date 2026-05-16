package com.vestigium.vestigiumnpc;

import com.vestigium.vestigiumnpc.bounty.BountyBoardManager;
import com.vestigium.vestigiumnpc.hostile.DoppelgangerManager;
import com.vestigium.vestigiumnpc.special.SpecialNPCManager;
import com.vestigium.vestigiumnpc.traveling.TravelingNPCBehaviorManager;
import com.vestigium.vestigiumnpc.traveling.TravelingNPCManager;
import com.vestigium.vestigiumnpc.villager.VillagerMemoryManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumNPC — villager memory, traveling NPCs, faction NPCs, hostile special NPCs.
 * Depends only on VestigiumLib. All cross-plugin communication via EventBus.
 */
public class VestigiumNPC extends JavaPlugin {

    private static VestigiumNPC instance;

    private VillagerMemoryManager        villagerMemoryManager;
    private TravelingNPCManager          travelingNPCManager;
    private TravelingNPCBehaviorManager  travelingNPCBehaviorManager;
    private DoppelgangerManager          doppelgangerManager;
    private SpecialNPCManager            specialNPCManager;
    private BountyBoardManager           bountyBoardManager;

    @Override
    public void onEnable() {
        instance = this;

        villagerMemoryManager       = new VillagerMemoryManager(this);
        travelingNPCManager         = new TravelingNPCManager(this);
        travelingNPCBehaviorManager = new TravelingNPCBehaviorManager(this);
        doppelgangerManager         = new DoppelgangerManager(this);
        specialNPCManager           = new SpecialNPCManager(this);
        bountyBoardManager          = new BountyBoardManager(this);

        villagerMemoryManager.init();
        travelingNPCManager.init();
        travelingNPCBehaviorManager.init();
        doppelgangerManager.init();
        specialNPCManager.init();
        bountyBoardManager.init();

        getLogger().info("VestigiumNPC enabled.");
    }

    @Override
    public void onDisable() {
        if (travelingNPCManager         != null) travelingNPCManager.shutdown();
        if (travelingNPCBehaviorManager != null) travelingNPCBehaviorManager.shutdown();
        if (doppelgangerManager         != null) doppelgangerManager.shutdown();
        if (specialNPCManager           != null) specialNPCManager.shutdown();
        if (bountyBoardManager          != null) bountyBoardManager.shutdown();
        getLogger().info("VestigiumNPC disabled.");
    }

    public static VestigiumNPC getInstance()                              { return instance; }
    public VillagerMemoryManager getVillagerMemoryManager()               { return villagerMemoryManager; }
    public TravelingNPCManager getTravelingNPCManager()                   { return travelingNPCManager; }
    public TravelingNPCBehaviorManager getTravelingNPCBehaviorManager()   { return travelingNPCBehaviorManager; }
    public DoppelgangerManager getDoppelgangerManager()                   { return doppelgangerManager; }
    public SpecialNPCManager getSpecialNPCManager()                       { return specialNPCManager; }
    public BountyBoardManager getBountyBoardManager()                     { return bountyBoardManager; }
}
