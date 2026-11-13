package com.vestigium.vestigiumeconomy.specialitems;

import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Relic Binding — items stamped with `vestigium:relic_owner` (player UUID STRING).
 *
 * Rules:
 *  - Bound items cannot be dropped or picked up by other players
 *  - Hopper/container transfer of bound items is blocked
 *  - On player death: bound items are stripped from the drop list and returned on respawn
 *    (stored in relic_cache.yml until the player rejoins)
 *
 * Items can only be bound once. An already-bound item cannot be rebound.
 * Binding is permanent — the only removal is admin command /verelic unbind.
 */
public class RelicManager implements Listener, CommandExecutor {

    public static final NamespacedKey RELIC_OWNER_KEY =
            new NamespacedKey("vestigium", "relic_owner");

    private final VestigiumEconomy plugin;
    private final File              cacheFile;
    private YamlConfiguration      cache;

    public RelicManager(VestigiumEconomy plugin) {
        this.plugin    = plugin;
        this.cacheFile = new File(plugin.getDataFolder(), "relic_cache.yml");
    }

    public void init() {
        cache = cacheFile.exists() ? YamlConfiguration.loadConfiguration(cacheFile) : new YamlConfiguration();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("verelic");
        if (cmd != null) cmd.setExecutor(this);
        plugin.getLogger().info("[RelicManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Drop prevention
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!isRelicOf(event.getItemDrop().getItemStack(), event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage("§cBound relics cannot be dropped.");
    }

    // -------------------------------------------------------------------------
    // Pickup prevention for other players
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack item = event.getItem().getItemStack();
        String owner = getOwner(item);
        if (owner == null) return;
        if (!player.getUniqueId().toString().equals(owner)) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------------------------
    // Hopper/container transfer prevention
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (isBound(event.getItem())) event.setCancelled(true);
    }

    // -------------------------------------------------------------------------
    // Death — strip relics from drops, cache for respawn
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        List<ItemStack> drops = event.getDrops();
        List<ItemStack> toCache = new ArrayList<>();

        drops.removeIf(item -> {
            if (isRelicOf(item, player)) { toCache.add(item); return true; }
            return false;
        });

        if (!toCache.isEmpty()) cacheRelics(player.getUniqueId(), toCache);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        List<ItemStack> cached = retrieveRelics(player.getUniqueId());
        if (cached.isEmpty()) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (ItemStack item : cached) player.getInventory().addItem(item);
            player.sendMessage("§aYour bound relic" + (cached.size() == 1 ? "" : "s") + " returned to you.");
        }, 5L);
    }

    // -------------------------------------------------------------------------
    // Command — /verelic
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7/verelic <bind [player] | unbind | info>");
            return true;
        }
        switch (args[0].toLowerCase()) {

            case "bind" -> {
                if (!sender.hasPermission("vestigium.relic.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                Player target = args.length >= 2
                        ? plugin.getServer().getPlayer(args[1])
                        : (sender instanceof Player p ? p : null);
                if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                if (!(target instanceof Player)) { sender.sendMessage("§cPlayers only."); return true; }
                ItemStack held = target.getInventory().getItemInMainHand();
                if (held.getType() == Material.AIR) { sender.sendMessage("§c" + target.getName() + " must hold an item."); return true; }
                if (isBound(held)) { sender.sendMessage("§cThis item is already bound."); return true; }
                bindItem(held, target);
                target.getInventory().setItemInMainHand(held);
                sender.sendMessage("§aItem bound to §f" + target.getName() + "§a.");
                target.sendMessage("§6Your §f" + itemName(held) + " §6has been bound to you as a Relic.");
            }

            case "unbind" -> {
                if (!sender.hasPermission("vestigium.relic.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
                ItemStack held = player.getInventory().getItemInMainHand();
                if (!isBound(held)) { sender.sendMessage("§cHeld item is not a bound relic."); return true; }
                ItemMeta meta = held.getItemMeta();
                meta.getPersistentDataContainer().remove(RELIC_OWNER_KEY);
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.removeIf(l -> l.contains("Relic"));
                meta.setLore(lore);
                held.setItemMeta(meta);
                player.getInventory().setItemInMainHand(held);
                sender.sendMessage("§aRelic binding removed.");
            }

            case "info" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
                ItemStack held = player.getInventory().getItemInMainHand();
                String owner = getOwner(held);
                if (owner == null) { player.sendMessage("§7Held item is not a relic."); return true; }
                OfflinePlayer op = plugin.getServer().getOfflinePlayer(UUID.fromString(owner));
                player.sendMessage("§7Relic bound to: §f" + (op.getName() != null ? op.getName() : owner));
            }

            default -> sender.sendMessage("§7/verelic <bind [player] | unbind | info>");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void bindItem(ItemStack item, Player owner) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(RELIC_OWNER_KEY, PersistentDataType.STRING,
                owner.getUniqueId().toString());
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add("§8✦ §6Relic §8— §7Bound to §f" + owner.getName());
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    public boolean isBound(ItemStack item) {
        return getOwner(item) != null;
    }

    public String getOwner(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
                .get(RELIC_OWNER_KEY, PersistentDataType.STRING);
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void cacheRelics(UUID playerUUID, List<ItemStack> items) {
        String key = playerUUID.toString();
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (ItemStack item : items) serialized.add(item.serialize());
        cache.set(key, serialized);
        saveCache();
    }

    @SuppressWarnings("unchecked")
    private List<ItemStack> retrieveRelics(UUID playerUUID) {
        String key = playerUUID.toString();
        List<?> raw = cache.getList(key);
        if (raw == null || raw.isEmpty()) return List.of();
        List<ItemStack> items = new ArrayList<>();
        for (Object entry : raw) {
            if (entry instanceof Map<?,?> map) {
                try { items.add(ItemStack.deserialize((Map<String, Object>) map)); }
                catch (Exception ignored) {}
            }
        }
        cache.set(key, null);
        saveCache();
        return items;
    }

    private void saveCache() {
        try { cache.save(cacheFile); }
        catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Failed to save relic_cache.yml", e); }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isRelicOf(ItemStack item, Player player) {
        String owner = getOwner(item);
        return owner != null && owner.equals(player.getUniqueId().toString());
    }

    private static String itemName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName())
            return item.getItemMeta().getDisplayName();
        String s = item.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
