package com.pokopia.platform

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos

/**
 * Loader-specific hooks Architectury's cross-platform event API does not cover.
 * Each platform provides an implementation and installs it before [com.pokopia.Pokopia.init].
 */
interface PlatformServices {
    /**
     * Registers a callback fired when a server chunk unloads, used to prune
     * transient (wild-only) natural habitats so they don't accumulate.
     * Architectury's ChunkEvent only exposes load/save, so this is platform-specific.
     */
    fun registerChunkUnload(callback: (ServerLevel, ChunkPos) -> Unit)

    /**
     * Registers a callback fired after datapacks reload (e.g. /reload) so freshly
     * loaded habitat definitions can be re-synced to connected players. Login
     * sync is handled cross-platform via Architectury's PLAYER_JOIN event.
     */
    fun registerDatapackReload(callback: (MinecraftServer) -> Unit)
}
