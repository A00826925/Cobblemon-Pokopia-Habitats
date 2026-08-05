package com.pokopia.habitat

import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome

/**
 * Shared biome matching for both habitat-level "can this habitat exist here"
 * gates and per-spawn "can this Pokemon appear here" gates. Specs are concrete
 * biome ids ("minecraft:plains") or biome tags ("#minecraft:is_forest").
 */
object BiomeMatcher {
    /** True if [biome] matches ANY of [specs] (OR). Empty specs => true (no restriction). */
    fun matchesAny(biome: Holder<Biome>, specs: List<String>): Boolean {
        if (specs.isEmpty()) return true
        for (spec in specs) {
            val matched = if (spec.startsWith("#")) {
                val rl = ResourceLocation.tryParse(spec.substring(1)) ?: continue
                biome.`is`(TagKey.create(Registries.BIOME, rl))
            } else {
                val rl = ResourceLocation.tryParse(spec) ?: continue
                biome.`is`(ResourceKey.create(Registries.BIOME, rl))
            }
            if (matched) return true
        }
        return false
    }
}
