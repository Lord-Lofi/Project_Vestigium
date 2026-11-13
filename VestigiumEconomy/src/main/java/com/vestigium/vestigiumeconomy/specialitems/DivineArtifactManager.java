package com.vestigium.vestigiumeconomy.specialitems;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Divine Artifacts — five powerful passive items distinct from VestigiumMagic artifacts.
 *
 *   UNDYING_SHIELD  — Iron Shield, CMD 60008
 *     Passive: once per 10 min, survive a killing blow at 1 HP.
 *     Stored: vestigium:undying_expire LONG in player PDC.
 *     Trigger: EntityDamageEvent MONITOR — if damage would kill, cancel + set 1 HP.
 *
 *   CROWN_OF_SEASONS — Golden Helmet, CMD 60009
 *     Passive: while worn, grants a seasonal buff refreshed every 20 ticks.
 *     Spring→Regen I, Summer→Strength I, Autumn→Fire Res, Winter→Resistance I.
 *
 *   TIDE_CALLERS_PEARL — Nautilus Shell, CMD 60010
 *     Passive: Conduit Power while submerged and holding.
 *     Active (right-click): AoE Water Breathing (3m) to allies within 5 blocks; costs 30 omen.
 *     10-min item cooldown.
 *
 *   RESONANCE_CORE — Echo Shard, CMD 60011
 *     Passive: while held in ancient city, send AoE healing pulse to nearby players every 30s.
 *     Stored: vestigium:resonance_core_pulse LONG in player PDC.
 *
 *   VOID_COMPASS — Compass, CMD 60012
 *     Passive: while held, reduces omen accumulation events by marking vestigium:void_compass BYTE
 *     on the player (checked by omen systems). No-hold = no effect.
 *     Right-click: shows current omen + effective omen readout (same data as ancient_compass).
 */
public class DivineArtifactManager implements Listener, CommandExecutor {

    private static final NamespacedKey ARTIFACT_KEY    = new NamespacedKey("vestigium", "divine_artifact");
    private static final NamespacedKey UNDYING_KEY     = new NamespacedKey("vestigium", "undying_expire");
    private static final NamespacedKey ARTIFACT_CD_KEY = new NamespacedKey("vestigium", "divine_artifact_cd");
    private static final NamespacedKey RESONANCE_KEY   = new NamespacedKey("vestigium", "resonance_core_pulse");
    private static final NamespacedKey VOID_COMP_KEY   = new NamespacedKey("vestigium", "void_compass");

    private static final long UNDYING_COOLDOWN   = 10L * 60 * 1000;
    private static final long ACTIVE_COOLDOWN    = 10L * 60 * 1000;
    private static final long RESONANCE_INTERVAL = 30L * 1000;

    private final VestigiumEconomy plugin;
    private BukkitRunnable passiveTask;

    public DivineArtifactManager(VestigiumEconomy plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("vedivine");
        if (cmd != null) cmd.setExecutor(this);
        startPassiveTask();
        plugin.getLogger().info("[DivineArtifactManager] Initialized — 5 divine artifacts.");
    }

    public void shutdown() {
        if (passiveTask != null) passiveTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Passive 20-tick task
    // -------------------------------------------------------------------------

    private void startPassiveTask() {
        passiveTask = new BukkitRunnable() {
            @Override public void run() {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    tickCrown(player);
                    tickTide(player);
                    tickResonance(player);
                    tickVoidCompass(player);
                }
            }
        };
        passiveTask.runTaskTimer(plugin, 20L, 20L);
    }

    private void tickCrown(Player player) {
        var helmet = player.getInventory().getHelmet();
        if (!isArtifact(helmet, "crown_of_seasons")) return;
        var season = VestigiumLib.getSeasonAPI().getCurrentSeason();
        PotionEffectType effect = switch (season.name()) {
            case "SPRING" -> PotionEffectType.REGENERATION;
            case "SUMMER" -> PotionEffectType.STRENGTH;
            case "AUTUMN" -> PotionEffectType.FIRE_RESISTANCE;
            case "WINTER" -> PotionEffectType.RESISTANCE;
            default       -> PotionEffectType.REGENERATION;
        };
        player.addPotionEffect(new PotionEffect(effect, 60, 0, true, false));
    }

