package com.pokopia.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB

/**
 * Renders every synced habitat while the player holds the Habitat Scanner:
 *  - a barely-visible bounding box outline
 *  - the habitat's name floating above the box, billboarded to the camera
 *
 * Loader-neutral: each platform's world-render hook supplies the pose stack and
 * camera (NeoForge RenderLevelStageEvent / Fabric WorldRenderEvents).
 */
object HabitatRenderer {
    private const val MAX_RENDER_DISTANCE = 96.0

    fun render(poseStack: PoseStack, camera: Camera) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (!PokopiaClient.isHoldingScanner(player)) return
        val habitats = ClientHabitatCache.habitats
        if (habitats.isEmpty()) return

        val cameraPos = camera.position
        val bufferSource = minecraft.renderBuffers().bufferSource()

        poseStack.pushPose()
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
        val lineConsumer = bufferSource.getBuffer(RenderType.lines())
        for (habitat in habitats) {
            val aabb = AABB(
                habitat.min.x.toDouble(), habitat.min.y.toDouble(), habitat.min.z.toDouble(),
                (habitat.max.x + 1).toDouble(), (habitat.max.y + 1).toDouble(), (habitat.max.z + 1).toDouble()
            )
            if (aabb.center.distanceToSqr(cameraPos) > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) continue
            // Barely visible: low-alpha soft green outline.
            LevelRenderer.renderLineBox(poseStack, lineConsumer, aabb, 0.55f, 1.0f, 0.75f, 0.35f)
        }
        poseStack.popPose()
        bufferSource.endBatch(RenderType.lines())

        // Name labels
        for (habitat in habitats) {
            val centerX = (habitat.min.x + habitat.max.x + 1) / 2.0
            val centerZ = (habitat.min.z + habitat.max.z + 1) / 2.0
            val topY = habitat.max.y + 1.6
            val distSq = cameraPos.distanceToSqr(centerX, topY, centerZ)
            if (distSq > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) continue

            poseStack.pushPose()
            poseStack.translate(centerX - cameraPos.x, topY - cameraPos.y, centerZ - cameraPos.z)
            poseStack.mulPose(camera.rotation())
            poseStack.scale(-0.025f, -0.025f, 0.025f)
            val matrix = poseStack.last().pose()
            val font = minecraft.font
            val text = Component.literal(habitat.name)
            val x = -font.width(text) / 2f
            val background = (minecraft.options.getBackgroundOpacity(0.25f) * 255.0f).toInt() shl 24
            font.drawInBatch(
                text, x, 0f, 0xFFFFFFFF.toInt(), false, matrix, bufferSource,
                Font.DisplayMode.SEE_THROUGH, background, LightTexture.FULL_BRIGHT
            )
            font.drawInBatch(
                text, x, 0f, 0xFFFFFFFF.toInt(), false, matrix, bufferSource,
                Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT
            )
            poseStack.popPose()
        }
        bufferSource.endBatch()
    }
}
