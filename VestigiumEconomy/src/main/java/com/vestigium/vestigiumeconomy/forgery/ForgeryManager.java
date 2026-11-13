package com.vestigium.vestigiumeconomy.forgery;

import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;

import java.util.*;

/**
 * Black-market forgery system. The Forger (ZombieVillager NPC) clones any
 * Vestigium item for 5 Vestige Shards; the copy looks identical but carries
 * a vestigium:forged=1b tag and has all effect PDC keys stripped.
 *
 * All Vestigium effect systems call ForgeryManager.isForged() before activating.
 * Players can authenticate items by holding an Echo Shard in their off-hand
 * and right-clicking while holding the item in their main hand.
 */
public class ForgeryManager implements Listener, CommandExecutor {

    public static final NamespacedKey FORGED_KEY    = new NamespacedKey("vestigium", "forged");
    private static final NamespacedKey FORGER_KEY   = new NamespacedKey("vestigium", "forger");
    private static final NamespacedKey FORGE_CD_KEY = new NamespacedKey("vestigium", "forger_cd");

    private static final List<NamespacedKey> EFFECT_KEYS = List.of(
            new NamespacedKey("vestigium", "runes"),
            new NamespacedKey("vestigium", "rune_type"),
            new NamespacedKey("vestigium", "ritual_item"),
            new NamespacedKey("vestigium", "ritual_cooldown"),
            new NamespacedKey("vestigium", "titan_bone"),
            new NamespacedKey("vestigium", "divine_artifact"),
            new NamespacedKey("vestigium", "resonant_artifact"),
            new NamespacedKey("vestigium", "temper"),
            new NamespacedKey("vestigium", "weapon_blade"),
            new NamespacedKey("vestigium", "cursed")
    );

    private static final int  FORGE_COST     = 5;
    private static final long FORGE_COOLDOWN = 60_000L;

    private final VestigiumEconomy plugin;

