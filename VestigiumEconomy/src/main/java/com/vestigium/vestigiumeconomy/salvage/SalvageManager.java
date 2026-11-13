package com.vestigium.vestigiumeconomy.salvage;

import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import com.vestigium.vestigiumeconomy.component.ComponentWeaponManager.ComponentType;
import com.vestigium.vestigiumeconomy.runic.RuneManager.RuneType;
import com.vestigium.vestigiumeconomy.temper.TemperManager.TemperType;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Salvage System — right-click any Grindstone with Vestigium gear (main hand) to
 * break it down into recoverable materials.
 *
 * Returns:
 *  - Base material drop (always)
 *  - 60% chance to recover temper shard
 *  - 50% chance per rune to recover that rune item
 *  - 75% chance per component type to recover that component item
 *  - Cursed items (vestigium:cursed): 25% chance to spawn a hex zone on break
 */
public class SalvageManager implements Listener {

    private static final NamespacedKey TEMPER_KEY   = new NamespacedKey("vestigium", "temper");
    private static final NamespacedKey RUNES_KEY    = new NamespacedKey("vestigium", "runes");
    private static final NamespacedKey BLADE_KEY    = new NamespacedKey("vestigium", "weapon_blade");
    private static final NamespacedKey HILT_KEY     = new NamespacedKey("vestigium", "weapon_hilt");
    private static final NamespacedKey CORE_KEY     = new NamespacedKey("vestigium", "weapon_core");
    private static final NamespacedKey CURSED_KEY   = new NamespacedKey("vestigium", "cursed");

    private static final double TEMPER_RECOVER_CHANCE    = 0.60;
    private static final double RUNE_RECOVER_CHANCE      = 0.50;
    private static final double COMPONENT_RECOVER_CHANCE = 0.75;
    private static final double CURSE_HEX_CHANCE         = 0.25;

    private final VestigiumEconomy plugin;
    private final Random rng = new Random();

    public SalvageManager(VestigiumEconomy plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[SalvageManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Grindstone salvage
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrindstone(PlayerInteractEvent event) {
        if (event.getAction()       != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand()         != EquipmentSlot.HAND)       return;
        if (event.getClickedBlock() == null)                      return;
        if (event.getClickedBlock().getType() != Material.GRINDSTONE) return;

        Player    player = event.getPlayer();
        ItemStack held   = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == Material.AIR || !held.hasItemMeta()) return;

        // Only intercept Vestigium-tagged items
        if (!isVestigiumItem(held)) return;

        event.setCancelled(true);

        ItemMeta meta = held.getItemMeta();
        List<ItemStack> drops = new ArrayList<>();

        // Base material
        drops.add(new ItemStack(getBaseMaterial(held.getType()), 1));

        // Temper shard recovery
        String temperId = meta.getPersistentDataContainer().get(TEMPER_KEY, PersistentDataType.STRING);
        if (temperId != null) {
            TemperType temper = TemperType.byId(temperId);
            if (temper != null && rng.nextDouble() < TEMPER_RECOVER_CHANCE) {
                drops.add(plugin.getTemperManager().createShard(temper));
            }
        }

        // Rune recovery
        String runesRaw = meta.getPersistentDataContainer().get(RUNES_KEY, PersistentDataType.STRING);
        if (runesRaw != null && !runesRaw.isBlank()) {
            for (String runeId : runesRaw.split(",")) {
                RuneType rune = RuneType.byId(runeId);
                if (rune != null && rng.nextDouble() < RUNE_RECOVER_CHANCE) {
                    drops.add(plugin.getRuneManager().createRune(rune));
                }
            }
        }

        // Component recovery (component weapons only)
        var pdc = meta.getPersistentDataContainer();
        recoverComponent(pdc, BLADE_KEY, drops);
        recoverComponent(pdc, HILT_KEY,  drops);
        recoverComponent(pdc, CORE_KEY,  drops);

        // Cursed item hex zone
        boolean cursed = pdc.has(CURSED_KEY, PersistentDataType.BYTE);
        if (cursed && rng.nextDouble() < CURSE_HEX_CHANCE) {
            spawnHexZone(player);
        }

        // Consume the item
        if (held.getAmount() > 1) held.setAmount(held.getAmount() - 1);
        else player.getInventory().setItemInMainHand(null);

        // Drop recoveries at player's feet
        Location loc = player.getLocation();
        for (ItemStack drop : drops) {
            player.getWorld().dropItemNaturally(loc, drop);
        }

        // Feedback
        player.sendMessage("§7Salvaged §f" + fmt(held.getType()) + "§7. "
                + "§8(" + drops.size() + " material" + (drops.size() != 1 ? "s" : "") + " recovered)");
        player.getWorld().playSound(loc, Sound.BLOCK_GRINDSTONE_USE, 1f, 0.8f);
        player.getWorld().spawnParticle(Particle.SMOKE, loc.add(0, 1, 0), 12, 0.2, 0.3, 0.2);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isVestigiumItem(ItemStack item) {
        if (!item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(TEMPER_KEY,  PersistentDataType.STRING)
            || pdc.has(RUNES_KEY,   PersistentDataType.STRING)
            || pdc.has(BLADE_KEY,   PersistentDataType.STRING)
            || pdc.has(CURSED_KEY,  PersistentDataType.BYTE);
    }

    private void recoverComponent(
            org.bukkit.persistence.PersistentDataContainer pdc,
            NamespacedKey key,
            List<ItemStack> drops) {
        String id = pdc.get(key, PersistentDataType.STRING);
        if (id == null) return;
        ComponentType t = ComponentType.byId(id);
        if (t != null && rng.nextDouble() < COMPONENT_RECOVER_CHANCE) {
            drops.add(plugin.getComponentWeaponManager().createComponent(t));
        }
    }

    private void spawnHexZone(Player player) {
        Location loc = player.getLocation();
        // Apply Wither + Weakness in a 4-block radius for 5 seconds
        for (org.bukkit.entity.Entity e : loc.getWorld().getNearbyEntities(loc, 4, 4, 4)) {
            if (e instanceof org.bukkit.entity.LivingEntity living) {
                living.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,   100, 0, true, true));
                living.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,  100, 0, true, true));
            }
        }
        loc.getWorld().spawnParticle(Particle.SOUL, loc.add(0, 0.5, 0), 40, 2, 1, 2, 0.05);
        loc.getWorld().playSound(loc, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 1f, 0.6f);
        player.sendMessage("§8The cursed item releases a hex as it breaks...");
    }

    private static Material getBaseMaterial(Material itemType) {
        String n = itemType.name();
        if (n.startsWith("DIAMOND"))  return Material.DIAMOND;
        if (n.startsWith("NETHERITE")) return Material.NETHERITE_SCRAP;
        if (n.startsWith("IRON"))     return Material.IRON_INGOT;
        if (n.startsWith("GOLD"))     return Material.GOLD_INGOT;
        if (n.startsWith("STONE") || n.endsWith("STONE")) return Material.COBBLESTONE;
        if (n.startsWith("WOOD") || n.endsWith("SWORD") && n.startsWith("WOODEN")) return Material.OAK_PLANKS;
        if (itemType == Material.DIAMOND_SWORD) return Material.DIAMOND;
        return Material.IRON_INGOT;
    }

    private static String fmt(Material m) {
        String s = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
