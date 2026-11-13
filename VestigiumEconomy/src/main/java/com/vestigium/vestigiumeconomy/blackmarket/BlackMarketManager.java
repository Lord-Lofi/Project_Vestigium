package com.vestigium.vestigiumeconomy.blackmarket;

import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Black Market — a hidden Wandering Trader NPC gated behind notoriety ≥ 75
 * (NOTORIOUS or MOST_WANTED). Players at lower notoriety are turned away.
 *
 * Offers five items purchasable with Emeralds or Gold Nuggets:
 *   1. Elusive Tincture  — Invisibility III (5m) + Speed II (3m)
 *   2. Shadow Powder     — AoE Blindness II + Slowness I in 4 blocks (right-click air)
 *   3. Forger's Brand    — tags held Vestigium gear as cursed (vestigium:cursed)
 *   4. Syndicate Token   — stamps notoriety-suppression timestamp on player
 *   5. Warden's Eye      — reveals nearest Named Warden coords (30-min item cooldown)
 *
 * NPC: Wandering Trader tagged with vestigium:black_market BYTE PDC.
 * Persistence: black_market.yml (world:x:y:z of each spawned trader's location).
 * Admin: /veblackmarket spawn | remove | list
 */
public class BlackMarketManager implements Listener, CommandExecutor {

    // PDC keys
    private static final NamespacedKey BM_NPC_KEY     = new NamespacedKey("vestigium", "black_market");
    private static final NamespacedKey NOTORIETY_KEY  = new NamespacedKey("vestigium", "notoriety");
    private static final NamespacedKey SYNDICATE_KEY  = new NamespacedKey("vestigium", "syndicate_expire");
    private static final NamespacedKey WARDEN_EYE_CD  = new NamespacedKey("vestigium", "warden_eye_cd");
    private static final NamespacedKey ITEM_ID_KEY    = new NamespacedKey("vestigium", "bm_item_id");

    // PDC keys for cursed-item and Vestigium-gear detection (must match SalvageManager)
    private static final NamespacedKey CURSED_KEY     = new NamespacedKey("vestigium", "cursed");
    private static final NamespacedKey TEMPER_KEY     = new NamespacedKey("vestigium", "temper");
    private static final NamespacedKey RUNES_KEY      = new NamespacedKey("vestigium", "runes");
    private static final NamespacedKey COMP_BLADE_KEY = new NamespacedKey("vestigium", "weapon_blade");

    private static final int NOTORIETY_GATE = 75;

    // Item CMD slots
    private static final int CMD_TINCTURE = 30002;
    private static final int CMD_POWDER   = 30003;
    private static final int CMD_BRAND    = 30004;
    private static final int CMD_TOKEN    = 30005;
    private static final int CMD_EYE      = 30006;

    private static final long SYNDICATE_DURATION_MS = 15L * 60 * 1000;
    private static final long WARDEN_EYE_COOLDOWN   = 30L * 60 * 1000;

    private final VestigiumEconomy plugin;
    private final File              dataFile;
    private YamlConfiguration      data;
    private final Set<UUID>         registeredNpcs = new HashSet<>();

    public BlackMarketManager(VestigiumEconomy plugin) {
        this.plugin   = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "black_market.yml");
    }

    public void init() {
        loadData();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("veblackmarket");
        if (cmd != null) cmd.setExecutor(this);
        plugin.getLogger().info("[BlackMarketManager] Initialized — "
                + registeredNpcs.size() + " registered trader(s).");
    }

    // -------------------------------------------------------------------------
    // NPC interaction
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTraderClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof WanderingTrader trader)) return;

        var pdc = trader.getPersistentDataContainer();
        if (!pdc.has(BM_NPC_KEY, PersistentDataType.BYTE)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (!hasGateAccess(player)) {
            player.sendMessage("§8\"§7I don't know you. Come back when your name means something.§8\"");
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.9f);
            return;
        }

        openBlackMarket(trader, player);
    }

    // -------------------------------------------------------------------------
    // Item effect handlers
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        String id = getItemId(item);
        if (id == null) return;

        Player player = event.getPlayer();

        switch (id) {
            case "elusive_tincture" -> {
                event.setCancelled(true);
                consume(player, item);
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 6000, 2, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,        3600, 1, true, false));
                player.sendMessage("§8§o\"You are no longer here.\"");
                player.getWorld().playSound(player.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 1f, 1.2f);
            }
            case "syndicate_token" -> {
                event.setCancelled(true);
                consume(player, item);
                long expiry = System.currentTimeMillis() + SYNDICATE_DURATION_MS;
                player.getPersistentDataContainer()
                        .set(SYNDICATE_KEY, PersistentDataType.LONG, expiry);
                player.sendMessage("§7The Syndicate acknowledges you. Authorities will look elsewhere for §e15 minutes§7.");
                player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 0.8f);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRightClickItem(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getItem() == null) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        String id = getItemId(item);
        if (id == null) return;

        switch (id) {
            case "shadow_powder" -> {
                if (event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
                event.setCancelled(true);
                consume(player, item);
                Location loc = player.getLocation();
                for (Entity e : loc.getWorld().getNearbyEntities(loc, 4, 4, 4)) {
                    if (e instanceof LivingEntity living) {
                        living.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 1, true, true));
                        living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,  100, 0, true, true));
                    }
                }
                loc.getWorld().spawnParticle(Particle.SQUID_INK, loc.add(0, 1, 0), 30, 2, 1, 2, 0.02);
                loc.getWorld().playSound(loc, Sound.ENTITY_SQUID_SQUIRT, 1f, 0.6f);
                player.sendMessage("§8The powder bursts.");
            }
            case "forgers_brand" -> {
                if (event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
                // Brand applies to the item in the *off hand*, not the brand itself
                ItemStack target = player.getInventory().getItemInOffHand();
                if (!isVestigiumGear(target)) {
                    player.sendMessage("§cHold a Vestigium-tagged item in your §foff hand §cto brand it.");
                    return;
                }
                event.setCancelled(true);
                ItemMeta meta = target.getItemMeta();
                if (meta == null) return;
                meta.getPersistentDataContainer().set(CURSED_KEY, PersistentDataType.BYTE, (byte) 1);
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add("§8✦ §4Cursed §8— releases a hex when salvaged.");
                meta.setLore(lore);
                target.setItemMeta(meta);
                player.getInventory().setItemInOffHand(target);
                consume(player, item);
                player.sendMessage("§4The brand takes hold.");
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.5f, 1.5f);
            }
            case "wardens_eye" -> {
                if (event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
                event.setCancelled(true);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    long now = System.currentTimeMillis();
                    long cd  = meta.getPersistentDataContainer()
                            .getOrDefault(WARDEN_EYE_CD, PersistentDataType.LONG, 0L);
                    if (now < cd) {
                        long remaining = (cd - now) / 60000;
                        player.sendMessage("§7The eye is clouded. Recharges in §f" + remaining + " min§7.");
                        return;
                    }
                    // Scan for named Wardens
                    Warden nearest = findNamedWarden(player);
                    if (nearest == null) {
                        player.sendMessage("§7The eye reveals no Named Wardens in this world.");
                    } else {
                        Location wl = nearest.getLocation();
                        player.sendMessage("§5A Named Warden stirs — §f"
                                + nearest.getCustomName() + " §5at §f"
                                + wl.getBlockX() + " " + wl.getBlockY() + " " + wl.getBlockZ()
                                + " §5(§f" + (int) player.getLocation().distance(wl) + " §5blocks)");
                        player.getWorld().playSound(player.getLocation(),
                                Sound.ENTITY_WARDEN_SONIC_BOOM, 0.4f, 0.6f);
                    }
                    meta.getPersistentDataContainer()
                            .set(WARDEN_EYE_CD, PersistentDataType.LONG, now + WARDEN_EYE_COOLDOWN);
                    item.setItemMeta(meta);
                    player.getInventory().setItemInMainHand(item);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Merchant window
    // -------------------------------------------------------------------------

    private void openBlackMarket(WanderingTrader npc, Player player) {
        List<MerchantRecipe> recipes = new ArrayList<>();

        recipes.add(recipe(createEmeralds(5),  createTincture()));
        recipes.add(recipe(createGoldNuggets(8), createShadowPowder()));
        recipes.add(recipe(createEmeralds(3),  createForgersBrand()));
        recipes.add(recipe(createEmeralds(10), createSyndicateToken()));
        recipes.add(recipe(createEmeralds(15), createWardensEye()));

        npc.setRecipes(recipes);
        player.openMerchant(npc, true);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WANDERING_TRADER_AMBIENT, 0.5f, 0.7f);
    }

    // -------------------------------------------------------------------------
    // Command — /veblackmarket
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7/veblackmarket <spawn | remove | list>");
            return true;
        }
        switch (args[0].toLowerCase()) {

            case "spawn" -> {
                if (!sender.hasPermission("vestigium.blackmarket.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
                WanderingTrader npc = (WanderingTrader) player.getWorld()
                        .spawnEntity(player.getLocation(), EntityType.WANDERING_TRADER);
                npc.setCustomName("§8The Broker");
                npc.setCustomNameVisible(false);
                npc.setAI(false);
                npc.setSilent(true);
                npc.setPersistent(true);
                npc.setInvulnerable(true);
                npc.getPersistentDataContainer().set(BM_NPC_KEY, PersistentDataType.BYTE, (byte) 1);
                registeredNpcs.add(npc.getUniqueId());
                saveNpc(npc);
                sender.sendMessage("§aBlack Market broker spawned.");
            }

            case "remove" -> {
                if (!sender.hasPermission("vestigium.blackmarket.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
                WanderingTrader nearest = findNearestBroker(player, 8);
                if (nearest == null) { sender.sendMessage("§cNo Black Market broker within 8 blocks."); return true; }
                registeredNpcs.remove(nearest.getUniqueId());
                removeNpcFromData(nearest.getUniqueId());
                nearest.remove();
                sender.sendMessage("§aBlack Market broker removed.");
            }

            case "list" -> {
                sender.sendMessage("§6§l── Black Market Brokers ──");
                if (registeredNpcs.isEmpty()) { sender.sendMessage("§7None registered."); return true; }
                for (String key : data.getKeys(false)) {
                    sender.sendMessage("§8• §7" + key);
                }
            }

            default -> sender.sendMessage("§7/veblackmarket <spawn | remove | list>");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Item factories
    // -------------------------------------------------------------------------

    private ItemStack createTincture() {
        ItemStack item = new ItemStack(Material.GLASS_BOTTLE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName("§9Elusive Tincture");
        meta.setCustomModelData(CMD_TINCTURE);
        meta.setLore(List.of(
                "§7Invisibility III (5m) + Speed II (3m) on consume.",
                "§8§oObtained at great personal risk."
        ));
        meta.getPersistentDataContainer().set(ITEM_ID_KEY, PersistentDataType.STRING, "elusive_tincture");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createShadowPowder() {
        ItemStack item = new ItemStack(Material.GUNPOWDER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName("§8Shadow Powder");
        meta.setCustomModelData(CMD_POWDER);
        meta.setLore(List.of(
                "§7Right-click to release: Blindness II + Slowness I",
                "§7to all entities within 4 blocks for 5 seconds.",
                "§8§oNo questions asked."
        ));
        meta.getPersistentDataContainer().set(ITEM_ID_KEY, PersistentDataType.STRING, "shadow_powder");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createForgersBrand() {
        ItemStack item = new ItemStack(Material.FEATHER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName("§4Forger's Brand");
        meta.setCustomModelData(CMD_BRAND);
        meta.setLore(List.of(
                "§7Hold Vestigium gear in off hand, right-click to brand.",
                "§7Branded items release a hex zone when salvaged (25%).",
                "§8Single use."
        ));
        meta.getPersistentDataContainer().set(ITEM_ID_KEY, PersistentDataType.STRING, "forgers_brand");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createSyndicateToken() {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName("§6Syndicate Token");
        meta.setCustomModelData(CMD_TOKEN);
        meta.setLore(List.of(
                "§7Consume to enter Syndicate protection for 15 minutes.",
                "§7Authorities are slow to notice your transgressions.",
                "§8Single use."
        ));
        meta.getPersistentDataContainer().set(ITEM_ID_KEY, PersistentDataType.STRING, "syndicate_token");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createWardensEye() {
        ItemStack item = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName("§5Warden's Eye");
        meta.setCustomModelData(CMD_EYE);
        meta.setLore(List.of(
                "§7Right-click to reveal the nearest Named Warden's location.",
                "§830-minute cooldown stored in item."
        ));
        meta.getPersistentDataContainer().set(ITEM_ID_KEY, PersistentDataType.STRING, "wardens_eye");
        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void loadData() {
        data = dataFile.exists() ? YamlConfiguration.loadConfiguration(dataFile) : new YamlConfiguration();
        for (String key : data.getKeys(false)) {
            try { registeredNpcs.add(UUID.fromString(data.getString(key + ".uuid", ""))); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveNpc(WanderingTrader npc) {
        String key = npc.getUniqueId().toString();
        Location loc = npc.getLocation();
        data.set(key + ".uuid",  npc.getUniqueId().toString());
        data.set(key + ".world", loc.getWorld().getName());
        data.set(key + ".x",     loc.getX());
        data.set(key + ".y",     loc.getY());
        data.set(key + ".z",     loc.getZ());
        saveData();
    }

    private void removeNpcFromData(UUID uuid) {
        data.set(uuid.toString(), null);
        saveData();
    }

    private void saveData() {
        try { data.save(dataFile); }
        catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Failed to save black_market.yml", e); }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean hasGateAccess(Player player) {
        int notoriety = player.getPersistentDataContainer()
                .getOrDefault(NOTORIETY_KEY, PersistentDataType.INTEGER, 0);
        return notoriety >= NOTORIETY_GATE;
    }

    private String getItemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
                .get(ITEM_ID_KEY, PersistentDataType.STRING);
    }

    private boolean isVestigiumGear(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(TEMPER_KEY, PersistentDataType.STRING)
            || pdc.has(RUNES_KEY,  PersistentDataType.STRING)
            || pdc.has(COMP_BLADE_KEY, PersistentDataType.STRING);
    }

    private Warden findNamedWarden(Player player) {
        return player.getWorld().getEntitiesByClass(Warden.class).stream()
                .filter(w -> w.getCustomName() != null && !w.getCustomName().isEmpty())
                .min(Comparator.comparingDouble(w -> w.getLocation().distanceSquared(player.getLocation())))
                .orElse(null);
    }

    private WanderingTrader findNearestBroker(Player player, double radius) {
        return player.getNearbyEntities(radius, radius, radius).stream()
                .filter(e -> e instanceof WanderingTrader wt
                        && wt.getPersistentDataContainer().has(BM_NPC_KEY, PersistentDataType.BYTE))
                .map(e -> (WanderingTrader) e)
                .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(player.getLocation())))
                .orElse(null);
    }

    private static void consume(Player player, ItemStack item) {
        if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
        else player.getInventory().setItemInMainHand(null);
    }

    private static MerchantRecipe recipe(ItemStack price, ItemStack result) {
        MerchantRecipe r = new MerchantRecipe(result, Integer.MAX_VALUE);
        r.addIngredient(price);
        r.setExperienceReward(false);
        return r;
    }

    private static ItemStack createEmeralds(int amount) {
        return new ItemStack(Material.EMERALD, amount);
    }

    private static ItemStack createGoldNuggets(int amount) {
        return new ItemStack(Material.GOLD_NUGGET, amount);
    }
}
