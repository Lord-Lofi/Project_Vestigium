package com.vestigium.vestigiumnpc.hostile;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;

/**
 * Optional visual layer for the Doppelganger, split into two tiers:
 *
 *   Tier 1 (always): Player skull helmet — the target player's face is rendered
 *                    on the zombie's head with zero chance of drop.
 *
 *   Tier 2 (ProtocolLib present): Tab-list injection — a fake PLAYER_INFO_UPDATE
 *                    ADD entry is sent to nearby players so the doppelganger
 *                    appears in the tab list as that player. Cleaned up on despawn.
 */
public final class DoppelgangerVisuals {

    private DoppelgangerVisuals() {}

    public static boolean isProtocolLibAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("ProtocolLib");
    }

    // -------------------------------------------------------------------------
    // Tier 1 — skull helmet (no ProtocolLib required)
    // -------------------------------------------------------------------------

    public static void applySkullHelmet(Zombie zombie, OfflinePlayer target) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        if (meta == null) return;
        meta.setOwningPlayer(target);
        skull.setItemMeta(meta);
        zombie.getEquipment().setHelmet(skull);
        zombie.getEquipment().setHelmetDropChance(0f);
    }

    // -------------------------------------------------------------------------
    // Tier 2 — tab-list injection (ProtocolLib)
    // -------------------------------------------------------------------------

    public static void injectTabEntry(Plugin plugin, Zombie zombie, OfflinePlayer target) {
        if (!isProtocolLibAvailable()) return;
        try {
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();

            WrappedGameProfile fakeProfile = new WrappedGameProfile(
                    target.getUniqueId(),
                    target.getName() != null ? target.getName() : "Unknown");

            PacketContainer addPacket = pm.createPacket(
                    PacketType.Play.Server.PLAYER_INFO);
            addPacket.getPlayerInfoAction()
                    .write(0, EnumWrappers.PlayerInfoAction.ADD_PLAYER);
            addPacket.getPlayerInfoDataLists()
                    .write(0, List.of(new PlayerInfoData(
                            fakeProfile,
                            0,
                            EnumWrappers.NativeGameMode.SURVIVAL,
                            WrappedChatComponent.fromText(
                                    fakeProfile.getName()))));

            for (Player p : zombie.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(zombie.getLocation()) < 16384) {
                    try { pm.sendServerPacket(p, addPacket); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[DoppelgangerVisuals] Tab inject failed: " + e.getMessage());
        }
    }

    public static void removeTabEntry(Plugin plugin, UUID targetUUID, org.bukkit.World world) {
        if (!isProtocolLibAvailable()) return;
        try {
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();
            PacketContainer removePacket = pm.createPacket(
                    PacketType.Play.Server.PLAYER_INFO);
            removePacket.getPlayerInfoAction()
                    .write(0, EnumWrappers.PlayerInfoAction.REMOVE_PLAYER);
            removePacket.getPlayerInfoDataLists()
                    .write(0, List.of(new PlayerInfoData(
                            new WrappedGameProfile(targetUUID, ""),
                            0,
                            EnumWrappers.NativeGameMode.SURVIVAL,
                            null)));

            for (Player p : world.getPlayers()) {
                try { pm.sendServerPacket(p, removePacket); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[DoppelgangerVisuals] Tab remove failed: " + e.getMessage());
        }
    }
}
