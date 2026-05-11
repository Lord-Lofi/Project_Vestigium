package com.vestigium.vestigiumlore.terminal;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumlore.VestigiumLore;
import com.vestigium.vestigiumlore.cipher.CipherManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Resonant Terminals, End Archive Terminals, and Antecedent Nether Terminals.
 *
 * All three types are lectern blocks tagged with:
 *   vestigium:terminal_type  — "resonant", "end", or "nether"
 *   vestigium:terminal_lore  — lore key passed to LoreRegistry.getLoreContent()
 *
 * Each type requires a specific cipher item to read. Successful reads grant
 * the corresponding lore fragment and apply ambient effects.
 *
 * Cipher gates:
 *   RESONANT   (Echo Shard)       — ancient city terminals
 *   ANTECEDENT (Amethyst Shard)   — End Archive terminals
 *   TIDAL      (Nautilus Shell)   — Antecedent Nether expedition terminals
 *
 * Admin: /vcterminal set <resonant|end|nether> <lore_key>
 *         /vcterminal clear
 *         /vcterminal info
 */
public class TerminalManager implements Listener, CommandExecutor {

    // PDC keys written onto the Lectern TileEntity
    private static final NamespacedKey TERMINAL_TYPE_KEY =
            new NamespacedKey("vestigium", "terminal_type");
    private static final NamespacedKey TERMINAL_LORE_KEY =
            new NamespacedKey("vestigium", "terminal_lore");

    private static final long COOLDOWN_MS = 10 * 60 * 1000L;

    // key: playerUUID + ":" + world:x:y:z
    private final Map<String, Long> cooldowns = new HashMap<>();

    private final VestigiumLore plugin;

    public TerminalManager(VestigiumLore plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("vcterminal");
        if (cmd != null) cmd.setExecutor(this);
        plugin.getLogger().info("[TerminalManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Interaction
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand()   != EquipmentSlot.HAND)      return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.LECTERN) return;

        Lectern lecternState = (Lectern) block.getState();
        String typeKey = lecternState.getPersistentDataContainer()
                .get(TERMINAL_TYPE_KEY, PersistentDataType.STRING);
        if (typeKey == null) return; // normal lectern, not a terminal

        event.setCancelled(true);
        TerminalType type = TerminalType.fromKey(typeKey);
        if (type == null) return;

        Player player = event.getPlayer();
        String loreKey = lecternState.getPersistentDataContainer()
                .get(TERMINAL_LORE_KEY, PersistentDataType.STRING);
        if (loreKey == null) {
            player.sendMessage("§8This terminal has no catalogued records.");
            return;
        }

        if (!plugin.getCipherManager().playerHasCipher(player, type.cipherType())) {
            player.sendMessage(type.noCipherMessage());
            return;
        }

        String cooldownKey = player.getUniqueId() + ":" + locKey(block.getLocation());
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(cooldownKey);
        if (until != null && now < until) {
            long remaining = (until - now) / 1000;
            player.sendMessage("§8The terminal is still processing. (" + remaining + "s)");
            return;
        }

        String content = VestigiumLib.getLoreRegistry().getLoreContent(loreKey, "main");
        if (content.isEmpty()) {
            player.sendMessage("§8The terminal connects — but its records are corrupted or absent.");
            return;
        }

