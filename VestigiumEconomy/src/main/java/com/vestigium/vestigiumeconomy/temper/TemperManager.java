package com.vestigium.vestigiumeconomy.temper;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tempered Gear — applies elemental tempers to weapons/armor at a Smithing Table.
 * Hold gear (main hand) + temper shard (off hand), right-click any Smithing Table.
 * One temper per item. Effects trigger passively on combat events.
 */
public class TemperManager implements Listener, CommandExecutor {

    private static final NamespacedKey TEMPER_KEY          = new NamespacedKey("vestigium", "temper");
    private static final NamespacedKey TEMPER_MATERIAL_KEY = new NamespacedKey("vestigium", "temper_material");
    private static final String LORE_PREFIX = "§8❄ ";

    private final VestigiumEconomy plugin;
    private final Random rng = new Random();

    public TemperManager(VestigiumEconomy plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("vetemper");
        if (cmd != null) cmd.setExecutor(this);
        plugin.getLogger().info("[TemperManager] Initialized — " + TemperType.values().length + " tempers.");
    }

    // -------------------------------------------------------------------------
    // Smithing Table inscription
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSmithingTable(PlayerInteractEvent event) {
        if (event.getAction()       != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand()         != EquipmentSlot.HAND)       return;
        if (event.getClickedBlock() == null)                      return;
        if (event.getClickedBlock().getType() != Material.SMITHING_TABLE) return;

        Player    player    = event.getPlayer();
        ItemStack gear      = player.getInventory().getItemInMainHand();
        ItemStack shardItem = player.getInventory().getItemInOffHand();

        TemperType temper = getTemperType(shardItem);
        if (temper == null) return;

        event.setCancelled(true);

        if (gear.getType() == Material.AIR || !isTemperableGear(gear.getType())) {
            player.sendMessage("§cHold weapon or armor in your main hand to temper.");
            return;
        }

        ItemMeta meta = gear.getItemMeta();
        if (meta == null) return;

        String existing = meta.getPersistentDataContainer()
                .get(TEMPER_KEY, PersistentDataType.STRING);
        if (existing != null) {
            player.sendMessage("§cThis item is already tempered with §f"
                    + TemperType.byId(existing).displayName() + "§c. Salvage it to remove.");
            return;
        }

        meta.getPersistentDataContainer().set(TEMPER_KEY, PersistentDataType.STRING, temper.id());

        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(LORE_PREFIX + temper.displayName() + " §8— §7" + temper.effect());
        meta.setLore(lore);
        gear.setItemMeta(meta);
        player.getInventory().setItemInMainHand(gear);

        if (shardItem.getAmount() > 1) shardItem.setAmount(shardItem.getAmount() - 1);
        else player.getInventory().setItemInOffHand(null);

        String gearName = meta.hasDisplayName() ? meta.getDisplayName() : fmt(gear.getType());
        player.sendMessage(temper.displayName() + " §7applied to §f" + gearName + "§7.");
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1.3f);
        player.getWorld().spawnParticle(Particle.ENCHANT,
                player.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.05);
    }

    // -------------------------------------------------------------------------
    // Combat effects
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity()  instanceof LivingEntity victim)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        TemperType temper = getTemperFromItem(weapon);
        if (temper == null) return;

        switch (temper) {
            case FROST -> {
                if (rng.nextDouble() < 0.35) {
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0, true, true));
                }
            }
            case BLAZE -> {
                if (rng.nextDouble() < 0.30) {
                    victim.setFireTicks(Math.max(victim.getFireTicks(), 60));
                }
            }
            case TIDE -> {
                if (attacker.isInWater()) {
                    event.setDamage(event.getDamage() * 1.4);
                }
            }
            case VOID -> {
                if (rng.nextDouble() < 0.10) {
                    // Pull victim toward attacker — schedule 1 tick to avoid knockback override
                    var vLoc  = victim.getLocation();
                    var aLoc  = attacker.getLocation();
                    var pull  = aLoc.toVector().subtract(vLoc.toVector()).normalize().multiply(1.8);
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (victim.isValid()) victim.setVelocity(pull);
                    }, 1L);
                }
            }
            case STORM -> {
                boolean sprinting = attacker.isSprinting();
                if (sprinting) {
                    event.setDamage(event.getDamage() + 2.0);
                }
            }
            default -> { /* GROWTH handled on kill */ }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        ItemStack weapon = killer.getInventory().getItemInMainHand();
        if (getTemperFromItem(weapon) == TemperType.GROWTH) {
            killer.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false));
        }
    }

    // -------------------------------------------------------------------------
    // Command — /vetemper
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7/vetemper <list | give <type> [player] | inspect>");
            return true;
        }
        switch (args[0].toLowerCase()) {

            case "list" -> {
                sender.sendMessage("§6§l── Tempered Gear ──");
                sender.sendMessage("§7Hold gear (main) + temper shard (off), right-click a §fSmithing Table§7.");
                for (TemperType t : TemperType.values()) {
                    sender.sendMessage(LORE_PREFIX + t.displayName() + " §8— §7" + t.effect());
                }
            }

            case "give" -> {
                if (!sender.hasPermission("vestigium.temper.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (args.length < 2) { sender.sendMessage("§7/vetemper give <type> [player]"); return true; }
                TemperType t = TemperType.byId(args[1]);
                if (t == null) { sender.sendMessage("§cUnknown temper: §f" + args[1]); return true; }
                Player target = args.length >= 3
                        ? plugin.getServer().getPlayer(args[2])
                        : (sender instanceof Player p ? p : null);
                if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                target.getInventory().addItem(createShard(t));
                sender.sendMessage("§aGave " + t.displayName() + " §ashard to §f" + target.getName() + "§a.");
            }

            case "inspect" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
                ItemStack held = player.getInventory().getItemInMainHand();
                TemperType t = getTemperFromItem(held);
                if (t == null) player.sendMessage("§7No temper on held item.");
                else player.sendMessage("§7Temper: " + t.displayName() + " §8— §7" + t.effect());
            }

            default -> sender.sendMessage("§7/vetemper <list | give <type> [player] | inspect>");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Item factory
    // -------------------------------------------------------------------------

    public ItemStack createShard(TemperType type) {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(type.displayName() + " §7Shard");
        meta.setCustomModelData(type.cmd());
        meta.setLore(List.of(
                "§7" + type.effect(),
                "§8Hold gear (main) + shard (off) at a §7Smithing Table §8to temper."
        ));
        meta.getPersistentDataContainer()
                .set(TEMPER_MATERIAL_KEY, PersistentDataType.STRING, type.id());
        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public TemperType getTemperType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String s = item.getItemMeta().getPersistentDataContainer()
                .get(TEMPER_MATERIAL_KEY, PersistentDataType.STRING);
        return s == null ? null : TemperType.byId(s);
    }

    public TemperType getTemperFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String s = item.getItemMeta().getPersistentDataContainer()
                .get(TEMPER_KEY, PersistentDataType.STRING);
        return s == null ? null : TemperType.byId(s);
    }

    private static boolean isTemperableGear(Material m) {
        String n = m.name();
        return n.endsWith("_SWORD")    || n.endsWith("_AXE")
            || n.endsWith("_HELMET")   || n.endsWith("_CHESTPLATE")
            || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
            || m == Material.BOW       || m == Material.CROSSBOW
            || m == Material.TRIDENT;
    }

    private static String fmt(Material m) {
        String s = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // -------------------------------------------------------------------------
    // Temper catalogue
    // -------------------------------------------------------------------------

    public enum TemperType {
        FROST("frost",     "§bFrost Temper",     50001, "35% chance to Slow the target on hit."),
        BLAZE("blaze",     "§6Blaze Temper",     50002, "30% chance to ignite the target on hit."),
        TIDE("tide",       "§3Tide Temper",      50003, "+40% weapon damage while submerged."),
        VOID("void",       "§8Void Temper",      50004, "10% chance to pull the target toward you on hit."),
        GROWTH("growth",   "§aGrowth Temper",    50005, "Killing an enemy grants Regeneration I (3s)."),
        STORM("storm",     "§eStorm Temper",     50006, "+2 damage while sprinting.");

        private final String id;
        private final String displayName;
        private final int    cmd;
        private final String effect;

        TemperType(String id, String displayName, int cmd, String effect) {
            this.id          = id;
            this.displayName = displayName;
            this.cmd         = cmd;
            this.effect      = effect;
        }

        public String id()          { return id; }
        public String displayName() { return displayName; }
        public int    cmd()         { return cmd; }
        public String effect()      { return effect; }

        public static TemperType byId(String id) {
            for (TemperType t : values()) if (t.id.equals(id)) return t;
            return null;
        }
    }
}