    private void tickTide(Player player) {
        var main = player.getInventory().getItemInMainHand();
        var off  = player.getInventory().getItemInOffHand();
        if (!isArtifact(main, "tide_callers_pearl") && !isArtifact(off, "tide_callers_pearl")) return;
        if (player.isInWater()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.CONDUIT_POWER, 60, 0, true, false));
        }
    }

    private void tickResonance(Player player) {
        var main = player.getInventory().getItemInMainHand();
        var off  = player.getInventory().getItemInOffHand();
        if (!isArtifact(main, "resonance_core") && !isArtifact(off, "resonance_core")) return;
        if (player.getLocation().getBlock().getBiome() != Biome.DEEP_DARK) return;

        long now = System.currentTimeMillis();
        long last = player.getPersistentDataContainer()
                .getOrDefault(RESONANCE_KEY, PersistentDataType.LONG, 0L);
        if (now - last < RESONANCE_INTERVAL) return;

        player.getPersistentDataContainer().set(RESONANCE_KEY, PersistentDataType.LONG, now);
        for (Player nearby : player.getWorld().getNearbyEntitiesByType(Player.class,
                player.getLocation(), 8)) {
            nearby.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false));
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.5f, 1.5f);
    }

    private void tickVoidCompass(Player player) {
        var main = player.getInventory().getItemInMainHand();
        var off  = player.getInventory().getItemInOffHand();
        boolean held = isArtifact(main, "void_compass") || isArtifact(off, "void_compass");
        if (held) {
            player.getPersistentDataContainer().set(VOID_COMP_KEY, PersistentDataType.BYTE, (byte) 1);
        } else {
            player.getPersistentDataContainer().remove(VOID_COMP_KEY);
        }
    }

    // -------------------------------------------------------------------------
    // Undying Shield — survive killing blow
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        var offhand = player.getInventory().getItemInOffHand();
        if (!isArtifact(offhand, "undying_shield")) return;
        if (player.getHealth() - event.getFinalDamage() > 0) return;

        long now    = System.currentTimeMillis();
        long expiry = player.getPersistentDataContainer()
                .getOrDefault(UNDYING_KEY, PersistentDataType.LONG, 0L);
        if (now < expiry) return;

        event.setCancelled(true);
        player.setHealth(1.0);
        player.getPersistentDataContainer().set(UNDYING_KEY, PersistentDataType.LONG, now + UNDYING_COOLDOWN);
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 4, true, false));
        player.sendMessage("§6The Undying Shield absorbs the blow. §8(10-min recharge)");
        player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING,
                player.getLocation().add(0, 1, 0), 40, 0.5, 1, 0.5);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 1f);
    }

    // -------------------------------------------------------------------------
    // Active abilities (right-click)
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        String id = getArtifactId(held);
        if (id == null) return;

        switch (id) {
            case "tide_callers_pearl" -> activateTidePearl(event, player, held);
            case "void_compass"       -> activateVoidCompass(event, player);
        }
    }

    private void activateTidePearl(PlayerInteractEvent event, Player player, ItemStack item) {
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        long cd  = getItemCooldown(item);
        if (now < cd) {
            player.sendMessage("§7Tide Pearl recharges in §f" + (cd - now) / 60000 + " min§7.");
            return;
        }
        int omen = VestigiumLib.getOmenAPI().getOmenScore();
        if (omen < 30) { player.sendMessage("§cNot enough omen to call the tide."); return; }
        VestigiumLib.getOmenAPI().addOmen(-30);
        for (Player nearby : player.getWorld().getNearbyEntitiesByType(Player.class,
                player.getLocation(), 5)) {
            nearby.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 3600, 0, true, false));
        }
        setItemCooldown(item, now + ACTIVE_COOLDOWN);
        player.getInventory().setItemInMainHand(item);
        player.sendMessage("§3The tide answers. §8(−30 omen)");
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 0.5f, 1.3f);
    }

    private void activateVoidCompass(PlayerInteractEvent event, Player player) {
        event.setCancelled(true);
        int omen          = VestigiumLib.getOmenAPI().getOmenScore();
        int effectiveOmen = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
        var season        = VestigiumLib.getSeasonAPI().getCurrentSeason();
        int tidal         = VestigiumLib.getSeasonAPI().getTidalPhase();
        player.sendMessage("§8§l── Void Compass ──");
        player.sendMessage("§7Omen:          §f" + omen + " §8(effective: §f" + effectiveOmen + "§8)");
        player.sendMessage("§7Season:        §f" + season.name());
        player.sendMessage("§7Tidal phase:   §f" + tidal);
    }

    // -------------------------------------------------------------------------
    // Command — /vedivine
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sender.sendMessage("§7/vedivine <list | give <type> [player]>"); return true; }
        switch (args[0].toLowerCase()) {
            case "list" -> {
                sender.sendMessage("§6§l── Divine Artifacts ──");
                sender.sendMessage("  §fundying_shield §8— §7Survive killing blow once per 10m (CMD 60008)");
                sender.sendMessage("  §fcrown_of_seasons §8— §7Passive seasonal buff while worn (CMD 60009)");
                sender.sendMessage("  §ftide_callers_pearl §8— §7Conduit Power submerged; AoE WB active (CMD 60010)");
                sender.sendMessage("  §fresonance_core §8— §7AoE heal pulse in deep dark every 30s (CMD 60011)");
                sender.sendMessage("  §fvoid_compass §8— §7Omen suppression passive + readout (CMD 60012)");
            }
            case "give" -> {
                if (!sender.hasPermission("vestigium.divine.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (args.length < 2) { sender.sendMessage("§7/vedivine give <type> [player]"); return true; }
                Player target = args.length >= 3
                        ? plugin.getServer().getPlayer(args[2])
                        : (sender instanceof Player p ? p : null);
                if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                ItemStack item = createArtifact(args[1]);
                if (item == null) { sender.sendMessage("§cUnknown type."); return true; }
                target.getInventory().addItem(item);
                sender.sendMessage("§aGave §f" + args[1] + " §ato §f" + target.getName() + "§a.");
            }
            default -> sender.sendMessage("§7/vedivine <list | give <type> [player]>");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Item factories
    // -------------------------------------------------------------------------

    public ItemStack createArtifact(String id) {
        return switch (id) {
            case "undying_shield" -> build(Material.SHIELD, "§6Undying Shield", 60008, id,
                    "§7Passive: survive a killing blow once per §f10 min§7.",
                    "§8Hold in off hand.");
            case "crown_of_seasons" -> build(Material.GOLDEN_HELMET, "§eCrown of Seasons", 60009, id,
                    "§7Passive: seasonal buff while worn.",
                    "§8Spring→Regen  Summer→Str  Autumn→Fire Res  Winter→Resist");
            case "tide_callers_pearl" -> build(Material.NAUTILUS_SHELL, "§3Tide Caller's Pearl", 60010, id,
                    "§7Passive: Conduit Power while submerged.",
                    "§7Active: AoE Water Breathing (3m) in 5 blocks. §8Costs 30 omen. 10m cooldown.");
            case "resonance_core" -> build(Material.ECHO_SHARD, "§9Resonance Core", 60011, id,
                    "§7Passive: AoE heal pulse every §f30s §7while held in the deep dark.");
            case "void_compass" -> build(Material.COMPASS, "§8Void Compass", 60012, id,
                    "§7Passive: suppresses omen accumulation flags while held.",
                    "§7Right-click: omen / season / tidal readout.");
            default -> null;
        };
    }

    private ItemStack build(Material mat, String name, int cmd, String id, String... loreLines) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(name);
        meta.setCustomModelData(cmd);
        meta.setLore(List.of(loreLines));
        meta.getPersistentDataContainer().set(ARTIFACT_KEY, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public boolean isArtifact(ItemStack item, String id) {
        return id.equals(getArtifactId(item));
    }

    private String getArtifactId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
                .get(ARTIFACT_KEY, PersistentDataType.STRING);
    }

    private long getItemCooldown(ItemStack item) {
        if (!item.hasItemMeta()) return 0L;
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(ARTIFACT_CD_KEY, PersistentDataType.LONG, 0L);
    }

    private void setItemCooldown(ItemStack item, long expiry) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(ARTIFACT_CD_KEY, PersistentDataType.LONG, expiry);
        item.setItemMeta(meta);
    }
}
