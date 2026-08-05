package com.pokopia.client

import com.pokopia.network.ClientboundHabitatSyncPayload
import com.pokopia.network.ClientboundOpenHabitatScreenPayload
import com.pokopia.network.ClientboundOpenPcPickerPayload
import com.pokopia.network.HabitatInfo
import com.pokopia.registry.PokopiaItems
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player

/** Client-only helpers. Only ever touched on the client. */
object PokopiaClient {
    fun isHoldingScanner(player: Player): Boolean =
        player.mainHandItem.`is`(PokopiaItems.HABITAT_SCANNER.get()) ||
            player.offhandItem.`is`(PokopiaItems.HABITAT_SCANNER.get())
}

/** The habitats of the dimension the client is currently in. */
object ClientHabitatCache {
    @Volatile
    var habitats: List<HabitatInfo> = emptyList()
        private set

    fun update(list: List<HabitatInfo>) {
        habitats = list
    }
}

/** Applies S2C payloads on the client thread. */
object ClientPayloadHandler {
    fun handleSync(payload: ClientboundHabitatSyncPayload) {
        ClientHabitatCache.update(payload.habitats)
    }

    fun handleOpenScreen(payload: ClientboundOpenHabitatScreenPayload) {
        val minecraft = Minecraft.getInstance()
        val current = minecraft.screen
        if (current is HabitatScreen && current.habitatId == payload.habitatId) {
            current.updateData(payload)
        } else {
            minecraft.setScreen(HabitatScreen(payload))
        }
    }

    fun handleOpenPcPicker(payload: ClientboundOpenPcPickerPayload) {
        Minecraft.getInstance().setScreen(PcPickerScreen(payload))
    }
}
