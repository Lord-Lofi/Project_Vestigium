package com.vestigium.vestigiumplayer.title;

public record TitleDefinition(
        String key,
        String display,
        int reqStructures,
        int reqCataclysms,
        int reqBossKills,
        int reqLoreFrags
) {}
