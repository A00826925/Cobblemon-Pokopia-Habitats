package com.pokopia.habitat

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.pokopia.Pokopia
import com.pokopia.network.PokopiaNetwork
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/**
 * One occupant slot of a habitat.
 *
 * Wild occupants (ownerId == null) are ordinary wild Pokemon that spawned in;
 * they are allowed to despawn naturally when players are far away.
 *
 * Owned occupants (ownerId != null) are player Pokemon assigned from the PC
 * using Cobblemon's pasture tethering: the Pokemon NEVER leaves the PC
 * storage - the world entity is a tethered projection and the PC shows the
 * "roaming" icon. They can never be lost or duplicated.
 */
class OccupantSlot {
    var entityId: UUID? = null
    var pokemonId: UUID? = null
    var ownerId: UUID? = null
    var speciesId: String? = null
    var missCount: Int = 0

    /** Locked slots hold no Pokemon: nothing spawns there and none can be assigned. */
    var locked: Boolean = false

    val isEmpty: Boolean get() = entityId == null
    val isOwned: Boolean get() = ownerId != null

    /** Locking preserves the flag; only the occupant data is cleared. */
    fun clear() {
        entityId = null
        pokemonId = null
        ownerId = null
        speciesId = null
        missCount = 0
    }

    fun save(): CompoundTag {
        val tag = CompoundTag()
        entityId?.let { tag.putUUID("entity", it) }
        pokemonId?.let { tag.putUUID("pokemon", it) }
        ownerId?.let { tag.putUUID("owner", it) }
        speciesId?.let { tag.putString("species", it) }
        if (locked) tag.putBoolean("locked", true)
        return tag
    }

    fun load(tag: CompoundTag) {
        if (tag.hasUUID("entity")) entityId = tag.getUUID("entity")
        // legacy key from older saves
        if (entityId == null && tag.hasUUID("pokemon") && !tag.hasUUID("entity")) {
            entityId = tag.getUUID("pokemon")
        }
        if (tag.hasUUID("pokemon")) pokemonId = tag.getUUID("pokemon")
        if (tag.hasUUID("owner")) ownerId = tag.getUUID("owner")
        if (tag.contains("species")) speciesId = tag.getString("species")
        if (tag.contains("locked")) locked = tag.getBoolean("locked")
    }
}

/**
 * A registered habitat in the world: its bounding box, which habitat
 * definition it is currently assigned to, and the Pokemon living in it.
 */
