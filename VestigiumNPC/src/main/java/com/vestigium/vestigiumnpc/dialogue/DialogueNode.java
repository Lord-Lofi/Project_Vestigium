package com.vestigium.vestigiumnpc.dialogue;

import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

@FunctionalInterface
public interface DialogueNode {
    DialogueStep apply(Player player, Villager villager);
}
