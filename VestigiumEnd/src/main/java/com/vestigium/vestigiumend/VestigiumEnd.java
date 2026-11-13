package com.vestigium.vestigiumend;

import com.vestigium.vestigiumend.atmosphere.EndAtmosphereManager;
import com.vestigium.vestigiumend.biome.EndBiomeManager;
import com.vestigium.vestigiumend.corruption.VoidCorruptionManager;
import com.vestigium.vestigiumend.echo.DragonEchoManager;
import com.vestigium.vestigiumend.lore.EndLoreManager;
import com.vestigium.vestigiumend.mob.EndMobManager;
import com.vestigium.vestigiumend.structure.EndStructureManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
    private EndAtmosphereManager   endAtmosphereManager;
    private EndBiomeManager        endBiomeManager;
    private EndStructureManager    endStructureManager;

    @Override
    public void onEnable() {
        instance = this;

        endMobManager         = new EndMobManager(this);
        voidCorruptionManager = new VoidCorruptionManager(this);
        dragonEchoManager     = new DragonEchoManager(this);
        endLoreManager        = new EndLoreManager(this);
        endAtmosphereManager  = new EndAtmosphereManager(this);
        endBiomeManager       = new EndBiomeManager(this);
        endStructureManager   = new EndStructureManager(this);

        endMobManager.init();
        voidCorruptionManager.init();
        dragonEchoManager.init();
        endLoreManager.init();
        endAtmosphereManager.init();
        endBiomeManager.init();
        endStructureManager.init();

        getLogger().info("VestigiumEnd enabled.");
    }

    @Override
    public void onDisable() {
        if (endAtmosphereManager  != null) endAtmosphereManager.shutdown();
        if (endLoreManager        != null) endLoreManager.shutdown();
        if (voidCorruptionManager != null) voidCorruptionManager.shutdown();
        if (dragonEchoManager     != null) dragonEchoManager.shutdown();
        if (endBiomeManager       != null) endBiomeManager.shutdown();
        if (endStructureManager   != null) endStructureManager.shutdown();
        getLogger().info("VestigiumEnd disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("vestructure")) {
            return endStructureManager != null && endStructureManager.handleCommand(sender, args);
        }
        return false;
    }

    public static VestigiumEnd getInstance()                    { return instance; }
    public EndMobManager getEndMobManager()                     { return endMobManager; }
    public VoidCorruptionManager getVoidCorruptionManager()     { return voidCorruptionManager; }
    public DragonEchoManager getDragonEchoManager()             { return dragonEchoManager; }
    public EndBiomeManager getEndBiomeManager()                 { return endBiomeManager; }
    public EndStructureManager getEndStructureManager()         { return endStructureManager; }
}