        cooldowns.put(cooldownKey, now + COOLDOWN_MS);
        deliverTerminalLore(player, type, loreKey, content, block.getLocation());
    }

    // -------------------------------------------------------------------------
    // Admin command
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vestigium.terminal.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cMust be run by a player.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§7Usage: /vcterminal <set <type> <lore_key> | clear | info>");
            return true;
        }

        Block target = player.getTargetBlockExact(5);
        if (target == null || target.getType() != Material.LECTERN) {
            sender.sendMessage("§cLook at a lectern block (within 5 blocks).");
            return true;
        }
        Lectern state = (Lectern) target.getState();

        switch (args[0].toLowerCase()) {
            case "set" -> {
                if (args.length < 3) {
                    sender.sendMessage("§7Usage: /vcterminal set <resonant|end|nether> <lore_key>");
                    return true;
                }
                TerminalType type = TerminalType.fromKey(args[1]);
                if (type == null) {
                    sender.sendMessage("§cUnknown type. Use: resonant, end, nether");
                    return true;
                }
                String loreKey = args[2];
                state.getPersistentDataContainer()
                        .set(TERMINAL_TYPE_KEY, PersistentDataType.STRING, type.key());
                state.getPersistentDataContainer()
                        .set(TERMINAL_LORE_KEY, PersistentDataType.STRING, loreKey);
                state.update();
                sender.sendMessage("§aTerminal set: §f" + type.displayName()
                        + " §a→ lore key §f" + loreKey);
            }
            case "clear" -> {
                state.getPersistentDataContainer().remove(TERMINAL_TYPE_KEY);
                state.getPersistentDataContainer().remove(TERMINAL_LORE_KEY);
                state.update();
                sender.sendMessage("§aTerminal markers removed from this lectern.");
            }
            case "info" -> {
                String typeKey = state.getPersistentDataContainer()
                        .get(TERMINAL_TYPE_KEY, PersistentDataType.STRING);
                String loreKey = state.getPersistentDataContainer()
                        .get(TERMINAL_LORE_KEY, PersistentDataType.STRING);
                if (typeKey == null) {
                    sender.sendMessage("§7This lectern is not a registered terminal.");
                } else {
                    sender.sendMessage("§7Type: §f" + typeKey + "  §7Lore key: §f" + loreKey);
                }
            }
            default -> sender.sendMessage("§7Usage: /vcterminal <set <type> <lore_key> | clear | info>");
        }
        return true;
    }

    // -------------------------------------------------------------------------

    private void deliverTerminalLore(Player player, TerminalType type,
                                     String loreKey, String content, Location loc) {
        // Ambient effect at terminal
        loc.getWorld().spawnParticle(type.particle(), loc.clone().add(0, 1, 0),
                20, 0.4, 0.5, 0.4, 0.02);
        loc.getWorld().playSound(loc, type.sound(), 0.7f, 1.0f);

        player.sendMessage(" ");
        player.sendMessage(type.header());

        // Check if this lore key has any multi-script cipher sections
        boolean hasMultiScript = false;
        for (CipherManager.CipherType ct : CipherManager.CipherType.values()) {
            if (!VestigiumLib.getLoreRegistry().getLoreContent(loreKey, ct.key()).isEmpty()) {
                hasMultiScript = true;
                break;
            }
        }

        if (hasMultiScript) {
            Set<CipherManager.CipherType> held = plugin.getCipherManager().getHeldCiphers(player);
            deliverMultiScript(player, loreKey, held);
        } else {
            player.sendMessage("§7§o" + content);
        }

        player.sendMessage(" ");

        String fragmentId = loreKey + "_terminal";
        VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), fragmentId);
        player.sendMessage("§8[Fragment catalogued: " + fragmentId + "]");
    }

    private void deliverMultiScript(Player player, String loreKey,
                                    Set<CipherManager.CipherType> held) {
        // If player holds all three ciphers and a combined section exists, show that
        if (held.size() == CipherManager.CipherType.values().length) {
            String combined = VestigiumLib.getLoreRegistry().getLoreContent(loreKey, "combined");
            if (!combined.isEmpty()) {
                player.sendMessage("§f§l[All scripts decoded — unified record]");
                player.sendMessage("§7§o" + combined);
                return;
            }
        }

        int encryptedSections = 0;
        for (CipherManager.CipherType ct : CipherManager.CipherType.values()) {
            String sectionText = VestigiumLib.getLoreRegistry().getLoreContent(loreKey, ct.key());
            if (sectionText.isEmpty()) continue;

            if (held.contains(ct)) {
                player.sendMessage(ct.glyphHeader());
                player.sendMessage("§7§o" + sectionText);
            } else {
                player.sendMessage(ct.encryptedHeader());
                player.sendMessage(generateGlyphs(ct, sectionText.length(), loreKey.hashCode()));
                encryptedSections++;
            }
        }

        if (encryptedSections > 0) {
            player.sendMessage("§8§o" + encryptedSections
                    + " script section(s) remain unreadable — acquire the corresponding cipher.");
        }
    }

    private static String generateGlyphs(CipherManager.CipherType type, int textLength, long seed) {
        char[] chars = type.glyphChars();
        Random rng = new Random(seed ^ (long) type.key().hashCode());
        // Scale glyph count loosely to text length; cap so chat isn't flooded
        int count = Math.min(Math.max(textLength / 5, 12), 48);
        StringBuilder sb = new StringBuilder("§8§o");
        for (int i = 0; i < count; i++) {
            if (i > 0 && i % 9 == 0) sb.append(' ');
            sb.append(chars[rng.nextInt(chars.length)]);
        }
        return sb.toString();
    }

    private static String locKey(Location loc) {
        return loc.getWorld().getName()
                + ":" + loc.getBlockX()
                + ":" + loc.getBlockY()
                + ":" + loc.getBlockZ();
    }

    // -------------------------------------------------------------------------
    // Terminal type enum
    // -------------------------------------------------------------------------

    public enum TerminalType {
        RESONANT("resonant",
                CipherManager.CipherType.RESONANT,
                "§8[§bResonant Archive §8— Ancient City Terminal]",
                "§8The symbols pulse with sculk frequency. You need the §bResonant Cipher §8to read this.",
                Particle.SCULK_SOUL,
                Sound.BLOCK_SCULK_SENSOR_CLICKING),

        END_ARCHIVE("end",
                CipherManager.CipherType.ANTECEDENT,
                "§8[§dEnd Archive §8— Antecedent Observatory Terminal]",
                "§8Antecedent script is carved into every surface. You need the §dAntecedent Cipher §8to decode it.",
                Particle.END_ROD,
                Sound.BLOCK_ENDER_CHEST_OPEN),

        NETHER_CAMP("nether",
                CipherManager.CipherType.TIDAL,
                "§8[§3Expedition Log §8— Antecedent Nether Camp Terminal]",
                "§8Expedition notes glow with soul light. You need the §3Tidal Cipher §8to interpret them.",
                Particle.SOUL_FIRE_FLAME,
                Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD);

        private final String key;
        private final CipherManager.CipherType cipherType;
        private final String header;
        private final String noCipherMessage;
        private final Particle particle;
        private final Sound sound;

        TerminalType(String key, CipherManager.CipherType cipherType,
                     String header, String noCipherMessage,
                     Particle particle, Sound sound) {
            this.key            = key;
            this.cipherType     = cipherType;
            this.header         = header;
            this.noCipherMessage = noCipherMessage;
            this.particle       = particle;
            this.sound          = sound;
        }

        public String key()                        { return key; }
        public CipherManager.CipherType cipherType() { return cipherType; }
        public String header()                     { return header; }
        public String noCipherMessage()            { return noCipherMessage; }
        public Particle particle()                 { return particle; }
        public Sound sound()                       { return sound; }

        public String displayName() {
            return switch (this) {
                case RESONANT   -> "Resonant Terminal";
                case END_ARCHIVE -> "End Archive Terminal";
                case NETHER_CAMP -> "Nether Camp Terminal";
            };
        }

        public static TerminalType fromKey(String key) {
            for (TerminalType t : values()) {
                if (t.key.equals(key)) return t;
            }
            return null;
        }
    }
}
