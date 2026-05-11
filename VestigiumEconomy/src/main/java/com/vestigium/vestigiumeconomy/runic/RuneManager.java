package com.vestigium.vestigiumeconomy.runic;

import com.vestigium.vestigiumeconomy.VestigiumEconomy;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runic Crafting — inscribes mob-drop rune items onto weapons/armor at a
 * Stonecutter. Each rune grants a passive or triggered combat property.
 *
 * Inscription: hold gear (main hand) + rune (off hand), right-click any Stonecutter.
 * Max 2 runes per item.
 *
 * Rune IDs are stored as comma-delimited STRING in PDC key "vestigium:runes"
 * on the gear ItemStack.
 */
public class RuneManager implements Listener, CommandExecutor {

    static final NamespacedKey RUNES_KEY    = new NamespacedKey("vestigium", "runes");
    private static final NamespacedKey RUNE_TYPE_KEY = new NamespacedKey("vestigium", "rune_type");

    private static final int    MAX_RUNES    = 2;
    private static final String LORE_PREFIX  = "§8◆ ";

    private final VestigiumEconomy plugin;
    private BukkitRunnable sightTask;

    public RuneManager(VestigiumEconomy plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(new RuneDropListener(this), plugin);
        plugin.getServer().getPluginManager().registerEvents(
                new RuneEffectHandler(this, plugin), plugin);

        var cmd = plugin.getCommand("verunic");
        if (cmd != null) cmd.setExecutor(this);

        // Sight rune: refresh Night Vision every 20 ticks while health is low
        sightTask = new BukkitRunnable() {
            @Override public void run() {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (playerHasRune(p, "sight") && p.getHealth() <= 10.0) {
                        p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                                org.bukkit.potion.PotionEffectType.NIGHT_VISION, 60, 0, true, false));
                    }
                }
            }
        };
        sightTask.runTaskTimer(plugin, 20L, 20L);

        plugin.getLogger().info("[RuneManager] Initialized — " + RuneType.values().length + " rune types.");
    }

    public void shutdown() {
        if (sightTask != null) sightTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Stonecutter inscription
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStonecutter(PlayerInteractEvent event) {
        if (event.getAction()       != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand()         != EquipmentSlot.HAND)       return;
        if (event.getClickedBlock() == null)                      return;
        if (event.getClickedBlock().getType() != Material.STONECUTTER) return;

        Player    player   = event.getPlayer();
        ItemStack gear     = player.getInventory().getItemInMainHand();
        ItemStack runeItem = player.getInventory().getItemInOffHand();

        // Only intercept if the off-hand item is a rune; otherwise open normal GUI
        RuneType runeType = getRuneType(runeItem);
        if (runeType == null) return;

        event.setCancelled(true);

        if (gear.getType() == Material.AIR || !isInscribable(gear.getType())) {
            player.sendMessage("§cHold the gear to inscribe in your main hand.");
            return;
        }

        List<String> current = getItemRunes(gear);
        if (current.size() >= MAX_RUNES) {
            player.sendMessage("§cThis item already holds " + MAX_RUNES
                    + " runes. Use §f/verunic remove <1|2> §cto free a slot.");
            return;
        }
        if (current.contains(runeType.id())) {
            player.sendMessage("§cThis item already has " + runeType.displayName() + "§c.");
            return;
        }

        // Apply rune to gear
        ItemMeta meta = gear.getItemMeta();
        if (meta == null) return;

        current.add(runeType.id());
        meta.getPersistentDataContainer()
                .set(RUNES_KEY, PersistentDataType.STRING, String.join(",", current));

        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(LORE_PREFIX + runeType.displayName() + " §8— §7" + runeType.effect());
        meta.setLore(lore);
        gear.setItemMeta(meta);
        player.getInventory().setItemInMainHand(gear);

        // Consume one rune item
        if (runeItem.getAmount() > 1) {
            runeItem.setAmount(runeItem.getAmount() - 1);
        } else {
            player.getInventory().setItemInOffHand(null);
        }

        String gearName = meta.hasDisplayName()
                ? meta.getDisplayName()
                : formatMaterial(gear.getType());
        player.sendMessage("§a" + runeType.displayName() + " §ainscribed onto §f" + gearName + "§a.");
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 1f, 1.2f);
        player.getWorld().spawnParticle(Particle.ENCHANT,
                player.getLocation().add(0, 1, 0), 24, 0.3, 0.5, 0.3, 0.1);
    }

    // -------------------------------------------------------------------------
    // Command — /verunic
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7/verunic <list | give <rune> [player] | inspect | remove <1|2>>");
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "list" -> {
                sender.sendMessage("§6§l── Runic Crafting ──");
                sender.sendMessage("§7Hold gear (main hand) + rune (off hand) and right-click a §fStonecutter §7to inscribe.");
                sender.sendMessage("§7Max §f" + MAX_RUNES + " §7runes per item.");
                for (RuneType rt : RuneType.values()) {
                    sender.sendMessage(LORE_PREFIX + rt.displayName()
                            + " §8(§7" + rt.id() + "§8) §8— §7" + rt.effect());
                    sender.sendMessage("  §8Source: §7" + rt.dropSource());
                }
            }

            case "give" -> {
                if (!sender.hasPermission("vestigium.runic.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§7/verunic give <rune> [player]"); return true;
                }
                RuneType rt = RuneType.byId(args[1]);
                if (rt == null) {
                    sender.sendMessage("§cUnknown rune: §f" + args[1]); return true;
                }
                Player target = args.length >= 3
                        ? plugin.getServer().getPlayer(args[2])
                        : (sender instanceof Player p ? p : null);
                if (target == null) {
                    sender.sendMessage("§cPlayer not found."); return true;
                }
                target.getInventory().addItem(createRune(rt));
                sender.sendMessage("§aGave " + rt.displayName() + " §ato §f" + target.getName() + "§a.");
            }

            case "inspect" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cPlayers only."); return true;
                }
                ItemStack held = player.getInventory().getItemInMainHand();
                List<String> runes = getItemRunes(held);
                if (runes.isEmpty()) {
                    player.sendMessage("§7This item has no runes inscribed.");
                } else {
                    player.sendMessage("§6Inscribed runes on §f"
                            + (held.getItemMeta() != null && held.getItemMeta().hasDisplayName()
                                    ? held.getItemMeta().getDisplayName()
                                    : formatMaterial(held.getType())) + "§6:");
                    for (int i = 0; i < runes.size(); i++) {
                        RuneType rt = RuneType.byId(runes.get(i));
                        String name = rt != null ? rt.displayName() : runes.get(i);
                        String desc = rt != null ? " §8— §7" + rt.effect() : "";
                        player.sendMessage("  §7" + (i + 1) + ". " + name + desc);
                    }
                }
            }

            case "remove" -> {
                if (!sender.hasPermission("vestigium.runic.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cPlayers only."); return true;
                }
                if (args.length < 2) {
                    player.sendMessage("§7/verunic remove <1|2>"); return true;
                }
                int idx;
                try { idx = Integer.parseInt(args[1]) - 1; }
                catch (NumberFormatException e) {
                    player.sendMessage("§cEnter 1 or 2."); return true;
                }
                ItemStack held = player.getInventory().getItemInMainHand();
                List<String> runes = new ArrayList<>(getItemRunes(held));
                if (idx < 0 || idx >= runes.size()) {
                    player.sendMessage("§cNo rune at slot " + (idx + 1) + "."); return true;
                }
                String removedId = runes.remove(idx);
                RuneType rt = RuneType.byId(removedId);

                ItemMeta meta = held.getItemMeta();
                if (meta == null) return true;

                if (runes.isEmpty()) {
                    meta.getPersistentDataContainer().remove(RUNES_KEY);
                } else {
                    meta.getPersistentDataContainer()
                            .set(RUNES_KEY, PersistentDataType.STRING, String.join(",", runes));
                }
                if (meta.hasLore()) {
                    String expectedLine = rt != null
                            ? LORE_PREFIX + rt.displayName() + " §8— §7" + rt.effect()
                            : null;
                    List<String> lore = new ArrayList<>(meta.getLore());
                    if (expectedLine != null) {
                        lore.remove(expectedLine);
                    } else {
                        lore.removeIf(l -> l.startsWith(LORE_PREFIX + removedId));
                    }
                    meta.setLore(lore);
                }
                held.setItemMeta(meta);
                player.getInventory().setItemInMainHand(held);
                player.sendMessage("§aRemoved: §f"
                        + (rt != null ? rt.displayName() : removedId) + "§a.");
            }

            default -> sender.sendMessage(
                    "§7/verunic <list | give <rune> [player] | inspect | remove <1|2>>");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Item factory
    // -------------------------------------------------------------------------

    public ItemStack createRune(RuneType type) {
        ItemStack item = new ItemStack(type.material());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(type.displayName());
        meta.setLore(List.of(
                "§7" + type.effect(),
                "§8Drop: §7" + type.dropSource(),
                "§8Hold gear (main) + rune (off) at a §7Stonecutter §8to inscribe."
        ));
        meta.getPersistentDataContainer()
                .set(RUNE_TYPE_KEY, PersistentDataType.STRING, type.id());
        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public RuneType getRuneType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String stored = item.getItemMeta().getPersistentDataContainer()
                .get(RUNE_TYPE_KEY, PersistentDataType.STRING);
        return stored == null ? null : RuneType.byId(stored);
    }

    public boolean playerHasRune(Player player, String runeId) {
        return hasRuneInItem(player.getInventory().getHelmet(),     runeId)
            || hasRuneInItem(player.getInventory().getChestplate(), runeId)
            || hasRuneInItem(player.getInventory().getLeggings(),   runeId)
            || hasRuneInItem(player.getInventory().getBoots(),      runeId)
            || hasRuneInItem(player.getInventory().getItemInMainHand(), runeId);
    }

    boolean hasRuneInItem(ItemStack item, String runeId) {
        if (item == null || !item.hasItemMeta()) return false;
        String stored = item.getItemMeta().getPersistentDataContainer()
                .get(RUNES_KEY, PersistentDataType.STRING);
        return stored != null && Arrays.asList(stored.split(",")).contains(runeId);
    }

    public List<String> getItemRunes(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return new ArrayList<>();
        String stored = item.getItemMeta().getPersistentDataContainer()
                .get(RUNES_KEY, PersistentDataType.STRING);
        if (stored == null || stored.isBlank()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(stored.split(",")));
    }

    private static boolean isInscribable(Material m) {
        String n = m.name();
        return n.endsWith("_SWORD")      || n.endsWith("_AXE")
            || n.endsWith("_HELMET")     || n.endsWith("_CHESTPLATE")
            || n.endsWith("_LEGGINGS")   || n.endsWith("_BOOTS")
            || m == Material.BOW         || m == Material.CROSSBOW
            || m == Material.TRIDENT     || m == Material.SHIELD;
    }

    private static String formatMaterial(Material m) {
        String s = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // -------------------------------------------------------------------------
    // Rune catalogue
    // -------------------------------------------------------------------------

    public enum RuneType {
        WARDING("warding",    "§6Rune of Warding",    Material.BONE,
                "15% chance to reflect 3 damage to your attacker.",
                "Skeleton (5%)"),
        VENOM("venom",        "§aRune of Venom",      Material.SPIDER_EYE,
                "20% chance to inflict Poison I (3s) on hit.",
                "Spider / Cave Spider (8%)"),
        RESILIENCE("resilience", "§cRune of Resilience", Material.ROTTEN_FLESH,
                "Once per 60s: fully absorb a hit of 6+ damage.",
                "Zombie (6%)"),
        THUNDER("thunder",    "§eRune of Thunder",    Material.GUNPOWDER,
                "Killing blow: Slowness I (3s) to nearby enemies.",
                "Creeper (10%)"),
        DEEP("deep",          "§bRune of the Deep",   Material.PRISMARINE_SHARD,
                "40% reduced damage while submerged.",
                "Drowned (8%)"),
        SWIFTNESS("swiftness","§fRune of Swiftness",  Material.BLAZE_ROD,
                "Killing an enemy grants Speed I (4s).",
                "Blaze (7%)"),
        SIGHT("sight",        "§dRune of Sight",      Material.PHANTOM_MEMBRANE,
                "Night Vision while your health is below 5 hearts.",
                "Phantom (12%)"),
        SCULK("sculk",        "§9Rune of Sculk",      Material.ECHO_SHARD,
                "Wardens lose track of you 50% of the time.",
                "Warden (3%)");

        private final String   id;
        private final String   displayName;
        private final Material material;
        private final String   effect;
        private final String   dropSource;

        RuneType(String id, String displayName, Material material,
                 String effect, String dropSource) {
            this.id          = id;
            this.displayName = displayName;
            this.material    = material;
            this.effect      = effect;
            this.dropSource  = dropSource;
        }

        public String   id()          { return id; }
        public String   displayName() { return displayName; }
        public Material material()    { return material; }
        public String   effect()      { return effect; }
        public String   dropSource()  { return dropSource; }

        public static RuneType byId(String id) {
            for (RuneType rt : values()) {
                if (rt.id.equals(id)) return rt;
            }
            return null;
        }
    }
}