class HabitatInstance(
    val uuid: UUID,
    var definitionId: ResourceLocation,
    val box: HabitatBox,
    /**
     * True for habitats a player made with the Habitat Scanner. These are
     * always saved. Naturally detected habitats are false and are only saved
     * while they hold a player-owned Pokemon (see [shouldPersist]) - otherwise
     * they are transient and simply regenerate when players revisit the area,
     * which keeps the saved data bounded no matter how far anyone explores.
     */
    val playerCreated: Boolean = false
) {
    val slots: Array<OccupantSlot> = Array(MAX_SLOTS) { OccupantSlot() }

    /** Consecutive spawner cycles the habitat failed to match any definition (not persisted). */
    var invalidCycles: Int = 0

    /** Spawns are paused until this game time while a player has the menu open. */
    var suppressSpawnsUntil: Long = 0L

    val definition: HabitatDefinition? get() = PokopiaHabitats.get(definitionId)

    val displayName: String get() = definition?.displayName ?: definitionId.toString()

    fun occupiedSlots(): Int = slots.count { !it.isEmpty }

    fun firstFreeSlot(maxAllowed: Int): Int {
        for (i in 0 until minOf(maxAllowed, MAX_SLOTS)) {
            if (slots[i].isEmpty && !slots[i].locked) return i
        }
        return -1
    }

    fun clearSlot(slot: Int) = slots[slot].clear()

    /** True if any slot holds a player-owned (PC-tethered) Pokemon. */
    fun hasOwnedOccupant(): Boolean = slots.any { it.isOwned }

    /**
     * Whether this habitat is worth writing to disk: player-made habitats
     * always, and any habitat currently holding one of a player's Pokemon.
     * Everything else (wild-only natural habitats) is transient.
     */
    fun shouldPersist(): Boolean = playerCreated || hasOwnedOccupant()

    /** Species PATHS of current occupants, for the no-duplicate-species spawn rule. */
    fun occupiedSpecies(): Set<String> =
        slots.mapNotNull { it.speciesId?.substringAfter(':')?.lowercase() }.toSet()

    fun save(): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("uuid", uuid)
        tag.putString("definition", definitionId.toString())
        tag.putBoolean("playerCreated", playerCreated)
        tag.put("min", NbtUtils.writeBlockPos(box.min))
        tag.put("max", NbtUtils.writeBlockPos(box.max))
        val slotsTag = ListTag()
        for (i in 0 until MAX_SLOTS) {
            slotsTag.add(slots[i].save())
        }
        tag.put("slots", slotsTag)
        return tag
    }

    companion object {
        const val MAX_SLOTS = 3

        fun load(tag: CompoundTag): HabitatInstance? {
            val uuid = if (tag.hasUUID("uuid")) tag.getUUID("uuid") else return null
            val definitionId = ResourceLocation.tryParse(tag.getString("definition")) ?: return null
            val min = NbtUtils.readBlockPos(tag, "min").orElse(null) ?: return null
            val max = NbtUtils.readBlockPos(tag, "max").orElse(null) ?: return null
            // Legacy saves (no flag) were all persisted regardless of origin, so
            // treat them as player-created to avoid dropping anything on upgrade.
            val playerCreated = if (tag.contains("playerCreated")) tag.getBoolean("playerCreated") else true
            val instance = HabitatInstance(uuid, definitionId, HabitatBox(min, max), playerCreated)
            // current format
            val slotsTag = tag.getList("slots", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until minOf(slotsTag.size, MAX_SLOTS)) {
                instance.slots[i].load(slotsTag.getCompound(i))
            }
            // legacy format ("occupants" with slot/pokemon keys)
            val legacy = tag.getList("occupants", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until legacy.size) {
                val entry = legacy.getCompound(i)
                val slot = entry.getInt("slot")
                if (slot in 0 until MAX_SLOTS && entry.hasUUID("pokemon") && instance.slots[slot].isEmpty) {
                    instance.slots[slot].entityId = entry.getUUID("pokemon")
                }
            }
            return instance
        }
    }
}

/**
 * Per-dimension store of all registered habitats. Persisted with the level.
 */
class HabitatManager : SavedData() {
    private val habitats = LinkedHashMap<UUID, HabitatInstance>()

    fun all(): Collection<HabitatInstance> = habitats.values

    fun get(uuid: UUID): HabitatInstance? = habitats[uuid]

    fun habitatAt(pos: BlockPos): HabitatInstance? = habitats.values.firstOrNull { it.box.contains(pos) }

    fun overlapsAny(box: HabitatBox): Boolean = habitats.values.any { it.box.intersects(box) }

    /** Habitats whose box overlaps the given chunk footprint AND the [minY,maxY] slice. */
    fun intersectingChunkSlice(chunkPos: ChunkPos, minY: Int, maxY: Int): List<HabitatInstance> =
        habitats.values.filter {
            it.box.min.x <= chunkPos.maxBlockX && it.box.max.x >= chunkPos.minBlockX &&
                it.box.min.z <= chunkPos.maxBlockZ && it.box.max.z >= chunkPos.minBlockZ &&
                it.box.min.y <= maxY && it.box.max.y >= minY
        }

