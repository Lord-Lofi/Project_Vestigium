package com.vestigium.vestigiumeconomy;

import com.vestigium.lib.api.PlaceholderAPIHook;
import com.vestigium.vestigiumeconomy.currency.CurrencyManager;
import com.vestigium.vestigiumeconomy.economy.VaultHook;
import com.vestigium.vestigiumeconomy.market.DynamicMarketManager;
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

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        vaultHook = new VaultHook();
        vaultHook.init(this);

        currencyManager      = new CurrencyManager(this);
        dynamicMarketManager = new DynamicMarketManager(this);
        treasureManager      = new TreasureManager(this);

        currencyManager.init();
        dynamicMarketManager.init();
        treasureManager.init();

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
        getLogger().info("VestigiumEconomy disabled.");
    }

    public static VestigiumEconomy getInstance()             { return instance; }
    public VaultHook getVaultHook()                          { return vaultHook; }
    public double getShardValue()                            { return getConfig().getDouble("vestige-shard-value", 500.0); }
    public CurrencyManager getCurrencyManager()              { return currencyManager; }
    public DynamicMarketManager getDynamicMarketManager()    { return dynamicMarketManager; }
    public TreasureManager getTreasureManager()              { return treasureManager; }
}
