package com.pokopia.habitat

import com.pokopia.PokopiaConfig
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.server.level.ServerLevel

/**
 * The contents of a scanned box: every non-air block state (liquids included)
 * and every item displayed on an item frame or armor stand inside the box.
 */
class ScanResult(
    val states: List<BlockState>,
    val displayedItems: List<ItemStack>
) {
    fun countBlocks(requirement: BlockRequirement): Int = states.count { requirement.matches(it) }

    fun countItems(requirement: ItemRequirement): Int =
        displayedItems.filter { requirement.matches(it) }.sumOf { it.count }

    fun satisfies(definition: HabitatDefinition): Boolean {
        if (definition.blocks.isEmpty() && definition.heldItems.isEmpty()) return false
        return definition.blocks.all { countBlocks(it) >= it.count } &&
            definition.heldItems.all { countItems(it) >= it.count }
    }
}

object HabitatDetector {

    /** The habitat/scanner box for a given anchor block: WxW footprint centered on it, H tall going UP. */
    fun boxAt(anchor: BlockPos): HabitatBox {
        val width = PokopiaConfig.scannerBoxWidth
        val height = PokopiaConfig.scannerBoxHeight
        val half = width / 2
        return HabitatBox(
            BlockPos(anchor.x - half, anchor.y, anchor.z - half),
            BlockPos(anchor.x - half + width - 1, anchor.y + height - 1, anchor.z - half + width - 1)
        )
    }

    /**
     * Scans every block and item-holding entity inside the box.
     * Waterlogged blocks contribute both their block AND the fluid's block, so
     * "minecraft:water" requirements see waterlogged blocks too.
     */
    fun scan(level: ServerLevel, box: HabitatBox): ScanResult {
        // Air is normally skipped, but is included when some loaded definition
        // requires it (e.g. "minecraft:air" / "minecraft:cave_air" for a cave
        // habitat) so those requirements can be counted.
        val includeAir = PokopiaHabitats.requiresAir
        val states = ArrayList<BlockState>()
        for (pos in BlockPos.betweenClosed(box.min, box.max)) {
            val state = level.getBlockState(pos)
            if (state.isAir) {
                if (includeAir) states.add(state)
                continue
            }
            states.add(state)
            val fluid = state.fluidState
            if (!fluid.isEmpty) {
                val fluidBlockState = fluid.createLegacyBlock()
                if (!fluidBlockState.`is`(state.block)) {
                    states.add(fluidBlockState)
                }
            }
        }
        return ScanResult(states, collectDisplayedItems(level, box.toAABB()))
    }

    /** Items shown on item frames and armor stands (entities that can hold items). */
    private fun collectDisplayedItems(level: ServerLevel, area: AABB): List<ItemStack> {
        val items = ArrayList<ItemStack>()
        for (frame in level.getEntitiesOfClass(ItemFrame::class.java, area)) {
            if (!frame.item.isEmpty) items.add(frame.item)
        }
        for (stand in level.getEntitiesOfClass(ArmorStand::class.java, area)) {
            for (stack in stand.allSlots) {
                if (!stack.isEmpty) items.add(stack)
            }
        }
        return items
    }

    /** How far above the top matched block the habitat box extends, so Pokemon have room to live. */
    const val SPAWN_HEADROOM = 2

    /** All habitat definitions the scan satisfies, most specific first. */
    fun matchingDefinitions(result: ScanResult): List<HabitatDefinition> =
        PokopiaHabitats.orderedDefinitions.filter { result.satisfies(it) }

    /** A block state a Pokemon can occupy: air or liquid. */
    fun isPassable(state: BlockState): Boolean = state.isAir || !state.fluidState.isEmpty