    public ForgeryManager(VestigiumEconomy plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("veforgery");
        if (cmd != null) cmd.setExecutor(this);
        plugin.getLogger().info("[ForgeryManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Static API — used by RuneManager and RitualManager
    // -------------------------------------------------------------------------

    public static boolean isForged(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(FORGED_KEY, PersistentDataType.BYTE, (byte) 0) == 1;
    }

    // -------------------------------------------------------------------------
    // NPC interaction — ZombieVillager forger
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onForgerInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof ZombieVillager npc)) return;
        if (!npc.getPersistentDataContainer().has(FORGER_KEY, PersistentDataType.BYTE)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();

        if (held.getType() == Material.AIR || !held.hasItemMeta()) {
            player.sendMessage("§8[Forger] §7Hold the item you want copied in your main hand.");
            return;
        }
        if (isForged(held)) {
            player.sendMessage("§8[Forger] §cCan't copy a fake, friend.");
            return;
        }
        if (!isVestigiumItem(held)) {
            player.sendMessage("§8[Forger] §7That ain't worth my time. Bring me a Vestigium item.");
            return;
        }

        long now = System.currentTimeMillis();
        long cd = player.getPersistentDataContainer()
                .getOrDefault(FORGE_CD_KEY, PersistentDataType.LONG, 0L);
        if (now < cd) {
            player.sendMessage("§8[Forger] §7Come back in §f" + (cd - now) / 1000 + "s§7.");
            return;
        }

        int shards = plugin.getCurrencyManager().countShards(player);
        if (shards < FORGE_COST) {
            player.sendMessage("§8[Forger] §7Costs §d" + FORGE_COST
                    + " Vestige Shards§7. You have §f" + shards + "§7.");
            return;
        }

        plugin.getCurrencyManager().removeShards(player, FORGE_COST);
        player.getPersistentDataContainer()
                .set(FORGE_CD_KEY, PersistentDataType.LONG, now + FORGE_COOLDOWN);

        player.getInventory().addItem(makeForgedCopy(held));
        player.sendMessage("§8[Forger] §7Looks identical. §8Don't get caught.");
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 0.7f);
    }

    // -------------------------------------------------------------------------
    // Authentication — Echo Shard off-hand + Vestigium item main hand, right-click
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAuthenticate(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        if (player.getInventory().getItemInOffHand().getType() != Material.ECHO_SHARD) return;

        ItemStack main = player.getInventory().getItemInMainHand();
        if (main.getType() == Material.AIR || !main.hasItemMeta()) return;
        if (!isVestigiumItem(main) && !isForged(main)) return;

        event.setCancelled(true);
        if (isForged(main)) {
            player.sendMessage("§c§lCOUNTERFEIT DETECTED §r§7— This item is a forgery.");
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.5f);
            player.getWorld().spawnParticle(Particle.SMOKE,
                    player.getLocation().add(0, 1, 0), 12, 0.2, 0.3, 0.2, 0.03);
        } else {
            player.sendMessage("§a§lAUTHENTIC §r§7— This item is genuine.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.5f);
        }
    }

    // -------------------------------------------------------------------------
    // /veforgery
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vestigium.forgery.admin")) {
            sender.sendMessage("§cNo permission."); return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§7/veforgery <spawn | remove | list | inspect>"); return true;
        }
        switch (args[0].toLowerCase()) {
            case "spawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cIn-game only."); return true;
                }
                player.getWorld().spawn(player.getLocation(), ZombieVillager.class, z -> {
                    z.setCustomName("§8§lThe Forger");
                    z.setCustomNameVisible(true);
                    z.setAI(false);
                    z.setSilent(true);
                    z.setInvulnerable(true);
                    z.setPersistent(true);
                    z.setAdult();
                    z.setConversionTime(-1);
                    z.getPersistentDataContainer().set(FORGER_KEY, PersistentDataType.BYTE, (byte) 1);
                });
                sender.sendMessage("§aForger spawned.");
            }
            case "remove" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cIn-game only."); return true;
                }
                boolean removed = player.getNearbyEntities(5, 5, 5).stream()
                        .filter(e -> e instanceof ZombieVillager zv
                                && zv.getPersistentDataContainer().has(FORGER_KEY, PersistentDataType.BYTE))
                        .findFirst()
                        .map(e -> { e.remove(); return true; })
                        .orElse(false);
                sender.sendMessage(removed ? "§aForger removed." : "§cNo forger within 5 blocks.");
            }
            case "list" -> {
                var forgers = new ArrayList<String>();
                for (World w : plugin.getServer().getWorlds()) {
                    for (ZombieVillager zv : w.getEntitiesByClass(ZombieVillager.class)) {
                        if (zv.getPersistentDataContainer().has(FORGER_KEY, PersistentDataType.BYTE)) {
                            Location l = zv.getLocation();
                            forgers.add(w.getName() + " " + l.getBlockX()
                                    + "," + l.getBlockY() + "," + l.getBlockZ());
                        }
                    }
                }
                sender.sendMessage("§6Forger NPCs: §f" + forgers.size());
                forgers.forEach(s -> sender.sendMessage("  §7" + s));
            }
            case "inspect" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cIn-game only."); return true;
                }
                ItemStack held = player.getInventory().getItemInMainHand();
                if (held.getType() == Material.AIR || !held.hasItemMeta()) {
                    sender.sendMessage("§cHold an item."); return true;
                }
                sender.sendMessage(isForged(held)
                        ? "§c[FORGED] This item is a counterfeit."
                        : "§a[GENUINE] This item appears authentic.");
            }
            default -> sender.sendMessage("§7/veforgery <spawn | remove | list | inspect>");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ItemStack makeForgedCopy(ItemStack original) {
        ItemStack copy = original.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return copy;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (NamespacedKey key : EFFECT_KEYS) pdc.remove(key);
        pdc.set(FORGED_KEY, PersistentDataType.BYTE, (byte) 1);
        copy.setItemMeta(meta);
        return copy;
    }

    private boolean isVestigiumItem(ItemStack item) {
        if (!item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        for (NamespacedKey key : EFFECT_KEYS) {
            if (pdc.has(key)) return true;
        }
        return false;
    }
}
