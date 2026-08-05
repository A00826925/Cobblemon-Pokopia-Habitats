package com.pokopia.fabric

import com.mojang.blaze3d.vertex.PoseStack
import com.pokopia.client.HabitatRenderer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents

/** Fabric client entry point (declared in fabric.mod.json). */
object PokopiaFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        // Draw the habitat outlines/labels after translucent geometry, matching
        // the NeoForge AFTER_PARTICLES stage closely enough for the faint boxes.
        WorldRenderEvents.AFTER_TRANSLUCENT.register { context ->
            val poseStack = context.matrixStack()
                ?: PoseStack().apply { mulPose(context.positionMatrix()) }
            HabitatRenderer.render(poseStack, context.camera())
        }
    }
}
