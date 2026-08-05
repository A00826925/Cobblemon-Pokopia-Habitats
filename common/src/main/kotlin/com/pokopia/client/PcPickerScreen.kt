package com.pokopia.client

import com.pokopia.network.ClientboundOpenPcPickerPayload
import com.pokopia.network.PcEntry
import com.pokopia.network.ServerboundAssignPcPokemonPayload
import com.pokopia.network.ServerboundHabitatScreenClosedPayload
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import dev.architectury.networking.NetworkManager
import java.util.UUID

/**
 * Simple paged list of the player's PC Pokemon (excluding ones already
 * roaming/tethered). Clicking one assigns it to the vacant habitat slot; the
 * server then reopens the habitat menu. There is deliberately no check that
 * the Pokemon could naturally spawn in the habitat.
 */
class PcPickerScreen(payload: ClientboundOpenPcPickerPayload) :
    Screen(Component.translatable("pokopia.screen.pick_title")) {

    private val habitatId: UUID = payload.habitatId
    private val slot: Int = payload.slot
    private val entries: List<PcEntry> = payload.entries
    private var page = 0

    private val perPage = 7
    private val rowWidth = 190
    private val rowHeight = 18

    private val pageCount: Int get() = if (entries.isEmpty()) 1 else (entries.size + perPage - 1) / perPage

    override fun init() {
        val left = width / 2 - rowWidth / 2
        var y = height / 2 - (perPage * (rowHeight + 2) + 24) / 2 + 16
        val start = page * perPage
        for (entry in entries.drop(start).take(perPage)) {
            addRenderableWidget(
                Button.builder(
                    Component.literal(entry.name).append(
                        Component.translatable("pokopia.screen.pick_level", entry.level)
                    )
                ) {
                    NetworkManager.sendToServer(
                        ServerboundAssignPcPokemonPayload(habitatId, slot, entry.pokemonId)
                    )
                }.bounds(left, y, rowWidth, rowHeight - 2).build()
            )
            y += rowHeight
        }
        val navY = y + 6
        addRenderableWidget(
            Button.builder(Component.literal("<")) {
                if (page > 0) { page--; rebuildWidgets() }
            }.bounds(left, navY, 40, 16).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("pokopia.screen.pick_back")) {
                onClose()
            }.bounds(left + rowWidth / 2 - 35, navY, 70, 16).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal(">")) {
                if (page < pageCount - 1) { page++; rebuildWidgets() }
            }.bounds(left + rowWidth - 40, navY, 40, 16).build()
        )
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTick)
        val top = height / 2 - (perPage * (rowHeight + 2) + 24) / 2
        guiGraphics.drawCenteredString(font, title, width / 2, top - 4, 0xFFFFFF)
        guiGraphics.drawCenteredString(
            font,
            Component.literal("${page + 1} / $pageCount"),
            width / 2, top + 4 + perPage * rowHeight + 26, 0x999999
        )
        if (entries.isEmpty()) {
            guiGraphics.drawCenteredString(
                font,
                Component.translatable("pokopia.screen.pick_empty"),
                width / 2, height / 2, 0x888888
            )
        }
    }

    override fun onClose() {
        NetworkManager.sendToServer(ServerboundHabitatScreenClosedPayload(habitatId))
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false
}