    /**
     * Registers a new habitat if its box does not overlap an existing one
     * (habitats may never overlap or claim blocks already claimed by another).
     */
    fun register(
        level: ServerLevel,
        definition: HabitatDefinition,
        box: HabitatBox,
        playerCreated: Boolean = false
    ): HabitatInstance? {
        if (overlapsAny(box)) return null
        val instance = HabitatInstance(UUID.randomUUID(), definition.id, box, playerCreated)
        habitats[instance.uuid] = instance
        setDirty()
        PokopiaNetwork.syncHabitatsToLevel(level)
        return instance
    }

    fun remove(level: ServerLevel, uuid: UUID) {
        if (habitats.remove(uuid) != null) {
            setDirty()
            PokopiaNetwork.syncHabitatsToLevel(level)
        }
    }

    /**
     * Drops transient (wild-only, non-player-made) habitats whose center is in
     * the given chunk. Called when a chunk unloads so naturally detected
     * habitats do not accumulate in memory for the whole session - they cost
     * nothing while unloaded and simply regenerate when a player revisits.
     * Player-made and PC-owned habitats are kept (and are saved anyway).
     *
     * No client sync is sent: an unloading chunk is out of everyone's render
     * range, so nothing is drawn for it, and the client's cache self-corrects
     * on the next sync (which any nearby habitat registration triggers).
     */
    fun pruneTransientInChunk(chunkPos: ChunkPos): Boolean {
        val victims = habitats.values.filter {
            !it.playerCreated && !it.hasOwnedOccupant() &&
                (it.box.center.x shr 4) == chunkPos.x && (it.box.center.z shr 4) == chunkPos.z
        }
        if (victims.isEmpty()) return false
        victims.forEach { habitats.remove(it.uuid) }
        return true
    }

    /**
     * Removes a habitat. Wild occupants are NOT despawned - they simply lose
     * their habitat tag and roam free (and may despawn naturally later).
     * Owned occupants are safely returned to their owner's PC by clearing the
     * pasture tether: if the entity is loaded it is recalled; if it sits in an
     * unloaded chunk the tether is cleared on the stored Pokemon instead, and
     * the stale entity removes itself when its chunk next loads. Either way
     * the Pokemon is in the PC and can never be lost.
     */
    fun destroy(level: ServerLevel, habitat: HabitatInstance) {
        for (slot in habitat.slots) {
            if (slot.isEmpty) {
                continue
            }
            val entity = slot.entityId?.let { level.getEntity(it) } as? PokemonEntity
            if (slot.isOwned) {
                if (entity != null) {
                    entity.tethering = null
                    entity.pokemon.tetheringId = null
                    entity.pokemon.recall()
                } else {
                    val ownerId = slot.ownerId
                    val pokemonId = slot.pokemonId
                    if (ownerId != null && pokemonId != null) {
                        Cobblemon.storage.getPC(ownerId, level.registryAccess())[pokemonId]?.tetheringId = null
                    }
                }
            } else {
                // wild: let them roam free
                entity?.removeTag(HabitatSpawner.HABITAT_TAG_PREFIX + habitat.uuid)
            }
            slot.clear()
        }
        remove(level, habitat.uuid)
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        // Only player-made habitats, and natural ones currently holding a
        // player's Pokemon, are written to disk. Wild-only natural habitats are
        // transient and regenerate on revisit, keeping the file size bounded.
        val list = ListTag()
        habitats.values.forEach { if (it.shouldPersist()) list.add(it.save()) }
        tag.put("habitats", list)
        return tag
    }

    companion object {
        private const val DATA_NAME = "${Pokopia.MOD_ID}_habitats"

        private val FACTORY: Factory<HabitatManager> = Factory(
            { HabitatManager() },
            { tag, _ -> load(tag) },
            null
        )

        fun get(level: ServerLevel): HabitatManager =
            level.dataStorage.computeIfAbsent(FACTORY, DATA_NAME)

        private fun load(tag: CompoundTag): HabitatManager {
            val manager = HabitatManager()
            val list = tag.getList("habitats", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                HabitatInstance.load(list.getCompound(i))?.let { manager.habitats[it.uuid] = it }
            }
            return manager
        }
    }
}
