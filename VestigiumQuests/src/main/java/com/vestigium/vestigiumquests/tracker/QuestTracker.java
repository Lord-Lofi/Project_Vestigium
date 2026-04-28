package com.vestigium.vestigiumquests.tracker;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.util.BlockStructureTag;
import com.vestigium.vestigiumquests.VestigiumQuests;
import com.vestigium.vestigiumquests.registry.QuestDefinition;
import com.vestigium.vestigiumquests.registry.QuestRegistry;
import com.vestigium.vestigiumquests.registry.QuestType;
import com.vestigium.vestigiumquests.reward.QuestRewardManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Per-player quest progress tracking.
 *
 * Active quest state is stored as Player PDC:
 *   vq_active_{questId}   → Integer  current progress toward count
 *   vq_complete_{questId} → Boolean  quest completed
 *
 * Flat-file backup persists active quest lists per player to
 * plugins/VestigiumQuests/progress/{uuid}.yml on shutdown.
 *
 * Supported auto-progress hooks:
 *   KILL    — EntityDeathEvent, target = EntityType name
 *   COLLECT — PlayerPickupItemEvent, target = Material name
 *   EXPLORE — PlayerMoveEvent, checks structure_id PDC on block underfoot
 *   LORE    — polled on LoreRegistry fragment grant (manual call from LoreDeliveryManager via EventBus)
 */
public class QuestTracker implements Listener {

    private final VestigiumQuests plugin;
    private final QuestRegistry questRegistry;
    private final QuestRewardManager rewardManager;

    public QuestTracker(VestigiumQuests plugin, QuestRegistry questRegistry,
                        QuestRewardManager rewardManager) {
        this.plugin = plugin;
        this.questRegistry = questRegistry;
        this.rewardManager = rewardManager;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[QuestTracker] Initialized.");
    }

    public void saveAll() {
        File dir = new File(plugin.getDataFolder(), "progress");
        dir.mkdirs();
        plugin.getServer().getOnlinePlayers().forEach(p -> savePlayer(p, dir));
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public boolean isActive(Player player, String questId) {
        NamespacedKey key = activeKey(questId);
        return player.getPersistentDataContainer().has(key, PersistentDataType.INTEGER);
    }

    public boolean isComplete(Player player, String questId) {
        return player.getPersistentDataContainer()
                .getOrDefault(completeKey(questId), PersistentDataType.BOOLEAN, false);
    }

    public int getProgress(Player player, String questId) {
        return player.getPersistentDataContainer()
                .getOrDefault(activeKey(questId), PersistentDataType.INTEGER, 0);
    }

    /** Assigns a quest to a player if gates pass and it is not already active/complete. */
    public boolean assignQuest(Player player, String questId) {
        Optional<QuestDefinition> opt = questRegistry.getById(questId);
        if (opt.isEmpty()) return false;
        QuestDefinition def = opt.get();

        if (!def.repeatable() && isComplete(player, questId)) return false;
        if (isActive(player, questId)) return false;

        int omen = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
        if (omen < def.minOmen() || omen > def.maxOmen()) return false;

        player.getPersistentDataContainer()
                .set(activeKey(questId), PersistentDataType.INTEGER, 0);
        player.sendMessage("§6[Quest] §eNew quest: §f" + def.title());
        player.sendMessage("§7" + def.description());
        return true;
    }

    /** Advances quest progress by delta. Completes quest if count is reached. */
    public void advanceProgress(Player player, String questId, int delta) {
        if (!isActive(player, questId)) return;
        QuestDefinition def = questRegistry.getById(questId).orElse(null);
        if (def == null) return;

        int current = getProgress(player, questId) + delta;
        if (current >= def.count()) {
            completeQuest(player, def);
        } else {
            player.getPersistentDataContainer()
                    .set(activeKey(questId), PersistentDataType.INTEGER, current);
            player.sendMessage("§6[Quest] §7" + def.title() + ": §e" + current + "§7/§e" + def.count());
        }
    }

    /** Admin override: force-completes a quest regardless of progress or gates. */
    public boolean forceComplete(Player player, String questId) {
        QuestDefinition def = questRegistry.getById(questId).orElse(null);
        if (def == null) return false;
        completeQuest(player, def);
        return true;
    }

    private void completeQuest(Player player, QuestDefinition def) {
        player.getPersistentDataContainer().remove(activeKey(def.id()));
        player.getPersistentDataContainer()
                .set(completeKey(def.id()), PersistentDataType.BOOLEAN, true);
        player.sendMessage("§6[Quest Complete] §e" + def.title());
        rewardManager.grantRewards(player, def);
    }

    // -------------------------------------------------------------------------
    // Auto-progress event hooks
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;
        Player killer = event.getEntity().getKiller();
        String mobType = event.getEntityType().name();

        questRegistry.getAll().stream()
                .filter(q -> q.type() == QuestType.KILL && mobType.equalsIgnoreCase(q.target()))
                .forEach(q -> advanceProgress(killer, q.id(), 1));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        String matName = event.getItem().getItemStack().getType().name();

        questRegistry.getAll().stream()
                .filter(q -> q.type() == QuestType.COLLECT && matName.equalsIgnoreCase(q.target()))
                .forEach(q -> advanceProgress(player, q.id(),
                        event.getItem().getItemStack().getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Player player = event.getPlayer();
        String structureId = BlockStructureTag.get(event.getTo().getBlock().getRelative(0, -1, 0));
        if (structureId == null) return;

        String finalStructureId = structureId;
        questRegistry.getAll().stream()
                .filter(q -> q.type() == QuestType.EXPLORE
                        && finalStructureId.equalsIgnoreCase(q.target()))
                .forEach(q -> advanceProgress(player, q.id(), 1));
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void savePlayer(Player player, File dir) {
        YamlConfiguration cfg = new YamlConfiguration();
        List<String> active = new ArrayList<>();
        for (QuestDefinition def : questRegistry.getAll()) {
            if (isActive(player, def.id())) {
                active.add(def.id() + ":" + getProgress(player, def.id()));
            }
        }
        cfg.set("active", active);
        File f = new File(dir, player.getUniqueId() + ".yml");
        try { cfg.save(f); } catch (IOException e) { /* best-effort */ }
    }

    // -------------------------------------------------------------------------
    // Keys
    // -------------------------------------------------------------------------

    private NamespacedKey activeKey(String questId) {
        return new NamespacedKey("vestigium", "vq_active_" + questId);
    }

    private NamespacedKey completeKey(String questId) {
        return new NamespacedKey("vestigium", "vq_complete_" + questId);
    }
}
