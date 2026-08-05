package com.pokopia.client

import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.pokemon.Species
import com.pokopia.Pokopia
import com.pokopia.network.ClientboundOpenHabitatScreenPayload
import com.pokopia.network.ServerboundCycleHabitatPayload
import com.pokopia.network.ServerboundHabitatPokemonActionPayload
import com.pokopia.network.ServerboundHabitatScreenClosedPayload
import com.pokopia.network.ServerboundRecallHabitatPayload
import com.pokopia.network.ServerboundRequestPcListPayload
import com.pokopia.network.ServerboundToggleSlotLockPayload
import com.pokopia.network.SlotInfo
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import dev.architectury.networking.NetworkManager
import java.util.UUID

/**
 * The habitat management menu: two squares side by side.
 *
 * Left square  - the habitat selector. Clicking it cycles to the next habitat
 *                definition that the detected blocks also satisfy.
 * Right square - three rows, one per Pokemon slot. Each occupied slot renders
 *                the live Pokemon model (same portrait the Party/PC screens
 *                use) beside its name/level and a slot-appropriate button:
 *                 wild occupant -> Release
 *                 your Pokemon  -> To PC
 *                 vacant        -> Assign from PC (opens the picker)
 * While this menu is open the habitat pauses its own spawning.
 */
class HabitatScreen(payload: ClientboundOpenHabitatScreenPayload) :
    Screen(Component.translatable("pokopia.screen.title")) {

    val habitatId: UUID = payload.habitatId
    private var habitatName: String = payload.habitatName
    private var matchCount: Int = payload.matchCount
    private var slots: List<SlotInfo> = payload.slots

    private val leftW = 110
    private val panelH = 138
    private val rightW = 158
    private val rowH = panelH / 3
    private val gap = 12
    private val portrait = rowH - 6

    private var leftX = 0
    private var rightX = 0
    private var topY = 0

    fun updateData(payload: ClientboundOpenHabitatScreenPayload) {
        habitatName = payload.habitatName
        matchCount = payload.matchCount
        slots = payload.slots
        rebuildWidgets()
    }

    override fun init() {
        leftX = width / 2 - (leftW + rightW + gap) / 2
        rightX = leftX + leftW + gap
        topY = height / 2 - panelH / 2

        // Recall: teleports the habitat's Pokemon back home.
        addRenderableWidget(
            Button.builder(Component.translatable("pokopia.screen.recall")) {
                NetworkManager.sendToServer(ServerboundRecallHabitatPayload(habitatId))
            }.bounds(leftX, topY + panelH + 6, leftW, 16).build()
        )

        for (index in 0 until 3) {
            val slot = slots.getOrNull(index) ?: SlotInfo.VACANT
            val rowTop = topY + index * rowH

            // Buttons are added BEFORE the portrait so a portrait failure can
            // never prevent them from appearing.
            val btnX = rightX + portrait + 12
            val btnW = rightW - portrait - 18
            val btnY = rowTop + rowH - 16
            val lockW = 42
            val actionW = btnW - lockW - 4

            // Lock / unlock toggle (always present) at the row's right edge.
            val lockLabel = if (slot.locked) "pokopia.screen.unlock" else "pokopia.screen.lock"
            addRenderableWidget(
                Button.builder(Component.translatable(lockLabel)) {
                    NetworkManager.sendToServer(ServerboundToggleSlotLockPayload(habitatId, index))
                }.bounds(btnX + actionW + 4, btnY, lockW, 14).build()
            )

            // Primary action, unless the slot is locked (then it stays empty).
            if (!slot.locked) {
                if (!slot.occupied) {
                    addRenderableWidget(
                        Button.builder(Component.translatable("pokopia.screen.assign")) {
                            NetworkManager.sendToServer(ServerboundRequestPcListPayload(habitatId, index))
                        }.bounds(btnX, btnY, actionW, 14).build()
                    )
                } else if (slot.owned && slot.mine) {
                    addRenderableWidget(
                        Button.builder(Component.translatable("pokopia.screen.pc")) {
                            NetworkManager.sendToServer(ServerboundHabitatPokemonActionPayload(habitatId, index, true))
                        }.bounds(btnX, btnY, actionW, 14).build()
                    )
                } else if (!slot.owned) {
                    addRenderableWidget(
                        Button.builder(Component.translatable("pokopia.screen.release")) {
                            NetworkManager.sendToServer(ServerboundHabitatPokemonActionPayload(habitatId, index, false))
                        }.bounds(btnX, btnY, actionW, 14).build()
                    )
                }
            }

            // Live Pokemon portrait, exactly like the Party/PC screens. Fully
            // guarded: any Cobblemon client API mismatch just skips the model
            // (the name still shows) and never breaks the rest of the menu.
            if (slot.occupied && slot.species.isNotEmpty()) {
                addPortrait(slot, rightX + 4, rowTop + 3)
            }
        }
    }

    private fun addPortrait(slot: SlotInfo, x: Int, y: Int) {
        try {
            val rl = ResourceLocation.tryParse(slot.species) ?: return
            val species = resolveSpecies(rl) ?: return
            val widget = ModelWidget(
                pX = x,
                pY = y,
                pWidth = portrait,
                pHeight = portrait,
                pokemon = RenderablePokemon(species, slot.aspects.toSet()),
                baseScale = 1.35F,
                rotationY = 35F
            )
            addRenderableOnly(widget)
        } catch (t: Throwable) {
            Pokopia.LOGGER.debug("Skipping habitat portrait for {}: {}", slot.species, t.toString())
        }
    }

    /**
     * Resolves a species by id reflectively. PokemonSpecies.getByIdentifier is
     * compiled as a static call against Cobblemon 1.6 but is an instance method
     * at runtime on 1.7+, which otherwise throws IncompatibleClassChangeError.
     * Reflection works for either shape.
     */
    private fun resolveSpecies(id: ResourceLocation): Species? = try {
        val cls = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonSpecies")
        val instance = runCatching { cls.getField("INSTANCE").get(null) }.getOrNull()
        val method = cls.getMethod("getByIdentifier", ResourceLocation::class.java)
        method.invoke(instance, id) as? Species
    } catch (t: Throwable) {
        null
    }

    // Panels are drawn in renderBackground so the widgets (rendered by
    // super.render afterwards) end up ON TOP of the squares, not under them.
    override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick)

        val hoveringLeft = isInLeftSquare(mouseX.toDouble(), mouseY.toDouble())
        val leftFill = if (hoveringLeft && matchCount > 1) 0xC0304530.toInt() else 0xC0202020.toInt()
        guiGraphics.fill(leftX, topY, leftX + leftW, topY + panelH, leftFill)
        drawBorder(guiGraphics, leftX, topY, leftW, panelH, 0xFF55FF88.toInt())

        guiGraphics.fill(rightX, topY, rightX + rightW, topY + panelH, 0xC0202020.toInt())
        drawBorder(guiGraphics, rightX, topY, rightW, panelH, 0xFF5588FF.toInt())
        for (index in 1 until 3) {
            val rowTop = topY + index * rowH
            guiGraphics.fill(rightX + 1, rowTop, rightX + rightW - 1, rowTop + 1, 0xFF5588FF.toInt())
        }
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTick)

        guiGraphics.drawCenteredString(font, title, width / 2, topY - 16, 0xFFFFFF)

        // ---- left square texts
        guiGraphics.drawCenteredString(
            font, Component.translatable("pokopia.screen.habitat"),
            leftX + leftW / 2, topY + 14, 0xAAAAAA
        )
        guiGraphics.drawCenteredString(
            font, Component.literal(habitatName),
            leftX + leftW / 2, topY + panelH / 2 - 4, 0x55FF88
        )
        val subtitle = if (matchCount > 1) {
            Component.translatable("pokopia.screen.cycle_hint", matchCount)
        } else {
            Component.translatable("pokopia.screen.only_match")
        }
        guiGraphics.drawCenteredString(font, subtitle, leftX + leftW / 2, topY + panelH - 22, 0x888888)

        // ---- right square texts (portraits render themselves as widgets)
        for (index in 0 until 3) {
            val rowTop = topY + index * rowH
            val textX = rightX + portrait + 12
            val slot = slots.getOrNull(index) ?: SlotInfo.VACANT
            if (slot.occupied) {
                guiGraphics.drawString(font, Component.literal(slot.name), textX, rowTop + 4, 0xFFFFFF)
                val badge = when {
                    slot.owned && slot.mine -> Component.translatable("pokopia.screen.yours").withStyle(ChatFormatting.AQUA)
                    slot.owned -> Component.translatable("pokopia.screen.owned").withStyle(ChatFormatting.GOLD)
                    else -> Component.translatable("pokopia.screen.wild").withStyle(ChatFormatting.GREEN)
                }
                val levelText = if (slot.level > 0) {
                    Component.translatable("pokopia.screen.level", slot.level).copy()
                        .append(Component.literal(" ")).append(badge)
                } else {
                    badge.copy()
                }
                guiGraphics.drawString(font, levelText, textX, rowTop + 14, 0xAAAAAA)
            } else if (slot.locked) {
                guiGraphics.drawString(
                    font, Component.translatable("pokopia.screen.locked").withStyle(ChatFormatting.RED),
                    rightX + 8, rowTop + rowH / 2 - 4, 0xFFFFFF
                )
            } else {
                guiGraphics.drawString(
                    font, Component.translatable("pokopia.screen.vacant"),
                    rightX + 8, rowTop + rowH / 2 - 4, 0x666666
                )
            }
        }
    }

    private fun drawBorder(guiGraphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int, color: Int) {
        guiGraphics.fill(x, y, x + w, y + 1, color)
        guiGraphics.fill(x, y + h - 1, x + w, y + h, color)
        guiGraphics.fill(x, y, x + 1, y + h, color)
        guiGraphics.fill(x + w - 1, y, x + w, y + h, color)
    }

    private fun isInLeftSquare(mouseX: Double, mouseY: Double): Boolean =
        mouseX >= leftX && mouseX < leftX + leftW && mouseY >= topY && mouseY < topY + panelH

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && isInLeftSquare(mouseX, mouseY)) {
            minecraft?.soundManager?.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
            NetworkManager.sendToServer(ServerboundCycleHabitatPayload(habitatId))
            return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun onClose() {
        NetworkManager.sendToServer(ServerboundHabitatScreenClosedPayload(habitatId))
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false
}
