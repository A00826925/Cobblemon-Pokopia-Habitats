package com.pokopia.network

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.pokopia.Pokopia
import com.pokopia.habitat.HabitatDetector
import com.pokopia.habitat.HabitatInstance
import com.pokopia.habitat.HabitatManager
import com.pokopia.habitat.HabitatSpawner
import com.pokopia.registry.PokopiaItems
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.networking.NetworkManager
import dev.architectury.networking.NetworkManager.PacketContext
import java.util.UUID

/** Client-facing summary of one habitat, used for rendering boxes and labels. */
data class HabitatInfo(
    val uuid: UUID,
    val min: BlockPos,
    val max: BlockPos,
    val name: String
) {
    fun write(buf: FriendlyByteBuf) {
        buf.writeUUID(uuid)
        buf.writeBlockPos(min)
        buf.writeBlockPos(max)
        buf.writeUtf(name)
    }

    companion object {
        fun read(buf: FriendlyByteBuf) = HabitatInfo(buf.readUUID(), buf.readBlockPos(), buf.readBlockPos(), buf.readUtf())
    }
}

/** S2C: full habitat list for the player's current dimension. */
class ClientboundHabitatSyncPayload(val habitats: List<HabitatInfo>) : CustomPacketPayload {
    constructor(buf: FriendlyByteBuf) : this(buf.readList { HabitatInfo.read(it) })

    fun write(buf: FriendlyByteBuf) {
        buf.writeCollection(habitats) { b, info -> info.write(b) }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ClientboundHabitatSyncPayload>(Pokopia.id("habitat_sync"))
        val CODEC: net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, ClientboundHabitatSyncPayload> =
            CustomPacketPayload.codec({ payload, buf -> payload.write(buf) }, { buf -> ClientboundHabitatSyncPayload(buf) })
    }
}

/**
 * One occupant slot as shown in the habitat menu.
 * owned = a player's Pokemon (tethered from the PC); mine = owned by the viewer.
 */
