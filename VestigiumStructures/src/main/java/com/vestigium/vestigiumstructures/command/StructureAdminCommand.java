package com.vestigium.vestigiumstructures.command;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.vestigium.lib.util.BlockStructureTag;
import com.vestigium.vestigiumstructures.VestigiumStructures;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Handles the /vstructure command for admin-side structure management.
 *
 * /vstructure paste <id>   — loads the named schematic and pastes it at the
 *                            player's feet, then tags the anchor block.
 */
public class StructureAdminCommand implements CommandExecutor, TabCompleter {

    private final VestigiumStructures plugin;

    public StructureAdminCommand(VestigiumStructures plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command must be run by a player.");
            return true;
        }

        if (!player.hasPermission("vestigium.structures.admin")) {
            player.sendMessage("§cYou do not have permission.");
            return true;
        }

        if (args.length < 2 || !args[0].equalsIgnoreCase("paste")) {
            player.sendMessage("§eUsage: /vstructure paste <id>");
            return true;
        }

        String id = args[1];

        if (plugin.getSchematicManager() == null) {
            player.sendMessage("§cWorldEdit is not installed — schematic loading is disabled.");
            return true;
        }

        if (!plugin.getSchematicManager().exists(id)) {
            player.sendMessage("§cNo schematic file found for id: §e" + id
                    + "§c. Place it in §eplugins/VestigiumStructures/schematics/§c.");
            return true;
        }

        Optional<Clipboard> clip = plugin.getSchematicManager().load(id);
        if (clip.isEmpty()) {
            player.sendMessage("§cFailed to load schematic for: §e" + id);
            return true;
        }

        org.bukkit.Location loc = player.getLocation();
        plugin.getStructurePlacer().paste(clip.get(), loc, false);
        BlockStructureTag.set(loc.getBlock(), id);

        player.sendMessage("§aPasted §e" + id + " §aat your location. Anchor block tagged.");

        if (id.startsWith("resonant_archive")) {
            plugin.getResonantArchiveManager().registerAnchor(loc.getBlock(), id);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd,
                                      String label, String[] args) {
        if (args.length == 1) {
            return List.of("paste").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("paste")) {
            return plugin.getStructureRegistry().getAll().stream()
                    .map(StructureDefinition -> StructureDefinition.id())
                    .filter(id -> id.startsWith(args[1].toLowerCase()))
                    .sorted()
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
