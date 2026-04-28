package com.vestigium.vestigiumeconomy;

import com.vestigium.vestigiumeconomy.currency.CurrencyManager;
import com.vestigium.vestigiumeconomy.market.DynamicMarketManager;
import com.vestigium.vestigiumeconomy.treasure.TreasureManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumEconomy — Vestige Shard custom currency, dynamic faction-driven market
 * pricing, and structure loot tables.
 * Depends only on VestigiumLib.
 */
public class VestigiumEconomy extends JavaPlugin {

    private static VestigiumEconomy instance;

    private CurrencyManager      currencyManager;
    private DynamicMarketManager dynamicMarketManager;
    private TreasureManager      treasureManager;

    @Override
    public void onEnable() {
        instance = this;

        currencyManager      = new CurrencyManager(this);
        dynamicMarketManager = new DynamicMarketManager(this);
        treasureManager      = new TreasureManager(this);

        currencyManager.init();
        dynamicMarketManager.init();
        treasureManager.init();

        getLogger().info("VestigiumEconomy enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("VestigiumEconomy disabled.");
    }

    public static VestigiumEconomy getInstance()             { return instance; }
    public CurrencyManager getCurrencyManager()              { return currencyManager; }
    public DynamicMarketManager getDynamicMarketManager()    { return dynamicMarketManager; }
    public TreasureManager getTreasureManager()              { return treasureManager; }
}
