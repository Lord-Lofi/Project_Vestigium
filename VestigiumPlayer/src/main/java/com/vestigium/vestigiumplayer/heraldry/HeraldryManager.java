package com.vestigium.vestigiumplayer.heraldry;

import com.vestigium.vestigiumplayer.VestigiumPlayer;
import org.bukkit.*;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Heraldry system — players pick a base color and a sigil symbol that
 * identify them across the world.
 *
 * Stored in player PDC:
 *   vestigium:heraldry_color  — DyeColor name (e.g. "RED")
 *   vestigium:heraldry_symbol — symbol key (e.g. "skull")
 *
 * Heraldry banners (vestigium:heraldry_banner PDC on the item) are
 * generated on demand. The glow color of death gravestones also reflects
 * heraldry (handled in PlayerEpitaphManager).
 *
 * /vpheraldry         — open the design GUI
 * /vpheraldry banner  — receive a heraldry banner item
 */
public class HeraldryManager implements Listener, CommandExecutor {

    static final NamespacedKey HERALDRY_COLOR  = new NamespacedKey("vestigium", "heraldry_color");
    static final NamespacedKey HERALDRY_SYMBOL = new NamespacedKey("vestigium", "heraldry_symbol");
    static final NamespacedKey HERALDRY_BANNER = new NamespacedKey("vestigium", "heraldry_banner");

    private static final String GUI_TITLE = "§5Heraldry §8— §7Choose Your Sigil";

    private static final DyeColor[] COLORS = DyeColor.values(); // 16 colors

    // 10 playable sigil symbols
    private static final String[]      SYMBOL_KEYS     = {
            "cross", "skull", "creeper", "flower", "rhombus",
            "globe", "mojang", "straight_cross", "piglin", "triangle"
    };
    private static final PatternType[] SYMBOL_PATTERNS = {
            PatternType.CROSS, PatternType.SKULL, PatternType.CREEPER,
            PatternType.FLOWER, PatternType.RHOMBUS, PatternType.GLOBE,
            PatternType.MOJANG, PatternType.STRAIGHT_CROSS, PatternType.PIGLIN,
            PatternType.TRIANGLE_BOTTOM
    };
    private static final Material[] SYMBOL_ICONS = {
            Material.NETHER_STAR, Material.WITHER_SKELETON_SKULL, Material.CREEPER_HEAD,
            Material.OXEYE_DAISY, Material.DIAMOND, Material.FILLED_MAP,
            Material.PAPER, Material.IRON_BARS, Material.GOLD_INGOT,
            Material.TOTEM_OF_UNDYING
    };
    private static final String[] SYMBOL_NAMES = {
            "§fStar Cross", "§8Skull", "§aCreeper",
            "§bFlower", "§bDiamond", "§3Globe",
            "§cCrest", "§7Portcullis", "§6Piglin",
            "§eMountain"
    };

    // GUI layout (36 slots):
    //  Slots  0-15: 16 color swatches (rows 0-1)
    //  Slots 16-17: glass pane dividers
    //  Slots 18-27: 10 symbol icons (rows 2-3 partial)
    //  Slots 28-33: glass pane dividers
    //  Slot    34:  preview banner
    //  Slot    35:  glass pane

    private final Set<UUID> openGuis = new HashSet<>();
    private final VestigiumPlayer plugin;

