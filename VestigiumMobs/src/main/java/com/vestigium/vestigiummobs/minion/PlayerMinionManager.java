package com.vestigium.vestigiummobs.minion;

import com.vestigium.vestigiummobs.VestigiumMobs;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Player-summonable minion system — six distinct roles, max 3 concurrent per player.
 *
 * Roles:
 *   SHADE     — invisible assassin; +50% HP/damage/speed
 *   GOLEM     — tank; 2× HP; taunts nearby hostiles onto itself every 2 s
 *   WISP      — support; half HP; follows player, emits glow particles when close
 *   HEXBLADE  — debuffer (Stray); natural slowness arrows + Weakness I on hit
 *   HARVESTER — item collector (Drowned); vacuums dropped items within 5 blocks to owner
 *   SCOUT     — patrol (Zombie); actionbar hostile-count alerts within 20 blocks
 *
 * Command: /minion summon <role> | dismiss <role|all> | list
 * Permission: vestigium.minion
 */
public class PlayerMinionManager implements Listener {

    private static final NamespacedKey OWNER_KEY =
            new NamespacedKey("vestigium", "player_minion_owner");
    private static final NamespacedKey ROLE_KEY =
            new NamespacedKey("vestigium", "player_minion_role");

    private static final int MAX_PER_PLAYER = 3;

    private final VestigiumMobs plugin;
    // player UUID → (role → minion UUID)
    private final Map<UUID, Map<MinionRole, UUID>> playerMinions = new HashMap<>();
    // minion UUID → owner UUID (reverse lookup)
    private final Map<UUID, UUID> minionOwners = new HashMap<>();

    private BukkitRunnable mainTask;

    public PlayerMinionManager(VestigiumMobs plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startMainTask();
        registerCommand();
        plugin.getLogger().info("[PlayerMinionManager] Initialized.");
    }