    /**
     * Shrinks the scanned area down to the blocks (and item-displaying entities)
     * that actually belong to the definition, so the habitat's bounding box hugs
     * the build instead of covering the whole scan box. The result is extended
     * [SPAWN_HEADROOM] blocks upward so Pokemon have space to stand.
     */
    fun tightBoxFor(level: ServerLevel, scanBox: HabitatBox, definition: HabitatDefinition): HabitatBox? {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        var found = false
        fun include(x: Int, y: Int, z: Int) {
            found = true
            if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
            if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
        }
        for (pos in BlockPos.betweenClosed(scanBox.min, scanBox.max)) {
            val state = level.getBlockState(pos)
            // Air only shapes the box for definitions that actually require air
            // (the matches() check below handles that); otherwise it is ignored.
            val fluidBlock = state.fluidState.takeIf { !it.isEmpty }?.createLegacyBlock()
            val matches = definition.blocks.any { req ->
                req.matches(state) || (fluidBlock != null && req.matches(fluidBlock))
            }
            if (matches) include(pos.x, pos.y, pos.z)
        }
        if (definition.heldItems.isNotEmpty()) {
            val area = scanBox.toAABB()
            for (frame in level.getEntitiesOfClass(ItemFrame::class.java, area)) {
                if (definition.heldItems.any { it.matches(frame.item) }) {
                    val p = frame.blockPosition()
                    include(p.x, p.y, p.z)
                }
            }
            for (stand in level.getEntitiesOfClass(ArmorStand::class.java, area)) {
                if (stand.allSlots.any { stack -> definition.heldItems.any { it.matches(stack) } }) {
                    val p = stand.blockPosition()
                    include(p.x, p.y, p.z)
                }
            }
        }
        if (!found) return null
        return HabitatBox(BlockPos(minX, minY, minZ), BlockPos(maxX, maxY + SPAWN_HEADROOM, maxZ))
    }

    /**
     * True if the box has at least one spot where a Pokemon can exist without
     * suffocating: a passable block with a passable block above it. Habitats
     * buried inside solid ground fail this and are never registered.
     */
    fun hasSpawnSpace(level: ServerLevel, box: HabitatBox): Boolean {
        for (x in box.min.x..box.max.x) {
            for (z in box.min.z..box.max.z) {
                for (y in box.min.y..box.max.y) {
                    val pos = BlockPos(x, y, z)
                    if (isPassable(level.getBlockState(pos)) && isPassable(level.getBlockState(pos.above()))) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Re-scans an existing habitat's box and returns the definitions it
     * satisfies, filtered by each definition's habitat-level biome gate at the
     * box center. This is the world-aware match used for registration and the
     * management menu; the [ScanResult] overload is biome-unaware.
     */
    fun matchingDefinitions(level: ServerLevel, box: HabitatBox): List<HabitatDefinition> =
        matchingDefinitions(scan(level, box)).filter { it.placementAllowed(level, box.center) }

    /** True if the given block state is relevant to ANY loaded habitat definition. */
    fun isInterestingBlock(state: BlockState): Boolean {
        if (state.isAir) return false
        for (definition in PokopiaHabitats.orderedDefinitions) {
            for (requirement in definition.blocks) {
                if (requirement.matches(state)) return true
                // waterlogged support for the cheap pre-pass
                val fluid = state.fluidState
                if (!fluid.isEmpty && requirement.matches(fluid.createLegacyBlock())) return true
            }
        }
        return false
    }
}

/** An axis-aligned habitat bounding box in inclusive block coordinates. */
data class HabitatBox(val min: BlockPos, val max: BlockPos) {
    fun toAABB(): AABB = AABB(
        min.x.toDouble(), min.y.toDouble(), min.z.toDouble(),
        (max.x + 1).toDouble(), (max.y + 1).toDouble(), (max.z + 1).toDouble()
    )

    fun contains(pos: BlockPos): Boolean =
        pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z

    fun intersects(other: HabitatBox): Boolean =
        min.x <= other.max.x && max.x >= other.min.x &&
            min.y <= other.max.y && max.y >= other.min.y &&
            min.z <= other.max.z && max.z >= other.min.z

    val center: BlockPos
        get() = BlockPos((min.x + max.x) / 2, (min.y + max.y) / 2, (min.z + max.z) / 2)
}
