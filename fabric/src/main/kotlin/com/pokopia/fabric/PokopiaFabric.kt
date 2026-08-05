package com.pokopia.fabric

import com.pokopia.Pokopia
import net.fabricmc.api.ModInitializer

/** Fabric common entry point (declared in fabric.mod.json). */
object PokopiaFabric : ModInitializer {
    override fun onInitialize() {
        Pokopia.platform = FabricPlatformServices()
        Pokopia.init()
    }
}