    public void shutdown() {
        if (mainTask != null) mainTask.cancel();
        new ArrayList<>(playerMinions.keySet()).forEach(uid -> {
            Player p = plugin.getServer().getPlayer(uid);
            if (p != null) dismissAll(p);
            else {
                Map<MinionRole, UUID> m = playerMinions.remove(uid);
                if (m != null) m.values().forEach(id -> {
                    minionOwners.remove(id);
                    Entity e = plugin.getServer().getEntity(id);
                    if (e != null && e.isValid()) e.remove();
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public boolean spawnMinion(Player player, MinionRole role) {
        Map<MinionRole, UUID> myMinions =
                playerMinions.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(MinionRole.class));

        // Prune dead minions from the map
        myMinions.entrySet().removeIf(e -> {
            Entity entity = plugin.getServer().getEntity(e.getValue());
            return entity == null || !entity.isValid();
        });

        if (myMinions.containsKey(role)) {
            player.sendMessage("§cYou already have a " + role.displayName() + " §cactive.");
            return false;
        }
        if (myMinions.size() >= MAX_PER_PLAYER) {
            player.sendMessage("§cYou cannot have more than " + MAX_PER_PLAYER + " minions active at once.");
            return false;
        }

        Location loc = player.getLocation();
        Mob minion = (Mob) loc.getWorld().spawnEntity(loc, role.entityType());

        minion.getPersistentDataContainer()
                .set(OWNER_KEY, PersistentDataType.STRING, player.getUniqueId().toString());
        minion.getPersistentDataContainer()
                .set(ROLE_KEY, PersistentDataType.STRING, role.name().toLowerCase());

        applyRoleStats(minion, role, player);
        myMinions.put(role, minion.getUniqueId());
        minionOwners.put(minion.getUniqueId(), player.getUniqueId());
        player.sendMessage("§7Your " + role.displayName() + " §7has been summoned.");
        return true;
    }

    public boolean dismissMinion(Player player, MinionRole role) {
        Map<MinionRole, UUID> myMinions = playerMinions.get(player.getUniqueId());
        if (myMinions == null) return false;

        UUID minionId = myMinions.remove(role);
        if (minionId == null) return false;

        minionOwners.remove(minionId);
        Entity entity = plugin.getServer().getEntity(minionId);
        if (entity != null && entity.isValid()) entity.remove();
        player.sendMessage("§7Your " + role.displayName() + " §7has been dismissed.");
        return true;
    }

    public void dismissAll(Player player) {
        Map<MinionRole, UUID> myMinions = playerMinions.remove(player.getUniqueId());
        if (myMinions == null) return;
        myMinions.values().forEach(id -> {
            minionOwners.remove(id);
            Entity e = plugin.getServer().getEntity(id);
            if (e != null && e.isValid()) e.remove();
        });
        player.sendMessage("§7All minions dismissed.");
    }

    // -------------------------------------------------------------------------
    // Stats
    // -------------------------------------------------------------------------

    private void applyRoleStats(Mob minion, MinionRole role, Player owner) {
        switch (role) {
            case SHADE -> {
                scale(minion, Attribute.MAX_HEALTH,    1.5);
                scale(minion, Attribute.ATTACK_DAMAGE, 1.5);
                scale(minion, Attribute.MOVEMENT_SPEED, 1.5);
                minion.addPotionEffect(
                        new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
                minion.setCustomName("§8Shade");
                minion.setCustomNameVisible(false);
            }
            case GOLEM -> {
                scale(minion, Attribute.MAX_HEALTH, 2.0);
                minion.setCustomName("§7Sentinel Golem");
                minion.setCustomNameVisible(true);
            }
            case WISP -> {
                scale(minion, Attribute.MAX_HEALTH, 0.5);
                minion.setCustomName("§eGuiding Wisp");
                minion.setCustomNameVisible(true);
                minion.getPathfinder().moveTo(owner, 1.0);
            }
            case HEXBLADE -> {
                minion.setCustomName("§dHexblade");
                minion.setCustomNameVisible(true);
            }
            case HARVESTER -> {
                minion.setCustomName("§6Harvester");
                minion.setCustomNameVisible(true);
            }
            case SCOUT -> {
                scale(minion, Attribute.MOVEMENT_SPEED, 1.3);
                minion.setCustomName("§bScout");
                minion.setCustomNameVisible(true);
            }
        }
        var hp = minion.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) minion.setHealth(hp.getValue());
    }

    private void scale(Mob mob, Attribute attr, double mult) {
        var inst = mob.getAttribute(attr);
        if (inst != null) inst.setBaseValue(inst.getBaseValue() * mult);
    }

    // -------------------------------------------------------------------------
    // Main Task
    // -------------------------------------------------------------------------

    private void startMainTask() {
        mainTask = new BukkitRunnable() {
            @Override
            public void run() {
                playerMinions.forEach((ownerUUID, roleMap) -> {
                    Player owner = plugin.getServer().getPlayer(ownerUUID);
                    if (owner == null) return;

                    roleMap.entrySet().removeIf(e -> {
                        Entity entity = plugin.getServer().getEntity(e.getValue());
                        return entity == null || !entity.isValid();
                    });

                    roleMap.forEach((role, minionUUID) -> {
                        Entity entity = plugin.getServer().getEntity(minionUUID);
                        if (!(entity instanceof Mob minion) || !minion.isValid()) return;

                        switch (role) {
                            case SHADE -> minion.addPotionEffect(
                                    new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0, false, false));
                            case GOLEM -> tauntNearbyMobs(minion, owner);
                            case WISP  -> {
                                minion.getPathfinder().moveTo(owner, 1.0);
                                if (minion.getLocation().distanceSquared(owner.getLocation()) < 100) {
                                    owner.getWorld().spawnParticle(
                                            org.bukkit.Particle.GLOW,
                                            owner.getLocation().add(0, 1, 0),
                                            6, 0.5, 0.5, 0.5, 0.0);
                                }
                            }
                            case HARVESTER -> collectNearbyItems(minion, owner);
                            case SCOUT     -> sendScoutAlert(minion, owner);
                            default -> {}
                        }
                    });
                });
            }
        };
        mainTask.runTaskTimer(plugin, 40L, 40L);
    }

    private void tauntNearbyMobs(Mob golem, Player owner) {
        golem.getWorld().getNearbyEntities(owner.getLocation(), 8, 8, 8).stream()
                .filter(e -> e instanceof Monster && !minionOwners.containsKey(e.getUniqueId()))
                .forEach(e -> ((Monster) e).setTarget(golem));
    }

    private void collectNearbyItems(Mob harvester, Player owner) {
        harvester.getWorld().getNearbyEntities(harvester.getLocation(), 5, 5, 5).stream()
                .filter(e -> e instanceof Item)
                .forEach(e -> {
                    ItemStack stack = ((Item) e).getItemStack();
                    Map<Integer, ItemStack> leftover = owner.getInventory().addItem(stack);
                    if (leftover.isEmpty()) {
                        e.remove();
                    } else {
                        ((Item) e).setItemStack(leftover.get(0));
                    }
                });
    }

    private void sendScoutAlert(Mob scout, Player owner) {
        long count = scout.getWorld()
                .getNearbyEntities(scout.getLocation(), 20, 20, 20).stream()
                .filter(e -> e instanceof Monster && !minionOwners.containsKey(e.getUniqueId()))
                .count();
        if (count > 0) {
            owner.sendActionBar(Component.text(
                    "§c" + count + " hostile" + (count == 1 ? "" : "s") + " detected nearby"));
        }
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHexbladeHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof AbstractArrow arrow)) return;
        if (!(arrow.getShooter() instanceof Mob shooter)) return;

        String roleStr = shooter.getPersistentDataContainer()
                .get(ROLE_KEY, PersistentDataType.STRING);
        if (!"hexblade".equals(roleStr)) return;

        victim.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 200, 0, false, true));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 200, 0, false, true));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMinionDeath(EntityDeathEvent event) {
        UUID minionId = event.getEntity().getUniqueId();
        UUID ownerUUID = minionOwners.remove(minionId);
        if (ownerUUID == null) return;

        Map<MinionRole, UUID> myMinions = playerMinions.get(ownerUUID);
        if (myMinions != null) myMinions.values().remove(minionId);

        String roleStr = event.getEntity().getPersistentDataContainer()
                .get(ROLE_KEY, PersistentDataType.STRING);
        Player owner = plugin.getServer().getPlayer(ownerUUID);
        if (owner != null && roleStr != null) {
            owner.sendMessage("§cYour " + roleStr + " has fallen.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        dismissAll(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        dismissAll(event.getPlayer());
    }

    // -------------------------------------------------------------------------
    // Command
    // -------------------------------------------------------------------------

    private void registerCommand() {
        var cmd = plugin.getCommand("minion");
        if (cmd == null) return;

        cmd.setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            if (args.length == 0) { showHelp(player); return true; }

            switch (args[0].toLowerCase()) {
                case "summon" -> {
                    if (args.length < 2) { player.sendMessage("§7Usage: /minion summon <role>"); return true; }
                    MinionRole role = MinionRole.fromString(args[1]);
                    if (role == null) {
                        player.sendMessage("§cUnknown role. Available: " + String.join(", ", MinionRole.names()));
                        return true;
                    }
                    if (!player.hasPermission("vestigium.minion")) {
                        player.sendMessage("§cYou do not have permission to summon minions.");
                        return true;
                    }
                    spawnMinion(player, role);
                }
                case "dismiss" -> {
                    if (args.length < 2 || args[1].equalsIgnoreCase("all")) {
                        dismissAll(player);
                    } else {
                        MinionRole role = MinionRole.fromString(args[1]);
                        if (role == null) { player.sendMessage("§cUnknown role."); return true; }
                        if (!dismissMinion(player, role))
                            player.sendMessage("§cYou don't have a " + role.displayName() + " §cactive.");
                    }
                }
                case "list" -> listMinions(player);
                default -> showHelp(player);
            }
            return true;
        });

        cmd.setTabCompleter((sender, command, alias, args) -> {
            if (args.length == 1) return List.of("summon", "dismiss", "list");
            if (args.length == 2) {
                if (args[0].equalsIgnoreCase("summon")) return MinionRole.names();
                if (args[0].equalsIgnoreCase("dismiss")) {
                    List<String> opts = new ArrayList<>(MinionRole.names());
                    opts.add("all");
                    return opts;
                }
            }
            return List.of();
        });
    }

    private void showHelp(Player p) {
        p.sendMessage("§7/minion summon <role> §8— Summon a minion");
        p.sendMessage("§7/minion dismiss <role|all> §8— Dismiss minion(s)");
        p.sendMessage("§7/minion list §8— Show active minions");
        p.sendMessage("§7Roles: §f" + String.join("§7, §f", MinionRole.names()));
    }

    private void listMinions(Player player) {
        Map<MinionRole, UUID> myMinions =
                playerMinions.getOrDefault(player.getUniqueId(), Map.of());
        if (myMinions.isEmpty()) { player.sendMessage("§7You have no active minions."); return; }
        player.sendMessage("§7Active minions (" + myMinions.size() + "/" + MAX_PER_PLAYER + "):");
        myMinions.forEach((role, uid) -> {
            Entity e = plugin.getServer().getEntity(uid);
            String alive = (e != null && e.isValid()) ? "§a✔" : "§c✘";
            player.sendMessage("  " + alive + " " + role.displayName());
        });
    }
}