    public HeraldryManager(VestigiumPlayer plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("vpheraldry");
        if (cmd != null) cmd.setExecutor(this);
        plugin.getLogger().info("[HeraldryManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public DyeColor getColor(Player player) {
        String stored = player.getPersistentDataContainer()
                .get(HERALDRY_COLOR, PersistentDataType.STRING);
        if (stored == null) return DyeColor.WHITE;
        try { return DyeColor.valueOf(stored); } catch (Exception e) { return DyeColor.WHITE; }
    }

    public PatternType getSymbolPattern(Player player) {
        String stored = player.getPersistentDataContainer()
                .get(HERALDRY_SYMBOL, PersistentDataType.STRING);
        if (stored != null) {
            for (int i = 0; i < SYMBOL_KEYS.length; i++) {
                if (SYMBOL_KEYS[i].equals(stored)) return SYMBOL_PATTERNS[i];
            }
        }
        return PatternType.CROSS;
    }

    public boolean hasHeraldry(Player player) {
        return player.getPersistentDataContainer()
                .has(HERALDRY_COLOR, PersistentDataType.STRING);
    }

    public ItemStack createBanner(Player player) {
        DyeColor base = getColor(player);
        PatternType symbol = getSymbolPattern(player);
        Material bannerMat = Material.valueOf(base.name() + "_BANNER");
        ItemStack banner = new ItemStack(bannerMat);
        BannerMeta meta = (BannerMeta) banner.getItemMeta();
        if (meta == null) return banner;
        DyeColor contrast = (base == DyeColor.WHITE || base == DyeColor.LIGHT_GRAY
                || base == DyeColor.YELLOW || base == DyeColor.LIME)
                ? DyeColor.BLACK : DyeColor.WHITE;
        meta.addPattern(new Pattern(contrast, symbol));
        meta.setDisplayName("§f" + player.getName() + "§7's Heraldry");
        meta.getPersistentDataContainer()
                .set(HERALDRY_BANNER, PersistentDataType.STRING, player.getUniqueId().toString());
        banner.setItemMeta(meta);
        return banner;
    }

    /** Maps DyeColor to the nearest ChatColor for scoreboard team glow tint. */
    public ChatColor getGlowColor(DyeColor dye) {
        return switch (dye) {
            case WHITE      -> ChatColor.WHITE;
            case ORANGE     -> ChatColor.GOLD;
            case MAGENTA    -> ChatColor.LIGHT_PURPLE;
            case LIGHT_BLUE -> ChatColor.AQUA;
            case YELLOW     -> ChatColor.YELLOW;
            case LIME       -> ChatColor.GREEN;
            case PINK       -> ChatColor.LIGHT_PURPLE;
            case GRAY       -> ChatColor.DARK_GRAY;
            case LIGHT_GRAY -> ChatColor.GRAY;
            case CYAN       -> ChatColor.DARK_AQUA;
            case PURPLE     -> ChatColor.DARK_PURPLE;
            case BLUE       -> ChatColor.DARK_BLUE;
            case BROWN      -> ChatColor.DARK_RED;
            case GREEN      -> ChatColor.DARK_GREEN;
            case RED        -> ChatColor.RED;
            case BLACK      -> ChatColor.BLACK;
        };
    }

    // -------------------------------------------------------------------------
    // Command
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cPlayers only.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("banner")) {
            if (!hasHeraldry(player)) {
                player.sendMessage("§7You have not chosen a heraldry yet. Use §d/vpheraldry §7to design your sigil.");
                return true;
            }
            player.getInventory().addItem(createBanner(player));
            player.sendMessage("§7Your heraldry banner was added to your inventory.");
            return true;
        }
        openGui(player);
        return true;
    }

    // -------------------------------------------------------------------------
    // GUI
    // -------------------------------------------------------------------------

    private void openGui(Player player) {
        openGuis.add(player.getUniqueId());
        Inventory inv = Bukkit.createInventory(null, 36, GUI_TITLE);
        populateGui(inv, player);
        player.openInventory(inv);
    }

    private void populateGui(Inventory inv, Player player) {
        String curColor  = player.getPersistentDataContainer()
                .get(HERALDRY_COLOR, PersistentDataType.STRING);
        String curSymbol = player.getPersistentDataContainer()
                .get(HERALDRY_SYMBOL, PersistentDataType.STRING);

        // Slots 0-15: 16 color swatches
        for (int i = 0; i < 16; i++) {
            DyeColor color = COLORS[i];
            Material concrete = Material.valueOf(color.name() + "_CONCRETE");
            ItemStack item = new ItemStack(concrete);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                boolean selected = color.name().equals(curColor);
                meta.setDisplayName((selected ? "§a▸ " : "§7") + formatName(color.name()));
                if (selected) meta.setLore(List.of("§a✓ Your sigil color"));
                item.setItemMeta(meta);
            }
            inv.setItem(i, item);
        }

        // Slots 16-17: dividers
        inv.setItem(16, pane());
        inv.setItem(17, pane());

        // Slots 18-27: 10 symbol icons
        for (int i = 0; i < SYMBOL_KEYS.length; i++) {
            ItemStack icon = new ItemStack(SYMBOL_ICONS[i]);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                boolean selected = SYMBOL_KEYS[i].equals(curSymbol);
                meta.setDisplayName((selected ? "§a▸ " : "") + SYMBOL_NAMES[i]);
                if (selected) meta.setLore(List.of("§a✓ Your sigil symbol"));
                icon.setItemMeta(meta);
            }
            inv.setItem(18 + i, icon);
        }

        // Slots 28-33: dividers
        for (int s = 28; s <= 33; s++) inv.setItem(s, pane());

        // Slot 34: preview banner (if color + symbol chosen)
        if (curColor != null && curSymbol != null) {
            ItemStack preview = createBanner(player);
            ItemMeta pm = preview.getItemMeta();
            if (pm != null) {
                pm.setDisplayName("§7Preview");
                pm.setLore(List.of("§8Your current heraldry", "§8Close to save."));
                preview.setItemMeta(pm);
            }
            inv.setItem(34, preview);
        } else {
            inv.setItem(34, pane());
        }

        inv.setItem(35, pane());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!openGuis.contains(player.getUniqueId())) return;

        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 36) return;

        if (slot <= 15) {
            // Color selection
            DyeColor color = COLORS[slot];
            player.getPersistentDataContainer()
                    .set(HERALDRY_COLOR, PersistentDataType.STRING, color.name());
            populateGui(event.getInventory(), player);
            player.sendActionBar("§7Sigil color: §f" + formatName(color.name()));

        } else if (slot >= 18 && slot <= 27) {
            // Symbol selection
            int idx = slot - 18;
            player.getPersistentDataContainer()
                    .set(HERALDRY_SYMBOL, PersistentDataType.STRING, SYMBOL_KEYS[idx]);
            populateGui(event.getInventory(), player);
            player.sendActionBar("§7Sigil symbol: " + SYMBOL_NAMES[idx]);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!openGuis.remove(uuid)) return;
        Player player = (Player) event.getPlayer();
        if (hasHeraldry(player)) {
            player.sendMessage("§7Heraldry saved. Use §d/vpheraldry banner §7to craft your banner.");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ItemStack pane() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) { meta.setDisplayName("§8"); pane.setItemMeta(meta); }
        return pane;
    }

    private static String formatName(String enumName) {
        String[] parts = enumName.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
