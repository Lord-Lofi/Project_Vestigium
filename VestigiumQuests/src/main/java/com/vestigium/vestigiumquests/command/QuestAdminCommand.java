package com.vestigium.vestigiumquests.command;

import com.vestigium.vestigiumquests.VestigiumQuests;
import com.vestigium.vestigiumquests.registry.QuestDefinition;
import com.vestigium.vestigiumquests.tracker.QuestTracker;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.stream.Collectors;

public class QuestAdminCommand implements CommandExecutor {

    private final VestigiumQuests plugin;

    public QuestAdminCommand(VestigiumQuests plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vestigium.quest.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§7Usage: /vquest <assign|list|complete> <player> [questId]");
            return true;
        }

        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer '" + args[1] + "' not found.");
            return true;
        }

        QuestTracker tracker = plugin.getQuestTracker();

        switch (args[0].toLowerCase()) {
            case "assign" -> {
                if (args.length < 3) { sender.sendMessage("§7Usage: /vquest assign <player> <questId>"); return true; }
                boolean ok = tracker.assignQuest(target, args[2]);
                sender.sendMessage(ok ? "§aAssigned §e" + args[2] + "§a to §e" + target.getName() + "§a."
                        : "§cCould not assign — quest unknown, already active, or gates not met.");
            }
            case "list" -> {
                String active = plugin.getQuestRegistry().getAll().stream()
                        .filter(q -> tracker.isActive(target, q.id()))
                        .map(q -> "§e" + q.id() + "§7 (" + tracker.getProgress(target, q.id()) + "/" + q.count() + ")")
                        .collect(Collectors.joining(", "));
                String done = plugin.getQuestRegistry().getAll().stream()
                        .filter(q -> tracker.isComplete(target, q.id()))
                        .map(QuestDefinition::id)
                        .collect(Collectors.joining(", "));
                sender.sendMessage("§6[Quests] §f" + target.getName());
                sender.sendMessage("§7Active: " + (active.isEmpty() ? "none" : active));
                sender.sendMessage("§7Complete: " + (done.isEmpty() ? "none" : "§a" + done));
            }
            case "complete" -> {
                if (args.length < 3) { sender.sendMessage("§7Usage: /vquest complete <player> <questId>"); return true; }
                boolean ok = tracker.forceComplete(target, args[2]);
                sender.sendMessage(ok ? "§aForce-completed §e" + args[2] + "§a for §e" + target.getName() + "§a."
                        : "§cUnknown quest id.");
            }
            default -> sender.sendMessage("§7Usage: /vquest <assign|list|complete> <player> [questId]");
        }
        return true;
    }
}
