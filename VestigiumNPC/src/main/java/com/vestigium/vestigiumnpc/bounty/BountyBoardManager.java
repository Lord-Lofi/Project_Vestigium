package com.vestigium.vestigiumnpc.bounty;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.OmenThresholdEvent;
import com.vestigium.vestigiumnpc.VestigiumNPC;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bounty Board system — physical in-world sign boards at villages,
 * mercenary posts, and bandit camps that display procedurally generated
 * contracts.
 *
 * Contract lifecycle:
 *   1. Admin registers a sign as a board with /vcbounty board place <type>
 *   2. Board generates 4 contracts on first load; refreshes every 20 minutes
 *   3. Player right-clicks the sign to view available contracts
 *   4. Player types /vcbounty take <1-4> to accept one; receives a Contract
 *      Note book with the details
 *   5. KILL contracts track progress via EntityDeathEvent
 *   6. COLLECT contracts: player right-clicks the board while holding
 *      the required items — auto-consumed and contract completed
 *   7. /vcbounty abandon drops the active contract
 *
 * Collective famine mechanic:
 *   When the server omen descends through 300, all boards inject an
 *   "Emergency Harvest" contract (collect Bread). Completions aggregate
 *   into a server pool. When the pool reaches 200 units the famine ends
 *   and omen is reduced by 30.
 */
public class BountyBoardManager implements Listener, CommandExecutor {

    // -------------------------------------------------------------------------
    // Data model
    // -------------------------------------------------------------------------

    public enum BoardType { VILLAGE, MERCENARY_POST, BANDIT_CAMP }
    public enum ContractType { KILL, COLLECT }

    public record BountyContract(
            String id, ContractType type, String displayName,
            EntityType mobTarget, Material itemTarget,
            int quantity, Material rewardMat, int rewardCount,
            boolean isFamine
    ) {}

    // -------------------------------------------------------------------------
    // Contract templates  [type, name, mobTarget, itemTarget, qty, rewardMat, rewardCount]
    // -------------------------------------------------------------------------

    private static final List<Object[]> VILLAGE_TEMPLATES = List.of(
            new Object[]{ContractType.COLLECT, "Grain Delivery",    null,               Material.WHEAT,    16, Material.EMERALD, 5},
            new Object[]{ContractType.COLLECT, "Bread for the Table", null,             Material.BREAD,     8, Material.EMERALD, 4},
            new Object[]{ContractType.COLLECT, "Root Harvest",      null,               Material.CARROT,   16, Material.EMERALD, 5},
            new Object[]{ContractType.COLLECT, "Potato Stock",      null,               Material.POTATO,   16, Material.EMERALD, 5},
            new Object[]{ContractType.KILL,    "Undead Culling",    EntityType.ZOMBIE,  null,               5, Material.EMERALD, 6},
            new Object[]{ContractType.KILL,    "Skeleton Hunt",     EntityType.SKELETON,null,               5, Material.EMERALD, 6},
            new Object[]{ContractType.KILL,    "Pillager Patrol",   EntityType.PILLAGER,null,               3, Material.EMERALD,10}
    );

    private static final List<Object[]> MERCENARY_TEMPLATES = List.of(
            new Object[]{ContractType.KILL,    "Raider Suppression",EntityType.PILLAGER,null,              10, Material.EMERALD,15},
            new Object[]{ContractType.KILL,    "Witch Contract",    EntityType.WITCH,   null,               5, Material.EMERALD,12},
            new Object[]{ContractType.KILL,    "Enderman Bounty",   EntityType.ENDERMAN,null,               3, Material.EMERALD,10},
            new Object[]{ContractType.KILL,    "Spider Clearance",  EntityType.CAVE_SPIDER,null,            8, Material.EMERALD, 8},
            new Object[]{ContractType.COLLECT, "Nether Reagents",   null,               Material.BLAZE_ROD, 8, Material.EMERALD,20},
            new Object[]{ContractType.COLLECT, "Ghast Tears",       null,               Material.GHAST_TEAR,6, Material.EMERALD,18}
    );

