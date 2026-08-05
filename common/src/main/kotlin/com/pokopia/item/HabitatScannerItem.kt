package com.pokopia.item

import com.pokopia.PokopiaConfig
import com.pokopia.habitat.HabitatDetector
import com.pokopia.habitat.HabitatManager
import com.pokopia.network.PokopiaNetwork
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult

/**
 * The Habitat Scanner.
 *
 * Right click a block (or liquid):
 *  - inside an existing habitat -> opens the habitat management menu
 *  - otherwise -> scans a WxWxH box (config: default 5x5, 5 blocks upward)
 *    centered on the clicked block for a matching habitat definition and
 *    registers a new habitat when one matches.
 *
 * While the scanner is held, nearby habitats show their name and a faint
 * bounding box (client-side rendering).
 */
class HabitatScannerItem(properties: Properties) : Item(properties) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val player = context.player ?: return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        return scanAt(level as ServerLevel, player as ServerPlayer, context.clickedPos)
    }

    override fun use(level: Level, player: Player, usedHand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(usedHand)
        if (level.isClientSide) return InteractionResultHolder.success(stack)
        // useOn does not fire for liquids, so ray trace including fluids here -
        // water and lava habitats need to be clickable too.
        val hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY)
        if (hit.type != HitResult.Type.BLOCK) return InteractionResultHolder.pass(stack)
        scanAt(level as ServerLevel, player as ServerPlayer, hit.blockPos)
        return InteractionResultHolder.success(stack)
    }

    private fun scanAt(level: ServerLevel, player: ServerPlayer, clickedPos: BlockPos): InteractionResult {
        val manager = HabitatManager.get(level)

        // Right-clicking an existing habitat opens its menu.
        val existing = manager.habitatAt(clickedPos) ?: manager.habitatAt(clickedPos.above())
        if (existing != null) {
            PokopiaNetwork.openHabitatScreen(player, existing)
            return InteractionResult.CONSUME
        }

        // Fresh scan: box centered on the clicked block, extending upward.
        // Definitions are filtered by their habitat-level biome gate so a
        // "forest only" habitat can't be formed outside a forest biome.
        val scanBox = HabitatDetector.boxAt(clickedPos)
        val scan = HabitatDetector.scan(level, scanBox)
        val matches = HabitatDetector.matchingDefinitions(scan)
            .filter { it.placementAllowed(level, scanBox.center) }
        if (matches.isEmpty()) {
            player.displayClientMessage(
                Component.translatable("pokopia.message.no_habitat").withStyle(ChatFormatting.GRAY), true
            )
            level.playSound(null, clickedPos, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.6f, 0.7f)
            return InteractionResult.CONSUME
        }

        // The habitat's bounding box hugs the blocks that make it up, not the full scan box.
        val definition = matches.first()
        val box = HabitatDetector.tightBoxFor(level, scanBox, definition) ?: scanBox

        // Habitats need open space, or the Pokemon would suffocate.
        if (!HabitatDetector.hasSpawnSpace(level, box)) {
            player.displayClientMessage(
                Component.translatable("pokopia.message.no_space").withStyle(ChatFormatting.RED), true
            )
            return InteractionResult.CONSUME
        }

        val instance = manager.register(level, definition, box, playerCreated = true)
        if (instance == null) {
            player.displayClientMessage(
                Component.translatable("pokopia.message.overlap").withStyle(ChatFormatting.RED), true
            )
            return InteractionResult.CONSUME
        }

        player.displayClientMessage(
            Component.translatable("pokopia.message.habitat_found", instance.displayName)
                .withStyle(ChatFormatting.GREEN),
            true
        )
        level.playSound(null, clickedPos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7f, 1.4f)
        return InteractionResult.CONSUME
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        tooltipComponents: MutableList<Component>,
        tooltipFlag: TooltipFlag
    ) {
        tooltipComponents.add(
            Component.translatable(
                "item.pokopia.habitat_scanner.tooltip",
                scannerWidthSafe(), scannerHeightSafe()
            ).withStyle(ChatFormatting.GRAY)
        )
        tooltipComponents.add(
            Component.translatable("item.pokopia.habitat_scanner.tooltip2").withStyle(ChatFormatting.DARK_GRAY)
        )
    }

    // Tooltips can render before configs load (e.g. main menu search); fall back to defaults.
    private fun scannerWidthSafe(): Int = runCatching { PokopiaConfig.scannerBoxWidth }.getOrDefault(5)
    private fun scannerHeightSafe(): Int = runCatching { PokopiaConfig.scannerBoxHeight }.getOrDefault(5)
}
