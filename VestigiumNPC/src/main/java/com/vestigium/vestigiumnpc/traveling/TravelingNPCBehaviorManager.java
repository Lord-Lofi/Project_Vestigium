package com.vestigium.vestigiumnpc.traveling;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumnpc.VestigiumNPC;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles right-click interaction behavior for all traveling NPC types.
 * Dispatches on the vestigium:npc_type PDC tag set by TravelingNPCManager.
 *
 * Behaviors:
 *   CHRONICLER       — delivers 3 recent server events from server_memory.yml
 *   LAST_RESONANT    — dramatic intro + sets quest flag; once per player; DEEP_DARK only
 *   HERMIT           — cryptic message + 20% titan bone gift; 20-min cooldown
 *   MERCENARY_POST   — hire a Zombie escort for 5 shards; 10-min duration
 *   EXILED_MAGE      — curse or cleanse held Vestigium gear for shards
 *   ORE_BROKER       — native merchant GUI with ore trades
 *   TRAPPED_MINER    — rescue interaction → random ore reward; NPC despawns
 *   CRYSTAL_HERMIT   — titan bone → amethyst exchange; 15-min cooldown
 *   VEIN_WHISPERER   — scan nearby blocks for ores; directional actionbar hint
 *   LORE_SCHOLAR     — compare civilization lore fragments; reward for 2+
 */
public class TravelingNPCBehaviorManager implements Listener, CommandExecutor {

    private static final NamespacedKey NPC_TYPE_KEY       = new NamespacedKey("vestigium", "npc_type");
    private static final NamespacedKey TITAN_BONE_KEY     = new NamespacedKey("vestigium", "titan_bone");
    private static final NamespacedKey DIVINE_KEY         = new NamespacedKey("vestigium", "divine_artifact");
    private static final NamespacedKey RESONANT_KEY       = new NamespacedKey("vestigium", "resonant_artifact");
    private static final NamespacedKey TEMPER_KEY         = new NamespacedKey("vestigium", "temper");
    private static final NamespacedKey RUNES_KEY          = new NamespacedKey("vestigium", "runes");
    private static final NamespacedKey BLADE_KEY          = new NamespacedKey("vestigium", "weapon_blade");
    private static final NamespacedKey CURSED_KEY         = new NamespacedKey("vestigium", "cursed");
    private static final NamespacedKey SHARD_KEY          = new NamespacedKey("vestigium", "vestige_shard");

    // Player PDC cooldown/state keys
    private static final NamespacedKey CHRONICLER_CD_KEY     = new NamespacedKey("vestigium", "chronicler_cd");
    private static final NamespacedKey HERMIT_CD_KEY         = new NamespacedKey("vestigium", "hermit_cd");
    private static final NamespacedKey CRYSTAL_HERMIT_CD_KEY = new NamespacedKey("vestigium", "crystal_hermit_cd");
    private static final NamespacedKey VEIN_CD_KEY           = new NamespacedKey("vestigium", "vein_whisperer_cd");
    private static final NamespacedKey SCHOLAR_CD_KEY        = new NamespacedKey("vestigium", "lore_scholar_cd");
    private static final NamespacedKey LAST_RESONANT_KEY     = new NamespacedKey("vestigium", "last_resonant_met");
    private static final NamespacedKey HIRED_MERC_KEY        = new NamespacedKey("vestigium", "hired_mercenary");
    private static final NamespacedKey MERC_OWNER_KEY        = new NamespacedKey("vestigium", "mercenary_owner");
    private static final NamespacedKey MERC_EXPIRE_KEY       = new NamespacedKey("vestigium", "merc_expire");

    private static final long CHRONICLER_CD   = 5L  * 60 * 1000;
    private static final long HERMIT_CD       = 20L * 60 * 1000;
    private static final long CRYSTAL_CD      = 15L * 60 * 1000;
    private static final long VEIN_CD         = 2L  * 60 * 1000;
    private static final long SCHOLAR_CD      = 10L * 60 * 1000;
    private static final long MERC_DURATION   = 10L * 60 * 1000;

