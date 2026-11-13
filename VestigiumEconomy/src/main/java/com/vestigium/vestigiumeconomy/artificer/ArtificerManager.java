package com.vestigium.vestigiumeconomy.artificer;

import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import com.vestigium.vestigiumeconomy.currency.CurrencyManager;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Artificer — a persistent Villager NPC that reforges Vestigium-tagged gear in
 * exchange for physical Vestige Shard items.
 *
 * Services (right-click Artificer while holding Vestigium gear to see the menu):
 *   temper   — remove existing temper from item (5 shards)
 *   runes    — strip all runes and return them as physical rune items (3 shards per rune)
 *   artisan  — add Artisan Mark (+0.5 bonus damage, once per item, 8 shards)
 *
 * Execute with: /veartificer reforge <temper|runes|artisan>
 * Admin: /veartificer spawn [name] | remove | list
 *
 * Villager NPC tagged with vestigium:artificer BYTE PDC.
 * Persistence: artificer_stations.yml (uuid + world + xyz per NPC).
 */
public class ArtificerManager implements Listener, CommandExecutor {

    // PDC keys
    private static final NamespacedKey ARTIFICER_KEY    = new NamespacedKey("vestigium", "artificer");
    private static final NamespacedKey TEMPER_KEY       = new NamespacedKey("vestigium", "temper");
    private static final NamespacedKey RUNES_KEY        = new NamespacedKey("vestigium", "runes");
    private static final NamespacedKey RUNE_TYPE_KEY    = new NamespacedKey("vestigium", "rune_type");
    private static final NamespacedKey COMP_BLADE_KEY   = new NamespacedKey("vestigium", "weapon_blade");
    private static final NamespacedKey ARTISAN_KEY      = new NamespacedKey("vestigium", "artisan_bonus");

    private static final int COST_TEMPER  = 5;
    private static final int COST_RUNE    = 3;  // per rune extracted
    private static final int COST_ARTISAN = 8;

    private static final double ARTISAN_BONUS = 0.5;

    private final VestigiumEconomy plugin;
    private final File              dataFile;
    private YamlConfiguration      data;

    // Players who recently right-clicked an Artificer — maps player UUID → nearby artificer UUID
    private final Map<UUID, UUID> pendingInteraction = new HashMap<>();

