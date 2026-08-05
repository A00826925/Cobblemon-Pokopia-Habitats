package com.pokopia.fabric

import com.pokopia.platform.PlatformServices
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos

/** Fabric implementations of the hooks Architectury's common event API lacks. */
class FabricPlatformServices : PlatformServices {
    override fun registerChunkUnload(callback: (ServerLevel, ChunkPos) -> Unit) {
        ServerChunkEvents.CHUNK_UNLOAD.register { level, chunk ->
            callback(level, chunk.pos)
        }
    }

    override fun registerDatapackReload(callback: (MinecraftServer) -> Unit) {
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register { server, _, _ ->
            callback(server)
        }
    }
}
