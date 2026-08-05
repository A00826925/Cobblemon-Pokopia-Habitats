package com.pokopia.neoforge

import com.pokopia.platform.PlatformServices
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.OnDatapackSyncEvent
import net.neoforged.neoforge.event.level.ChunkEvent

/** NeoForge implementations of the hooks Architectury's common event API lacks. */
class NeoForgePlatformServices : PlatformServices {
    override fun registerChunkUnload(callback: (ServerLevel, ChunkPos) -> Unit) {
        NeoForge.EVENT_BUS.addListener { event: ChunkEvent.Unload ->
            val level = event.level as? ServerLevel ?: return@addListener
            callback(level, event.chunk.pos)
        }
    }

    override fun registerDatapackReload(callback: (MinecraftServer) -> Unit) {
        NeoForge.EVENT_BUS.addListener { event: OnDatapackSyncEvent ->
            // player == null => a /reload (not a login). Login sync is handled
            // cross-platform via Architectury's PLAYER_JOIN event, so skip it here
            // to avoid syncing every joiner twice.
            if (event.player == null) {
                callback(event.playerList.server)
            }
        }
    }
}
