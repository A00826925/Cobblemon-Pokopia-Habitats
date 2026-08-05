package com.pokopia.habitat

import com.google.gson.JsonObject
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * A block requirement for a habitat. A requirement can match ONE spec
 * ("minecraft:water", "#minecraft:flowers") or a GROUP of alternatives (an OR:
 * any listed block/tag counts toward the same requirement), so compat packs
 * can say "1 chair: mod1 spruce chair OR mod1 oak chair OR any mod2 chair".
 * Liquids (water/lava) are ordinary blocks here, and waterlogged blocks also
 * count towards the fluid's block.
 */
class BlockRequirement private constructor(
    private val blocks: List<Block>,
    private val tags: List<TagKey<Block>>,
    val count: Int,
    val key: String
) {
    fun matches(state: BlockState): Boolean {
        for (block in blocks) if (state.`is`(block)) return true
        for (tag in tags) if (state.`is`(tag)) return true
        return false
    }

    companion object {
        /** Builds from spec strings ("ns:block" or "#ns:tag"). Null if nothing resolves. */
        fun of(specs: Collection<String>, count: Int, key: String, warn: (String) -> Unit): BlockRequirement? {
            val blocks = ArrayList<Block>()
            val tags = ArrayList<TagKey<Block>>()
            for (spec in specs) {
                if (spec.startsWith("#")) {
                    val rl = ResourceLocation.tryParse(spec.substring(1))
                    if (rl != null) tags.add(TagKey.create(Registries.BLOCK, rl)) else warn("bad tag id '$spec'")
                } else {
                    val rl = ResourceLocation.tryParse(spec)
                    val block = rl?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
                    if (block != null) blocks.add(block) else warn("unknown block '$spec'")
                }
            }
            if (blocks.isEmpty() && tags.isEmpty()) return null
            return BlockRequirement(blocks, tags, count, key)
        }
    }
}

/**
 * An item requirement: items that must be displayed inside the habitat on
 * item frames or armor stands. Same single-spec or OR-group semantics as
 * [BlockRequirement], with "ns:item" / "#ns:item_tag" specs.
 */
class ItemRequirement private constructor(
    private val items: List<Item>,
    private val tags: List<TagKey<Item>>,
    val count: Int,
    val key: String
) {
    fun matches(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        for (item in items) if (stack.`is`(item)) return true
        for (tag in tags) if (stack.`is`(tag)) return true
        return false
    }

    companion object {
        fun of(specs: Collection<String>, count: Int, key: String, warn: (String) -> Unit): ItemRequirement? {
            val items = ArrayList<Item>()
            val tags = ArrayList<TagKey<Item>>()
            for (spec in specs) {
                if (spec.startsWith("#")) {
                    val rl = ResourceLocation.tryParse(spec.substring(1))
                    if (rl != null) tags.add(TagKey.create(Registries.ITEM, rl)) else warn("bad tag id '$spec'")
                } else {
                    val rl = ResourceLocation.tryParse(spec)
                    val item = rl?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }
                    if (item != null) items.add(item) else warn("unknown item '$spec'")
                }
            }
            if (items.isEmpty() && tags.isEmpty()) return null
            return ItemRequirement(items, tags, count, key)
        }
    }
}

/**
 * One Pokemon that can spawn in a habitat.
 *
 * @param properties Cobblemon properties string, e.g. "pikachu" or "eevee shiny=yes".
 *                   Works with any species Cobblemon knows about, including datapack addons.
 * @param rarity     Rarity tag; used as a selection weight when a habitat rolls a spawn.
 * @param weight     Explicit weight override; if <= 0 the rarity's weight is used.
 * @param chance     0..1 chance the spawn actually happens once this entry is selected.
 * @param minLevel   Optional spawn level (or range start). Falls back to the habitat's
 *                   default_level, then to Cobblemon's own defaults. A level inside the
 *                   properties string always wins.
 * @param maxLevel   Optional range end; the level is rolled uniformly in [min, max].
 * @param biomes     Optional biome gate: ids ("minecraft:plains") or "#tags"
 *                   ("#minecraft:is_forest"). Empty = any biome; otherwise the entry only
 *                   spawns when the habitat sits in one of the listed biomes/tags.
 */
data class SpawnEntry(
    val properties: String,
    val rarity: String,
    val weight: Double,
    val chance: Double,
    val minLevel: Int? = null,
    val maxLevel: Int? = null,
    val biomes: List<String> = emptyList(),
    /** Optional dimension gate: dimension ids ("minecraft:the_nether"). Empty = any. */
    val dimensions: List<String> = emptyList()
) {
    val effectiveWeight: Double
        get() = if (weight > 0) weight else RARITY_WEIGHTS.getOrDefault(rarity.lowercase(), DEFAULT_WEIGHT)

    companion object {
        val RARITY_WEIGHTS: Map<String, Double> = mapOf(
            "common" to 100.0,
            "uncommon" to 45.0,
            "rare" to 15.0,
            "ultra-rare" to 5.0,
            "ultra_rare" to 5.0,
            "epic" to 5.0,
            "legendary" to 1.0
        )
        const val DEFAULT_WEIGHT = 45.0
    }
}

