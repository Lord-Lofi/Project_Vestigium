package com.vestigium.vestigiumeconomy.specialitems;

import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Shrine Blessings — five shrine types registered on Chiseled Stone Brick blocks.
 * Right-clicking a shrine with the required offering grants a powerful temporary buff.
 * 45-minute per-player per-shrine cooldown stored in player PDC.
 *
 * Shrine types:
 *   VALOR    — 5 Emeralds    → Strength II (10m) + Resistance I (5m)
 *   TIDE     — 4 Prismarine  → Water Breathing (15m) + Dolphin's Grace (5m)
 *   RESONANT — 1 Echo Shard  → Night Vision (15m) + darkness resistance (PDC timestamp)
 *   GROWTH   — 8 Wheat       → Regen I (15m) + Saturation (5m)
 *   VOID     — 1 Ender Pearl → Resistance I (10m) + Slow Fall (5m)
 *
 * Each shrine is a block tagged with:
 *   vestigium:shrine_type STRING (the shrine type id)
 * on the block's tile entity — but Chiseled Stone Brick is not a tile entity.
 * Instead, shrine locations are persisted to shrine_stations.yml and checked
 * by coordinate key "<world>:<x>:<y>:<z>".
 */
public class ShrineBlessingManager implements Listener, CommandExecutor {

    private static final NamespacedKey SHRINE_CD_KEY =
            new NamespacedKey("vestigium", "shrine_cd");

    private static final long COOLDOWN_MS = 45L * 60 * 1000;
    private static final long DARKNESS_DURATION_MS = 15L * 60 * 1000;

    private final VestigiumEconomy plugin;
    private final File              dataFile;
    private YamlConfiguration      data;

    // key: "world:x:y:z" → shrine type id
    private final Map<String, ShrineType> shrines = new HashMap<>();

