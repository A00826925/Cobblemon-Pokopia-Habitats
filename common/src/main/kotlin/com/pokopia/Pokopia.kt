package com.pokopia

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.pokopia.habitat.ChunkScanHandler
import com.pokopia.habitat.HabitatSpawner
import com.pokopia.habitat.PokopiaHabitats
import com.pokopia.network.PokopiaNetwork
import com.pokopia.platform.PlatformServices
import com.pokopia.registry.PokopiaItems
import dev.architectury.registry.ReloadListenerRegistry
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Cobblemon Pokopia - Pokemon Pokopia style habitat spawning for Cobblemon.
 *
 * Instead of Cobblemon's natural spawning, Pokemon appear inside player-built
 * "habitats": small areas whose blocks (and item-frame / armor-stand items)
 * match a habitat definition loaded from JSON data files.
 *
 * Loader-neutral entry point. Each platform (Fabric / NeoForge) installs its
 * [PlatformServices] and calls [init] from its own mod entry point.
 */
object Pokopia {
    const val MOD_ID = "pokopia"

    val LOGGER: Logger = LoggerFactory.getLogger("Cobblemon Pokopia")

    /** Installed by the platform entry point before [init] runs. */
    lateinit var platform: PlatformServices

    fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)

    fun init() {
        // Load / create the config file.
        PokopiaConfig.load()

        // Registries (Architectury DeferredRegister).
        PokopiaItems.register()

        // Networking.
        PokopiaNetwork.register()
        PokopiaNetwork.registerSyncEvents()

        // Habitat definitions come from datapacks (data/<namespace>/pokopia/habitats/*.json).
        ReloadListenerRegistry.register(PackType.SERVER_DATA, PokopiaHabitats.ReloadListener, id("habitats"))

        // Game logic.
        HabitatSpawner.register()
        ChunkScanHandler.register()

        // Disable Cobblemon's own natural spawning when configured to (default: off).
        // This only cancels the natural world spawner - pokeballs, commands,
        // fishing, etc. still work, as do our own habitat spawns.
        CobblemonEvents.ENTITY_SPAWN.subscribe(Priority.HIGHEST) { event ->
            if (PokopiaConfig.disableNaturalCobblemonSpawns) {
                event.cancel()
            }
        }

        LOGGER.info("Cobblemon Pokopia initialised - habitats ready to be built!")
    }
}
