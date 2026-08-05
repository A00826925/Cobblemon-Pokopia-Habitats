package com.pokopia.habitat

import com.pokopia.Pokopia
import com.pokopia.PokopiaConfig
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.AABB
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import java.util.UUID

/**
 * Natural habitat detection.
 *
 * The moment a player steps into a new chunk, a slice of that chunk is scanned
 * for habitats: the full 16x16 footprint, [PokopiaConfig.chunkScanVerticalRange]
 * blocks tall, centered on the CHUNK's center horizontally and on the PLAYER's
 * height vertically (half up, half down). A per-player cooldown (default 10s)
 * stops fast travel (elytra!) from triggering a scan storm.
 *
 * Performance: the slice pass is a cheap block-state sweep that only collects
 * positions relevant to loaded habitat definitions; full box matching only runs
 * on a bounded number of candidate anchors.
 */
object ChunkScanHandler {
    private const val MAX_CANDIDATES = 32

    private val lastChunk = HashMap<UUID, Long>()
    private val lastScanTime = HashMap<UUID, Long>()

    fun register() {
        TickEvent.PLAYER_POST.register { player ->
            val serverPlayer = player as? ServerPlayer ?: return@register
            if (serverPlayer.tickCount % 10 != 0) return@register
            onPlayerMoved(serverPlayer)
        }
        PlayerEvent.PLAYER_QUIT.register { player ->
            lastChunk.remove(player.uuid)
            lastScanTime.remove(player.uuid)
        }
        // Prune transient (wild-only) natural habitats when their chunk unloads,
        // so they don't pile up in memory over a long session. They regenerate
        // when a player returns to the area. Architectury has no chunk-unload
        // event, so this comes from the platform layer.
        Pokopia.platform.registerChunkUnload { level, chunkPos ->
            HabitatManager.get(level).pruneTransientInChunk(chunkPos)
        }
    }

