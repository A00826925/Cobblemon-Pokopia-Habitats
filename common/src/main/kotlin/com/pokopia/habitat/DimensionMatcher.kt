package com.pokopia.habitat

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel

/**
 * Shared dimension matching for habitat-level "can this habitat exist here"
 * gates and per-spawn "can this Pokemon appear here" gates. Specs are concrete
 * dimension ids ("minecraft:overworld", "minecraft:the_nether",
 * "minecraft:the_end", or a modded dimension id). Empty => no restriction.
 */
object DimensionMatcher {
    fun matchesAny(level: ServerLevel, specs: List<String>): Boolean {
        if (specs.isEmpty()) return true
        val here = level.dimension().location()
        for (spec in specs) {
            if (ResourceLocation.tryParse(spec) == here) return true
        }
        return false
    }
}
