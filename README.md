# Cobblemon-Pokopia-Habitats

Pokemon Pokopia-style habitat spawn mechanics for [Cobblemon](https://cobblemon.com/).

Build a Habitat Scanner and search for habitats for pokemon to live in! Each habitat has its required blocks present to form!

You can craft a Habitat Scanner by using a Sapling, a Pokedex and a Wide Lens

- Up to 3 pokemon can spawn in a detected habitat.
- Habitat boundaries can be seen by holding the Habitat Scanner.
- Habitats can be destroyed by hitting a solid block in the habitat with the scanner.
- Right click a solid block in the habitat with the Habitat Scanner to open the habitat menu.
- You can lock habitat pokemon slots to limit the spawns.
- You can assign your own pokemon to the habitats.
- If multiple habitats meet the requirements, you may cycle through them by clicking the habitat button.

Additional habitats can be added via datapack and jar files, so any pokemon expansion mod may add their own habitat compatibility.
Not only that, but current habitats can be edited without overriding them to add more pokemon or required items to them. Referr to the json guide.

## Dependencies

Fabric dependencies:

| Dependency                | Version          |
| ------------------------- | ---------------- |
| Minecraft                 | `1.21.1`         |
| Fabric Loader             | `≥ 0.16.14`      |
| Fabric API                | `0.115.6+1.21.1` |
| Fabric Language Kotlin    | `≥ 1.12.0`       |
| Architectury API (Fabric) | `13.0.11`        |
| Cobblemon (Fabric)        | `1.6.1+1.21.1`   |

Neoforge dependencies:

| Dependency                  | Version                         |
| --------------------------- | ------------------------------- |
| Minecraft                   | `1.21.1`                        |
| NeoForge                    | `21.1.182` (accepts `≥ 21.1.0`) |
| KotlinForForge              | `5.10.0`                        |
| Architectury API (NeoForge) | `13.0.11`                       |
| Cobblemon (NeoForge)        | `1.6.1+1.21.1`                  |

## Spawning rules

- Each habitat holds up to **3** Pokemon (`maxPokemonPerHabitat`).
- Every `habitatSpawnIntervalSeconds` (default 20s) each loaded habitat with a free slot rolls a spawn:
  an entry is picked by **rarity weight**, then its **chance** (0-1) is rolled.
- Spawn pacing is controlled by `habitatSpawnIntervalSeconds` in `config/pokopia-common.toml`
  (seconds between spawn rolls per habitat) plus each spawn entry's `rarity`/`chance` in its JSON.
- **A species already living in the habitat cannot spawn again** - remaining slots skip entries
  already present, giving rarer Pokemon their chance.
- Pokemon only spawn at spots with breathing room (passable block + passable block above);
  if a habitat has no such spot, nothing spawns - no suffocation.
- **Wild habitat Pokemon may despawn** like any wild Pokemon when players are far away (keeps
  world data lean); the slot frees up and something new spawns when you return. Pokemon you
  assigned from your PC never despawn. Wanderers of both kinds get teleported home.
- When an occupant dies, its slot frees up immediately.
- If a habitat's blocks are broken so it no longer matches **any** definition, the habitat is
  removed after a short grace period (2 spawn cycles). Wild occupants are NOT despawned - they
  simply roam free; assigned Pokemon return safely to their owner's PC.
- **Shinies**: yes - habitat spawns roll Cobblemon's normal shiny chance (`shinyRate` in
  Cobblemon's config). A datapack can also force it per entry: `"pokemon": "pikachu shiny=yes"`.
- **Modded blocks**: any registered block/item id or tag works in habitat JSON, including ones
  from other mods (unknown ids just log a warning and are skipped). **modded pokemon** also work.
- Habitats **can never overlap**; blocks already claimed by one habitat can't be claimed by another.
- With `disableNaturalCobblemonSpawns = true`, Cobblemon's own natural spawning is
  cancelled entirely - Pokemon only appear in habitats. Pokeballs, commands, fishing etc. still work.
- With `enableAutomaticHabitatDetection = true`, You can make it so habitats generate automatically around the world, however, its mostly experimental and might
  mess with your world tps. They are made to despawn if not interacted with.


  **Assigned Pokemon use Cobblemon's pasture tethering**: the Pokemon never actually leaves your PC -
the world entity is a tethered projection and your PC shows it as roaming (same icon as a pasture
block). That means an assigned Pokemon can never be lost or duplicated: destroying the habitat,
chunk unloads, crashes - in every case it is safe in (or safely returns to) your PC.

## Building

Open CMD in root folder and run: 

```bash
./gradlew build
```

jars generate in fabric/build/libs and neoforge/build/libs, use the ones with "<version>.jar" without a -suffix at the end. 