data class SlotInfo(
    val occupied: Boolean,
    val name: String,
    val level: Int,
    val owned: Boolean,
    val mine: Boolean,
    /** Full species id ("cobblemon:pikachu") for portrait rendering, or "" if unknown. */
    val species: String,
    /** Cosmetic aspects (shiny, form, gender...) so the portrait matches the real Pokemon. */
    val aspects: List<String>,
    /** Locked slots stay empty: nothing spawns and nothing can be assigned. */
    val locked: Boolean = false
) {
    fun write(buf: FriendlyByteBuf) {
        buf.writeBoolean(occupied)
        buf.writeUtf(name)
        buf.writeVarInt(level)
        buf.writeBoolean(owned)
        buf.writeBoolean(mine)
        buf.writeUtf(species)
        buf.writeCollection(aspects) { b, aspect -> b.writeUtf(aspect) }
        buf.writeBoolean(locked)
    }

    companion object {
        val VACANT = SlotInfo(false, "", 0, owned = false, mine = false, species = "", aspects = emptyList())
        fun read(buf: FriendlyByteBuf) = SlotInfo(
            buf.readBoolean(), buf.readUtf(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
            buf.readUtf(), buf.readList { it.readUtf() }, buf.readBoolean()
        )
    }
}

/** S2C: opens (or refreshes) the habitat management screen. */
class ClientboundOpenHabitatScreenPayload(
    val habitatId: UUID,
    val habitatName: String,
    val matchCount: Int,
    val slots: List<SlotInfo>
) : CustomPacketPayload {
    constructor(buf: FriendlyByteBuf) : this(
        buf.readUUID(),
        buf.readUtf(),
        buf.readVarInt(),
        buf.readList { SlotInfo.read(it) }
    )

    fun write(buf: FriendlyByteBuf) {
        buf.writeUUID(habitatId)
        buf.writeUtf(habitatName)
        buf.writeVarInt(matchCount)
        buf.writeCollection(slots) { b, slot -> slot.write(b) }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ClientboundOpenHabitatScreenPayload>(Pokopia.id("open_habitat_screen"))
        val CODEC: net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, ClientboundOpenHabitatScreenPayload> =
            CustomPacketPayload.codec({ payload, buf -> payload.write(buf) }, { buf -> ClientboundOpenHabitatScreenPayload(buf) })
    }
}

/** One selectable Pokemon in the PC picker. */
data class PcEntry(val pokemonId: UUID, val name: String, val level: Int) {
    fun write(buf: FriendlyByteBuf) {
        buf.writeUUID(pokemonId)
        buf.writeUtf(name)
        buf.writeVarInt(level)
    }

    companion object {
        fun read(buf: FriendlyByteBuf) = PcEntry(buf.readUUID(), buf.readUtf(), buf.readVarInt())
    }
}

/** S2C: opens the PC picker so the player can assign a Pokemon to a vacant slot. */
class ClientboundOpenPcPickerPayload(
    val habitatId: UUID,
    val slot: Int,
    val entries: List<PcEntry>
) : CustomPacketPayload {
    constructor(buf: FriendlyByteBuf) : this(buf.readUUID(), buf.readVarInt(), buf.readList { PcEntry.read(it) })

    fun write(buf: FriendlyByteBuf) {
        buf.writeUUID(habitatId)
        buf.writeVarInt(slot)
        buf.writeCollection(entries) { b, entry -> entry.write(b) }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ClientboundOpenPcPickerPayload>(Pokopia.id("open_pc_picker"))
        val CODEC: net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, ClientboundOpenPcPickerPayload> =
            CustomPacketPayload.codec({ payload, buf -> payload.write(buf) }, { buf -> ClientboundOpenPcPickerPayload(buf) })
    }
}

/** C2S: cycle the habitat to the next matching definition. */
class ServerboundCycleHabitatPayload(val habitatId: UUID) : CustomPacketPayload {
    constructor(buf: FriendlyByteBuf) : this(buf.readUUID())

    fun write(buf: FriendlyByteBuf) = buf.writeUUID(habitatId)

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ServerboundCycleHabitatPayload>(Pokopia.id("cycle_habitat"))
        val CODEC: net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, ServerboundCycleHabitatPayload> =
            CustomPacketPayload.codec({ payload, buf -> payload.write(buf) }, { buf -> ServerboundCycleHabitatPayload(buf) })
    }
}

/** C2S: teleport the habitat's loaded Pokemon back inside its bounding box. */
class ServerboundRecallHabitatPayload(val habitatId: UUID) : CustomPacketPayload {
    constructor(buf: FriendlyByteBuf) : this(buf.readUUID())

    fun write(buf: FriendlyByteBuf) = buf.writeUUID(habitatId)

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ServerboundRecallHabitatPayload>(Pokopia.id("recall_habitat"))
        val CODEC: net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, ServerboundRecallHabitatPayload> =
            CustomPacketPayload.codec({ payload, buf -> payload.write(buf) }, { buf -> ServerboundRecallHabitatPayload(buf) })
    }
}

/**
 * C2S: slot action. toPc=true returns the viewer's OWN tethered Pokemon to
 * the PC; toPc=false releases a WILD occupant so something new can spawn.
 */
class ServerboundHabitatPokemonActionPayload(
    val habitatId: UUID,
    val slot: Int,
    val toPc: Boolean
) : CustomPacketPayload {
    constructor(buf: FriendlyByteBuf) : this(buf.readUUID(), buf.readVarInt(), buf.readBoolean())

    fun write(buf: FriendlyByteBuf) {
        buf.writeUUID(habitatId)
        buf.writeVarInt(slot)
        buf.writeBoolean(toPc)
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ServerboundHabitatPokemonActionPayload>(Pokopia.id("habitat_pokemon_action"))
        val CODEC: net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, ServerboundHabitatPokemonActionPayload> =
            CustomPacketPayload.codec({ payload, buf -> payload.write(buf) }, { buf -> ServerboundHabitatPokemonActionPayload(buf) })
    }
}

