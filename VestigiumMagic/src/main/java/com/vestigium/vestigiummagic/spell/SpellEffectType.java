package com.vestigium.vestigiummagic.spell;

public enum SpellEffectType {
    BOLT,    // projectile along look direction, damages first entity hit
    BURST,   // AOE damage around caster
    WARD,    // temporary damage reduction aura
    PULL,    // pulls nearest entity toward caster
    BLINK,   // teleports caster forward along look direction
    REVEAL   // highlights nearby hidden structures/entities with particles
}
