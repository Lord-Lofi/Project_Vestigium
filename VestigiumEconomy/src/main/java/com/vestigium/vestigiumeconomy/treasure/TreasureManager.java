package com.vestigium.vestigiumeconomy.treasure;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Structure loot tables loaded from plugins/VestigiumEconomy/loot/*.yml.
 *
 * Chests tagged with "vestigium:loot_table" PDC STRING are populated
 * on first open. The chest is marked "ve_looted" after population to prevent
 * re-rolling.
 *
 * Loot table YAML:
 *   id: string
 *   rolls: int (how many items to pick)
 *   entries:
 *     - material: IRON_INGOT
 *       weight: 10
 *       min: 1
 *       max: 4
 *       shard_bonus: 5   (optional — adds Vestige Shards to opener's balance)
 *     - material: DIAMOND
 *       weight: 2
 *       min: 1
 *       max: 1
 *
 * Default loot tables are seeded for: common_ruin, ancient_vault, cartographer_cache.
 */
public class TreasureManager implements Listener {

    private static final NamespacedKey LOOT_TABLE_KEY =
            new NamespacedKey("vestigium", "loot_table");
    private static final NamespacedKey LOOTED_KEY =
            new NamespacedKey("vestigium", "ve_looted");

    private final VestigiumEconomy plugin;
    private final Map<String, LootTable> tables = new HashMap<>();

    public TreasureManager(VestigiumEconomy plugin) {
        this.plugin = plugin;
    }

    public void init() {
        File dir = new File(plugin.getDataFolder(), "loot");
        if (!dir.exists()) { dir.mkdirs(); saveDefaults(dir); }

        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                try {
                    LootTable t = LootTable.fromConfig(YamlConfiguration.loadConfiguration(f));
                    tables.put(t.id(), t);
                } catch (Exception e) {
                    plugin.getLogger().warning("[TreasureManager] Failed to load " + f.getName());
                }
            }
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[TreasureManager] Loaded " + tables.size() + " loot tables.");
    }

    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChestOpen(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (!(event.getClickedBlock().getState() instanceof Chest chest)) return;

        var pdc = chest.getPersistentDataContainer();
        String tableId = pdc.get(LOOT_TABLE_KEY, PersistentDataType.STRING);
        if (tableId == null) return;
        if (pdc.getOrDefault(LOOTED_KEY, PersistentDataType.BOOLEAN, false)) return;

        if (VestigiumLib.getProtectionAPI().isProtected(chest.getLocation())) return;

        LootTable table = tables.get(tableId);
        if (table == null) return;

        pdc.set(LOOTED_KEY, PersistentDataType.BOOLEAN, true);
        chest.update();

        List<ItemStack> loot = table.roll();
        var inv = chest.getInventory();
        inv.clear();
        for (ItemStack item : loot) {
            inv.addItem(item);
        }

        // Grant shard bonus
        int shardBonus = table.rollShardBonus();
        if (shardBonus > 0) {
            plugin.getCurrencyManager().addShards(event.getPlayer(), shardBonus);
            event.getPlayer().sendMessage("§dFound §f" + shardBonus
                    + " §dVestige Shards §damong the loot.");
        }
    }

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    private void saveDefaults(File dir) {
        record DefaultEntry(String mat, int weight, int min, int max, int shardBonus) {}
        record DefaultTable(String id, int rolls, List<DefaultEntry> entries) {}

        List<DefaultTable> defaults = List.of(
            new DefaultTable("common_ruin", 4, List.of(
                    new DefaultEntry("IRON_INGOT", 10, 1, 4, 0),
                    new DefaultEntry("BREAD", 8, 1, 3, 0),
                    new DefaultEntry("COAL", 12, 2, 6, 0),
                    new DefaultEntry("AMETHYST_SHARD", 3, 1, 2, 5))),
            new DefaultTable("ancient_vault", 6, List.of(
                    new DefaultEntry("DIAMOND", 2, 1, 2, 0),
                    new DefaultEntry("GOLD_INGOT", 6, 2, 5, 0),
                    new DefaultEntry("ECHO_SHARD", 1, 1, 1, 20),
                    new DefaultEntry("IRON_INGOT", 8, 3, 8, 0),
                    new DefaultEntry("AMETHYST_SHARD", 4, 2, 4, 10))),
            new DefaultTable("cartographer_cache", 5, List.of(
                    new DefaultEntry("MAP", 6, 1, 1, 0),
                    new DefaultEntry("COMPASS", 4, 1, 1, 0),
                    new DefaultEntry("PAPER", 10, 4, 8, 0),
                    new DefaultEntry("AMETHYST_SHARD", 5, 1, 3, 15),
                    new DefaultEntry("DIAMOND", 1, 1, 1, 0)))
        );

        for (DefaultTable dt : defaults) {
            File f = new File(dir, dt.id() + ".yml");
            if (f.exists()) continue;
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("id", dt.id());
            cfg.set("rolls", dt.rolls());
            List<Map<String, Object>> entries = new ArrayList<>();
            for (DefaultEntry de : dt.entries()) {
                entries.add(Map.of("material", de.mat(), "weight", de.weight(),
                        "min", de.min(), "max", de.max(), "shard_bonus", de.shardBonus()));
            }
            cfg.set("entries", entries);
            try { cfg.save(f); } catch (Exception e) { /* best-effort */ }
        }
    }

    // -------------------------------------------------------------------------
    // Loot table model
    // -------------------------------------------------------------------------

    private record LootEntry(Material material, int weight, int min, int max, int shardBonus) {}

    private record LootTable(String id, int rolls, List<LootEntry> entries) {

        static LootTable fromConfig(YamlConfiguration cfg) {
            String id = cfg.getString("id", "unknown");
            int rolls = cfg.getInt("rolls", 3);
            List<LootEntry> list = new ArrayList<>();
            for (var raw : cfg.getMapList("entries")) {
                try {
                    Material mat = Material.valueOf(raw.get("material").toString().toUpperCase());
                    int weight     = raw.get("weight")     instanceof Number n ? n.intValue() : 1;
                    int min        = raw.get("min")        instanceof Number n ? n.intValue() : 1;
                    int max        = raw.get("max")        instanceof Number n ? n.intValue() : 1;
                    int shardBonus = raw.get("shard_bonus") instanceof Number n ? n.intValue() : 0;
                    list.add(new LootEntry(mat, weight, min, max, shardBonus));
                } catch (Exception ignored) {}
            }
            return new LootTable(id, rolls, list);
        }

        List<ItemStack> roll() {
            int totalWeight = entries.stream().mapToInt(LootEntry::weight).sum();
            List<ItemStack> result = new ArrayList<>();
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (int i = 0; i < rolls; i++) {
                int roll = rng.nextInt(totalWeight);
                int cumulative = 0;
                for (LootEntry entry : entries) {
                    cumulative += entry.weight();
                    if (roll < cumulative) {
                        int amount = rng.nextInt(entry.min(), entry.max() + 1);
                        result.add(new ItemStack(entry.material(), amount));
                        break;
                    }
                }
            }
            return result;
        }

        int rollShardBonus() {
            return entries.stream()
                    .filter(e -> e.shardBonus() > 0)
                    .mapToInt(e -> ThreadLocalRandom.current().nextBoolean() ? e.shardBonus() : 0)
                    .sum();
        }
    }
}
