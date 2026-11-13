package com.vestigium.vestigiumeconomy;

import com.vestigium.lib.api.PlaceholderAPIHook;
import com.vestigium.vestigiumeconomy.currency.CurrencyManager;
import com.vestigium.vestigiumeconomy.economy.VaultHook;
import com.vestigium.vestigiumeconomy.forgery.ForgeryManager;
import com.vestigium.vestigiumeconomy.market.DynamicMarketManager;
import com.vestigium.vestigiumeconomy.alchemy.AlchemyManager;
import com.vestigium.vestigiumeconomy.artificer.ArtificerManager;
import com.vestigium.vestigiumeconomy.blackmarket.BlackMarketManager;
import com.vestigium.vestigiumeconomy.ritual.RitualManager;
import com.vestigium.vestigiumeconomy.runic.RuneManager;
import com.vestigium.vestigiumeconomy.specialitems.DivineArtifactManager;
import com.vestigium.vestigiumeconomy.specialitems.RelicManager;
import com.vestigium.vestigiumeconomy.specialitems.ResonantArtifactManager;
import com.vestigium.vestigiumeconomy.specialitems.ShrineBlessingManager;
import com.vestigium.vestigiumeconomy.specialitems.TitanBoneManager;
import com.vestigium.vestigiumeconomy.component.ComponentWeaponManager;
import com.vestigium.vestigiumeconomy.salvage.SalvageManager;
import com.vestigium.vestigiumeconomy.temper.TemperManager;
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

    private VaultHook               vaultHook;
    private CurrencyManager         currencyManager;
    private DynamicMarketManager    dynamicMarketManager;
    private TreasureManager         treasureManager;
    private RuneManager             runeManager;
    private AlchemyManager          alchemyManager;
    private RitualManager           ritualManager;
    private ForgeryManager          forgeryManager;
    private BlackMarketManager      blackMarketManager;
    private ArtificerManager        artificerManager;
    private RelicManager            relicManager;
    private ShrineBlessingManager   shrineManager;
    private TitanBoneManager        titanBoneManager;
    private DivineArtifactManager   divineArtifactManager;
    private ResonantArtifactManager resonantArtifactManager;
    private TemperManager           temperManager;
    private ComponentWeaponManager  componentWeaponManager;
    private SalvageManager          salvageManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        vaultHook = new VaultHook();
        vaultHook.init(this);

        currencyManager         = new CurrencyManager(this);
        dynamicMarketManager    = new DynamicMarketManager(this);
        treasureManager         = new TreasureManager(this);

        runeManager             = new RuneManager(this);
        ritualManager           = new RitualManager(this);
        alchemyManager          = new AlchemyManager(this);
        forgeryManager          = new ForgeryManager(this);
        blackMarketManager      = new BlackMarketManager(this);
        artificerManager        = new ArtificerManager(this);
        relicManager            = new RelicManager(this);
        shrineManager           = new ShrineBlessingManager(this);
        titanBoneManager        = new TitanBoneManager(this);
        divineArtifactManager   = new DivineArtifactManager(this);
        resonantArtifactManager = new ResonantArtifactManager(this);
        temperManager           = new TemperManager(this);
        componentWeaponManager  = new ComponentWeaponManager(this);
        salvageManager          = new SalvageManager(this);

        currencyManager.init();
        dynamicMarketManager.init();
        treasureManager.init();
        runeManager.init();
        ritualManager.init();
        alchemyManager.init();
        forgeryManager.init();
        blackMarketManager.init();
        artificerManager.init();
        relicManager.init();
        shrineManager.init();
        titanBoneManager.init();
        divineArtifactManager.init();
        resonantArtifactManager.init();
        temperManager.init();
        componentWeaponManager.init();
        salvageManager.init();

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
        // ForgeryManager has no background tasks; no shutdown needed
        if (divineArtifactManager   != null) divineArtifactManager.shutdown();
        if (resonantArtifactManager != null) resonantArtifactManager.shutdown();
        getLogger().info("VestigiumEconomy disabled.");
    }

    public static VestigiumEconomy getInstance()                { return instance; }
    public VaultHook getVaultHook()                             { return vaultHook; }
    public double getShardValue()                               { return getConfig().getDouble("vestige-shard-value", 500.0); }
    public CurrencyManager getCurrencyManager()                 { return currencyManager; }
    public DynamicMarketManager getDynamicMarketManager()       { return dynamicMarketManager; }
    public TreasureManager getTreasureManager()                 { return treasureManager; }
    public RuneManager getRuneManager()                         { return runeManager; }
    public RitualManager getRitualManager()                     { return ritualManager; }
    public AlchemyManager getAlchemyManager()                   { return alchemyManager; }
    public ForgeryManager getForgeryManager()                   { return forgeryManager; }
    public BlackMarketManager getBlackMarketManager()           { return blackMarketManager; }
    public ArtificerManager getArtificerManager()               { return artificerManager; }
    public RelicManager getRelicManager()                       { return relicManager; }
    public ShrineBlessingManager getShrineManager()             { return shrineManager; }
    public TitanBoneManager getTitanBoneManager()               { return titanBoneManager; }
    public DivineArtifactManager getDivineArtifactManager()     { return divineArtifactManager; }
    public ResonantArtifactManager getResonantArtifactManager() { return resonantArtifactManager; }
    public TemperManager getTemperManager()                     { return temperManager; }
    public ComponentWeaponManager getComponentWeaponManager()   { return componentWeaponManager; }
    public SalvageManager getSalvageManager()                   { return salvageManager; }
}