/**
 * A habitat definition, loaded from data/<namespace>/pokopia/habitats/<id>.json.
 */
class HabitatDefinition(
    val id: ResourceLocation,
    val displayName: String,
    val blocks: List<BlockRequirement>,
    val heldItems: List<ItemRequirement>,
    val spawns: List<SpawnEntry>,
    val defaultMinLevel: Int? = null,
    val defaultMaxLevel: Int? = null,
    /**
     * Habitat-level biome gate: ids ("minecraft:forest") or "#tags". If set,
     * the habitat can only be created where the biome matches one of these.
     * This is independent of per-spawn biome gates: a habitat allowed here can
     * still restrict individual Pokemon to a subset of biomes (or none).
     */
    val biomes: List<String> = emptyList(),
    /**
     * Habitat-level dimension gate: dimension ids ("minecraft:the_nether").
     * If set, the habitat can only be created in one of these dimensions.
     */
    val dimensions: List<String> = emptyList()
) {
    /** Total number of required blocks + items; used to prefer the most specific match. */
    val specificity: Int = blocks.sumOf { it.count } + heldItems.sumOf { it.count }

    /** True if a habitat of this definition may be created at [pos]'s biome. */
    fun biomeAllowed(level: net.minecraft.server.level.ServerLevel, pos: net.minecraft.core.BlockPos): Boolean {
        if (biomes.isEmpty()) return true
        return BiomeMatcher.matchesAny(level.getBiome(pos), biomes)
    }

    /** True if a habitat of this definition may be created in [level]'s dimension. */
    fun dimensionAllowed(level: net.minecraft.server.level.ServerLevel): Boolean =
        DimensionMatcher.matchesAny(level, dimensions)

    /** Both world-level gates (biome + dimension) at once. */
    fun placementAllowed(level: net.minecraft.server.level.ServerLevel, pos: net.minecraft.core.BlockPos): Boolean =
        dimensionAllowed(level) && biomeAllowed(level, pos)

    companion object {
        /** A requirement group being merged across datapacks: a count and its OR'd specs. */
        class ReqGroup(var count: Int) {
            val specs = LinkedHashSet<String>()
        }

        /** Mutable builder used while merging multiple datapack entries for the same id. */
        class Builder(val id: ResourceLocation) {
            var name: String? = null
            var defaultMinLevel: Int? = null
            var defaultMaxLevel: Int? = null
            val biomes = LinkedHashSet<String>()
            val dimensions = LinkedHashSet<String>()
            val blocks = LinkedHashMap<String, ReqGroup>()
            val heldItems = LinkedHashMap<String, ReqGroup>()
            val spawns = LinkedHashSet<SpawnEntry>()

            fun clear() {
                name = null
                defaultMinLevel = null
                defaultMaxLevel = null
                biomes.clear()
                dimensions.clear()
                blocks.clear()
                heldItems.clear()
                spawns.clear()
            }

            /**
             * Merges one JSON file into this builder. Files are applied from the
             * lowest-priority datapack to the highest. "replace": true discards
             * everything gathered so far; otherwise content is appended.
             *
             * Requirement entries come in two shapes:
             *   { "block"|"tag"|"item": "spec", "count": n }        - single spec
             *   { "key": "chair", "count": n, "any": [specs...] }   - OR group
             * Entries merge BY KEY (a single-spec entry's key defaults to its
             * spec): another pack reusing the key appends its specs into the
             * same OR group - that's how compat packs add, say, mod3 chairs to
             * an existing "chair" group. "count" only changes when present;
             * "overwrite": true resets the group's specs first.
             */
            fun merge(json: JsonObject) {
                if (json.has("replace") && json.get("replace").asBoolean) {
                    clear()
                }
                if (json.has("name")) {
                    name = json.get("name").asString
                }
                if (json.has("biomes")) {
                    val b = json.get("biomes")
                    if (b.isJsonArray) b.asJsonArray.forEach { biomes.add(it.asString) }
                    else biomes.add(b.asString)
                }
                if (json.has("dimensions")) {
                    val d = json.get("dimensions")
                    if (d.isJsonArray) d.asJsonArray.forEach { dimensions.add(it.asString) }
                    else dimensions.add(d.asString)
                }
                if (json.has("default_level")) {
                    val element = json.get("default_level")
                    if (element.isJsonArray) {
                        val arr = element.asJsonArray
                        if (arr.size() >= 2) {
                            defaultMinLevel = arr.get(0).asInt
                            defaultMaxLevel = arr.get(1).asInt
                        } else if (arr.size() == 1) {
                            defaultMinLevel = arr.get(0).asInt
                            defaultMaxLevel = arr.get(0).asInt
                        }
                    } else {
                        defaultMinLevel = element.asInt
                        defaultMaxLevel = element.asInt
                    }
                }
                if (json.has("blocks")) {
                    for (element in json.getAsJsonArray("blocks")) {
                        mergeRequirement(element.asJsonObject, blocks, isItem = false)
                    }
                }
                if (json.has("held_items")) {
                    for (element in json.getAsJsonArray("held_items")) {
                        mergeRequirement(element.asJsonObject, heldItems, isItem = true)
                    }
                }
                if (json.has("spawns")) {
                    for (element in json.getAsJsonArray("spawns")) {
                        val obj = element.asJsonObject
                        if (!obj.has("pokemon")) continue
                        var minLevel: Int? = null
                        var maxLevel: Int? = null
                        if (obj.has("level")) {
                            val lv = obj.get("level")
                            if (lv.isJsonArray) {
                                val arr = lv.asJsonArray
                                if (arr.size() >= 2) { minLevel = arr.get(0).asInt; maxLevel = arr.get(1).asInt }
                                else if (arr.size() == 1) { minLevel = arr.get(0).asInt; maxLevel = minLevel }
                            } else {
                                minLevel = lv.asInt
                                maxLevel = minLevel
                            }
                        }
                        val biomes = ArrayList<String>()
                        if (obj.has("biomes")) {
                            val b = obj.get("biomes")
                            if (b.isJsonArray) b.asJsonArray.forEach { biomes.add(it.asString) }
                            else biomes.add(b.asString)
                        }
                        val dimensions = ArrayList<String>()
                        if (obj.has("dimensions")) {
                            val d = obj.get("dimensions")
                            if (d.isJsonArray) d.asJsonArray.forEach { dimensions.add(it.asString) }
                            else dimensions.add(d.asString)
                        }
                        spawns.add(
                            SpawnEntry(
                                properties = obj.get("pokemon").asString,
                                rarity = if (obj.has("rarity")) obj.get("rarity").asString else "common",
                                weight = if (obj.has("weight")) obj.get("weight").asDouble else -1.0,
                                chance = (if (obj.has("chance")) obj.get("chance").asDouble else 1.0).coerceIn(0.0, 1.0),
                                minLevel = minLevel,
                                maxLevel = maxLevel,
                                biomes = biomes,
                                dimensions = dimensions
                            )
                        )
                    }
                }
            }

            private fun mergeRequirement(obj: JsonObject, target: LinkedHashMap<String, ReqGroup>, isItem: Boolean) {
                val specs = ArrayList<String>()
                if (obj.has("any")) {
                    for (member in obj.getAsJsonArray("any")) {
                        specs.add(member.asString)
                    }
                } else {
                    val single = when {
                        !isItem && obj.has("block") -> obj.get("block").asString
                        isItem && obj.has("item") -> obj.get("item").asString
                        obj.has("tag") -> "#" + obj.get("tag").asString.removePrefix("#")
                        else -> return
                    }
                    specs.add(single)
                }
                if (specs.isEmpty()) return
                val key = if (obj.has("key")) obj.get("key").asString else specs.first()
                val group = target.getOrPut(key) { ReqGroup(if (obj.has("count")) obj.get("count").asInt else 1) }
                if (obj.has("overwrite") && obj.get("overwrite").asBoolean) {
                    group.specs.clear()
                }
                if (obj.has("count")) {
                    group.count = obj.get("count").asInt
                }
                group.specs.addAll(specs)
            }

            fun build(warn: (String) -> Unit): HabitatDefinition {
                val blockReqs = blocks.mapNotNull { (key, group) ->
                    BlockRequirement.of(group.specs, group.count, key) { warn("Habitat $id ('$key'): $it") }
                        .also { if (it == null) warn("Habitat $id: requirement '$key' has no valid blocks, skipped") }
                }
                val itemReqs = heldItems.mapNotNull { (key, group) ->
                    ItemRequirement.of(group.specs, group.count, key) { warn("Habitat $id ('$key'): $it") }
                        .also { if (it == null) warn("Habitat $id: requirement '$key' has no valid items, skipped") }
                }
                val display = name ?: id.path.replace('_', ' ').replace('/', ' ')
                    .split(' ')
                    .joinToString(" ") { word -> word.replaceFirstChar { c -> c.uppercase() } }
                return HabitatDefinition(
                    id, display, blockReqs, itemReqs, spawns.toList(),
                    defaultMinLevel, defaultMaxLevel, biomes.toList(), dimensions.toList()
                )
            }
        }
    }
}
