package com.vestigium.vestigiumeconomy.currency;

import com.vestigium.vestigiumeconomy.VestigiumEconomy;
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
 * Vestige Shards — the custom currency of the Vestigium economy.
 *
 * Storage: Player PDC key "vestigium:ve_shards" (INTEGER).
 * Physical item: AMETHYST_SHARD tagged with "vestigium:vestige_shard" PDC BOOLEAN.
 *
 * Players can hold shards as physical items (auto-absorbed on interact with market)
 * or as a pure PDC balance. The two are kept in sync on player join/quit.
 *
 * Commands:
 *   /vecurrency give <player> <amount>   — admin give
 *   /vecurrency take <player> <amount>   — admin take
 *   /vecurrency check [player]           — check balance
 *   /vecurrency balance                  — self balance
 */
public class CurrencyManager implements CommandExecutor {

    public static final NamespacedKey BALANCE_KEY =
            new NamespacedKey("vestigium", "ve_shards");
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
    // Public API
    // -------------------------------------------------------------------------

    public int getBalance(Player player) {
        return player.getPersistentDataContainer()
                .getOrDefault(BALANCE_KEY, PersistentDataType.INTEGER, 0);
    }

    public void addShards(Player player, int amount) {
        if (amount <= 0) return;
        int current = getBalance(player);
        player.getPersistentDataContainer()
                .set(BALANCE_KEY, PersistentDataType.INTEGER, current + amount);
    }

    /**
     * Attempts to spend shards. Returns false if insufficient balance.
     */
    public boolean spendShards(Player player, int amount) {
        if (amount <= 0) return true;
        int current = getBalance(player);
        if (current < amount) return false;
        player.getPersistentDataContainer()
                .set(BALANCE_KEY, PersistentDataType.INTEGER, current - amount);
        return true;
    }

    public boolean hasShards(Player player, int amount) {
        return getBalance(player) >= amount;
    }

    /** Creates a physical Vestige Shard item. */
    public ItemStack createShardItem(int amount) {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§dVestige Shard");
            meta.setLore(List.of("§7A fragment of something that once mattered."));
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

    /** Absorbs any physical shard items in inventory into PDC balance. */
    public void absorbPhysicalShards(Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (!isShardItem(item)) continue;
            addShards(player, item.getAmount());
            inv.setItem(i, null);
        }
    }

    // -------------------------------------------------------------------------
    // Command
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) {
                sender.sendMessage("§dVestige Shards: §f" + getBalance(p));
                return true;
            }
            sender.sendMessage("§7Usage: /vecurrency <give|take|check|balance>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "balance" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cPlayer only."); return true; }
                sender.sendMessage("§dVestige Shards: §f" + getBalance(p));
            }
            case "give", "take" -> {
                if (!sender.hasPermission("vestigium.currency.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (args.length < 3) { sender.sendMessage("§7Usage: /vecurrency " + args[0] + " <player> <amount>"); return true; }
                Player target = plugin.getServer().getPlayer(args[1]);
                if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                int amount;
                try { amount = Integer.parseInt(args[2]); } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid amount."); return true;
                }
                if (args[0].equalsIgnoreCase("give")) {
                    addShards(target, amount);
                    sender.sendMessage("§aGave §f" + amount + " §dVestige Shards §ato §f" + target.getName() + "§a.");
                    target.sendMessage("§aYou received §f" + amount + " §dVestige Shards§a.");
                } else {
                    if (!spendShards(target, amount)) {
                        sender.sendMessage("§c" + target.getName() + " does not have enough shards.");
                    } else {
                        sender.sendMessage("§aRemoved §f" + amount + " §dVestige Shards §afrom §f" + target.getName() + "§a.");
                    }
                }
            }
            case "check" -> {
                if (!sender.hasPermission("vestigium.currency.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (args.length < 2) { sender.sendMessage("§7Usage: /vecurrency check <player>"); return true; }
                Player target = plugin.getServer().getPlayer(args[1]);
                if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                sender.sendMessage("§f" + target.getName() + "§7 has §d" + getBalance(target) + " §dVestige Shards§7.");
            }
            default -> sender.sendMessage("§7Usage: /vecurrency <give|take|check|balance>");
        }
        return true;
    }
}
