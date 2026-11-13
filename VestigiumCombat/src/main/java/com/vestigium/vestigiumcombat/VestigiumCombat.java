package com.vestigium.vestigiumcombat;

import com.vestigium.vestigiumcombat.boss.BossFightManager;
import com.vestigium.vestigiumcombat.combo.ComboSystem;
import com.vestigium.vestigiumcombat.parry.ParrySystem;
import com.vestigium.vestigiumcombat.status.CustomStatusEffectManager;
import com.vestigium.vestigiumcombat.tracker.CombatTracker;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumCombat — combo system, custom status effects, and combat state tracking.
 * Depends only on VestigiumLib.
 */
public class VestigiumCombat extends JavaPlugin {

    private static VestigiumCombat instance;

    private CombatTracker             combatTracker;
    private ComboSystem               comboSystem;
    private ParrySystem               parrySystem;
    private BossFightManager          bossFightManager;
    private CustomStatusEffectManager statusEffectManager;

    @Override
    public void onEnable() {
        instance = this;

        combatTracker       = new CombatTracker(this);
        comboSystem         = new ComboSystem(this, combatTracker);
        parrySystem         = new ParrySystem(this);
        bossFightManager    = new BossFightManager(this);
        statusEffectManager = new CustomStatusEffectManager(this);

        combatTracker.init();
        comboSystem.init();
        parrySystem.init();
        bossFightManager.init();
        statusEffectManager.init();

        getLogger().info("VestigiumCombat enabled.");
    }

    @Override
    public void onDisable() {
        if (combatTracker       != null) combatTracker.shutdown();
        if (statusEffectManager != null) statusEffectManager.shutdown();
        getLogger().info("VestigiumCombat disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("vcombat")) return false;
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cThis command is player-only.");
            return true;
        }
        if (!p.hasPermission("vestigium.combat.use")) {
            p.sendMessage("§cYou do not have permission.");
            return true;
        }
        int combo = combatTracker.getComboCount(p);
        boolean inCombat = combatTracker.isInCombat(p);
        p.sendMessage("§7--- §6Combat Stats §7---");
        p.sendMessage("§7Combo: §e" + combo);
        p.sendMessage("§7In combat: §e" + (inCombat ? "§ayes" : "§7no"));
        return true;
    }

    public static VestigiumCombat getInstance()                  { return instance; }
    public CombatTracker getCombatTracker()                      { return combatTracker; }
    public ComboSystem getComboSystem()                          { return comboSystem; }
    public ParrySystem getParrySystem()                          { return parrySystem; }
    public BossFightManager getBossFightManager()                { return bossFightManager; }
    public CustomStatusEffectManager getStatusEffectManager()    { return statusEffectManager; }
}
