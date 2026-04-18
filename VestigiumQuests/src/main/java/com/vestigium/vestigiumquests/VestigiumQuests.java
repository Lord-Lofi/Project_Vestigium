package com.vestigium.vestigiumquests;

import com.vestigium.vestigiumquests.dispatch.FactionQuestDispenser;
import com.vestigium.vestigiumquests.registry.QuestRegistry;
import com.vestigium.vestigiumquests.reward.QuestRewardManager;
import com.vestigium.vestigiumquests.tracker.QuestTracker;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumQuests — YAML-driven quest framework, faction quest dispatch,
 * per-player progress tracking, and reward delivery.
 * Depends only on VestigiumLib.
 */
public class VestigiumQuests extends JavaPlugin {

    private static VestigiumQuests instance;

    private QuestRegistry       questRegistry;
    private QuestTracker        questTracker;
    private FactionQuestDispenser factionQuestDispenser;
    private QuestRewardManager  questRewardManager;

    @Override
    public void onEnable() {
        instance = this;

        questRegistry         = new QuestRegistry(this);
        questRewardManager    = new QuestRewardManager(this);
        questTracker          = new QuestTracker(this, questRegistry, questRewardManager);
        factionQuestDispenser = new FactionQuestDispenser(this, questRegistry, questTracker);

        questRegistry.load();
        questTracker.init();
        factionQuestDispenser.init();

        getLogger().info("VestigiumQuests enabled.");
    }

    @Override
    public void onDisable() {
        if (questTracker != null) questTracker.saveAll();
        getLogger().info("VestigiumQuests disabled.");
    }

    public static VestigiumQuests getInstance()                  { return instance; }
    public QuestRegistry getQuestRegistry()                      { return questRegistry; }
    public QuestTracker getQuestTracker()                        { return questTracker; }
    public FactionQuestDispenser getFactionQuestDispenser()      { return factionQuestDispenser; }
    public QuestRewardManager getQuestRewardManager()            { return questRewardManager; }
}
