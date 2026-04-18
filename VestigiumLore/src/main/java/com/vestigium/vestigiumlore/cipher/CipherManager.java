package com.vestigium.vestigiumlore.cipher;

import com.vestigium.vestigiumlore.VestigiumLore;
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
 * Manages cipher items that unlock different reading tiers.
 *
 * Cipher types (stored as "cipher_type" PDC STRING on the item):
 *   resonant   — reads Resonant Terminals in ancient cities
 *   antecedent — reads Antecedent script found in deep ruins
 *   tidal      — reads tidal inscription markers
 *
 * Ciphers are obtained through the lore chain, quest rewards, or admin /vccipher give.
 */
public class CipherManager implements CommandExecutor {

    private static final NamespacedKey CIPHER_TYPE_KEY =
            new NamespacedKey("vestigium", "cipher_type");

    private final VestigiumLore plugin;

    public CipherManager(VestigiumLore plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(
                new CipherDropListener(plugin, this), plugin);

        var cmd = plugin.getCommand("vccipher");
        if (cmd != null) cmd.setExecutor(this);

        plugin.getLogger().info("[CipherManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Item creation
    // -------------------------------------------------------------------------

    public ItemStack createCipher(CipherType type) {
        ItemStack item = new ItemStack(type.material());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(type.displayName());
        meta.setLore(List.of("§7" + type.description()));
        meta.getPersistentDataContainer()
                .set(CIPHER_TYPE_KEY, PersistentDataType.STRING, type.key());
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCipher(ItemStack item, CipherType type) {
        if (item == null || item.getItemMeta() == null) return false;
        String stored = item.getItemMeta().getPersistentDataContainer()
                .get(CIPHER_TYPE_KEY, PersistentDataType.STRING);
        return type.key().equals(stored);
    }

    public boolean playerHasCipher(Player player, CipherType type) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isCipher(item, type)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Admin command — /vccipher give <player> <type>
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vestigium.cipher.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage("§7Usage: /vccipher give <player> <resonant|antecedent|tidal>");
            return true;
        }
        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found.");
            return true;
        }
        CipherType type;
        try {
            type = CipherType.valueOf(args[2].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cUnknown cipher type: " + args[2]);
            return true;
        }
        target.getInventory().addItem(createCipher(type));
        sender.sendMessage("§aGave " + type.displayName() + " §ato " + target.getName() + ".");
        return true;
    }

    // -------------------------------------------------------------------------
    // Cipher type enum
    // -------------------------------------------------------------------------

    public enum CipherType {
        RESONANT(Material.ECHO_SHARD, "resonant",
                "§bResonant Cipher",
                "Allows reading of Resonant Terminals in ancient cities."),
        ANTECEDENT(Material.AMETHYST_SHARD, "antecedent",
                "§dAntecedent Cipher",
                "Deciphers the script of the Antecedent people."),
        TIDAL(Material.NAUTILUS_SHELL, "tidal",
                "§3Tidal Cipher",
                "Reads the tidal inscriptions left by deep-sea cultures.");

        private final Material material;
        private final String key;
        private final String displayName;
        private final String description;

        CipherType(Material material, String key, String displayName, String description) {
            this.material = material;
            this.key = key;
            this.displayName = displayName;
            this.description = description;
        }

        public Material material()    { return material; }
        public String key()           { return key; }
        public String displayName()   { return displayName; }
        public String description()   { return description; }
    }
}