/** C2S: request the PC list to assign a Pokemon into a vacant slot. */
class ServerboundRequestPcListPayload(val habitatId: UUID, val slot: Int) : CustomPacketPayload {
    constructor(buf: FriendlyByteBuf) : this(buf.readUUID(), buf.readVarInt())

    fun write(buf: FriendlyByteBuf) {
        buf.writeUUID(habitatId)
        buf.writeVarInt(slot)
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ServerboundRequestPcListPayload>(Pokopia.id("request_pc_list"))
        val CODEC: net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, ServerboundRequestPcListPayload> =
            CustomPacketPayload.codec({ payload, buf -> payload.write(buf) }, { buf -> ServerboundRequestPcListPayload(buf) })
    }
}

/** C2S: assign the given PC Pokemon to a vacant habitat slot (pasture-style tether). */
class ServerboundAssignPcPokemonPayload(
    val habitatId: UUID,
    val slot: Int,
    val pokemonId: UUID
) : CustomPacketPayload {
    constructor(buf: FriendlyByteBuf) : this(buf.readUUID(), buf.readVarInt(), buf.readUUID())

    fun write(buf: FriendlyByteBuf) {
        buf.writeUUID(habitatId)
        buf.writeVarInt(slot)
        buf.writeUUID(pokemonId)
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ServerboundAssignPcPokemonPayload>(Pokopia.id("assign_pc_pokemon"))
        val CODEC: net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, ServerboundAssignPcPokemonPayload> =
            CustomPacketPayload.codec({ payload, buf -> payload.write(buf) }, { buf -> ServerboundAssignPcPokemonPayload(buf) })
    }
}

/** C2S: toggle a slot's lock (locked slots stay empty). */
class ServerboundToggleSlotLockPayload(val habitatId: UUID, val slot: Int) : CustomPacketPayload {
    constructor(buf: FriendlyByteBuf) : this(buf.readUUID(), buf.readVarInt())

    fun write(buf: FriendlyByteBuf) {
        buf.writeUUID(habitatId)
        buf.writeVarInt(slot)
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ServerboundToggleSlotLockPayload>(Pokopia.id("toggle_slot_lock"))
        val CODEC: net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, ServerboundToggleSlotLockPayload> =
            CustomPacketPayload.codec({ payload, buf -> payload.write(buf) }, { buf -> ServerboundToggleSlotLockPayload(buf) })
    }
}

/** C2S: the habitat menu (or PC picker) closed - spawning may resume. */
class ServerboundHabitatScreenClosedPayload(val habitatId: UUID) : CustomPacketPayload {
    constructor(buf: FriendlyByteBuf) : this(buf.readUUID())

    fun write(buf: FriendlyByteBuf) = buf.writeUUID(habitatId)

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ServerboundHabitatScreenClosedPayload>(Pokopia.id("habitat_screen_closed"))
        val CODEC: net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, ServerboundHabitatScreenClosedPayload> =
            CustomPacketPayload.codec({ payload, buf -> payload.write(buf) }, { buf -> ServerboundHabitatScreenClosedPayload(buf) })
    }
}

object PokopiaNetwork {
    /** Players must be within this range of a habitat to manage it from the menu. */
    private const val INTERACTION_RANGE = 16.0

    /** Spawn suppression window while the menu is open; refreshed on every menu packet. */
    private const val SUPPRESS_TICKS = 1200L

    /** How far a tethered (owned) Pokemon may roam before Cobblemon safely recalls it. */
    private const val ROAM_MARGIN = 24

