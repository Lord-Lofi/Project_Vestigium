package com.vestigium.vestigiumeconomy.currency;

import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import com.vestigium.vestigiumeconomy.economy.VaultHook;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Manages physical Vestige Shard items and the /vecurrency admin command.
 *
 * Vestige Shards are rare loot — not the server's currency. They are amethyst
 * shards tagged with PDC so the server can distinguish them from plain amethyst.
 * Players auto-sell them when interacting with a market (see DynamicMarketManager).
 * Each shard is worth VaultHook.SHARD_VALUE in the Vault economy.
 *
 * Actual currency is handled entirely through VaultHook (Vault soft dependency).
 * /vecurrency wraps Vault balance checks and admin give/take.
 */
public class CurrencyManager implements CommandExecutor {

    public static final NamespacedKey SHARD_ITEM_KEY =
            new NamespacedKey("vestigium", "vestige_shard");

    private final VestigiumEconomy plugin;

    public CurrencyManager(VestigiumEconomy plugin) {
        this.plugin = plugin;
    }

    public void init() {
        var cmd = plugin.getCommand("vecurrency");
        if (cmd != null) cmd.setExecutor(this);
        plugin.getLogger().info("[CurrencyManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Vestige Shard item
    // -------------------------------------------------------------------------

    /** Creates a tagged Vestige Shard item stack. */
    public ItemStack createShardItem(int amount) {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§dVestige Shard");
            meta.setLore(List.of(
                    "§7A fragment of something that once mattered.",
                    "§8Sell at any market for §6" + (int) plugin.getShardValue() + " §8each."
            ));
            meta.getPersistentDataContainer()
                    .set(SHARD_ITEM_KEY, PersistentDataType.BOOLEAN, true);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isShardItem(ItemStack item) {
        if (item == null || item.getItemMeta() == null) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(SHARD_ITEM_KEY, PersistentDataType.BOOLEAN, false);
    }

    /** Counts physical Vestige Shards across the player's entire inventory. */
    public int countShards(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isShardItem(item)) count += item.getAmount();
        }
        return count;
    }

    /**
     * Removes up to {@code amount} Vestige Shards from the player's inventory.
     * Returns the number actually removed.
     */
    public int removeShards(Player player, int amount) {
        int remaining = amount;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
            ItemStack item = inv.getItem(i);
            if (!isShardItem(item)) continue;
            int take = Math.min(item.getAmount(), remaining);
            remaining -= take;
            if (item.getAmount() - take == 0) {
                inv.setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - take);
            }
        }
        return amount - remaining;
    }

    // -------------------------------------------------------------------------
    // /vecurrency — wraps Vault balance + shard count display
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        VaultHook vault = plugin.getVaultHook();
        String sub = args.length > 0 ? args[0].toLowerCase() : "balance";

        switch (sub) {
            case "balance" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cPlayer only."); return true;
                }
                if (vault.isEnabled()) {
                    sender.sendMessage("§6Balance: §f" + vault.format(vault.getBalance(player)));
                } else {
                    sender.sendMessage("§cNo economy provider available.");
                }
                int shards = countShards(player);
                if (shards > 0)
                    sender.sendMessage("§dVestige Shards in inventory: §f" + shards
                            + " §7(worth §6" + vault.format(shards * plugin.getShardValue()) + "§7)");
            }
            case "give", "take" -> {
                if (!sender.hasPermission("vestigium.currency.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (args.length < 3) {
                    sender.sendMessage("§7Usage: /vecurrency " + sub + " <player> <amount>"); return true;
                }
                Player target = plugin.getServer().getPlayer(args[1]);
                if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                if (!vault.isEnabled()) { sender.sendMessage("§cNo economy provider."); return true; }
                double amount;
                try { amount = Double.parseDouble(args[2]); }
                catch (NumberFormatException e) { sender.sendMessage("§cInvalid amount."); return true; }
                if (sub.equals("give")) {
                    vault.deposit(target, amount);
                    sender.sendMessage("§aGave §f" + vault.format(amount) + " §ato §f" + target.getName() + "§a.");
                    target.sendMessage("§aYou received §f" + vault.format(amount) + "§a.");
                } else {
                    vault.withdraw(target, amount);
                    sender.sendMessage("§aRemoved §f" + vault.format(amount) + " §afrom §f" + target.getName() + "§a.");
                }
            }
            case "check" -> {
                if (!sender.hasPermission("vestigium.currency.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§7Usage: /vecurrency check <player>"); return true;
                }
                Player target = plugin.getServer().getPlayer(args[1]);
                if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                if (!vault.isEnabled()) { sender.sendMessage("§cNo economy provider."); return true; }
                sender.sendMessage("§f" + target.getName() + " §7— §6"
                        + vault.format(vault.getBalance(target))
                        + " §7| §d" + countShards(target) + " Vestige Shards");
            }
            default -> sender.sendMessage("§7Usage: /vecurrency <balance|give|take|check>");
        }
        return true;
    }
}
