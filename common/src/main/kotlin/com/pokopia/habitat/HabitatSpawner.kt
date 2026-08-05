package com.pokopia.habitat

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.pokopia.Pokopia
import com.pokopia.PokopiaConfig
import com.pokopia.registry.PokopiaItems
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.InteractionEvent
import dev.architectury.event.events.common.TickEvent
import java.util.UUID

/**
 * Spawns Pokemon into habitats and keeps habitat state healthy:
 *  - rolls new spawns for habitats with free slots (rarity-weighted + chance gate)
 *  - a species already living in the habitat cannot spawn again, so free slots
 *    lean towards the rarer entries
 *  - only spawns at positions with breathing room, so nothing suffocates
 *  - wild occupants are ordinary wild Pokemon: they MAY despawn naturally when
 *    players are far away (their slot frees up and something respawns later);
 *    owned (PC-tethered) occupants never despawn
 *  - spawning pauses while a player has the habitat's menu open
 *  - frees a slot the moment its Pokemon dies, and detects captures
 *  - teleports wandering occupants back to their habitat
 *  - removes habitats whose required blocks were broken
 *  - lets the Habitat Scanner destroy a habitat by hitting it (left click)
 */
object HabitatSpawner {
    const val HABITAT_TAG_PREFIX = "pokopia_habitat:"

    /** Cycles a WILD occupant's entity must be missing before its slot frees up. */
    private const val MISS_GRACE_CYCLES = 3

    /** Spawner cycles a habitat may fail to match any definition before it is removed. */
    private const val INVALID_GRACE_CYCLES = 2

    /** How far an occupant may stray from its habitat before being brought home. */
    private const val TETHER_DISTANCE = 16.0

    private var tickCounter = 0

    fun register() {
        TickEvent.SERVER_POST.register { server ->
            tickCounter++
            val interval = PokopiaConfig.spawnIntervalSeconds * 20
            if (tickCounter % interval.coerceAtLeast(20) != 0) return@register
            for (level in server.allLevels) {
                tickLevel(level)
            }
        }
        // Free the slot immediately when a habitat Pokemon dies.
        EntityEvent.LIVING_DEATH.register { entity, _ ->
            val level = entity.level() as? ServerLevel ?: return@register EventResult.pass()
            val tag = entity.tags.firstOrNull { it.startsWith(HABITAT_TAG_PREFIX) } ?: return@register EventResult.pass()
            val habitatId = runCatching { UUID.fromString(tag.removePrefix(HABITAT_TAG_PREFIX)) }.getOrNull()
                ?: return@register EventResult.pass()
            val manager = HabitatManager.get(level)
            val habitat = manager.get(habitatId) ?: return@register EventResult.pass()
            for (slot in habitat.slots) {
                if (slot.entityId == entity.uuid) {
                    slot.clear()
                    manager.setDirty()
                }
            }
            EventResult.pass()
        }
        // Hitting a habitat while holding the scanner destroys it.
        InteractionEvent.LEFT_CLICK_BLOCK.register { player, _, pos, _ ->
            val serverPlayer = player as? ServerPlayer ?: return@register EventResult.pass()
            val holdingScanner = serverPlayer.mainHandItem.`is`(PokopiaItems.HABITAT_SCANNER.get()) ||
                serverPlayer.offhandItem.`is`(PokopiaItems.HABITAT_SCANNER.get())
            if (!holdingScanner) return@register EventResult.pass()
            val level = serverPlayer.serverLevel()
            val manager = HabitatManager.get(level)
            val habitat = manager.habitatAt(pos) ?: return@register EventResult.pass()
            val name = habitat.displayName
            manager.destroy(level, habitat)
            serverPlayer.displayClientMessage(
                Component.translatable("pokopia.message.habitat_destroyed", name).withStyle(ChatFormatting.YELLOW),
                true
            )
            // Cancel the vanilla left-click so the block isn't also mined.
            EventResult.interruptFalse()
        }
    }

    private fun tickLevel(level: ServerLevel) {
        val manager = HabitatManager.get(level)
        if (manager.all().isEmpty()) return
        var dirty = false
        for (habitat in manager.all().toList()) {
            // Only touch habitats whose chunks are loaded.
            if (!level.isLoaded(habitat.box.center)) continue
            if (maintainOccupants(level, habitat)) dirty = true

            // Habitats whose blocks were broken disappear (after a short grace,
            // so briefly breaking one block mid-renovation doesn't nuke it).
            val scan = HabitatDetector.scan(level, habitat.box)
            val definition = habitat.definition
            val valid = (definition != null && scan.satisfies(definition)) ||
                HabitatDetector.matchingDefinitions(scan).isNotEmpty()
            if (!valid) {
                habitat.invalidCycles++
                if (habitat.invalidCycles >= INVALID_GRACE_CYCLES) {
                    manager.destroy(level, habitat)
                }
                continue
            }
            habitat.invalidCycles = 0

            if (definition != null && scan.satisfies(definition) && trySpawn(level, habitat, definition)) {
                dirty = true
            }
        }
        if (dirty) manager.setDirty()
    }