    public ArtificerManager(VestigiumEconomy plugin) {
        this.plugin   = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "artificer_stations.yml");
    }

    public void init() {
        loadData();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("veartificer");
        if (cmd != null) cmd.setExecutor(this);
        plugin.getLogger().info("[ArtificerManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // NPC right-click — print menu
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArtificerClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Villager villager)) return;
        if (!villager.getPersistentDataContainer().has(ARTIFICER_KEY, PersistentDataType.BYTE)) return;

        event.setCancelled(true);
        Player player   = event.getPlayer();
        ItemStack held  = player.getInventory().getItemInMainHand();

        pendingInteraction.put(player.getUniqueId(), villager.getUniqueId());

        player.sendMessage("§6§l── The Artificer ──");
        if (!isVestigiumGear(held)) {
            player.sendMessage("§7Hold §fVestigium-tagged gear §7in your main hand, then choose a service:");
        } else {
            player.sendMessage("§7Services for §f" + heldName(held) + "§7:");
        }

        String temper = getTemper(held);
        List<String> runes = getRunes(held);
        boolean hasArtisan = hasArtisanMark(held);

        sendOffer(player, "temper",  COST_TEMPER,
                temper != null ? "§8Remove current temper (§f" + temper + "§8)" : "§8No temper to remove");
        sendOffer(player, "runes",   COST_RUNE * Math.max(1, runes.size()),
                !runes.isEmpty() ? "§8Extract §f" + runes.size() + " rune(s)§8 and return them" : "§8No runes to extract");
        sendOffer(player, "artisan", COST_ARTISAN,
                hasArtisan ? "§8Already marked — max one Artisan Mark per item" : "§8Add +0.5 bonus damage (once per item)");

        player.sendMessage("§8Type §7/veartificer reforge <temper|runes|artisan> §8to proceed.");
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 1f, 1.1f);
    }

    // -------------------------------------------------------------------------
    // Combat — Artisan Mark bonus damage
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (weapon == null || !weapon.hasItemMeta()) return;
        if (weapon.getItemMeta().getPersistentDataContainer().has(ARTISAN_KEY, PersistentDataType.BYTE)) {
            event.setDamage(event.getDamage() + ARTISAN_BONUS);
        }
    }

    // -------------------------------------------------------------------------
    // Command — /veartificer
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7/veartificer <reforge <temper|runes|artisan> | spawn [name] | remove | list>");
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "reforge" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
                if (args.length < 2) { sender.sendMessage("§7/veartificer reforge <temper|runes|artisan>"); return true; }

                UUID npcUUID = pendingInteraction.get(player.getUniqueId());
                if (npcUUID == null || !isNearArtificer(player, npcUUID)) {
                    player.sendMessage("§cYou must be near an Artificer to reforge. Right-click one first.");
                    return true;
                }

                ItemStack gear = player.getInventory().getItemInMainHand();
                if (!isVestigiumGear(gear)) {
                    player.sendMessage("§cHold Vestigium-tagged gear in your main hand.");
                    return true;
                }

                switch (args[1].toLowerCase()) {
                    case "temper" -> reforgeTemper(player, gear);
                    case "runes"  -> reforgeRunes(player, gear);
                    case "artisan" -> reforgeArtisan(player, gear);
                    default -> player.sendMessage("§cUnknown service. Choose: temper, runes, artisan.");
                }
            }

            case "spawn" -> {
                if (!sender.hasPermission("vestigium.artificer.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
                String npcName = args.length >= 2 ? args[1] : "The Artificer";
                Villager villager = (Villager) player.getWorld()
                        .spawnEntity(player.getLocation(), EntityType.VILLAGER);
                villager.setCustomName("§6" + npcName);
                villager.setCustomNameVisible(true);
                villager.setAI(false);
                villager.setSilent(true);
                villager.setPersistent(true);
                villager.setInvulnerable(true);
                villager.setVillagerType(Villager.Type.PLAINS);
                villager.setProfession(Villager.Profession.MASON);
                villager.getPersistentDataContainer().set(ARTIFICER_KEY, PersistentDataType.BYTE, (byte) 1);
                saveNpc(villager);
                sender.sendMessage("§aArtificer §f" + npcName + " §aspawned.");
            }

            case "remove" -> {
                if (!sender.hasPermission("vestigium.artificer.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
                Villager nearest = findNearestArtificer(player, 8);
                if (nearest == null) { sender.sendMessage("§cNo Artificer within 8 blocks."); return true; }
                removeNpcFromData(nearest.getUniqueId());
                nearest.remove();
                sender.sendMessage("§aArtificer removed.");
            }

            case "list" -> {
                sender.sendMessage("§6§l── Registered Artificers ──");
                if (data.getKeys(false).isEmpty()) { sender.sendMessage("§7None."); return true; }
                for (String key : data.getKeys(false)) {
                    sender.sendMessage("§8• §7" + data.getString(key + ".world") + " — "
                            + data.getInt(key + ".x") + " " + data.getInt(key + ".y") + " " + data.getInt(key + ".z"));
                }
            }

            default -> sender.sendMessage("§7/veartificer <reforge <temper|runes|artisan> | spawn [name] | remove | list>");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Reforge services
    // -------------------------------------------------------------------------

    private void reforgeTemper(Player player, ItemStack gear) {
        ItemMeta meta = gear.getItemMeta();
        if (meta == null) return;
        String temper = meta.getPersistentDataContainer().get(TEMPER_KEY, PersistentDataType.STRING);
        if (temper == null) { player.sendMessage("§cThis item has no temper to remove."); return; }
        if (!consumeShards(player, COST_TEMPER)) return;

        meta.getPersistentDataContainer().remove(TEMPER_KEY);
        // Strip temper lore line (starts with §8❄)
        stripLore(meta, "§8❄");
        gear.setItemMeta(meta);
        player.getInventory().setItemInMainHand(gear);
        player.sendMessage("§7The §f" + temper + " §7temper has been stripped. " + COST_TEMPER + " shards consumed.");
        playCraft(player);
    }

    private void reforgeRunes(Player player, ItemStack gear) {
        ItemMeta meta = gear.getItemMeta();
        if (meta == null) return;
        String runesRaw = meta.getPersistentDataContainer().get(RUNES_KEY, PersistentDataType.STRING);
        if (runesRaw == null || runesRaw.isBlank()) { player.sendMessage("§cThis item has no runes to extract."); return; }

        List<String> runeIds = Arrays.asList(runesRaw.split(","));
        int cost = COST_RUNE * runeIds.size();
        if (!consumeShards(player, cost)) return;

        meta.getPersistentDataContainer().remove(RUNES_KEY);
        // Strip rune lore lines (start with §8◆)
        stripLore(meta, "§8◆");
        gear.setItemMeta(meta);
        player.getInventory().setItemInMainHand(gear);

        // Return rune items
        for (String runeId : runeIds) {
            ItemStack runeItem = buildRuneItem(runeId);
            if (runeItem != null) player.getInventory().addItem(runeItem);
        }
        player.sendMessage("§7" + runeIds.size() + " rune(s) extracted. §f" + cost + " §7shards consumed.");
        playCraft(player);
    }

    private void reforgeArtisan(Player player, ItemStack gear) {
        ItemMeta meta = gear.getItemMeta();
        if (meta == null) return;
        if (meta.getPersistentDataContainer().has(ARTISAN_KEY, PersistentDataType.BYTE)) {
            player.sendMessage("§cThis item already has an Artisan Mark."); return;
        }
        if (!consumeShards(player, COST_ARTISAN)) return;

        meta.getPersistentDataContainer().set(ARTISAN_KEY, PersistentDataType.BYTE, (byte) 1);
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add("§8✦ §6Artisan Mark §8— §7+" + ARTISAN_BONUS + " base damage");
        meta.setLore(lore);
        gear.setItemMeta(meta);
        player.getInventory().setItemInMainHand(gear);
        player.sendMessage("§6Artisan Mark applied. §f" + COST_ARTISAN + " §7shards consumed.");
        playCraft(player);
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void loadData() {
        data = dataFile.exists() ? YamlConfiguration.loadConfiguration(dataFile) : new YamlConfiguration();
    }

    private void saveNpc(Villager npc) {
        String key = npc.getUniqueId().toString();
        Location loc = npc.getLocation();
        data.set(key + ".uuid",  npc.getUniqueId().toString());
        data.set(key + ".world", loc.getWorld().getName());
        data.set(key + ".x",     loc.getBlockX());
        data.set(key + ".y",     loc.getBlockY());
        data.set(key + ".z",     loc.getBlockZ());
        saveData();
    }

    private void removeNpcFromData(UUID uuid) {
        data.set(uuid.toString(), null);
        saveData();
    }

    private void saveData() {
        try { data.save(dataFile); }
        catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Failed to save artificer_stations.yml", e); }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean consumeShards(Player player, int amount) {
        CurrencyManager cm = plugin.getCurrencyManager();
        int found = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (cm.isShardItem(item)) found += item.getAmount();
        }
        if (found < amount) {
            player.sendMessage("§cNot enough Vestige Shards. Need §f" + amount + "§c, have §f" + found + "§c.");
            return false;
        }
        // Consume shards from inventory
        int remaining = amount;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !cm.isShardItem(item)) continue;
            if (item.getAmount() <= remaining) {
                remaining -= item.getAmount();
                item.setAmount(0);
            } else {
                item.setAmount(item.getAmount() - remaining);
                remaining = 0;
            }
            if (remaining == 0) break;
        }
        return true;
    }

    private static void stripLore(ItemMeta meta, String prefix) {
        if (!meta.hasLore()) return;
        List<String> lore = new ArrayList<>(meta.getLore());
        lore.removeIf(l -> l.startsWith(prefix));
        meta.setLore(lore);
    }

    private static ItemStack buildRuneItem(String runeId) {
        // Build a minimal rune item — same PDC signature as RuneManager.createRune()
        // Uses the rune_type PDC key so RuneManager will recognise it on inscription
        ItemStack item = new ItemStack(Material.BONE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        meta.setDisplayName("§7Rune of §f" + runeId);
        meta.setLore(List.of("§8Extracted by Artificer."));
        meta.getPersistentDataContainer()
                .set(new NamespacedKey("vestigium", "rune_type"), PersistentDataType.STRING, runeId);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isVestigiumGear(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(TEMPER_KEY,  PersistentDataType.STRING)
            || pdc.has(RUNES_KEY,   PersistentDataType.STRING)
            || pdc.has(COMP_BLADE_KEY, PersistentDataType.STRING);
    }

    private boolean hasArtisanMark(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(ARTISAN_KEY, PersistentDataType.BYTE);
    }

    private String getTemper(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(TEMPER_KEY, PersistentDataType.STRING);
    }

    private List<String> getRunes(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return List.of();
        String raw = item.getItemMeta().getPersistentDataContainer().get(RUNES_KEY, PersistentDataType.STRING);
        return (raw == null || raw.isBlank()) ? List.of() : Arrays.asList(raw.split(","));
    }

    private boolean isNearArtificer(Player player, UUID npcUUID) {
        return player.getNearbyEntities(10, 10, 10).stream()
                .anyMatch(e -> e.getUniqueId().equals(npcUUID));
    }

    private Villager findNearestArtificer(Player player, double radius) {
        return player.getNearbyEntities(radius, radius, radius).stream()
                .filter(e -> e instanceof Villager v
                        && v.getPersistentDataContainer().has(ARTIFICER_KEY, PersistentDataType.BYTE))
                .map(e -> (Villager) e)
                .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(player.getLocation())))
                .orElse(null);
    }

    private static String heldName(ItemStack item) {
        if (item == null) return "nothing";
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName())
            return item.getItemMeta().getDisplayName();
        String s = item.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void sendOffer(Player player, String service, int cost, String note) {
        player.sendMessage("  §8[§7" + service + "§8] §f" + cost + " shards §8— " + note);
    }

    private void playCraft(Player player) {
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.7f, 1.2f);
        player.getWorld().spawnParticle(Particle.ENCHANT,
                player.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.1);
    }
}
