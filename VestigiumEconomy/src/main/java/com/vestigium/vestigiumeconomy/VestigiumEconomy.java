package com.vestigium.vestigiumeconomy;

import com.vestigium.lib.api.PlaceholderAPIHook;
import com.vestigium.vestigiumeconomy.currency.CurrencyManager;
import com.vestigium.vestigiumeconomy.economy.VaultHook;
import com.vestigium.vestigiumeconomy.market.DynamicMarketManager;
import com.vestigium.vestigiumeconomy.alchemy.AlchemyManager;
import com.vestigium.vestigiumeconomy.ritual.RitualManager;
import com.vestigium.vestigiumeconomy.runic.RuneManager;
import com.vestigium.vestigiumeconomy.specialitems.DivineArtifactManager;
import com.vestigium.vestigiumeconomy.specialitems.RelicManager;
import com.vestigium.vestigiumeconomy.specialitems.ResonantArtifactManager;
import com.vestigium.vestigiumeconomy.specialitems.ShrineBlessingManager;
import com.vestigium.vestigiumeconomy.specialitems.TitanBoneManager;
import com.vestigium.vestigiumeconomy.treasure.TreasureManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumEconomy — Vestige Shard custom currency, dynamic faction-driven market
 * pricing, and structure loot tables.
 * Depends only on VestigiumLib.
 */
public class VestigiumEconomy extends JavaPlugin {

    private static VestigiumEconomy instance;

    private VaultHook            vaultHook;
    private CurrencyManager      currencyManager;
    private DynamicMarketManager dynamicMarketManager;
    private TreasureManager      treasureManager;
    private RuneManager             runeManager;
    private AlchemyManager          alchemyManager;
    private RitualManager           ritualManager;
    private RelicManager            relicManager;
    private ShrineBlessingManager   shrineManager;
    private TitanBoneManager        titanBoneManager;
    private DivineArtifactManager   divineArtifactManager;
    private ResonantArtifactManager resonantArtifactManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        vaultHook = new VaultHook();
        vaultHook.init(this);

        currencyManager      = new CurrencyManager(this);
        dynamicMarketManager = new DynamicMarketManager(this);
        treasureManager      = new TreasureManager(this);

        runeManager             = new RuneManager(this);
        ritualManager           = new RitualManager(this);
        alchemyManager          = new AlchemyManager(this);
        relicManager            = new RelicManager(this);
        shrineManager           = new ShrineBlessingManager(this);
        titanBoneManager        = new TitanBoneManager(this);
        divineArtifactManager   = new DivineArtifactManager(this);
        resonantArtifactManager = new ResonantArtifactManager(this);

        currencyManager.init();
        dynamicMarketManager.init();
        treasureManager.init();
        runeManager.init();
        ritualManager.init();
        alchemyManager.init();
        relicManager.init();
        shrineManager.init();
        titanBoneManager.init();
        divineArtifactManager.init();
        resonantArtifactManager.init();

        var vebuy = getCommand("vebuy");
        if (vebuy != null) vebuy.setExecutor(dynamicMarketManager);

        // Register %vestigium_balance% and %vestigium_vestige_shards% with PAPI
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            PlaceholderAPIHook.register("balance", p -> {
                if (p == null || !vaultHook.isEnabled()) return "0";
                Player online = p.getPlayer();
                return online == null ? "0" : vaultHook.format(vaultHook.getBalance(online));
            });
            PlaceholderAPIHook.register("vestige_shards", p -> {
                if (p == null) return "0";
                Player online = p.getPlayer();
                return online == null ? "0" : String.valueOf(currencyManager.countShards(online));
            });
        }

        getLogger().info("VestigiumEconomy enabled.");
    }

    @Override
    public void onDisable() {
        if (dynamicMarketManager    != null) dynamicMarketManager.shutdown();
        if (runeManager             != null) runeManager.shutdown();
        if (ritualManager           != null) ritualManager.shutdown();
        if (alchemyManager          != null) alchemyManager.shutdown();
        if (divineArtifactManager   != null) divineArtifactManager.shutdown();
        if (resonantArtifactManager != null) resonantArtifactManager.shutdown();
        getLogger().info("VestigiumEconomy disabled.");
    }

    public static VestigiumEconomy getInstance()             { return instance; }
    public VaultHook getVaultHook()                          { return vaultHook; }
    public double getShardValue()                            { return getConfig().getDouble("vestige-shard-value", 500.0); }
    public CurrencyManager getCurrencyManager()              { return currencyManager; }
    public DynamicMarketManager getDynamicMarketManager()    { return dynamicMarketManager; }
    public TreasureManager getTreasureManager()              { return treasureManager; }
    public RuneManager getRuneManager()                              { return runeManager; }
    public RitualManager getRitualManager()                          { return ritualManager; }
    public AlchemyManager getAlchemyManager()                        { return alchemyManager; }
    public RelicManager getRelicManager()                            { return relicManager; }
    public ShrineBlessingManager getShrineManager()                  { return shrineManager; }
    public TitanBoneManager getTitanBoneManager()                    { return titanBoneManager; }
    public DivineArtifactManager getDivineArtifactManager()          { return divineArtifactManager; }
    public ResonantArtifactManager getResonantArtifactManager()      { return resonantArtifactManager; }
}
