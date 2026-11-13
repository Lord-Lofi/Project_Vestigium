package com.vestigium.vestigiumnpc.dialogue;

import com.vestigium.vestigiumnpc.VestigiumNPC;
import com.vestigium.vestigiumnpc.villager.VillagerMemoryManager;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Manages active dialogue sessions between players and Villagers.
 *
 * A session advances one node per right-click via startOrAdvance().
 * Sessions expire after 30 s of inactivity. Standard (non-NPC-type)
 * Villagers receive a tier-aware greeting tree from VillagerMemoryManager.
 */
public class DialogueManager implements Listener {

    private static final NamespacedKey NPC_TYPE_KEY = new NamespacedKey("vestigium", "npc_type");
    private static final long SESSION_TTL = 30_000L;

    private final VestigiumNPC plugin;
    private final Map<UUID, DialogueSession> sessions = new HashMap<>();
    private BukkitRunnable cleanupTask;

    private record DialogueSession(UUID villagerUUID, DialogueTree tree, String currentKey,
                                   long lastActivity) {
        DialogueSession advance(String nextKey) {
            return new DialogueSession(villagerUUID, tree, nextKey, System.currentTimeMillis());
        }
    }

    public DialogueManager(VestigiumNPC plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        cleanupTask = new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                sessions.entrySet().removeIf(e -> now - e.getValue().lastActivity() > SESSION_TTL);
            }
        };
        cleanupTask.runTaskTimer(plugin, 200L, 200L);
        plugin.getLogger().info("[DialogueManager] Initialized.");
    }

    public void shutdown() {
        if (cleanupTask != null) cleanupTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Starts or advances a dialogue session. If no session exists (or the
     * active session is with a different villager), starts from tree root.
     */
    public void startOrAdvance(Player player, Villager villager, DialogueTree tree) {
        UUID pid = player.getUniqueId();
        UUID vid = villager.getUniqueId();
        DialogueSession session = sessions.get(pid);

        String key = (session == null || !session.villagerUUID().equals(vid))
                ? tree.rootKey()
                : session.currentKey();

        DialogueNode node = tree.node(key);
        if (node == null) {
            sessions.remove(pid);
            return;
        }

        DialogueStep step = node.apply(player, villager);
        step.lines().forEach(player::sendMessage);

        if (step.isTerminal()) {
            sessions.remove(pid);
        } else {
            sessions.put(pid, new DialogueSession(vid, tree, step.nextKey(),
                    System.currentTimeMillis()));
        }
    }

    public void clearSession(Player player) {
        sessions.remove(player.getUniqueId());
    }

    public boolean hasActiveSession(Player player, Villager villager) {
        DialogueSession s = sessions.get(player.getUniqueId());
        return s != null && s.villagerUUID().equals(villager.getUniqueId());
    }

    // -------------------------------------------------------------------------
    // Standard villager greeting — fires for non-NPC-type Villagers
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onVillagerInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Villager villager)) return;
        if (villager.getPersistentDataContainer().has(NPC_TYPE_KEY, PersistentDataType.STRING)) return;

        Player player = event.getPlayer();
        VillagerMemoryManager vmm = plugin.getVillagerMemoryManager();
        int tier = vmm.getDialogueTier(villager, player.getUniqueId());
        startOrAdvance(player, villager, buildTierGreetingTree(tier));
    }

    // -------------------------------------------------------------------------
    // Tier-aware greeting trees
    // -------------------------------------------------------------------------

    public static DialogueTree buildTierGreetingTree(int tier) {
        return switch (tier) {
            case 1 -> DialogueTree.builder("greet")
                    .message("greet", "offer",
                            "§7\"Ah, you again. I remember.\"",
                            "§8(Acquaintance — trades 10% cheaper)")
                    .message("offer", null, "§7\"What can I do for you today?\"")
                    .build();
            case 2 -> DialogueTree.builder("greet")
                    .message("greet", "offer",
                            "§7\"Good to see you again, friend.\"",
                            "§8(Trusted — trades 20% cheaper)")
                    .message("offer", null, "§7\"I keep my best stock for people like you.\"")
                    .build();
            case 3 -> DialogueTree.builder("greet")
                    .message("greet", "offer",
                            "§7\"My door is always open to you, old ally.\"",
                            "§8(Ally — trades 30% cheaper)")
                    .message("offer", null, "§7\"Name your need. I'll do what I can.\"")
                    .build();
            default -> DialogueTree.builder("greet")
                    .message("greet", null, "§7\"...\"")
                    .build();
        };
    }
}
