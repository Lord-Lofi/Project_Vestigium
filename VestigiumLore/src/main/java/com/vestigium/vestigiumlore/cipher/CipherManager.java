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

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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

    public Set<CipherType> getHeldCiphers(Player player) {
        Set<CipherType> held = EnumSet.noneOf(CipherType.class);
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getItemMeta() == null) continue;
            String stored = item.getItemMeta().getPersistentDataContainer()
                    .get(CIPHER_TYPE_KEY, PersistentDataType.STRING);
            if (stored == null) continue;
            for (CipherType t : CipherType.values()) {
                if (t.key().equals(stored)) held.add(t);
            }
        }
        return held;
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
        // Runic glyphs — organic/ancient, sculk-associated
        RESONANT(Material.ECHO_SHARD, "resonant",
                "§bResonant Cipher",
                "Allows reading of Resonant Terminals in ancient cities.",
                "§9§l◈ §bRESONANT SCRIPT §9§l◈",
                "§9§l◈ §8RESONANT SCRIPT §8[Cipher Required]",
                new char[]{'ᚠ','ᚢ','ᚦ','ᚩ','ᚱ','ᚳ','ᚷ','ᚹ','ᚺ','ᚾ','ᛁ','ᛃ','ᛇ','ᛈ','ᛏ','ᛚ','ᛞ'}),

        // Mathematical/cosmic glyphs — precise, End-associated
        ANTECEDENT(Material.AMETHYST_SHARD, "antecedent",
                "§dAntecedent Cipher",
                "Deciphers the script of the Antecedent people.",
                "§5§l⊕ §dANTECEDENT SCRIPT §5§l⊕",
                "§5§l⊕ §8ANTECEDENT SCRIPT §8[Cipher Required]",
                new char[]{'⊕','⊗','⊚','∆','∇','∞','∂','⊛','⋆','∑','∏','∃','∈','⊥','∴','≡','∮'}),

        // Wave/fluid glyphs — flowing, ocean-associated
        TIDAL(Material.NAUTILUS_SHELL, "tidal",
                "§3Tidal Cipher",
                "Reads the tidal inscriptions left by deep-sea cultures.",
                "§3§l≋ §3TIDAL SCRIPT §3§l≋",
                "§3§l≋ §8TIDAL SCRIPT §8[Cipher Required]",
                new char[]{'≈','≋','≃','∿','∼','∽','∾','≀','∫','⊂','⊃','∩','∪','≺','≻','⊆','⊇'});

        private final Material material;
        private final String   key;
        private final String   displayName;
        private final String   description;
        private final String   glyphHeader;
        private final String   encryptedHeader;
        private final char[]   glyphChars;

        CipherType(Material material, String key, String displayName, String description,
                   String glyphHeader, String encryptedHeader, char[] glyphChars) {
            this.material        = material;
            this.key             = key;
            this.displayName     = displayName;
            this.description     = description;
            this.glyphHeader     = glyphHeader;
            this.encryptedHeader = encryptedHeader;
            this.glyphChars      = glyphChars;
        }

        public Material material()        { return material; }
        public String key()               { return key; }
        public String displayName()       { return displayName; }
        public String description()       { return description; }
        public String glyphHeader()       { return glyphHeader; }
        public String encryptedHeader()   { return encryptedHeader; }
        public char[] glyphChars()        { return glyphChars; }
    }
}
