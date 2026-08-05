package com.pokopia.habitat

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.pokopia.Pokopia
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.level.block.Blocks

/**
 * Loads habitat definitions from datapacks.
 *
 * Path: data/<namespace>/pokopia/habitats/<name>.json  ->  id "<namespace>:<name>"
 *
 * Unlike vanilla's SimpleJsonResourceReloadListener (which only keeps the
 * top-most file for an id), this loader reads the WHOLE resource stack for
 * each id, from lowest to highest priority pack, and merges the files.
 * That lets several datapacks append blocks/spawns to the same habitat id
 * without clobbering each other; a pack can still take full control with
 * "replace": true.
 */
object PokopiaHabitats {
    private const val DIRECTORY = "pokopia/habitats"
    private val GSON = Gson()

    @Volatile
    var definitions: Map<ResourceLocation, HabitatDefinition> = emptyMap()
        private set

    /** Definitions sorted by specificity (most demanding first) - the match order. */
    @Volatile
    var orderedDefinitions: List<HabitatDefinition> = emptyList()
        private set

    /** True if any loaded definition has a block requirement that matches air. */
    @Volatile
    var requiresAir: Boolean = false
        private set

    fun get(id: ResourceLocation): HabitatDefinition? = definitions[id]

    object ReloadListener : SimplePreparableReloadListener<Map<ResourceLocation, List<JsonObject>>>() {

        override fun prepare(
            resourceManager: ResourceManager,
            profiler: ProfilerFiller
        ): Map<ResourceLocation, List<JsonObject>> {
            val result = LinkedHashMap<ResourceLocation, List<JsonObject>>()
            val stacks = resourceManager.listResourceStacks(DIRECTORY) { it.path.endsWith(".json") }
            for ((fileLocation, resources) in stacks) {
                val path = fileLocation.path
                    .removePrefix("$DIRECTORY/")
                    .removeSuffix(".json")
                val id = ResourceLocation.fromNamespaceAndPath(fileLocation.namespace, path)
                val jsons = ArrayList<JsonObject>(resources.size)
                for (resource in resources) {
                    try {
                        resource.openAsReader().use { reader ->
                            jsons.add(GSON.fromJson(reader, JsonObject::class.java))
                        }
                    } catch (e: Exception) {
                        Pokopia.LOGGER.error("Failed to read habitat file {} from pack {}", fileLocation, resource.sourcePackId(), e)
                    }
                }
                if (jsons.isNotEmpty()) {
                    result[id] = jsons
                }
            }
            return result
        }

        override fun apply(
            prepared: Map<ResourceLocation, List<JsonObject>>,
            resourceManager: ResourceManager,
            profiler: ProfilerFiller
        ) {
            val loaded = LinkedHashMap<ResourceLocation, HabitatDefinition>()
            for ((id, jsons) in prepared) {
                try {
                    val builder = HabitatDefinition.Companion.Builder(id)
                    // Resource stacks are ordered from lowest to highest priority pack,
                    // so later files append to / override earlier ones.
                    jsons.forEach { builder.merge(it) }
                    val definition = builder.build { msg -> Pokopia.LOGGER.warn(msg) }
                    if (definition.blocks.isEmpty() && definition.heldItems.isEmpty()) {
                        Pokopia.LOGGER.warn("Habitat {} has no valid block or item requirements, skipping", id)
                        continue
                    }
                    if (definition.spawns.isEmpty()) {
                        Pokopia.LOGGER.warn("Habitat {} has no spawns; it can still be detected but stays empty", id)
                    }
                    loaded[id] = definition
                } catch (e: Exception) {
                    Pokopia.LOGGER.error("Failed to parse habitat {}", id, e)
                }
            }
            definitions = loaded
            orderedDefinitions = loaded.values.sortedWith(
                compareByDescending<HabitatDefinition> { it.specificity }.thenBy { it.id.toString() }
            )
            // Precompute whether any definition needs air, so scans only pay the
            // cost of tracking air blocks when a habitat actually requires them.
            val airStates = listOf(
                Blocks.AIR.defaultBlockState(),
                Blocks.CAVE_AIR.defaultBlockState(),
                Blocks.VOID_AIR.defaultBlockState()
            )
            requiresAir = loaded.values.any { def ->
                def.blocks.any { req -> airStates.any { req.matches(it) } }
            }
            Pokopia.LOGGER.info("Loaded {} Pokopia habitat definitions", loaded.size)
        }
    }
}
