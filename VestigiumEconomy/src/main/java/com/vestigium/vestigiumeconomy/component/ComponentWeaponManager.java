package com.vestigium.vestigiumeconomy.component;

import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import com.vestigium.vestigiumeconomy.runic.RuneManager;
import org.bukkit.*;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Component Weapons — assemble a custom blade from three parts (blade + hilt + core)
 * at an Anvil. Runic Hilt allows a third rune slot on the assembled weapon.
 *
 * Assembly: hold blade (main hand), have hilt + core anywhere in inventory,
 * right-click any Anvil. All three components are consumed; a Component Sword is produced.
 */
public class ComponentWeaponManager implements Listener, CommandExecutor {

    private static final NamespacedKey COMPONENT_KEY    = new NamespacedKey("vestigium", "component_type");
    private static final NamespacedKey WEAPON_BLADE_KEY = new NamespacedKey("vestigium", "weapon_blade");
    private static final NamespacedKey WEAPON_HILT_KEY  = new NamespacedKey("vestigium", "weapon_hilt");
    private static final NamespacedKey WEAPON_CORE_KEY  = new NamespacedKey("vestigium", "weapon_core");

    static final int MAX_RUNES_RUNIC_HILT = 3;
    private static final int CMD_WEAPON   = 50017;

    private final VestigiumEconomy plugin;

    public ComponentWeaponManager(VestigiumEconomy plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("vecomponent");
        if (cmd != null) cmd.setExecutor(this);
        plugin.getLogger().info("[ComponentWeaponManager] Initialized — "
                + ComponentType.values().length + " component types.");
    }

