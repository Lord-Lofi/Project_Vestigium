package com.vestigium.vestigiumcombat;

import com.vestigium.vestigiumcombat.combo.ComboSystem;
import com.vestigium.vestigiumcombat.status.CustomStatusEffectManager;
import com.vestigium.vestigiumcombat.tracker.CombatTracker;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumCombat — combo system, custom status effects, and combat state tracking.
 * Depends only on VestigiumLib.
 */
public class VestigiumCombat extends JavaPlugin {

    private static VestigiumCombat instance;

    private CombatTracker             combatTracker;
    private ComboSystem               comboSystem;
    private CustomStatusEffectManager statusEffectManager;

    @Override
    public void onEnable() {
        instance = this;

        combatTracker       = new CombatTracker(this);
        comboSystem         = new ComboSystem(this, combatTracker);
        statusEffectManager = new CustomStatusEffectManager(this);

        combatTracker.init();
        comboSystem.init();
        statusEffectManager.init();

        getLogger().info("VestigiumCombat enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("VestigiumCombat disabled.");
    }

    public static VestigiumCombat getInstance()                  { return instance; }
    public CombatTracker getCombatTracker()                      { return combatTracker; }
    public ComboSystem getComboSystem()                          { return comboSystem; }
    public CustomStatusEffectManager getStatusEffectManager()    { return statusEffectManager; }
}