    private static final List<Object[]> BANDIT_TEMPLATES = List.of(
            new Object[]{ContractType.KILL,    "Rival Elimination", EntityType.PILLAGER,null,               5, Material.GOLD_INGOT,12},
            new Object[]{ContractType.KILL,    "Hex Work",          EntityType.WITCH,   null,               3, Material.GOLD_INGOT, 8},
            new Object[]{ContractType.KILL,    "Corpse Run",        EntityType.ZOMBIE,  null,               8, Material.GOLD_INGOT, 6},
            new Object[]{ContractType.COLLECT, "Powder Acquisition",null,               Material.GUNPOWDER, 8, Material.GOLD_INGOT,10},
            new Object[]{ContractType.COLLECT, "Arrow Cache",       null,               Material.ARROW,    32, Material.GOLD_INGOT, 8}
    );

    private static final Object[] FAMINE_TEMPLATE = {
            ContractType.COLLECT, "Emergency Harvest", null, Material.BREAD, 4, Material.EMERALD, 8
    };

    // -------------------------------------------------------------------------

    private static final int    CONTRACTS_PER_BOARD  = 4;
    private static final int    REFRESH_TICKS        = 24_000; // 20 min
    // Famine starts when omen descends through threshold 200; ends when it ascends through 400
    private static final int    FAMINE_START_THRESHOLD = 200;
    private static final int    FAMINE_END_THRESHOLD   = 400;
    private static final int    FAMINE_THRESHOLD       = 200;  // food units server-wide

    private final VestigiumNPC plugin;
    private final File dataFile;

    private final Map<String, BoardType>              boards          = new LinkedHashMap<>();
    private final Map<String, List<BountyContract>>   boardContracts  = new HashMap<>();
    private final Map<UUID,   BountyContract>          activeContracts = new HashMap<>();
    private final Map<UUID,   Integer>                 killProgress    = new HashMap<>();

    // per-player "last viewed board" so /vcbounty take <n> knows which board
    private final Map<UUID, String> lastViewedBoard = new HashMap<>();

    private int     faminePool   = 0;
    private boolean famineActive = false;
    private BukkitRunnable refreshTask;

    public BountyBoardManager(VestigiumNPC plugin) {
        this.plugin   = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "bounty_boards.yml");
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("vcbounty");
        if (cmd != null) cmd.setExecutor(this);

        VestigiumLib.getEventBus().subscribe(OmenThresholdEvent.class, this::onOmenThreshold);

        loadBoards();
        boards.forEach((key, type) -> regenerateContracts(key, type));

