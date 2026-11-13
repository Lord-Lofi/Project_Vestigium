package com.vestigium.vestigiumeconomy.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Soft-dependency wrapper around Vault's Economy service.
 *
 * All market transactions and balance queries route through this class.
 * When Vault or an economy provider is absent, isEnabled() returns false
 * and every method either returns a safe default or does nothing.
 */
public class VaultHook {

    private Economy economy;
    private boolean enabled = false;

    public void init(Plugin plugin) {
        Plugin vaultPlugin = plugin.getServer().getPluginManager().getPlugin("Vault");
        if (vaultPlugin == null || !vaultPlugin.isEnabled()) {
            plugin.getLogger().warning("[VaultHook] Vault not found — market transactions disabled.");
            return;
        }
        RegisteredServiceProvider<Economy> rsp =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            plugin.getLogger().warning("[VaultHook] No economy provider registered with Vault.");
            return;
        }
        economy = rsp.getProvider();
        enabled = true;
        plugin.getLogger().info("[VaultHook] Economy provider: " + economy.getName());
    }

    public boolean isEnabled() { return enabled; }

    public double getBalance(Player player) {
        return enabled ? economy.getBalance(player) : 0;
    }

    public boolean has(Player player, double amount) {
        return enabled && economy.has(player, amount);
    }

    public boolean deposit(Player player, double amount) {
        return enabled && economy.depositPlayer(player, amount).transactionSuccess();
    }

    public boolean withdraw(Player player, double amount) {
        return enabled && economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    /** Returns the economy-formatted amount string (e.g. "$50.00" or "50 Gold"). */
    public String format(double amount) {
        return enabled ? economy.format(amount) : String.format("%.0f coins", amount);
    }

    public String currencyNameSingular() {
        return enabled ? economy.currencyNameSingular() : "coin";
    }

    public String currencyNamePlural() {
        return enabled ? economy.currencyNamePlural() : "coins";
    }
}
