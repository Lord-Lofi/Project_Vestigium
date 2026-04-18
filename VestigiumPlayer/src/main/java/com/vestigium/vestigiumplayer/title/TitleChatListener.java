package com.vestigium.vestigiumplayer.title;

import com.vestigium.vestigiumplayer.VestigiumPlayer;
import com.vestigium.vestigiumplayer.data.PlayerDataStore;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Prepends the player's active title to chat messages.
 * Runs at LOWEST priority so other chat plugins can process the result.
 */
public class TitleChatListener implements Listener {

    private final VestigiumPlayer plugin;
    private final PlayerDataStore dataStore;

    public TitleChatListener(VestigiumPlayer plugin, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.dataStore = dataStore;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        String titleKey = dataStore.getActiveTitle(event.getPlayer());
        if (titleKey.isBlank()) return;

        TitleManager.getAllTitles().stream()
                .filter(t -> t.key().equals(titleKey))
                .findFirst()
                .ifPresent(title -> {
                    String format = event.getFormat();
                    event.setFormat(title.display() + " " + format);
                });
    }
}
