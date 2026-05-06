package com.vestigium.vestigiumend;

import com.vestigium.vestigiumend.corruption.VoidCorruptionManager;
import com.vestigium.vestigiumend.echo.DragonEchoManager;
import com.vestigium.vestigiumend.lore.EndLoreManager;
import com.vestigium.vestigiumend.mob.EndMobManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumEnd — end biome mob variants, void corruption escalation,
 * and dragon echo events.
 */
public class VestigiumEnd extends JavaPlugin {

    private static VestigiumEnd instance;

    private EndMobManager          endMobManager;
    private VoidCorruptionManager  voidCorruptionManager;
    private DragonEchoManager      dragonEchoManager;
    private EndLoreManager         endLoreManager;

    @Override
    public void onEnable() {
        instance = this;

        endMobManager         = new EndMobManager(this);
        voidCorruptionManager = new VoidCorruptionManager(this);
        dragonEchoManager     = new DragonEchoManager(this);
        endLoreManager        = new EndLoreManager(this);

        endMobManager.init();
        voidCorruptionManager.init();
        dragonEchoManager.init();
        endLoreManager.init();

        getLogger().info("VestigiumEnd enabled.");
    }

    @Override
    public void onDisable() {
        if (endLoreManager        != null) endLoreManager.shutdown();
        if (voidCorruptionManager != null) voidCorruptionManager.shutdown();
        if (dragonEchoManager     != null) dragonEchoManager.shutdown();
        getLogger().info("VestigiumEnd disabled.");
    }

    public static VestigiumEnd getInstance()                    { return instance; }
    public EndMobManager getEndMobManager()                     { return endMobManager; }
    public VoidCorruptionManager getVoidCorruptionManager()     { return voidCorruptionManager; }
    public DragonEchoManager getDragonEchoManager()             { return dragonEchoManager; }
}