    // -------------------------------------------------------------------------
    // Anvil assembly
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvil(PlayerInteractEvent event) {
        if (event.getAction()       != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand()         != EquipmentSlot.HAND)       return;
        if (event.getClickedBlock() == null)                      return;
        if (event.getClickedBlock().getType() != Material.ANVIL
                && event.getClickedBlock().getType() != Material.CHIPPED_ANVIL
                && event.getClickedBlock().getType() != Material.DAMAGED_ANVIL) return;

        Player    player = event.getPlayer();
        ItemStack main   = player.getInventory().getItemInMainHand();

        ComponentType blade = getComponentType(main);
        if (blade == null || blade.slot() != ComponentSlot.BLADE) return;

        event.setCancelled(true);

        PlayerInventory inv = player.getInventory();
        ItemStack hiltItem = findComponent(inv, ComponentSlot.HILT);
        ItemStack coreItem = findComponent(inv, ComponentSlot.CORE);

        if (hiltItem == null) { player.sendMessage("§cYou need a §fHilt §ccomponent to assemble."); return; }
        if (coreItem == null) { player.sendMessage("§cYou need a §fCore §ccomponent to assemble."); return; }

        ComponentType hilt = getComponentType(hiltItem);
        ComponentType core = getComponentType(coreItem);

        // Consume components
        removeOne(inv, main);
        removeOne(inv, hiltItem);
        removeOne(inv, coreItem);

        // Build weapon
        ItemStack weapon = buildWeapon(blade, hilt, core);
        inv.addItem(weapon);

        player.sendMessage("§aAssembled: §f" + weapon.getItemMeta().getDisplayName());
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 0.9f);
        player.getWorld().spawnParticle(Particle.CRIT,
                player.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3);
    }

    // -------------------------------------------------------------------------
    // Weapon construction
    // -------------------------------------------------------------------------

    private ItemStack buildWeapon(ComponentType blade, ComponentType hilt, ComponentType core) {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String name = "§f" + blade.displayName() + " §8/ §7" + core.shortName();
        meta.setDisplayName(name);
        meta.setCustomModelData(CMD_WEAPON);

        List<String> lore = new ArrayList<>();
        lore.add("§8Blade: §7" + blade.displayName());
        lore.add("§8Hilt:  §7" + hilt.displayName());
        lore.add("§8Core:  §7" + core.displayName());
        lore.add("");
        lore.add("§7" + blade.effect());
        lore.add("§7" + hilt.effect());
        lore.add("§7" + core.effect());

        boolean hasRunicHilt = hilt == ComponentType.RUNIC_HILT;
        if (hasRunicHilt) {
            lore.add("§8◆ Runic Hilt — §73 rune slots unlocked.");
        }

        meta.setLore(lore);

        var pdc = meta.getPersistentDataContainer();
        pdc.set(WEAPON_BLADE_KEY, PersistentDataType.STRING, blade.id());
        pdc.set(WEAPON_HILT_KEY,  PersistentDataType.STRING, hilt.id());
        pdc.set(WEAPON_CORE_KEY,  PersistentDataType.STRING, core.id());
        if (hasRunicHilt) {
            // Mark expanded rune capacity; checked by RuneManager
            pdc.set(RuneManager.RUNES_KEY, PersistentDataType.STRING, "");
            pdc.set(new NamespacedKey("vestigium", "runic_hilt"), PersistentDataType.BYTE, (byte) 1);
        }

        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------
    // Command — /vecomponent
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7/vecomponent <list | give <type> [player]>");
            return true;
        }
        switch (args[0].toLowerCase()) {

            case "list" -> {
                sender.sendMessage("§6§l── Component Weapons ──");
                sender.sendMessage("§7Hold blade (main), have hilt + core in inventory, right-click §fAnvil§7.");
                sender.sendMessage("§eBlades:");
                for (ComponentType c : ComponentType.values()) {
                    if (c.slot() == ComponentSlot.BLADE)
                        sender.sendMessage("  §8• §f" + c.displayName() + " §8— §7" + c.effect());
                }
                sender.sendMessage("§eHilts:");
                for (ComponentType c : ComponentType.values()) {
                    if (c.slot() == ComponentSlot.HILT)
                        sender.sendMessage("  §8• §f" + c.displayName() + " §8— §7" + c.effect());
                }
                sender.sendMessage("§eCores:");
                for (ComponentType c : ComponentType.values()) {
                    if (c.slot() == ComponentSlot.CORE)
                        sender.sendMessage("  §8• §f" + c.displayName() + " §8— §7" + c.effect());
                }
            }

            case "give" -> {
                if (!sender.hasPermission("vestigium.component.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (args.length < 2) { sender.sendMessage("§7/vecomponent give <type> [player]"); return true; }
                ComponentType t = ComponentType.byId(args[1]);
                if (t == null) { sender.sendMessage("§cUnknown component: §f" + args[1]); return true; }
                Player target = args.length >= 3
                        ? plugin.getServer().getPlayer(args[2])
                        : (sender instanceof Player p ? p : null);
                if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                target.getInventory().addItem(createComponent(t));
                sender.sendMessage("§aGave §f" + t.displayName() + " §ato §f" + target.getName() + "§a.");
            }

            default -> sender.sendMessage("§7/vecomponent <list | give <type> [player]>");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Item factory
    // -------------------------------------------------------------------------

    public ItemStack createComponent(ComponentType type) {
        ItemStack item = new ItemStack(type.material());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(type.displayName());
        meta.setCustomModelData(type.cmd());
        meta.setLore(List.of(
                "§8Slot: §7" + type.slot().name().charAt(0)
                        + type.slot().name().substring(1).toLowerCase(),
                "§7" + type.effect(),
                "§8Assemble blade + hilt + core at an §7Anvil§8."
        ));
        meta.getPersistentDataContainer()
                .set(COMPONENT_KEY, PersistentDataType.STRING, type.id());
        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public ComponentType getComponentType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String s = item.getItemMeta().getPersistentDataContainer()
                .get(COMPONENT_KEY, PersistentDataType.STRING);
        return s == null ? null : ComponentType.byId(s);
    }

    public boolean hasRunicHilt(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(new NamespacedKey("vestigium", "runic_hilt"), PersistentDataType.BYTE);
    }

    private ItemStack findComponent(PlayerInventory inv, ComponentSlot slot) {
        for (ItemStack item : inv.getContents()) {
            if (item == null) continue;
            ComponentType t = getComponentType(item);
            if (t != null && t.slot() == slot) return item;
        }
        return null;
    }

    private void removeOne(PlayerInventory inv, ItemStack target) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack slot = inv.getItem(i);
            if (slot != null && slot.isSimilar(target)) {
                if (slot.getAmount() > 1) slot.setAmount(slot.getAmount() - 1);
                else inv.setItem(i, null);
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Component catalogue
    // -------------------------------------------------------------------------

    public enum ComponentSlot { BLADE, HILT, CORE }

    public enum ComponentType {
        // Blades
        SERRATED_BLADE("serrated_blade", "Serrated Blade", ComponentSlot.BLADE,
                Material.IRON_SWORD, 50007,
                "+1.5 base damage.", "Serrated"),
        HOLLOW_BLADE("hollow_blade", "Hollow Blade", ComponentSlot.BLADE,
                Material.IRON_SWORD, 50008,
                "20% chance to ignore armor on hit.", "Hollow"),
        RESONANT_BLADE("resonant_blade", "Resonant Blade", ComponentSlot.BLADE,
                Material.ECHO_SHARD, 50009,
                "Hits apply 2s Darkness to the target.", "Resonant"),

        // Hilts
        WRAPPED_HILT("wrapped_hilt", "Wrapped Hilt", ComponentSlot.HILT,
                Material.LEATHER, 50010,
                "Reduces attack cooldown by 10%.", "Wrapped"),
        COUNTERWEIGHT_HILT("counterweight_hilt", "Counterweight Hilt", ComponentSlot.HILT,
                Material.IRON_INGOT, 50011,
                "+0.5 knockback on hit.", "Counterweight"),
        RUNIC_HILT("runic_hilt", "Runic Hilt", ComponentSlot.HILT,
                Material.AMETHYST_SHARD, 50012,
                "Unlocks a third rune inscription slot.", "Runic"),

        // Cores
        EMBER_CORE("ember_core", "Ember Core", ComponentSlot.CORE,
                Material.BLAZE_ROD, 50013,
                "Kills restore 2 HP.", "Ember"),
        SOUL_CORE("soul_core", "Soul Core", ComponentSlot.CORE,
                Material.SOUL_SAND, 50014,
                "Critical hits Wither I (2s) the target.", "Soul"),
        SHADOW_CORE("shadow_core", "Shadow Core", ComponentSlot.CORE,
                Material.COAL, 50015,
                "20% chance to grant Invisibility (1s) on kill.", "Shadow");

        private final String        id;
        private final String        displayName;
        private final ComponentSlot slot;
        private final Material      material;
        private final int           cmd;
        private final String        effect;
        private final String        shortName;

        ComponentType(String id, String displayName, ComponentSlot slot,
                      Material material, int cmd, String effect, String shortName) {
            this.id          = id;
            this.displayName = displayName;
            this.slot        = slot;
            this.material    = material;
            this.cmd         = cmd;
            this.effect      = effect;
            this.shortName   = shortName;
        }

        public String        id()          { return id; }
        public String        displayName() { return displayName; }
        public ComponentSlot slot()        { return slot; }
        public Material      material()    { return material; }
        public int           cmd()         { return cmd; }
        public String        effect()      { return effect; }
        public String        shortName()   { return shortName; }

        public static ComponentType byId(String id) {
            for (ComponentType t : values()) if (t.id.equals(id)) return t;
            return null;
        }
    }
}