    public ShrineBlessingManager(VestigiumEconomy plugin) {
        this.plugin   = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "shrine_stations.yml");
    }

    public void init() {
        loadData();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("veshrine");
        if (cmd != null) cmd.setExecutor(this);
        plugin.getLogger().info("[ShrineBlessingManager] Initialized — " + shrines.size() + " shrine(s).");
    }

    // -------------------------------------------------------------------------
    // Shrine interaction
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShrineClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand()   != EquipmentSlot.HAND)       return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        String locKey = locKey(block.getLocation());
        ShrineType shrine = shrines.get(locKey);
        if (shrine == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        // Cooldown check
        String cdKey = locKey + ":" + shrine.id();
        long now = System.currentTimeMillis();
        long expiry = getPlayerCooldown(player, cdKey);
        if (now < expiry) {
            long mins = (expiry - now) / 60000;
            player.sendMessage("§8The §f" + shrine.displayName() + " §8shrine rests. Returns in §f" + mins + " min§8.");
            return;
        }

        // Offering check
        if (!consumeOffering(player, shrine)) {
            player.sendMessage("§7The shrine requires: §f" + shrine.offeringDescription() + "§7.");
            return;
        }

        // Apply blessings
        applyBlessing(player, shrine);
        setPlayerCooldown(player, cdKey, now + COOLDOWN_MS);

        player.sendMessage("§6The " + shrine.displayName() + " blesses you.");
        player.getWorld().playSound(block.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
        player.getWorld().spawnParticle(Particle.END_ROD, block.getLocation().add(0.5, 1, 0.5),
                30, 0.5, 0.5, 0.5, 0.02);
    }

    // -------------------------------------------------------------------------
    // Command — /veshrine
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7/veshrine <set <type> | remove | list | types>");
            return true;
        }
        switch (args[0].toLowerCase()) {

            case "set" -> {
                if (!sender.hasPermission("vestigium.shrine.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (args.length < 2) { sender.sendMessage("§7/veshrine set <type>"); return true; }
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
                ShrineType type = ShrineType.byId(args[1]);
                if (type == null) { sender.sendMessage("§cUnknown shrine type. Use /veshrine types."); return true; }
                Block target = player.getTargetBlockExact(5);
                if (target == null) { sender.sendMessage("§cLook at a block (within 5 blocks)."); return true; }
                String key = locKey(target.getLocation());
                shrines.put(key, type);
                saveShrine(key, type, target.getLocation());
                sender.sendMessage("§aBlock registered as §f" + type.displayName() + " §ashrine.");
            }

            case "remove" -> {
                if (!sender.hasPermission("vestigium.shrine.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
                Block target = player.getTargetBlockExact(5);
                if (target == null) { sender.sendMessage("§cLook at a block."); return true; }
                String key = locKey(target.getLocation());
                if (shrines.remove(key) == null) { sender.sendMessage("§cNo shrine at that block."); return true; }
                data.set(key, null);
                saveData();
                sender.sendMessage("§aShrine removed.");
            }

            case "list" -> {
                sender.sendMessage("§6§l── Shrines ──");
                if (shrines.isEmpty()) { sender.sendMessage("§7None registered."); return true; }
                shrines.forEach((k, t) -> sender.sendMessage("§8• §f" + t.displayName() + " §8— §7" + k));
            }

            case "types" -> {
                sender.sendMessage("§6§l── Shrine Types ──");
                for (ShrineType t : ShrineType.values())
                    sender.sendMessage("  §f" + t.id() + " §8— §7" + t.displayName()
                            + " §8(§7" + t.offeringDescription() + "§8)");
            }

            default -> sender.sendMessage("§7/veshrine <set <type> | remove | list | types>");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Blessing application
    // -------------------------------------------------------------------------

    private void applyBlessing(Player player, ShrineType shrine) {
        switch (shrine) {
            case VALOR -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,   12000, 1, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,  6000, 0, true, false));
            }
            case TIDE -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 18000, 0, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE,   6000, 0, true, false));
            }
            case RESONANT -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 18000, 0, true, false));
                long expire = System.currentTimeMillis() + DARKNESS_DURATION_MS;
                player.getPersistentDataContainer()
                        .set(new NamespacedKey("vestigium", "resonant_shrine_expire"),
                                PersistentDataType.LONG, expire);
            }
            case GROWTH -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 18000, 0, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION,    6000, 0, true, false));
            }
            case VOID -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,  12000, 0, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 6000, 0, true, false));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Offering consumption
    // -------------------------------------------------------------------------

    private boolean consumeOffering(Player player, ShrineType shrine) {
        ItemStack[] offering = shrine.offering();
        for (ItemStack req : offering) {
            int count = 0;
            for (ItemStack inv : player.getInventory().getContents()) {
                if (inv != null && inv.getType() == req.getType()) count += inv.getAmount();
            }
            if (count < req.getAmount()) return false;
        }
        // Consume
        for (ItemStack req : offering) {
            int remaining = req.getAmount();
            for (ItemStack inv : player.getInventory().getContents()) {
                if (inv == null || inv.getType() != req.getType()) continue;
                if (inv.getAmount() <= remaining) { remaining -= inv.getAmount(); inv.setAmount(0); }
                else { inv.setAmount(inv.getAmount() - remaining); remaining = 0; }
                if (remaining == 0) break;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Cooldown (stored as "<shrineKey>:<typeId>" → LONG in player PDC)
    // -------------------------------------------------------------------------

    private long getPlayerCooldown(Player player, String key) {
        String encoded = player.getPersistentDataContainer()
                .getOrDefault(SHRINE_CD_KEY, PersistentDataType.STRING, "");
        for (String entry : encoded.split(";")) {
            String[] parts = entry.split("=", 2);
            if (parts.length == 2 && parts[0].equals(key)) {
                try { return Long.parseLong(parts[1]); } catch (NumberFormatException ignored) {}
            }
        }
        return 0L;
    }

    private void setPlayerCooldown(Player player, String key, long expiry) {
        String encoded = player.getPersistentDataContainer()
                .getOrDefault(SHRINE_CD_KEY, PersistentDataType.STRING, "");
        Map<String, Long> map = new LinkedHashMap<>();
        for (String entry : encoded.split(";")) {
            String[] parts = entry.split("=", 2);
            if (parts.length == 2) {
                try { map.put(parts[0], Long.parseLong(parts[1])); } catch (NumberFormatException ignored) {}
            }
        }
        map.put(key, expiry);
        StringBuilder sb = new StringBuilder();
        map.forEach((k, v) -> { if (!sb.isEmpty()) sb.append(';'); sb.append(k).append('=').append(v); });
        player.getPersistentDataContainer().set(SHRINE_CD_KEY, PersistentDataType.STRING, sb.toString());
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void loadData() {
        data = dataFile.exists() ? YamlConfiguration.loadConfiguration(dataFile) : new YamlConfiguration();
        for (String key : data.getKeys(false)) {
            String typeId = data.getString(key + ".type");
            ShrineType type = typeId != null ? ShrineType.byId(typeId) : null;
            if (type != null) shrines.put(key, type);
        }
    }

    private void saveShrine(String key, ShrineType type, Location loc) {
        data.set(key + ".type",  type.id());
        data.set(key + ".world", loc.getWorld().getName());
        data.set(key + ".x",     loc.getBlockX());
        data.set(key + ".y",     loc.getBlockY());
        data.set(key + ".z",     loc.getBlockZ());
        saveData();
    }

    private void saveData() {
        try { data.save(dataFile); }
        catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Failed to save shrine_stations.yml", e); }
    }

    private static String locKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    // -------------------------------------------------------------------------
    // Shrine catalogue
    // -------------------------------------------------------------------------

    public enum ShrineType {
        VALOR("valor",     "§cShrine of Valor",
                new ItemStack[]{new ItemStack(Material.EMERALD, 5)},
                "5 Emeralds"),
        TIDE("tide",       "§3Shrine of the Tide",
                new ItemStack[]{new ItemStack(Material.PRISMARINE, 4)},
                "4 Prismarine"),
        RESONANT("resonant", "§9Shrine of Resonance",
                new ItemStack[]{new ItemStack(Material.ECHO_SHARD, 1)},
                "1 Echo Shard"),
        GROWTH("growth",   "§aShrine of Growth",
                new ItemStack[]{new ItemStack(Material.WHEAT, 8)},
                "8 Wheat"),
        VOID("void",       "§8Shrine of the Void",
                new ItemStack[]{new ItemStack(Material.ENDER_PEARL, 1)},
                "1 Ender Pearl");

        private final String      id;
        private final String      displayName;
        private final ItemStack[] offering;
        private final String      offeringDescription;

        ShrineType(String id, String displayName, ItemStack[] offering, String offeringDescription) {
            this.id                  = id;
            this.displayName         = displayName;
            this.offering            = offering;
            this.offeringDescription = offeringDescription;
        }

        public String      id()                  { return id; }
        public String      displayName()         { return displayName; }
        public ItemStack[] offering()            { return offering; }
        public String      offeringDescription() { return offeringDescription; }

        public static ShrineType byId(String id) {
            for (ShrineType t : values()) if (t.id.equals(id)) return t;
            return null;
        }
    }
}
