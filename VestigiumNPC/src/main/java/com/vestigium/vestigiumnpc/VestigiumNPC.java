package com.vestigium.vestigiumnpc;

import com.vestigium.vestigiumnpc.bounty.BountyBoardManager;
import com.vestigium.vestigiumnpc.dialogue.DialogueManager;
import com.vestigium.vestigiumnpc.hostile.DoppelgangerManager;
import com.vestigium.vestigiumnpc.special.SpecialNPCManager;
import com.vestigium.vestigiumnpc.spy.SpyNetworkManager;
import com.vestigium.vestigiumnpc.traveling.TravelingNPCBehaviorManager;
import com.vestigium.vestigiumnpc.traveling.TravelingNPCManager;
import com.vestigium.vestigiumnpc.village.VillagePoliticsManager;
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
    private DialogueManager              dialogueManager;
    private SpyNetworkManager            spyNetworkManager;
    private VillagePoliticsManager       villagePoliticsManager;

    @Override
    public void onEnable() {
        instance = this;

        villagerMemoryManager       = new VillagerMemoryManager(this);
        travelingNPCManager         = new TravelingNPCManager(this);
        travelingNPCBehaviorManager = new TravelingNPCBehaviorManager(this);
        doppelgangerManager         = new DoppelgangerManager(this);
        specialNPCManager           = new SpecialNPCManager(this);
        bountyBoardManager          = new BountyBoardManager(this);
        dialogueManager             = new DialogueManager(this);
        spyNetworkManager           = new SpyNetworkManager(this);
        villagePoliticsManager      = new VillagePoliticsManager(this);

        villagerMemoryManager.init();
        travelingNPCManager.init();
        travelingNPCBehaviorManager.init();
        travelingNPCBehaviorManager.setDialogueManager(dialogueManager);
        doppelgangerManager.init();
        specialNPCManager.init();
        bountyBoardManager.init();
        dialogueManager.init();
        spyNetworkManager.init();
        villagePoliticsManager.init();

        getCommand("vcpolitics").setExecutor(villagePoliticsManager);
        getCommand("vcpolitics").setTabCompleter(villagePoliticsManager);

        getLogger().info("VestigiumNPC enabled.");
    }

    @Override
    public void onDisable() {
        if (travelingNPCManager         != null) travelingNPCManager.shutdown();
        if (travelingNPCBehaviorManager != null) travelingNPCBehaviorManager.shutdown();
        if (doppelgangerManager         != null) doppelgangerManager.shutdown();
        if (specialNPCManager           != null) specialNPCManager.shutdown();
        if (bountyBoardManager          != null) bountyBoardManager.shutdown();
        if (dialogueManager             != null) dialogueManager.shutdown();
        if (villagePoliticsManager      != null) villagePoliticsManager.shutdown();
        getLogger().info("VestigiumNPC disabled.");
    }

    public static VestigiumNPC getInstance()                              { return instance; }
    public VillagerMemoryManager getVillagerMemoryManager()               { return villagerMemoryManager; }
    public TravelingNPCManager getTravelingNPCManager()                   { return travelingNPCManager; }
    public TravelingNPCBehaviorManager getTravelingNPCBehaviorManager()   { return travelingNPCBehaviorManager; }
    public DoppelgangerManager getDoppelgangerManager()                   { return doppelgangerManager; }
    public SpecialNPCManager getSpecialNPCManager()                       { return specialNPCManager; }
    public BountyBoardManager getBountyBoardManager()                     { return bountyBoardManager; }
    public DialogueManager getDialogueManager()                           { return dialogueManager; }
    public SpyNetworkManager getSpyNetworkManager()                       { return spyNetworkManager; }
    public VillagePoliticsManager getVillagePoliticsManager()             { return villagePoliticsManager; }
}
