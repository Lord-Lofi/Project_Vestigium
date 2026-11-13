# Project Vestigium

A modular Paper 1.21.11 plugin suite for a custom survival server. Seventeen plugins working together through a shared library to build a layered world of lore, exploration, economy, and atmospheric systems.

> **Status:** Active development — v0.1 feature-complete. Resource pack assets and in-world structure schematics pending.

---

## Modules

| Plugin | Purpose |
|--------|---------|
| **VestigiumLib** | Core library: OmenAPI, SeasonAPI, ReputationAPI, LoreRegistry, EventBus, ParticleManager, currency utilities. All other plugins depend on this. |
| **VestigiumAtmosphere** | Per-player atmospheric events: ancient city escalation, overworld night events (Watching Dark, Pilgrim's Road, Midnight Toll, Memory Bleed), weather events (Acid Rain, Sculk Storm, Blood Moon), sky events (meteors, aurora, eclipse, starfall vigil), ambient particles. |
| **VestigiumCaves** | Cave-specific content and underground atmosphere. |
| **VestigiumCombat** | Combo system, custom status effects, combat state tracking. |
| **VestigiumEconomy** | Vestige Shard currency, faction markets, runic crafting, ritual crafting, alchemy, tempered gear, component weapons, salvage system, relics, shrine blessings, titan bones, divine artifacts, forgery system, black market, Artificer NPC. |
| **VestigiumEnd** | End dimension content: 7 biome zones with distinct Enderman behaviour and atmospherics, 8 End structure types including The Hollow Throne and The Regent NPC, End lore delivery (dragon dialogue, convergence point, Enderman witness chain). |
| **VestigiumLore** | Lore delivery: resonant/End/Nether terminals, three-civilization cipher system, campfire stories, Messages in a Bottle, Server Memory Tome. |
| **VestigiumMagic** | Spell casting, mana management, artifact items. |
| **VestigiumMobs** | Custom mob variants (36+ types), 5 Named Wardens, 2 world bosses (Leviathan, Sunken God), shadow fauna, mythic beasts, territorial wildlife, full minion system (6 types), passive Worm and Echo Beast systems. |
| **VestigiumNether** | Antecedent Nether lore, Soul Road navigation with compass directions and milestone fragments, Wither Skeleton behavioural response, Bastion Politics and Piglin Lords, piglin faction quests. |
| **VestigiumNPC** | Villager memory (trade tiers, defense/abandonment history, price modifiers), traveling NPCs (10 behavior types), dialogue tree system, doppelganger system, bounty board system, spy network, village politics. |
| **VestigiumOcean** | Drowned Civilization (4 tiers of underwater sites with NPCs, passives, and recognition system), ocean mob management. |
| **VestigiumPlayer** | Player progression: stat tracking, 10 unlockable titles, 24-achievement tree, notoriety system, 7 utility items, player epitaphs, echo of the dead, legacy markers, heraldry system, tool memory. |
| **VestigiumQuests** | YAML-driven quest framework: 42 quests across 6 factions, prerequisite gating, season gates, daily repeatables, multi-stage chains. |
| **VestigiumStructures** | Custom structure registry, wandering dungeon (daily drift + seasonal jumps), Antecedent Waystone Network (energy economy, distance-based costs). |
| **VestigiumWorld** | Cataclysmic events, living-world behaviours, ecological memory. |

---

## Architecture

All inter-plugin communication goes through **VestigiumLib's EventBus** — no direct plugin dependencies between feature modules. The dependency graph is flat:

```
VestigiumLib
    └── (all other modules depend on VestigiumLib only)
```

### Key shared APIs (VestigiumLib)

- **OmenAPI** — server-wide omen score driving escalating world events
- **SeasonAPI** — four-season system (Spring/Summer/Autumn/Winter) affecting spawns, crops, and atmospherics
- **ReputationAPI** — per-player per-faction reputation scores
- **LoreRegistry** — loads lore YAML files, tracks player fragment collection via PDC, fires `LoreFragmentGrantedEvent`
- **ParticleManager** — priority-queued particle spawning with TPS gating and distance culling
- **EventBus** — typed publish/subscribe bus for cross-module events without compile-time coupling

---

## Permissions

All permissions are declared in each module's `plugin.yml` and are fully compatible with LuckPerms.

**Two umbrella nodes (defined in VestigiumLib):**

| Node | Default | Description |
|------|---------|-------------|
| `vestigium.admin` | op | Inherits all administrative permissions across every module |
| `vestigium.use` | true | Inherits all player-facing permissions across every module |

Grant `vestigium.admin` to staff and `vestigium.use` is already granted to everyone by default. Individual nodes can be granted or denied per-player via LuckPerms as normal.

---

## Schematic Structures

Structure definitions live in `plugins/VestigiumStructures/structures/*.yml`. Physical schematics go in `plugins/VestigiumStructures/schematics/` as WorldEdit `.schem` files (WE 7.x NBT format, exported with `//schem save`). Schematic *loading* code requires WorldEdit or FAWE as a soft dependency — this has not yet been implemented; the registry and definition framework are ready but paste logic is pending.

---

## Data Persistence

- **Player data:** Bukkit PersistentDataContainer (PDC) on the `Player` entity — survives restarts, no database required
- **Site/NPC registrations:** Per-plugin YAML files in `plugins/<PluginName>/`
- **Lore content:** YAML files in `plugins/VestigiumLib/lore/` — bundled defaults extracted on first run

---

## Resource Pack

A resource pack is required for full visual fidelity (custom item textures via CustomModelData, OptiFine CEM mob skins). The code hooks are complete; the asset files are pending artist work. See the [Resource Pack section in the task tracker](https://github.com/Lord-Lofi/Project_Vestigium) for the full list of needed PNGs and their paths.

---

## Building

Each module is a standard Maven project. Build order must respect the dependency graph:

```
mvn install   # in VestigiumLib first
mvn package   # in each other module
```

Built JARs go to `<module>/target/<plugin>-<version>.jar`. After each build, copy all JARs to the server's `plugins/` directory. Tested against Paper 1.21.11.

---

## Wiki

Full system documentation: [Project Vestigium Wiki](https://github.com/Lord-Lofi/Project_Vestigium/wiki)
