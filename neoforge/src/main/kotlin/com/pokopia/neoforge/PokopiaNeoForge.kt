package com.pokopia.neoforge

import com.pokopia.Pokopia
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment

/**
 * NeoForge entry point. Uses the KotlinForForge language provider (see
 * neoforge.mods.toml), so the object is instantiated directly. Architectury 13.x
 * captures the mod event bus automatically, so its DeferredRegister flushes
 * without any manual bus registration.
 */
@Mod(Pokopia.MOD_ID)
object PokopiaNeoForge {
    init {
        Pokopia.platform = NeoForgePlatformServices()
        Pokopia.init()
        if (FMLEnvironment.dist == Dist.CLIENT) {
            PokopiaNeoForgeClient.init()
        }
    }
}
