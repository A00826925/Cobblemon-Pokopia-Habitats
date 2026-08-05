package com.pokopia.neoforge

import com.pokopia.client.HabitatRenderer
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.common.NeoForge

/** Client-only render wiring for NeoForge. Only touched when Dist is CLIENT. */
object PokopiaNeoForgeClient {
    fun init() {
        NeoForge.EVENT_BUS.addListener { event: RenderLevelStageEvent ->
            if (event.stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                HabitatRenderer.render(event.poseStack, event.camera)
            }
        }
    }
}