    private fun onPlayerMoved(player: ServerPlayer) {
        if (!PokopiaConfig.enableAutomaticHabitatDetection) return
        val chunkKey = player.chunkPosition().toLong()
        if (lastChunk[player.uuid] == chunkKey) return
        lastChunk[player.uuid] = chunkKey

        val now = player.serverLevel().gameTime
        val cooldownTicks = PokopiaConfig.chunkScanCooldownSeconds * 20L
        val last = lastScanTime[player.uuid]
        if (last != null && now - last < cooldownTicks) return
        lastScanTime[player.uuid] = now

        // Scan a square of chunks around the player (config: chunkScanRadius,
        // 0 = just the chunk stepped into). Each chunk keeps its own habitat
        // budget. usedDefinitions is shared across the whole sweep so the area
        // gets VARIED habitats instead of three identical meadows.
        val level = player.serverLevel()
        val center = player.chunkPosition()
        val radius = PokopiaConfig.chunkScanRadius
        val usedDefinitions = HashSet<ResourceLocation>()
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                scanChunkSlice(level, player, ChunkPos(center.x + dx, center.z + dz), usedDefinitions)
            }
        }
    }

    private fun scanChunkSlice(
        level: ServerLevel,
        player: ServerPlayer,
        chunkPos: ChunkPos,
        usedDefinitions: MutableSet<ResourceLocation>
    ) {
        if (PokopiaHabitats.orderedDefinitions.isEmpty()) return
        if (!level.isLoaded(chunkPos.getMiddleBlockPosition(0))) return

        val verticalRange = PokopiaConfig.chunkScanVerticalRange
        val halfDown = verticalRange / 2
        val playerY = player.blockY
        val minY = (playerY - halfDown).coerceAtLeast(level.minBuildHeight)
        val maxY = (playerY - halfDown + verticalRange - 1).coerceAtMost(level.maxBuildHeight - 1)

        // Slice: full chunk footprint, centered on the chunk (not the player).
        val minPos = BlockPos(chunkPos.minBlockX, minY, chunkPos.minBlockZ)
        val maxPos = BlockPos(chunkPos.maxBlockX, maxY, chunkPos.maxBlockZ)

        // Natural detection is capped per chunk SLICE, not per whole column: only
        // scan while this slice holds fewer than maxNaturalHabitatsPerChunk
        // habitats, and never register past that cap (a big grass field shouldn't
        // flood a slice with meadows). Because the cap only sees habitats in the
        // current slice's Y-band, exploring a fresh slice underground can still
        // form new habitats even when the surface above already has its three.
        // The handheld scanner is exempt and can always add more.
        val manager = HabitatManager.get(level)
        val existing = manager.intersectingChunkSlice(chunkPos, minY, maxY)
        // Existing habitats in this slice count towards variety too.
        existing.forEach { usedDefinitions.add(it.definitionId) }
        val chunkBudget = PokopiaConfig.maxNaturalHabitatsPerChunk - existing.size
        if (chunkBudget <= 0) return

        // Cheap pass: find blocks that matter to any habitat definition.
        val candidates = ArrayList<BlockPos>()
        for (pos in BlockPos.betweenClosed(minPos, maxPos)) {
            if (candidates.size >= MAX_CANDIDATES * 8) break
            val state = level.getBlockState(pos)
            if (HabitatDetector.isInterestingBlock(state)) {
                candidates.add(pos.immutable())
            }
        }
        // Item frames / armor stands holding items are candidates too, so
        // pure "display item" habitats can be found naturally.
        val sliceAABB = AABB(
            minPos.x.toDouble(), minPos.y.toDouble(), minPos.z.toDouble(),
            (maxPos.x + 1).toDouble(), (maxPos.y + 1).toDouble(), (maxPos.z + 1).toDouble()
        )
        level.getEntitiesOfClass(ItemFrame::class.java, sliceAABB)
            .filter { !it.item.isEmpty }
            .forEach { candidates.add(it.blockPosition()) }
        level.getEntitiesOfClass(ArmorStand::class.java, sliceAABB)
            .filter { stand -> stand.allSlots.any { !it.isEmpty } }
            .forEach { candidates.add(it.blockPosition()) }

        if (candidates.isEmpty()) return

        var registered = 0
        val tested = ArrayList<HabitatBox>()
        val width = PokopiaConfig.scannerBoxWidth
        var attempts = 0

        for (candidate in candidates) {
            if (registered >= chunkBudget) break
            if (attempts >= MAX_CANDIDATES) break
            // Skip candidates inside an area we already tested or an existing habitat.
            if (tested.any { it.contains(candidate) }) continue

            // Anchor the box on the centroid of nearby interesting blocks so the
            // habitat box lines up with the build rather than its edge.
            val near = candidates.filter {
                Math.abs(it.x - candidate.x) <= width / 2 &&
                    Math.abs(it.z - candidate.z) <= width / 2 &&
                    it.y >= candidate.y && it.y - candidate.y < PokopiaConfig.scannerBoxHeight
            }
            val anchor = if (near.isEmpty()) candidate else BlockPos(
                near.sumOf { it.x } / near.size,
                near.minOf { it.y },
                near.sumOf { it.z } / near.size
            )

            val scanBox = HabitatDetector.boxAt(anchor)
            tested.add(scanBox)
            attempts++

            if (manager.overlapsAny(scanBox)) continue
            val matches = HabitatDetector.matchingDefinitions(level, scanBox)
            if (matches.isEmpty()) continue

            // Variety first: prefer a matching definition the area doesn't
            // have yet, falling back to an already-used one (a pure grass
            // field still becomes a meadow, but grass with water nearby
            // becomes meadow THEN pond instead of meadow, meadow, meadow).
            val definition = matches.firstOrNull { it.id !in usedDefinitions } ?: matches.first()

            // Shrink to the blocks that form the habitat, and require open space
            // so nothing is registered (and later suffocates) inside solid ground.
            val box = HabitatDetector.tightBoxFor(level, scanBox, definition) ?: continue
            if (!HabitatDetector.hasSpawnSpace(level, box)) continue

            // Automatic detections are silent - only scanner-made habitats
            // announce themselves. Hold the scanner to see what formed.
            manager.register(level, definition, box) ?: continue
            usedDefinitions.add(definition.id)
            registered++
        }
    }
}