    fun register() {
        // S2C: register the payload types (so the server can send them) and, on the
        // client, the handlers that apply them. Architectury only ever fires an S2C
        // receiver on the client, so the client-only handler references below are
        // never touched on a dedicated server.
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, ClientboundHabitatSyncPayload.TYPE, ClientboundHabitatSyncPayload.CODEC) { payload, context ->
            context.queue { com.pokopia.client.ClientPayloadHandler.handleSync(payload) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, ClientboundOpenHabitatScreenPayload.TYPE, ClientboundOpenHabitatScreenPayload.CODEC) { payload, context ->
            context.queue { com.pokopia.client.ClientPayloadHandler.handleOpenScreen(payload) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, ClientboundOpenPcPickerPayload.TYPE, ClientboundOpenPcPickerPayload.CODEC) { payload, context ->
            context.queue { com.pokopia.client.ClientPayloadHandler.handleOpenPcPicker(payload) }
        }

        // C2S: server-side handlers.
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, ServerboundCycleHabitatPayload.TYPE, ServerboundCycleHabitatPayload.CODEC) { payload, context ->
            context.queue { handleCycleHabitat(payload, context) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, ServerboundRecallHabitatPayload.TYPE, ServerboundRecallHabitatPayload.CODEC) { payload, context ->
            context.queue { handleRecallHabitat(payload, context) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, ServerboundHabitatPokemonActionPayload.TYPE, ServerboundHabitatPokemonActionPayload.CODEC) { payload, context ->
            context.queue { handlePokemonAction(payload, context) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, ServerboundRequestPcListPayload.TYPE, ServerboundRequestPcListPayload.CODEC) { payload, context ->
            context.queue { handleRequestPcList(payload, context) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, ServerboundAssignPcPokemonPayload.TYPE, ServerboundAssignPcPokemonPayload.CODEC) { payload, context ->
            context.queue { handleAssignPcPokemon(payload, context) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, ServerboundHabitatScreenClosedPayload.TYPE, ServerboundHabitatScreenClosedPayload.CODEC) { payload, context ->
            context.queue { handleScreenClosed(payload, context) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, ServerboundToggleSlotLockPayload.TYPE, ServerboundToggleSlotLockPayload.CODEC) { payload, context ->
            context.queue { handleToggleSlotLock(payload, context) }
        }
    }

    // ------------------------------------------------------------------ sync

    fun registerSyncEvents() {
        // Login (both loaders): send the current dimension's habitats to the joiner.
        PlayerEvent.PLAYER_JOIN.register { player -> syncHabitatsToPlayer(player) }
        PlayerEvent.CHANGE_DIMENSION.register { player, _, _ -> syncHabitatsToPlayer(player) }
        PlayerEvent.PLAYER_RESPAWN.register { player, _, _ -> syncHabitatsToPlayer(player) }
        // After /reload, freshly loaded definitions may change habitat validity;
        // re-sync everyone. (Login is already covered by PLAYER_JOIN above.)
        Pokopia.platform.registerDatapackReload { server ->
            server.playerList.players.forEach { syncHabitatsToPlayer(it) }
        }
    }

    fun syncHabitatsToPlayer(player: ServerPlayer) {
        val manager = HabitatManager.get(player.serverLevel())
        NetworkManager.sendToPlayer(player, ClientboundHabitatSyncPayload(manager.all().map { it.toInfo() }))
    }

    fun syncHabitatsToLevel(level: ServerLevel) {
        val payload = ClientboundHabitatSyncPayload(HabitatManager.get(level).all().map { it.toInfo() })
        level.players().forEach { NetworkManager.sendToPlayer(it, payload) }
    }

    private fun HabitatInstance.toInfo() = HabitatInfo(uuid, box.min, box.max, displayName)

    // ---------------------------------------------------------------- screen

    /**
     * Display name that only uses long-stable Cobblemon API (nickname +
     * species translated name). Pokemon.getDisplayName() changed signature
     * between Cobblemon 1.6 and 1.7 and caused NoSuchMethodError crashes.
     */
    private fun displayNameOf(pokemon: Pokemon): String =
        pokemon.nickname?.string ?: pokemon.species.translatedName.string

    /** Finds the Pokemon backing an owned slot, whether or not its entity is loaded. */
    private fun ownedPokemonOf(level: ServerLevel, slot: com.pokopia.habitat.OccupantSlot): Pokemon? {
        val ownerId = slot.ownerId ?: return null
        val pokemonId = slot.pokemonId ?: return null
        return Cobblemon.storage.getPC(ownerId, level.registryAccess())[pokemonId]
    }

    fun openHabitatScreen(player: ServerPlayer, habitat: HabitatInstance) {
        val level = player.serverLevel()
        // Pause spawning while the menu is open, so PC Pokemon can be
        // assigned in peace; refreshed by every menu packet, cleared on close.
        habitat.suppressSpawnsUntil = level.gameTime + SUPPRESS_TICKS

        // A wild occupant whose entity is gone (despawned while the player was
        // away) is a ghost - clear it right away instead of showing a stale
        // "Lv. 0" row. The player is standing here, so the chunk is loaded and
        // a missing entity really is gone. Owned slots are exempt: their
        // Pokemon lives in the PC and resolves by name below either way.
        var cleared = false
        for (slot in habitat.slots) {
            if (slot.isEmpty || slot.isOwned) continue
            val entity = slot.entityId?.let { level.getEntity(it) }
            if (entity == null || entity.isRemoved || !entity.isAlive) {
                slot.clear()
                cleared = true
            }
        }
        if (cleared) HabitatManager.get(level).setDirty()

        val matches = HabitatDetector.matchingDefinitions(level, habitat.box)
        val slots = habitat.slots.map { slot ->
            if (slot.isEmpty) return@map SlotInfo.VACANT.copy(locked = slot.locked)
            val entity = slot.entityId?.let { level.getEntity(it) } as? PokemonEntity
            val pokemon = if (entity != null && entity.isAlive) entity.pokemon else ownedPokemonOf(level, slot)
            if (pokemon != null) {
                SlotInfo(
                    occupied = true,
                    name = displayNameOf(pokemon),
                    level = pokemon.level,
                    owned = slot.isOwned,
                    mine = slot.ownerId == player.uuid,
                    species = pokemon.species.resourceIdentifier.toString(),
                    aspects = pokemon.aspects.toList(),
                    locked = slot.locked
                )
            } else {
                // wild occupant whose entity is not loaded right now: we know its
                // species (stored) but not its cosmetic aspects until it reloads.
                val speciesId = slot.speciesId ?: ""
                SlotInfo(
                    occupied = true,
                    name = speciesId.substringAfter(':').replaceFirstChar { it.uppercase() },
                    level = 0,
                    owned = false,
                    mine = false,
                    species = speciesId,
                    aspects = emptyList(),
                    locked = slot.locked
                )
            }
        }
        NetworkManager.sendToPlayer(
            player,
            ClientboundOpenHabitatScreenPayload(habitat.uuid, habitat.displayName, matches.size, slots)
        )
    }

    // -------------------------------------------------------------- handlers

    private fun validatedHabitat(player: ServerPlayer, habitatId: UUID): HabitatInstance? {
        val holdingScanner = player.mainHandItem.`is`(PokopiaItems.HABITAT_SCANNER.get()) ||
            player.offhandItem.`is`(PokopiaItems.HABITAT_SCANNER.get())
        if (!holdingScanner) return null
        val habitat = HabitatManager.get(player.serverLevel()).get(habitatId) ?: return null
        val center = habitat.box.center
        if (player.distanceToSqr(center.x + 0.5, center.y + 0.5, center.z + 0.5) >
            INTERACTION_RANGE * INTERACTION_RANGE
        ) return null
        return habitat
    }

    private fun handleCycleHabitat(payload: ServerboundCycleHabitatPayload, context: PacketContext) {
        val player = context.player as? ServerPlayer ?: return
        val habitat = validatedHabitat(player, payload.habitatId) ?: return
        val level = player.serverLevel()

        val matches = HabitatDetector.matchingDefinitions(level, habitat.box)
        if (matches.isEmpty()) {
            player.displayClientMessage(
                Component.translatable("pokopia.message.no_longer_matches").withStyle(ChatFormatting.RED), true
            )
            openHabitatScreen(player, habitat)
            return
        }
        val currentIndex = matches.indexOfFirst { it.id == habitat.definitionId }
        val next = matches[(currentIndex + 1).mod(matches.size)]
        if (next.id != habitat.definitionId) {
            // Switching definitions never removes the Pokemon already living here.
            habitat.definitionId = next.id
            HabitatManager.get(level).setDirty()
            syncHabitatsToLevel(level)
        }
        openHabitatScreen(player, habitat)
    }

    private fun handlePokemonAction(payload: ServerboundHabitatPokemonActionPayload, context: PacketContext) {
        val player = context.player as? ServerPlayer ?: return
        val habitat = validatedHabitat(player, payload.habitatId) ?: return
        if (payload.slot !in 0 until HabitatInstance.MAX_SLOTS) return
        val level = player.serverLevel()
        val slot = habitat.slots[payload.slot]
        if (slot.isEmpty) {
            openHabitatScreen(player, habitat)
            return
        }
        val entity = slot.entityId?.let { level.getEntity(it) } as? PokemonEntity

        if (payload.toPc) {
            // Only the OWNER can return their own tethered Pokemon to the PC.
            // Wild Pokemon can't be sent to the PC at all - catch them with a ball!
            if (!slot.isOwned || slot.ownerId != player.uuid) return
            val pokemon = if (entity != null && entity.isAlive) entity.pokemon else ownedPokemonOf(level, slot)
            if (entity != null && entity.isAlive) {
                entity.tethering = null
                entity.pokemon.tetheringId = null
                entity.pokemon.recall()
            } else {
                pokemon?.tetheringId = null
            }
            slot.clear()
            HabitatManager.get(level).setDirty()
            player.displayClientMessage(
                Component.translatable("pokopia.message.sent_to_pc", pokemon?.let { displayNameOf(it) } ?: "?")
                    .withStyle(ChatFormatting.GREEN),
                false
            )
        } else {
            // Release only applies to wild occupants.
            if (slot.isOwned) return
            if (entity != null && entity.isAlive) {
                val name = displayNameOf(entity.pokemon)
                entity.discard()
                player.displayClientMessage(
                    Component.translatable("pokopia.message.released", name).withStyle(ChatFormatting.YELLOW), false
                )
            }
            slot.clear()
            HabitatManager.get(level).setDirty()
        }
        openHabitatScreen(player, habitat)
    }

    /**
     * Teleports the habitat's occupants back inside its bounding box. Only
     * LOADED entities are moved - occupants sitting in unloaded chunks are
     * left alone (never re-created, so recall can never duplicate a Pokemon;
     * they'll be tethered home once their chunk loads again).
     */
    private fun handleRecallHabitat(payload: ServerboundRecallHabitatPayload, context: PacketContext) {
        val player = context.player as? ServerPlayer ?: return
        val habitat = validatedHabitat(player, payload.habitatId) ?: return
        val level = player.serverLevel()

        var recalled = 0
        var missing = 0
        for (slot in habitat.slots) {
            if (slot.isEmpty) continue
            val entity = slot.entityId?.let { level.getEntity(it) }
            if (entity == null || !entity.isAlive) {
                missing++
                continue
            }
            val home = HabitatSpawner.findSpawnPos(level, habitat.box, level.random)
                ?: habitat.box.center
            entity.teleportTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5)
            recalled++
        }
        val message = when {
            recalled > 0 && missing == 0 -> Component.translatable("pokopia.message.recalled", recalled)
                .withStyle(ChatFormatting.GREEN)
            recalled > 0 -> Component.translatable("pokopia.message.recalled_partial", recalled, missing)
                .withStyle(ChatFormatting.YELLOW)
            missing > 0 -> Component.translatable("pokopia.message.recall_too_far").withStyle(ChatFormatting.RED)
            else -> Component.translatable("pokopia.message.recall_empty").withStyle(ChatFormatting.GRAY)
        }
        player.displayClientMessage(message, true)
        openHabitatScreen(player, habitat)
    }

    private fun handleRequestPcList(payload: ServerboundRequestPcListPayload, context: PacketContext) {
        val player = context.player as? ServerPlayer ?: return
        val habitat = validatedHabitat(player, payload.habitatId) ?: return
        if (payload.slot !in 0 until HabitatInstance.MAX_SLOTS) return
        if (!habitat.slots[payload.slot].isEmpty || habitat.slots[payload.slot].locked) return
        habitat.suppressSpawnsUntil = player.serverLevel().gameTime + SUPPRESS_TICKS

        val pc = Cobblemon.storage.getPC(player)
        val entries = pc
            .filter { it.tetheringId == null }
            .map { PcEntry(it.uuid, displayNameOf(it), it.level) }
        NetworkManager.sendToPlayer(
            player,
            ClientboundOpenPcPickerPayload(habitat.uuid, payload.slot, entries)
        )
    }

    /**
     * Assigns a PC Pokemon to a vacant slot using Cobblemon's pasture
     * tethering: the Pokemon stays in the PC (shown there as roaming, like a
     * pastured Pokemon) and a tethered entity appears in the habitat. There is
     * deliberately NO check that the species could spawn in this habitat.
     */
    private fun handleAssignPcPokemon(payload: ServerboundAssignPcPokemonPayload, context: PacketContext) {
        val player = context.player as? ServerPlayer ?: return
        val habitat = validatedHabitat(player, payload.habitatId) ?: return
        if (payload.slot !in 0 until HabitatInstance.MAX_SLOTS) return
        val slot = habitat.slots[payload.slot]
        if (!slot.isEmpty || slot.locked) return
        val level = player.serverLevel()
        habitat.suppressSpawnsUntil = level.gameTime + SUPPRESS_TICKS

        val pc = Cobblemon.storage.getPC(player)
        val pokemon = pc[payload.pokemonId] ?: return
        if (pokemon.tetheringId != null) return

        val pos = HabitatSpawner.findSpawnPos(level, habitat.box, level.random)
        if (pos == null) {
            player.displayClientMessage(
                Component.translatable("pokopia.message.no_space").withStyle(ChatFormatting.RED), true
            )
            return
        }

        val entity = PokemonEntity(level, pokemon)
        entity.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, level.random.nextFloat() * 360f, 0f)
        if (!level.addFreshEntity(entity)) return

        val box = habitat.box
        val tetheringId = UUID.randomUUID()
        val tethering = createTethering(
            minRoamPos = BlockPos(box.min.x - ROAM_MARGIN, box.min.y - ROAM_MARGIN, box.min.z - ROAM_MARGIN),
            maxRoamPos = BlockPos(box.max.x + ROAM_MARGIN, box.max.y + ROAM_MARGIN, box.max.z + ROAM_MARGIN),
            playerId = player.uuid,
            playerName = player.gameProfile.name,
            tetheringId = tetheringId,
            pokemonId = pokemon.uuid,
            pcId = pc.uuid,
            entityId = entity.id,
            pasturePos = box.center
        )
        if (tethering == null) {
            entity.discard()
            Pokopia.LOGGER.error("Could not create a pasture tether on this Cobblemon version; PC assignment unavailable")
            return
        }
        pokemon.tetheringId = tetheringId
        entity.tethering = tethering
        entity.addTag(HabitatSpawner.HABITAT_TAG_PREFIX + habitat.uuid)

        slot.entityId = entity.uuid
        slot.pokemonId = pokemon.uuid
        slot.ownerId = player.uuid
        slot.speciesId = pokemon.species.resourceIdentifier.toString()
        slot.missCount = 0
        HabitatManager.get(level).setDirty()

        player.displayClientMessage(
            Component.translatable("pokopia.message.assigned", displayNameOf(pokemon), habitat.displayName)
                .withStyle(ChatFormatting.GREEN),
            false
        )
        openHabitatScreen(player, habitat)
    }

    /**
     * Toggles a slot's lock. Locking an occupied slot releases its current
     * occupant first (wild discarded, owned returned to its PC), then locks the
     * slot so nothing new can take it - lowering the habitat's capacity.
     */
    private fun handleToggleSlotLock(payload: ServerboundToggleSlotLockPayload, context: PacketContext) {
        val player = context.player as? ServerPlayer ?: return
        val habitat = validatedHabitat(player, payload.habitatId) ?: return
        if (payload.slot !in 0 until HabitatInstance.MAX_SLOTS) return
        val level = player.serverLevel()
        val slot = habitat.slots[payload.slot]

        if (!slot.locked && !slot.isEmpty) {
            // Free the occupant before locking.
            val entity = slot.entityId?.let { level.getEntity(it) } as? PokemonEntity
            if (slot.isOwned) {
                if (entity != null && entity.isAlive) {
                    entity.tethering = null
                    entity.pokemon.tetheringId = null
                    entity.pokemon.recall()
                } else {
                    ownedPokemonOf(level, slot)?.tetheringId = null
                }
            } else {
                if (entity != null && entity.isAlive) entity.discard()
            }
            slot.clear()
        }
        slot.locked = !slot.locked
        HabitatManager.get(level).setDirty()
        openHabitatScreen(player, habitat)
    }

    private fun handleScreenClosed(payload: ServerboundHabitatScreenClosedPayload, context: PacketContext) {
        val player = context.player as? ServerPlayer ?: return
        val habitat = HabitatManager.get(player.serverLevel()).get(payload.habitatId) ?: return
        habitat.suppressSpawnsUntil = 0L
    }

    /**
     * Builds a pasture Tethering across Cobblemon versions. The constructor
     * gained a trailing pasturePos parameter after 1.6 (the first 8 parameters
     * are identical in every version we support), so it is invoked
     * reflectively: the 9-arg form when present, the 8-arg form otherwise.
     * This keeps the mod compiling against Cobblemon 1.6.x while running
     * correctly on 1.6 through 1.8 at runtime.
     */
    private fun createTethering(
        minRoamPos: BlockPos,
        maxRoamPos: BlockPos,
        playerId: UUID,
        playerName: String,
        tetheringId: UUID,
        pokemonId: UUID,
        pcId: UUID,
        entityId: Int,
        pasturePos: BlockPos
    ): PokemonPastureBlockEntity.Tethering? {
        val constructors = PokemonPastureBlockEntity.Tethering::class.java.constructors
        // Newer Cobblemon: (..., entityId, pasturePos)
        constructors.firstOrNull { ctor ->
            ctor.parameterCount == 9 && ctor.parameterTypes[8] == BlockPos::class.java
        }?.let { ctor ->
            return runCatching {
                ctor.newInstance(minRoamPos, maxRoamPos, playerId, playerName,
                    tetheringId, pokemonId, pcId, entityId, pasturePos) as PokemonPastureBlockEntity.Tethering
            }.getOrNull()
        }
        // Cobblemon 1.6.x: no pasturePos
        constructors.firstOrNull { it.parameterCount == 8 }?.let { ctor ->
            return runCatching {
                ctor.newInstance(minRoamPos, maxRoamPos, playerId, playerName,
                    tetheringId, pokemonId, pcId, entityId) as PokemonPastureBlockEntity.Tethering
            }.getOrNull()
        }
        return null
    }
}