        refreshTask = new BukkitRunnable() {
            @Override public void run() {
                boards.forEach((key, type) -> regenerateContracts(key, type));
            }
        };
        refreshTask.runTaskTimer(plugin, REFRESH_TICKS, REFRESH_TICKS);
        plugin.getLogger().info("[BountyBoardManager] Initialized — " + boards.size() + " boards.");
    }

    public void shutdown() {
        if (refreshTask != null) refreshTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Omen / famine
    // -------------------------------------------------------------------------

    private void onOmenThreshold(OmenThresholdEvent event) {
        boolean crossed = event.getThreshold() == FAMINE_START_THRESHOLD && !event.isAscending();
        boolean lifted  = event.getThreshold() == FAMINE_END_THRESHOLD   &&  event.isAscending();

        if (crossed && !famineActive) {
            famineActive = true;
            faminePool   = 0;
            plugin.getServer().broadcastMessage(
                    "§6[Bounty Boards] §eFamine threatens the settlements. "
                            + "Emergency harvest contracts have been posted.");
            boards.forEach((key, type) -> injectFamineContract(key));
        } else if (lifted && famineActive) {
            endFamine();
        }
    }

    private void injectFamineContract(String boardKey) {
        List<BountyContract> list = boardContracts.computeIfAbsent(boardKey, k -> new ArrayList<>());
        list.removeIf(BountyContract::isFamine);
        list.add(buildContract(boardKey + ":famine", FAMINE_TEMPLATE, true));
    }

    private void endFamine() {
        famineActive = false;
        faminePool   = 0;
        boards.forEach((key, type) -> {
            List<BountyContract> list = boardContracts.get(key);
            if (list != null) list.removeIf(BountyContract::isFamine);
        });
        VestigiumLib.getOmenAPI().subtractOmen(30);
        plugin.getServer().broadcastMessage(
                "§a[Bounty Boards] §7The collective harvest has ended the famine. "
                        + "Omen reduced.");
    }

    // -------------------------------------------------------------------------
    // Board interaction (right-click sign)
    // -------------------------------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = event.getClickedBlock();
        if (b == null) return;
        String key = locKey(b.getLocation());
        if (!boards.containsKey(key)) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        lastViewedBoard.put(player.getUniqueId(), key);
        showBoard(player, key);
    }

    private void showBoard(Player player, String boardKey) {
        BoardType type = boards.get(boardKey);
        List<BountyContract> contracts = boardContracts.getOrDefault(boardKey, List.of());

        player.sendMessage("§6§l━━━ Bounty Board (" + formatBoardType(type) + ") ━━━");
        if (contracts.isEmpty()) {
            player.sendMessage("§7No contracts available.");
        } else {
            for (int i = 0; i < contracts.size(); i++) {
                BountyContract c = contracts.get(i);
                String progress = activeContracts.containsKey(player.getUniqueId())
                        && activeContracts.get(player.getUniqueId()).id().equals(c.id())
                        ? " §a[ACTIVE " + killProgress.getOrDefault(player.getUniqueId(), 0)
                                + "/" + c.quantity() + "]"
                        : "";
                player.sendMessage("§e " + (i + 1) + ". §f" + c.displayName()
                        + " §7— " + describeContract(c)
                        + " §7→ §6" + c.rewardCount() + "× "
                        + formatMaterial(c.rewardMat()) + progress);
            }
        }
        player.sendMessage("§7/vcbounty take <1-" + contracts.size() + "> to accept a contract.");
        if (activeContracts.containsKey(player.getUniqueId())) {
            BountyContract active = activeContracts.get(player.getUniqueId());
            if (active.type() == ContractType.COLLECT) {
                player.sendMessage("§7Right-click this board with the required items to complete your contract.");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Contract acceptance
    // -------------------------------------------------------------------------

    private void takeContract(Player player, String boardKey, int index) {
        List<BountyContract> contracts = boardContracts.getOrDefault(boardKey, List.of());
        if (index < 0 || index >= contracts.size()) {
            player.sendMessage("§cInvalid contract number.");
            return;
        }
        if (activeContracts.containsKey(player.getUniqueId())) {
            player.sendMessage("§cYou already have an active contract. Use /vcbounty abandon to drop it.");
            return;
        }
        BountyContract contract = contracts.get(index);
        activeContracts.put(player.getUniqueId(), contract);
        killProgress.put(player.getUniqueId(), 0);

        player.sendMessage("§6Contract accepted: §f" + contract.displayName());
        player.sendMessage("§7" + describeContract(contract));
        player.sendMessage("§7Reward: §6" + contract.rewardCount() + "× " + formatMaterial(contract.rewardMat()));
        if (contract.type() == ContractType.COLLECT) {
            player.sendMessage("§7Return to this board with the items to complete it.");
        }

        // Hand out a Contract Note book
        player.getInventory().addItem(createContractNote(contract));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f);
    }

    // -------------------------------------------------------------------------
    // Contract completion
    // -------------------------------------------------------------------------

    private void tryCompleteCollect(Player player, String boardKey) {
        BountyContract contract = activeContracts.get(player.getUniqueId());
        if (contract == null || contract.type() != ContractType.COLLECT) return;
        if (!boardContracts.getOrDefault(boardKey, List.of()).stream()
                .anyMatch(c -> c.id().equals(contract.id()))) return;

        // Check and remove items from inventory
        int needed = contract.quantity();
        int found  = Arrays.stream(player.getInventory().getContents())
                .filter(item -> item != null && item.getType() == contract.itemTarget())
                .mapToInt(ItemStack::getAmount).sum();

        if (found < needed) {
            player.sendMessage("§cYou need §f" + needed + "× "
                    + formatMaterial(contract.itemTarget())
                    + " §c(you have §f" + found + "§c).");
            return;
        }

        removeItems(player, contract.itemTarget(), needed);
        completeContract(player, contract, boardKey);
    }

    private void completeContract(Player player, BountyContract contract, String boardKey) {
        activeContracts.remove(player.getUniqueId());
        killProgress.remove(player.getUniqueId());

        // Give reward
        ItemStack reward = new ItemStack(contract.rewardMat(), contract.rewardCount());
        player.getInventory().addItem(reward);

        player.sendMessage("§a§lContract complete: §f" + contract.displayName()
                + " §a— §6" + contract.rewardCount() + "× " + formatMaterial(contract.rewardMat()) + " §aawarded!");
        player.getWorld().playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

        // Famine contribution
        if (contract.isFamine()) {
            faminePool += contract.quantity();
            if (faminePool >= FAMINE_THRESHOLD) endFamine();
        }
    }

    // -------------------------------------------------------------------------
    // Kill tracking
    // -------------------------------------------------------------------------

    @EventHandler
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        BountyContract contract = activeContracts.get(killer.getUniqueId());
        if (contract == null || contract.type() != ContractType.KILL) return;
        if (event.getEntity().getType() != contract.mobTarget()) return;

        int progress = killProgress.merge(killer.getUniqueId(), 1, Integer::sum);
        if (progress >= contract.quantity()) {
            // Find nearest board with this contract
            String boardKey = findBoardForContract(contract.id());
            completeContract(killer, contract, boardKey != null ? boardKey : "");
        } else {
            killer.sendActionBar("§6" + contract.displayName() + " §7— §f" + progress
                    + "/" + contract.quantity() + " §7kills");
        }
    }

    private String findBoardForContract(String contractId) {
        for (var entry : boardContracts.entrySet()) {
            if (entry.getValue().stream().anyMatch(c -> c.id().equals(contractId)))
                return entry.getKey();
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Commands — /vcbounty
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7Usage: /vcbounty <take <n>|abandon|status|board <place <type>|remove>>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "take" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
                if (args.length < 2) { player.sendMessage("§cUsage: /vcbounty take <1-4>"); return true; }
                String boardKey = lastViewedBoard.get(player.getUniqueId());
                if (boardKey == null) { player.sendMessage("§cView a Bounty Board first."); return true; }
                try {
                    int idx = Integer.parseInt(args[1]) - 1;
                    takeContract(player, boardKey, idx);
                } catch (NumberFormatException e) {
                    player.sendMessage("§cEnter a number between 1 and 4.");
                }
            }
            case "abandon" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
                if (activeContracts.remove(player.getUniqueId()) != null) {
                    killProgress.remove(player.getUniqueId());
                    player.sendMessage("§7Contract abandoned.");
                } else {
                    player.sendMessage("§7You have no active contract.");
                }
            }
            case "status" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
                BountyContract c = activeContracts.get(player.getUniqueId());
                if (c == null) { player.sendMessage("§7No active contract."); return true; }
                int prog = killProgress.getOrDefault(player.getUniqueId(), 0);
                player.sendMessage("§6Active: §f" + c.displayName() + " §7— " + describeContract(c));
                if (c.type() == ContractType.KILL)
                    player.sendMessage("§7Progress: §f" + prog + "/" + c.quantity());
                player.sendMessage("§7Reward: §6" + c.rewardCount() + "× " + formatMaterial(c.rewardMat()));
            }
            case "board" -> {
                if (!sender.hasPermission("vestigium.bounty.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§7Usage: /vcbounty board <place <type>|remove>"); return true;
                }
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
                if (args[1].equalsIgnoreCase("place")) {
                    if (args.length < 3) { player.sendMessage("§cSpecify type: village, mercenary_post, bandit_camp"); return true; }
                    BoardType type;
                    try { type = BoardType.valueOf(args[2].toUpperCase()); }
                    catch (IllegalArgumentException e) { player.sendMessage("§cUnknown type. Use village, mercenary_post, or bandit_camp."); return true; }
                    Block target = player.getTargetBlockExact(5);
                    if (target == null) { player.sendMessage("§cLook at a sign block."); return true; }
                    String key = locKey(target.getLocation());
                    boards.put(key, type);
                    regenerateContracts(key, type);
                    saveBoards();
                    player.sendMessage("§aBoard registered at " + target.getLocation().toVector() + " as §f" + type.name());
                } else if (args[1].equalsIgnoreCase("remove")) {
                    Block target = player.getTargetBlockExact(5);
                    if (target == null) { player.sendMessage("§cLook at a sign block."); return true; }
                    String key = locKey(target.getLocation());
                    if (boards.remove(key) != null) {
                        boardContracts.remove(key);
                        saveBoards();
                        player.sendMessage("§aBoard removed.");
                    } else {
                        player.sendMessage("§7No board registered there.");
                    }
                }
            }
            default -> sender.sendMessage("§7Usage: /vcbounty <take <n>|abandon|status|board <place <type>|remove>>");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Contract generation
    // -------------------------------------------------------------------------

    private void regenerateContracts(String boardKey, BoardType type) {
        List<Object[]> templates = switch (type) {
            case VILLAGE       -> VILLAGE_TEMPLATES;
            case MERCENARY_POST -> MERCENARY_TEMPLATES;
            case BANDIT_CAMP   -> BANDIT_TEMPLATES;
        };

        List<Object[]> pool = new ArrayList<>(templates);
        Collections.shuffle(pool, ThreadLocalRandom.current());
        List<BountyContract> contracts = new ArrayList<>();
        for (int i = 0; i < Math.min(CONTRACTS_PER_BOARD, pool.size()); i++) {
            contracts.add(buildContract(boardKey + ":" + i, pool.get(i), false));
        }
        if (famineActive) contracts.add(buildContract(boardKey + ":famine", FAMINE_TEMPLATE, true));
        boardContracts.put(boardKey, contracts);
    }

    private BountyContract buildContract(String id, Object[] t, boolean famine) {
        return new BountyContract(
                id,
                (ContractType) t[0],
                (String) t[1],
                (EntityType) t[2],
                (Material) t[3],
                (int) t[4],
                (Material) t[5],
                (int) t[6],
                famine
        );
    }

    // -------------------------------------------------------------------------
    // Contract Note
    // -------------------------------------------------------------------------

    private ItemStack createContractNote(BountyContract c) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Contract");
        meta.setAuthor("Bounty Board");
        meta.setDisplayName("§6Contract Note: §f" + c.displayName());
        String body = "CONTRACT\n§f" + c.displayName() + "\n\n"
                + "Task: " + describeContract(c) + "\n\n"
                + "Reward: " + c.rewardCount() + " " + formatMaterial(c.rewardMat()) + "\n\n"
                + (c.type() == ContractType.KILL
                        ? "Track your kills — progress shows on screen."
                        : "Return to the board with the items to collect your reward.");
        meta.addPage(body);
        book.setItemMeta(meta);
        return book;
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void loadBoards() {
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        var section = cfg.getConfigurationSection("boards");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String typeStr = section.getString(key + ".type");
            try { boards.put(key, BoardType.valueOf(typeStr)); }
            catch (Exception ignored) {}
        }
    }

    private void saveBoards() {
        YamlConfiguration cfg = new YamlConfiguration();
        boards.forEach((key, type) -> cfg.set("boards." + key + ".type", type.name()));
        try { cfg.save(dataFile); }
        catch (IOException e) { plugin.getLogger().warning("[BountyBoardManager] Save failed: " + e.getMessage()); }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void removeItems(Player player, Material mat, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != mat) continue;
            if (item.getAmount() <= remaining) {
                remaining -= item.getAmount();
                contents[i] = null;
            } else {
                item.setAmount(item.getAmount() - remaining);
                remaining = 0;
            }
        }
        player.getInventory().setContents(contents);
    }

    private static String locKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX()
                + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private static String describeContract(BountyContract c) {
        return c.type() == ContractType.KILL
                ? "Kill " + c.quantity() + "× " + formatEntityType(c.mobTarget())
                : "Collect " + c.quantity() + "× " + formatMaterial(c.itemTarget());
    }

    private static String formatBoardType(BoardType t) {
        return switch (t) {
            case VILLAGE        -> "Village";
            case MERCENARY_POST -> "Mercenary Post";
            case BANDIT_CAMP    -> "Bandit Camp";
        };
    }

    private static String formatMaterial(Material m) {
        if (m == null) return "?";
        String s = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String formatEntityType(EntityType t) {
        if (t == null) return "?";
        String s = t.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