    /**
     * Checks each occupied slot. Frees wild slots whose Pokemon despawned,
     * was captured or killed, and tethers escapees back home. Owned slots are
     * never freed just because the entity is missing - the Pokemon is safe in
     * the owner's PC and its entity may simply sit in an unloaded chunk.
     * Returns true if anything changed.
     */
    private fun maintainOccupants(level: ServerLevel, habitat: HabitatInstance): Boolean {
        var changed = false
        for (slot in habitat.slots) {
            if (slot.isEmpty) continue
            val entity = slot.entityId?.let { level.getEntity(it) }
            if (entity == null || entity.isRemoved || !entity.isAlive) {
                if (!slot.isOwned) {
                    slot.missCount++
                    if (slot.missCount >= MISS_GRACE_CYCLES) {
                        slot.clear()
                        changed = true
                    }
                }
                continue
            }
            slot.missCount = 0
            if (entity is PokemonEntity) {
                if (slot.isOwned) {
                    // Withdrawn from the habitat by other means (tether cleared)?
                    if (entity.pokemon.tetheringId == null) {
                        slot.clear()
                        changed = true
                        continue
                    }
                } else if (entity.pokemon.getOwnerUUID() != null) {
                    // A wild occupant that got caught no longer belongs to the habitat.
                    slot.clear()
                    changed = true
                    continue
                }
            }
            val center = habitat.box.center
            val distSq = entity.distanceToSqr(center.x + 0.5, center.y + 0.5, center.z + 0.5)
            if (distSq > TETHER_DISTANCE * TETHER_DISTANCE) {
                val home = findSpawnPos(level, habitat.box, level.random) ?: habitat.box.center
                entity.teleportTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5)
            }
        }
        return changed
    }

    /** Attempts one spawn roll for the habitat. Returns true if a Pokemon spawned. */
    private fun trySpawn(level: ServerLevel, habitat: HabitatInstance, definition: HabitatDefinition): Boolean {
        if (definition.spawns.isEmpty()) return false
        // Paused while someone is managing the habitat in the menu.
        if (level.gameTime < habitat.suppressSpawnsUntil) return false
        val slot = habitat.firstFreeSlot(PokopiaConfig.maxPokemonPerHabitat)
        if (slot < 0) return false

        // A species already living here cannot spawn again - remaining slots
        // give the rarer entries their chance. Entries also gated by biome:
        // if an entry lists biomes, the habitat must be in one of them.
        val present = habitat.occupiedSpecies()
        val biome = level.getBiome(habitat.box.center)
        val eligible = definition.spawns.filter { entry ->
            if (!BiomeMatcher.matchesAny(biome, entry.biomes)) return@filter false
            if (!DimensionMatcher.matchesAny(level, entry.dimensions)) return@filter false
            val species = PokemonProperties.parse(entry.properties).species
                ?.lowercase()?.substringAfter(':') ?: return@filter true
            species !in present
        }
        if (eligible.isEmpty()) return false

        val random = level.random
        val entry = pickWeighted(eligible, random) ?: return false
        if (random.nextDouble() > entry.chance) return false

        // Never spawn without breathing room - no suffocating Pokemon.
        val pos = findSpawnPos(level, habitat.box, random) ?: return false

        val properties = PokemonProperties.parse(entry.properties)
        if (properties.species == null) {
            Pokopia.LOGGER.warn(
                "Habitat {} spawn entry '{}' has no valid species (is the addon/datapack loaded?)",
                definition.id, entry.properties
            )
            return false
        }

        // Spawn level: an explicit level in the properties string wins; then the
        // entry's "level" (value or [min,max] range); then the habitat's
        // "default_level"; otherwise Cobblemon's own defaults apply.
        if (properties.level == null) {
            val min = entry.minLevel ?: definition.defaultMinLevel
            if (min != null) {
                val max = (entry.maxLevel ?: definition.defaultMaxLevel ?: min).coerceAtLeast(min)
                properties.level = if (max > min) min + random.nextInt(max - min + 1) else min
            }
        }

        val entity = properties.createEntity(level)
        entity.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, random.nextFloat() * 360f, 0f)
        // NOTE: wild habitat Pokemon are intentionally NOT persistence-required.
        // They may despawn like any wild Pokemon when players are far away,
        // freeing the slot for a fresh spawn later (performance + data hygiene).
        entity.addTag(HABITAT_TAG_PREFIX + habitat.uuid)
        if (!level.addFreshEntity(entity)) return false

        habitat.slots[slot].apply {
            entityId = entity.uuid
            pokemonId = entity.pokemon.uuid
            ownerId = null
            speciesId = entity.pokemon.species.resourceIdentifier.toString()
            missCount = 0
        }
        return true
    }

    private fun pickWeighted(entries: List<SpawnEntry>, random: RandomSource): SpawnEntry? {
        val total = entries.sumOf { it.effectiveWeight }
        if (total <= 0) return null
        var roll = random.nextDouble() * total
        for (entry in entries) {
            roll -= entry.effectiveWeight
            if (roll <= 0) return entry
        }
        return entries.last()
    }

    /**
     * Picks a position inside the box where a Pokemon can safely exist:
     * passable (air or liquid) at the position AND directly above it, with
     * support (or liquid) below. Returns null when the habitat has no such
     * spot - in that case nothing spawns rather than something suffocating.
     */
    fun findSpawnPos(level: ServerLevel, box: HabitatBox, random: RandomSource): BlockPos? {
        repeat(16) {
            val x = box.min.x + random.nextInt(box.max.x - box.min.x + 1)
            val z = box.min.z + random.nextInt(box.max.z - box.min.z + 1)
            for (y in box.max.y downTo box.min.y) {
                val pos = BlockPos(x, y, z)
                if (!HabitatDetector.isPassable(level.getBlockState(pos))) continue
                if (!HabitatDetector.isPassable(level.getBlockState(pos.above()))) continue
                val below = level.getBlockState(pos.below())
                if (!below.isAir) {
                    return pos
                }
            }
        }
        return null
    }
}
