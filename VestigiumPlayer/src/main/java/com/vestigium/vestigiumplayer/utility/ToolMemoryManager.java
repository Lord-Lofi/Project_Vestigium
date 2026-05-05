package com.vestigium.vestigiumplayer.utility;

import com.vestigium.vestigiumplayer.VestigiumPlayer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class ToolMemoryManager implements Listener {

    private static final NamespacedKey USE_COUNT_KEY   = new NamespacedKey("vestigium", "tool_use_count");
    private static final NamespacedKey MEMORY_DONE_KEY = new NamespacedKey("vestigium", "tool_memory_bonus");

    private static final int THRESHOLD = 1000;

    private final VestigiumPlayer plugin;

    public ToolMemoryManager(VestigiumPlayer plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[ToolMemoryManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        if (isMiningTool(tool)) tick(event.getPlayer(), tool);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) return;
        ItemStack weapon = p.getInventory().getItemInMainHand();
        if (isMeleeWeapon(weapon)) tick(p, weapon);
    }

    // -------------------------------------------------------------------------
    // Core logic
    // -------------------------------------------------------------------------

    private void tick(Player player, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        // Skip if bonus already applied
        if (meta.getPersistentDataContainer().has(MEMORY_DONE_KEY, PersistentDataType.BYTE)) return;

        int uses = meta.getPersistentDataContainer().getOrDefault(USE_COUNT_KEY, PersistentDataType.INTEGER, 0) + 1;
        meta.getPersistentDataContainer().set(USE_COUNT_KEY, PersistentDataType.INTEGER, uses);

        if (uses >= THRESHOLD) {
            applyBonus(player, item, meta);
        } else {
            item.setItemMeta(meta);
        }
    }

    private void applyBonus(Player player, ItemStack item, ItemMeta meta) {
        int currentLevel = meta.getEnchantLevel(Enchantment.EFFICIENCY);
        int newLevel = Math.min(currentLevel + 1, 5);
        meta.addEnchant(Enchantment.EFFICIENCY, newLevel, true);
        meta.getPersistentDataContainer().set(MEMORY_DONE_KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);

        player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().add(0, 1, 0), 40, 0.4, 0.4, 0.4, 1.0);
        player.sendActionBar(Component.text("§6✦ §eThis tool remembers you. §7(Efficiency +" + newLevel + ")"));
    }

    // -------------------------------------------------------------------------
    // Material helpers
    // -------------------------------------------------------------------------

    private boolean isMiningTool(ItemStack item) {
        if (item == null) return false;
        String name = item.getType().name();
        return name.endsWith("_PICKAXE") || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
                || item.getType() == Material.SHEARS;
    }

    private boolean isMeleeWeapon(ItemStack item) {
        if (item == null) return false;
        String name = item.getType().name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE");
    }
}
