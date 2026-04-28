package com.vestigium.vestigiumeconomy.market;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.FactionCollapseEvent;
import com.vestigium.lib.model.Faction;
import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import com.vestigium.vestigiumeconomy.currency.CurrencyManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Dynamic market pricing driven by faction health, omen, and season.
 *
 * Market items are sold by Villagers tagged with "vestigium:market_type" PDC STRING.
 * Right-clicking opens a simulated shop (chat-based menu via numbered reply).
 *
 * Price modifiers (multiplicative):
 *   Faction health < 20  → their goods cost 2x (scarcity)
 *   Faction health > 80  → their goods cost 0.8x (abundance)
 *   Omen > 600           → ALL prices +25%
 *   Season WINTER        → food prices +20%, fuel prices -10%
 *   Season SUMMER        → food prices -15%
 *
 * Prices refresh every 10 minutes. Stored in memory only — intentionally volatile.
 *
 * Built-in catalogue (Material → base Shard cost → faction):
 *   BREAD         5   VILLAGERS
 *   IRON_INGOT   10   VILLAGERS
 *   EMERALD      20   VILLAGERS
 *   CROSSBOW     40   MERCENARIES
 *   IRON_SWORD   25   MERCENARIES
 *   COAL          3   BANDITS  (black market)
 *   GUNPOWDER     8   BANDITS
 *   ECHO_SHARD   60   none     (rare, flat price)
 */
public class DynamicMarketManager implements Listener, CommandExecutor {

    private static final long PRICE_REFRESH_TICKS = 12_000L;
    private static final NamespacedKey MARKET_TYPE_KEY =
            new NamespacedKey("vestigium", "market_type");

    private static final List<MarketEntry> CATALOGUE = List.of(
            new MarketEntry(Material.BREAD,       5,  "villagers"),
            new MarketEntry(Material.IRON_INGOT,  10, "villagers"),
            new MarketEntry(Material.EMERALD,     20, "villagers"),
            new MarketEntry(Material.CROSSBOW,    40, "mercenaries"),
            new MarketEntry(Material.IRON_SWORD,  25, "mercenaries"),
            new MarketEntry(Material.COAL,         3, "bandits"),
            new MarketEntry(Material.GUNPOWDER,    8, "bandits"),
            new MarketEntry(Material.ECHO_SHARD,  60, "none")
    );

    private final VestigiumEconomy plugin;
    // Cached effective prices: Material → current shard cost
    private final Map<Material, Integer> effectivePrices = new LinkedHashMap<>();
    // Last market type each player interacted with — used by /vebuy
    private final Map<UUID, String> lastMarketType = new HashMap<>();

    public DynamicMarketManager(VestigiumEconomy plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        refreshPrices();

        new BukkitRunnable() {
            @Override public void run() { refreshPrices(); }
        }.runTaskTimer(plugin, PRICE_REFRESH_TICKS, PRICE_REFRESH_TICKS);

        VestigiumLib.getEventBus().subscribe(FactionCollapseEvent.class,
                e -> refreshPrices());

        plugin.getLogger().info("[DynamicMarketManager] Initialized.");
    }

    // -------------------------------------------------------------------------

    public int getPrice(Material material) {
        return effectivePrices.getOrDefault(material, 999);
    }

    private void refreshPrices() {
        int omen          = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
        var season        = VestigiumLib.getSeasonAPI().getCurrentSeason();
        double omenMult   = omen > 600 ? 1.25 : 1.0;

        for (MarketEntry entry : CATALOGUE) {
            double price = entry.basePrice();

            // Faction health modifier
            if (!"none".equals(entry.faction())) {
                try {
                    Faction faction = Faction.valueOf(entry.faction().toUpperCase());
                    var state = VestigiumLib.getFactionRegistry().getFactionState(faction.getKey());
                    if (state.isCollapsed())      price *= 2.0;
                    else if (state.canExpand())   price *= 0.8;
                } catch (IllegalArgumentException ignored) {}
            }

            price *= omenMult;

            // Season modifiers for food
            boolean isFood = entry.material() == Material.BREAD;
            if (isFood) {
                price *= switch (season) {
                    case WINTER -> 1.20;
                    case SUMMER -> 0.85;
                    default     -> 1.0;
                };
            }

            effectivePrices.put(entry.material(), Math.max(1, (int) Math.round(price)));
        }
    }

    // -------------------------------------------------------------------------
    // Market interaction
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMarketInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) return;
        String marketType = villager.getPersistentDataContainer()
                .get(MARKET_TYPE_KEY, PersistentDataType.STRING);
        if (marketType == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        // Absorb any physical shards first
        plugin.getCurrencyManager().absorbPhysicalShards(player);

        // Remember last market type for /vebuy
        lastMarketType.put(player.getUniqueId(), marketType);

        // Show catalogue for this market type
        List<MarketEntry> available = CATALOGUE.stream()
                .filter(e -> e.faction().equals(marketType) || "none".equals(e.faction()))
                .toList();

        player.sendMessage("§d=== Market (" + marketType + ") ===");
        player.sendMessage("§7Balance: §d" + plugin.getCurrencyManager().getBalance(player) + " shards");
        for (int i = 0; i < available.size(); i++) {
            MarketEntry entry = available.get(i);
            int price = getPrice(entry.material());
            player.sendMessage("§e[" + (i + 1) + "] §f"
                    + formatMat(entry.material()) + " §7— §d" + price + " shards");
        }
        player.sendMessage("§7Type §e/vebuy <number> §7to purchase.");
    }

    /** Called by /vebuy command handler. */
    public void processBuy(Player player, int index, String marketType) {
        List<MarketEntry> available = CATALOGUE.stream()
                .filter(e -> e.faction().equals(marketType) || "none".equals(e.faction()))
                .toList();

        if (index < 1 || index > available.size()) {
            player.sendMessage("§cInvalid selection.");
            return;
        }
        MarketEntry entry = available.get(index - 1);
        int price = getPrice(entry.material());

        CurrencyManager cm = plugin.getCurrencyManager();
        if (!cm.hasShards(player, price)) {
            player.sendMessage("§cNot enough shards. Need §d" + price + "§c, have §d" + cm.getBalance(player) + "§c.");
            return;
        }
        cm.spendShards(player, price);
        Map<Integer, org.bukkit.inventory.ItemStack> overflow =
                player.getInventory().addItem(new org.bukkit.inventory.ItemStack(entry.material()));
        overflow.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
        player.sendMessage("§aPurchased §f" + formatMat(entry.material())
                + " §afor §d" + price + " shards§a. Remaining: §d" + cm.getBalance(player));
    }

    // -------------------------------------------------------------------------
    // /vebuy command
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cPlayer only.");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage("§7Usage: /vebuy <number>");
            return true;
        }
        String marketType = lastMarketType.get(player.getUniqueId());
        if (marketType == null) {
            player.sendMessage("§cYou have not visited a market recently.");
            return true;
        }
        int index;
        try {
            index = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cPlease enter a number.");
            return true;
        }
        processBuy(player, index, marketType);
        return true;
    }

    // -------------------------------------------------------------------------

    private static String formatMat(Material m) {
        return Arrays.stream(m.name().split("_"))
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
                .reduce("", (a, b) -> a.isBlank() ? b : a + " " + b);
    }

    private record MarketEntry(Material material, int basePrice, String faction) {}
}
