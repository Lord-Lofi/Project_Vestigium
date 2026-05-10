package com.vestigium.vestigiummobs;

import com.vestigium.vestigiummobs.boss.LeviathanManager;
import com.vestigium.vestigiummobs.boss.SunkenGodManager;
import com.vestigium.vestigiummobs.passive.MythicBeastManager;
import com.vestigium.vestigiummobs.passive.ShadowFaunaManager;
import com.vestigium.vestigiummobs.passive.TheWormManager;
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
import com.vestigium.vestigiummobs.wildlife.TerritorialWildlifeManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumMobs — custom mob definitions, rare variants, territorial wildlife,
 * the minion system, and named Wardens.
 * Depends only on VestigiumLib.
 */
public class VestigiumMobs extends JavaPlugin {

    private static VestigiumMobs instance;

    private CustomHostileMobManager    hostileMobManager;
    private HollowKnightManager        hollowKnightManager;
    private FenWitchManager            fenWitchManager;
    private EchoBeastManager           echoBeastManager;
    private TideLurkerManager          tideLurkerManager;
    private ThornbackManager           thornbackManager;
    private PassiveMobManager          passiveMobManager;
    private TerritorialWildlifeManager wildlifeManager;
    private MinionSystem               minionSystem;
    private PlayerMinionManager        playerMinionManager;
    private NamedWardenManager         namedWardenManager;
    private LeviathanManager           leviathanManager;
    private SunkenGodManager           sunkenGodManager;
    private TheWormManager             theWormManager;
    private ShadowFaunaManager         shadowFaunaManager;
    private MythicBeastManager         mythicBeastManager;

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
        wildlifeManager    = new TerritorialWildlifeManager(this);
        minionSystem        = new MinionSystem(this);
        playerMinionManager = new PlayerMinionManager(this);
        namedWardenManager  = new NamedWardenManager(this);
        leviathanManager    = new LeviathanManager(this);
        sunkenGodManager    = new SunkenGodManager(this);
        theWormManager      = new TheWormManager(this);
        shadowFaunaManager  = new ShadowFaunaManager(this);
        mythicBeastManager  = new MythicBeastManager(this);

        hostileMobManager.init();
        hollowKnightManager.init();
        fenWitchManager.init();
        echoBeastManager.init();
        tideLurkerManager.init();
        thornbackManager.init();
        passiveMobManager.init();
        wildlifeManager.init();
        minionSystem.init();
        playerMinionManager.init();
        namedWardenManager.init();
        leviathanManager.init();
        sunkenGodManager.init();
        theWormManager.init();
        shadowFaunaManager.init();
        mythicBeastManager.init();

        var bossCmd = getCommand("vcboss");
        if (bossCmd != null) bossCmd.setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("vestigium.boss.admin")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            if (args.length < 2 || !args[0].equalsIgnoreCase("spawn")
                    || !(sender instanceof Player p)) {
                sender.sendMessage("§7Usage: /vcboss spawn <leviathan|sunken_god>");
                return true;
            }
            switch (args[1].toLowerCase()) {
                case "leviathan"  -> { leviathanManager.spawn(p.getLocation());
                                       sender.sendMessage("§3Leviathan spawned."); }
                case "sunken_god" -> { sunkenGodManager.spawn(p.getLocation());
                                       sender.sendMessage("§5Sunken God spawned."); }
                default           -> sender.sendMessage("§cUnknown boss. Use leviathan or sunken_god.");
            }
            return true;
        });

        getLogger().info("VestigiumMobs enabled.");
    }

    @Override
    public void onDisable() {
        if (echoBeastManager   != null) echoBeastManager.shutdown();
        if (tideLurkerManager  != null) tideLurkerManager.shutdown();
        if (passiveMobManager  != null) passiveMobManager.shutdown();
        if (wildlifeManager    != null) wildlifeManager.shutdown();
        if (namedWardenManager != null) namedWardenManager.shutdown();
        if (playerMinionManager != null) playerMinionManager.shutdown();
        if (minionSystem        != null) minionSystem.saveAll();
        if (leviathanManager    != null) leviathanManager.shutdown();
        if (sunkenGodManager    != null) sunkenGodManager.shutdown();
        if (theWormManager      != null) theWormManager.shutdown();
        if (shadowFaunaManager  != null) shadowFaunaManager.shutdown();
        getLogger().info("VestigiumMobs disabled.");
    }

    public static VestigiumMobs getInstance()                  { return instance; }
    public CustomHostileMobManager getHostileMobManager()      { return hostileMobManager; }
    public PassiveMobManager getPassiveMobManager()            { return passiveMobManager; }
    public MinionSystem getMinionSystem()                      { return minionSystem; }
    public NamedWardenManager getNamedWardenManager()          { return namedWardenManager; }
    public LeviathanManager getLeviathanManager()              { return leviathanManager; }
    public SunkenGodManager getSunkenGodManager()              { return sunkenGodManager; }
}