    private final VestigiumNPC plugin;
    private BukkitRunnable mercTask;

    public TravelingNPCBehaviorManager(VestigiumNPC plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("vcmage");
        if (cmd != null) cmd.setExecutor(this);
        startMercenaryTask();
        plugin.getLogger().info("[TravelingNPCBehaviorManager] Initialized.");
    }

    public void shutdown() {
        if (mercTask != null) mercTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Interaction dispatcher
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Villager npc)) return;
        String type = npc.getPersistentDataContainer().get(NPC_TYPE_KEY, PersistentDataType.STRING);
        if (type == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        switch (type) {
            case TravelingNPCType.CHRONICLER    -> handleChronicler(player, npc);
            case TravelingNPCType.LAST_RESONANT -> handleLastResonant(player, npc);
            case TravelingNPCType.HERMIT        -> handleHermit(player, npc);
            case TravelingNPCType.MERCENARY_POST -> handleMercenary(player);
            case TravelingNPCType.EXILED_MAGE   -> handleExiledMage(player);
            case TravelingNPCType.ORE_BROKER    -> handleOreBroker(player, npc);
            case TravelingNPCType.TRAPPED_MINER -> handleTrappedMiner(player, npc);
            case TravelingNPCType.CRYSTAL_HERMIT -> handleCrystalHermit(player);
            case TravelingNPCType.VEIN_WHISPERER -> handleVeinWhisperer(player);
            case TravelingNPCType.LORE_SCHOLAR  -> handleLoreScholar(player);
        }
    }

    // -------------------------------------------------------------------------
    // The Chronicler
    // -------------------------------------------------------------------------

    private void handleChronicler(Player player, Villager npc) {
        long now = System.currentTimeMillis();
        long cd  = player.getPersistentDataContainer()
                .getOrDefault(CHRONICLER_CD_KEY, PersistentDataType.LONG, 0L);
        if (now < cd) {
            player.sendMessage("§8[Chronicler] §7The records can wait. Return in §f"
                    + (cd - now) / 60000 + " §7min.");
            return;
        }
        player.getPersistentDataContainer().set(CHRONICLER_CD_KEY, PersistentDataType.LONG, now + CHRONICLER_CD);

        List<String> events = readServerMemory();
        player.sendMessage("§6§lThe Chronicler §r§8speaks:");
        if (events.isEmpty()) {
            player.sendMessage("§7\"The record runs thin. The server has been quiet.\"");
            return;
        }
        int count = Math.min(3, events.size());
        for (int i = events.size() - count; i < events.size(); i++) {
            player.sendMessage("§7\"" + events.get(i) + "\"");
        }
        player.getWorld().playSound(npc.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.4f, 0.8f);
    }

    private List<String> readServerMemory() {
        File memFile = new File(plugin.getDataFolder().getParentFile(),
                "VestigiumLore/server_memory.yml");
        if (!memFile.exists()) return Collections.emptyList();
        org.bukkit.configuration.file.YamlConfiguration cfg =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(memFile);
        List<String> events = new ArrayList<>();
        for (String key : cfg.getKeys(false)) {
            String entry = cfg.getString(key);
            if (entry != null && !entry.isBlank()) events.add(entry);
        }
        return events;
    }

    // -------------------------------------------------------------------------
    // The Last Resonant
    // -------------------------------------------------------------------------

    private static final String[] LAST_RESONANT_INTRO = {
        "§5\"You found me. After so long... someone found me.\"",
        "§5\"I am all that remains of those who listened when the city last spoke.\"",
        "§5\"They called me The Last Resonant. I did not choose the name.\"",
        "§5\"There is a task only you can complete. But you are not ready — not yet.\"",
        "§5\"Return when you have heard the city breathe. Then I will tell you everything.\""
    };

    private void handleLastResonant(Player player, Villager npc) {
        if (player.getLocation().getBlock().getBiome() != Biome.DEEP_DARK) {
            player.sendMessage("§8The entity seems to only appear in the deepest dark.");
            return;
        }

        boolean alreadyMet = player.getPersistentDataContainer()
                .has(LAST_RESONANT_KEY, PersistentDataType.BYTE);

        if (!alreadyMet) {
            player.getPersistentDataContainer()
                    .set(LAST_RESONANT_KEY, PersistentDataType.BYTE, (byte) 1);
            player.getWorld().spawnParticle(Particle.SCULK_SOUL, npc.getLocation().add(0, 1, 0),
                    20, 1, 0.5, 1, 0.01);
            player.getWorld().playSound(npc.getLocation(), Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.6f, 0.5f);
            for (int i = 0; i < LAST_RESONANT_INTRO.length; i++) {
                final int idx = i;
                plugin.getServer().getScheduler().runTaskLater(plugin,
                        () -> player.sendMessage(LAST_RESONANT_INTRO[idx]), i * 40L);
            }
            // Mark quest stage 0 unlocked
            player.getPersistentDataContainer()
                    .set(new NamespacedKey("vestigium", "quest_last_resonant"),
                            PersistentDataType.STRING, "stage_0");
            plugin.getLogger().info("[LastResonant] " + player.getName() + " encountered The Last Resonant.");
        } else {
            int stage = 0;
            String stageStr = player.getPersistentDataContainer()
                    .get(new NamespacedKey("vestigium", "quest_last_resonant"), PersistentDataType.STRING);
            if (stageStr != null) {
                try { stage = Integer.parseInt(stageStr.replace("stage_", "")); } catch (NumberFormatException ignored) {}
            }
            player.sendMessage("§5\"You return. The city watches.\"");
            if (stage == 0) {
                player.sendMessage("§7Have you listened? Have you heard it breathe?");
                player.sendMessage("§8(Spend time in the deep dark until the city acknowledges you.)");
            } else {
                player.sendMessage("§7\"The path ahead has no name. Walk it anyway.\"");
            }
            player.getWorld().playSound(npc.getLocation(), Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.3f, 0.6f);
        }
    }

    // -------------------------------------------------------------------------
    // Hermit
    // -------------------------------------------------------------------------

    private static final String[] HERMIT_LINES = {
        "§7\"The mountain does not ask why you climb it.\"",
        "§7\"I counted the stars once. Twice was enough.\"",
        "§7\"Something passed through here, three moons ago. Left no tracks.\"",
        "§7\"You carry too much. The weight shows in your eyes.\"",
        "§7\"The old paths still run beneath the new roads. Most have forgotten.\"",
        "§7\"Silence is a language. You haven't learned it yet.\"",
        "§7\"I have watched seventeen seasons from this ridge. None repeated.\"",
        "§7\"There are doors with no buildings around them. Stay curious.\"",
    };

    private void handleHermit(Player player, Villager npc) {
        long now = System.currentTimeMillis();
        long cd  = player.getPersistentDataContainer()
                .getOrDefault(HERMIT_CD_KEY, PersistentDataType.LONG, 0L);
        if (now < cd) {
            player.sendMessage("§7The hermit has nothing more to say for now.");
            return;
        }
        player.getPersistentDataContainer().set(HERMIT_CD_KEY, PersistentDataType.LONG, now + HERMIT_CD);

        String line = HERMIT_LINES[ThreadLocalRandom.current().nextInt(HERMIT_LINES.length)];
        player.sendMessage("§6Hermit: " + line);
        player.getWorld().playSound(npc.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.5f, 0.9f);

        // 20% chance: gift a random Titan Bone
        if (ThreadLocalRandom.current().nextDouble() < 0.20) {
            String[] bones = {"amber", "aqua", "crimson", "pale", "verdant", "crystal", "volcanic"};
            String boneId = bones[ThreadLocalRandom.current().nextInt(bones.length)];
            ItemStack bone = buildTitanBone(boneId);
            player.getInventory().addItem(bone);
            player.sendMessage("§8They press something into your hand without looking up.");
        }
    }

    private ItemStack buildTitanBone(String boneId) {
        String[] names = {"§6Amber Titan Bone", "§3Aqua Titan Bone", "§cCrimson Titan Bone",
                "§fPale Titan Bone", "§aVerdant Titan Bone", "§bCrystal Titan Bone", "§4Volcanic Titan Bone"};
        int[] cmds  = {60001, 60002, 60003, 60004, 60005, 60006, 60007};
        String[] ids = {"amber", "aqua", "crimson", "pale", "verdant", "crystal", "volcanic"};
        int idx = 0;
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(boneId)) { idx = i; break; }

        ItemStack item = new ItemStack(Material.BONE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(names[idx]);
        meta.setCustomModelData(cmds[idx]);
        meta.setLore(List.of("§7Found in: §f(unknown)", "§8A fragment of something impossibly large."));
        meta.getPersistentDataContainer().set(TITAN_BONE_KEY, PersistentDataType.STRING, boneId);
        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------
    // Mercenary Post — hire escort
    // -------------------------------------------------------------------------

    private void handleMercenary(Player player) {
        // Check if player already has a hired mercenary
        String existingUUID = player.getPersistentDataContainer()
                .get(HIRED_MERC_KEY, PersistentDataType.STRING);
        if (existingUUID != null) {
            player.sendMessage("§7You already have a hired blade. Let them go first.");
            return;
        }

        // Cost: 5 Vestige Shards
        int shards = countShards(player);
        if (shards < 5) {
            player.sendMessage("§cNot enough Vestige Shards. §7(Need §f5§7, have §f" + shards + "§7.)");
            return;
        }
        consumeShards(player, 5);

        // Spawn mercenary Zombie near player
        Location spawnLoc = player.getLocation().clone().add(2, 0, 0);
        Zombie merc = (Zombie) player.getWorld().spawnEntity(spawnLoc, EntityType.ZOMBIE);
        merc.setCustomName("§7Hired Blade");
        merc.setCustomNameVisible(true);
        merc.setBaby(false);
        merc.setMaxHealth(40.0);
        merc.setHealth(40.0);
        merc.getPersistentDataContainer()
                .set(MERC_OWNER_KEY, PersistentDataType.STRING, player.getUniqueId().toString());
        merc.getEquipment().setHelmet(new ItemStack(Material.CHAINMAIL_HELMET));
        merc.getEquipment().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        merc.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
        merc.getEquipment().setHelmetDropChance(0f);
        merc.getEquipment().setChestplateDropChance(0f);
        merc.getEquipment().setItemInMainHandDropChance(0f);

        long expiry = System.currentTimeMillis() + MERC_DURATION;
        player.getPersistentDataContainer().set(HIRED_MERC_KEY, PersistentDataType.STRING,
                merc.getUniqueId().toString());
        player.getPersistentDataContainer().set(MERC_EXPIRE_KEY, PersistentDataType.LONG, expiry);

        player.sendMessage("§7\"Work is work.\" §8The mercenary falls in behind you. §8(10 min)");
        player.getWorld().playSound(spawnLoc, Sound.ENTITY_VILLAGER_YES, 0.6f, 1.0f);
    }

    private void startMercenaryTask() {
        mercTask = new BukkitRunnable() {
            @Override public void run() {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    tickMercenary(player);
                }
            }
        };
        mercTask.runTaskTimer(plugin, 20L, 20L);
    }

    private void tickMercenary(Player player) {
        String mercUUIDStr = player.getPersistentDataContainer()
                .get(HIRED_MERC_KEY, PersistentDataType.STRING);
        if (mercUUIDStr == null) return;

        long expiry = player.getPersistentDataContainer()
                .getOrDefault(MERC_EXPIRE_KEY, PersistentDataType.LONG, 0L);
        if (System.currentTimeMillis() >= expiry) {
            dismissMercenary(player, mercUUIDStr, "§8Your hired blade's contract has expired.");
            return;
        }

        Zombie merc = findMercenary(mercUUIDStr);
        if (merc == null || !merc.isValid()) {
            player.getPersistentDataContainer().remove(HIRED_MERC_KEY);
            player.getPersistentDataContainer().remove(MERC_EXPIRE_KEY);
            return;
        }

        // Attack nearest hostile near player (within 12 blocks)
        LivingEntity target = nearestHostile(player, 12);
        if (target != null) {
            merc.setTarget(target);
        } else if (merc.getLocation().distance(player.getLocation()) > 8) {
            // Follow player
            merc.getPathfinder().moveTo(player, 0.85);
        }
    }

    private void dismissMercenary(Player player, String uuidStr, String msg) {
        player.getPersistentDataContainer().remove(HIRED_MERC_KEY);
        player.getPersistentDataContainer().remove(MERC_EXPIRE_KEY);
        Zombie merc = findMercenary(uuidStr);
        if (merc != null && merc.isValid()) {
            merc.getWorld().spawnParticle(Particle.SMOKE, merc.getLocation().add(0, 1, 0),
                    8, 0.3, 0.5, 0.3, 0.01);
            merc.remove();
        }
        player.sendMessage(msg);
    }

    private Zombie findMercenary(String uuidStr) {
        try {
            UUID uuid = UUID.fromString(uuidStr);
            for (World world : plugin.getServer().getWorlds()) {
                for (Entity e : world.getEntities()) {
                    if (e.getUniqueId().equals(uuid) && e instanceof Zombie z) return z;
                }
            }
        } catch (IllegalArgumentException ignored) {}
        return null;
    }

    private LivingEntity nearestHostile(Player player, double radius) {
        LivingEntity closest = null;
        double minDist = Double.MAX_VALUE;
        for (Entity e : player.getNearbyEntities(radius, radius, radius)) {
            if (!(e instanceof Monster m)) continue;
            if (m.getPersistentDataContainer().has(MERC_OWNER_KEY, PersistentDataType.STRING)) continue;
            double d = e.getLocation().distanceSquared(player.getLocation());
            if (d < minDist) { minDist = d; closest = m; }
        }
        return closest;
    }

    // Prevent the mercenary from targeting its own owner
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMercTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Zombie z)) return;
        String ownerStr = z.getPersistentDataContainer().get(MERC_OWNER_KEY, PersistentDataType.STRING);
        if (ownerStr == null) return;
        if (event.getTarget() instanceof Player p && p.getUniqueId().toString().equals(ownerStr)) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------------------------
    // Exiled Mage — curse or cleanse Vestigium gear
    // -------------------------------------------------------------------------

    private void handleExiledMage(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isVestigiumGear(held)) {
            player.sendMessage("§8§l── Exiled Mage ──");
            player.sendMessage("§7Hold a Vestigium-tagged item in your main hand.");
            player.sendMessage("§7  §fCurse§8: costs §f3 shards §8(tags item as cursed)");
            player.sendMessage("§7  §fCleanse§8: costs §f8 shards §8(removes curse)");
            return;
        }

        boolean isCursed = held.getItemMeta() != null &&
                held.getItemMeta().getPersistentDataContainer().has(CURSED_KEY, PersistentDataType.BYTE);

        if (!isCursed) {
            int shards = countShards(player);
            if (shards < 3) { player.sendMessage("§cNeed §f3 §cVestige Shards to curse this item."); return; }
            consumeShards(player, 3);
            ItemMeta meta = held.getItemMeta();
            meta.getPersistentDataContainer().set(CURSED_KEY, PersistentDataType.BYTE, (byte) 1);
            List<String> lore = new ArrayList<>(meta.getLore() == null ? List.of() : meta.getLore());
            lore.add("§8✦ §4Cursed");
            meta.setLore(lore);
            held.setItemMeta(meta);
            player.sendMessage("§4The mage speaks a word you cannot unhear. The item shudders.");
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, true, false));
        } else {
            int shards = countShards(player);
            if (shards < 8) { player.sendMessage("§cNeed §f8 §cVestige Shards to cleanse this item."); return; }
            consumeShards(player, 8);
            ItemMeta meta = held.getItemMeta();
            meta.getPersistentDataContainer().remove(CURSED_KEY);
            List<String> lore = new ArrayList<>(meta.getLore() == null ? List.of() : meta.getLore());
            lore.removeIf(l -> l.contains("§4Cursed"));
            meta.setLore(lore);
            held.setItemMeta(meta);
            player.sendMessage("§aThe shadow lifts. The mage looks satisfied — or perhaps disappointed.");
            player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().add(0, 1, 0),
                    20, 0.5, 0.5, 0.5, 0.1);
        }
    }

    // -------------------------------------------------------------------------
    // Ore Broker — merchant GUI
    // -------------------------------------------------------------------------

    private void handleOreBroker(Player player, Villager npc) {
        List<MerchantRecipe> trades = new ArrayList<>();

        trades.add(recipe(new ItemStack(Material.RAW_IRON, 4), new ItemStack(Material.EMERALD, 1),
                new ItemStack(Material.IRON_INGOT, 8)));
        trades.add(recipe(new ItemStack(Material.RAW_GOLD, 3), new ItemStack(Material.EMERALD, 1),
                new ItemStack(Material.GOLD_INGOT, 6)));
        trades.add(recipe(new ItemStack(Material.COAL, 8), null,
                new ItemStack(Material.EMERALD, 2)));
        trades.add(recipe(new ItemStack(Material.DIAMOND, 1), new ItemStack(Material.EMERALD, 3),
                new ItemStack(Material.DIAMOND, 2)));
        trades.add(recipe(new ItemStack(Material.LAPIS_LAZULI, 16), null,
                new ItemStack(Material.EMERALD, 3)));

        player.openMerchant(Bukkit.createMerchant(npc.getCustomName()), true);
        ((org.bukkit.inventory.MerchantInventory) player.getOpenInventory().getTopInventory())
                .getMerchant().setRecipes(trades);
    }

    private MerchantRecipe recipe(ItemStack ing1, ItemStack ing2, ItemStack result) {
        MerchantRecipe r = new MerchantRecipe(result, 999);
        r.addIngredient(ing1);
        if (ing2 != null) r.addIngredient(ing2);
        return r;
    }

    // -------------------------------------------------------------------------
    // Trapped Miner — rescue + reward
    // -------------------------------------------------------------------------

    private static final Material[] RESCUE_ORES = {
        Material.IRON_ORE, Material.GOLD_ORE, Material.COAL_ORE,
        Material.COPPER_ORE, Material.LAPIS_ORE, Material.REDSTONE_ORE
    };

    private void handleTrappedMiner(Player player, Villager npc) {
        player.sendMessage("§6Trapped Miner: §7\"Thank you! Here — take what I had on me.\"");
        int count = 4 + ThreadLocalRandom.current().nextInt(5);
        for (int i = 0; i < count; i++) {
            Material ore = RESCUE_ORES[ThreadLocalRandom.current().nextInt(RESCUE_ORES.length)];
            player.getInventory().addItem(new ItemStack(ore, 1 + ThreadLocalRandom.current().nextInt(4)));
        }
        player.sendActionBar("§aSaved the Miner! They left you some §fores§a.");
        player.getWorld().playSound(npc.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1.1f);
        npc.remove();
    }

    // -------------------------------------------------------------------------
    // Crystal Hermit — titan bone → amethyst exchange
    // -------------------------------------------------------------------------

    private void handleCrystalHermit(Player player) {
        long now = System.currentTimeMillis();
        long cd  = player.getPersistentDataContainer()
                .getOrDefault(CRYSTAL_HERMIT_CD_KEY, PersistentDataType.LONG, 0L);
        if (now < cd) {
            player.sendMessage("§b\"The crystals need time to align.\" §8(§7" + (cd - now) / 60000 + " min§8)");
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() != Material.BONE
                || held.getItemMeta() == null
                || !held.getItemMeta().getPersistentDataContainer().has(TITAN_BONE_KEY, PersistentDataType.STRING)) {
            player.sendMessage("§b\"Bring me a Titan Bone and I will show you what the crystals remember.\"");
            return;
        }

        player.getPersistentDataContainer().set(CRYSTAL_HERMIT_CD_KEY, PersistentDataType.LONG, now + CRYSTAL_CD);

        // Consume one bone
        held.setAmount(held.getAmount() - 1);

        // Give random amethyst reward
        int roll = ThreadLocalRandom.current().nextInt(3);
        ItemStack reward = switch (roll) {
            case 0 -> new ItemStack(Material.AMETHYST_BLOCK, 1);
            case 1 -> new ItemStack(Material.AMETHYST_SHARD, 6);
            default -> new ItemStack(Material.SPYGLASS, 1);
        };
        player.getInventory().addItem(reward);

        player.sendMessage("§b\"The bone sings a story the crystals already know.\"");
        player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().add(0, 1, 0),
                24, 0.5, 0.5, 0.5, 0.1);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
    }

    // -------------------------------------------------------------------------
    // Vein Whisperer — ore direction hint
    // -------------------------------------------------------------------------

    private void handleVeinWhisperer(Player player) {
        long now = System.currentTimeMillis();
        long cd  = player.getPersistentDataContainer()
                .getOrDefault(VEIN_CD_KEY, PersistentDataType.LONG, 0L);
        if (now < cd) {
            player.sendMessage("§7\"The earth is still listening. Try again soon.\"");
            return;
        }
        player.getPersistentDataContainer().set(VEIN_CD_KEY, PersistentDataType.LONG, now + VEIN_CD);

        Location nearest = findNearestOre(player.getLocation(), 20);
        if (nearest == null) {
            player.sendActionBar("§7\"Nothing stirs nearby. Dig deeper.\"");
            return;
        }

        Location pLoc = player.getLocation();
        double dx = nearest.getX() - pLoc.getX();
        double dy = nearest.getY() - pLoc.getY();
        double dz = nearest.getZ() - pLoc.getZ();
        String hDir  = dx > 0 ? "East" : "West";
        String vDir  = dy > 0 ? "above" : "below";
        String zDir  = dz > 0 ? "South" : "North";
        String oreType = nearest.getBlock().getType().name()
                .replace("_ORE", "").replace("DEEPSLATE_", "").replace("NETHER_", "")
                .toLowerCase();

        player.sendActionBar("§e\"" + oreType + " — head " + hDir + "/" + zDir + ", " + vDir + ".\"");
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_STEP, 0.5f, 1.5f);
    }

    private Location findNearestOre(Location center, int radius) {
        Set<Material> ores = Set.of(
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.ANCIENT_DEBRIS
        );
        Location nearest = null;
        double minDist = Double.MAX_VALUE;
        World world = center.getWorld();
        for (int x = -radius; x <= radius; x += 2) {
            for (int y = -radius; y <= radius; y += 2) {
                for (int z = -radius; z <= radius; z += 2) {
                    Location candidate = center.clone().add(x, y, z);
                    if (!ores.contains(candidate.getBlock().getType())) continue;
                    double d = candidate.distanceSquared(center);
                    if (d < minDist) { minDist = d; nearest = candidate; }
                }
            }
        }
        return nearest;
    }

    // -------------------------------------------------------------------------
    // Lore Scholar — compare civilization fragments
    // -------------------------------------------------------------------------

    private void handleLoreScholar(Player player) {
        long now = System.currentTimeMillis();
        long cd  = player.getPersistentDataContainer()
                .getOrDefault(SCHOLAR_CD_KEY, PersistentDataType.LONG, 0L);
        if (now < cd) {
            player.sendMessage("§7The scholar is still cross-referencing. §8(" + (cd - now) / 60000 + " min)");
            return;
        }

        var lore = VestigiumLib.getLoreRegistry();
        UUID uid = player.getUniqueId();

        boolean hasResonant   = lore.hasFragment(uid, "deep_archive_alpha_main")
                || lore.hasFragment(uid, "ancient_guardian_chamber_main");
        boolean hasAntecedent = lore.hasFragment(uid, "antecedent_vault_main");
        boolean hasTidal      = lore.hasFragment(uid, "ancient_guardian_chamber_combined");

        int count = (hasResonant ? 1 : 0) + (hasAntecedent ? 1 : 0) + (hasTidal ? 1 : 0);

        player.sendMessage("§6§lLore Scholar §r§8— §7fragments analysed:");
        player.sendMessage("  §9Resonant: §f" + (hasResonant ? "✓" : "—"));
        player.sendMessage("  §eAntecedent: §f" + (hasAntecedent ? "✓" : "—"));
        player.sendMessage("  §3Tidal: §f" + (hasTidal ? "✓" : "—"));

        if (count >= 2) {
            player.getPersistentDataContainer().set(SCHOLAR_CD_KEY, PersistentDataType.LONG, now + SCHOLAR_CD);
            player.sendMessage("§7\"The parallels are unmistakable. They all described the same moment.\"");
            lore.grantFragment(uid, "scholar_comparative_insight");
            player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().add(0, 1, 0),
                    30, 0.5, 0.5, 0.5, 0.1);
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.5f, 1.0f);
        } else if (count == 1) {
            player.sendMessage("§7\"Bring me records from at least two civilisations.\"");
            player.sendMessage("§8(Explore Resonant terminals, Antecedent sites, and Tidal archives.)");
        } else {
            player.sendMessage("§7\"You carry nothing I can use. Seek the old terminals.\"");
        }
    }

    // -------------------------------------------------------------------------
    // Command — /vcmage (dismiss hired blade)
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Player only."); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("dismiss")) {
            String mercUUID = player.getPersistentDataContainer()
                    .get(HIRED_MERC_KEY, PersistentDataType.STRING);
            if (mercUUID == null) { player.sendMessage("§7You have no hired blade."); return true; }
            dismissMercenary(player, mercUUID, "§7You dismiss your hired blade.");
        } else {
            player.sendMessage("§7/vcmage dismiss — release your hired mercenary");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isVestigiumGear(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(TEMPER_KEY, PersistentDataType.STRING)
                || pdc.has(RUNES_KEY, PersistentDataType.STRING)
                || pdc.has(BLADE_KEY, PersistentDataType.STRING)
                || pdc.has(CURSED_KEY, PersistentDataType.BYTE)
                || pdc.has(DIVINE_KEY, PersistentDataType.STRING)
                || pdc.has(RESONANT_KEY, PersistentDataType.BYTE)
                || pdc.has(TITAN_BONE_KEY, PersistentDataType.STRING);
    }

    private int countShards(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.hasItemMeta()
                    && item.getItemMeta().getPersistentDataContainer()
                           .has(SHARD_KEY, PersistentDataType.BYTE)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private void consumeShards(Player player, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || !item.hasItemMeta()) continue;
            if (!item.getItemMeta().getPersistentDataContainer()
                    .has(SHARD_KEY, PersistentDataType.BYTE)) continue;
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
}
