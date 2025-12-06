// SPDX-FileCopyrightText: 2021 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft

import ru.lazyhat.compuktercraft.gui.FrameInfo

/**
 * Event listeners for client-only code.
 *
 *
 * This is the client-only version of [CommonHooks], and so should be where all client-specific event handlers are
 * defined.
 */
object ClientHooks {
    fun onTick() {
        FrameInfo.onTick()
    }

    fun onRenderTick() {
        // PauseAwareTimer.tick(Minecraft.getInstance().isPaused())
        FrameInfo.onRenderTick()
    }

// 	fun onWorldUnload() {
// 		MonitorRenderState.destroyAll()
// 		SpeakerManager.reset()
// 	}
//
// 	fun onDisconnect() {
// 		ClientPocketComputers.reset()
// 	}
//
// 	fun drawHighlight(transform: PoseStack?, bufferSource: MultiBufferSource?, camera: Camera?, hit: BlockHitResult?): Boolean {
// 		return CableHighlightRenderer.drawHighlight(transform, bufferSource, camera, hit)
// 				|| MonitorHighlightRenderer.drawHighlight(transform, bufferSource, camera, hit)
// 	}
//
// 	fun onRenderHeldItem(
// 		transform: PoseStack?, render: MultiBufferSource?, lightTexture: Int, hand: InteractionHand?,
// 		pitch: Float, equipProgress: Float, swingProgress: Float, stack: ItemStack
// 	): Boolean {
// 		if (stack.getItem() is PocketComputerItem) {
// 			PocketItemRenderer.INSTANCE.renderItemFirstPerson(
// 				transform,
// 				render,
// 				lightTexture,
// 				hand,
// 				pitch,
// 				equipProgress,
// 				swingProgress,
// 				stack
// 			)
// 			return true
// 		}
// 		if (stack.getItem() is PrintoutItem) {
// 			PrintoutItemRenderer.INSTANCE.renderItemFirstPerson(
// 				transform,
// 				render,
// 				lightTexture,
// 				hand,
// 				pitch,
// 				equipProgress,
// 				swingProgress,
// 				stack
// 			)
// 			return true
// 		}
//
// 		return false
// 	}
//
// 	fun onRenderItemFrame(transform: PoseStack?, render: MultiBufferSource?, frame: ItemFrame?, stack: ItemStack, light: Int): Boolean {
// 		if (stack.getItem() is PrintoutItem) {
// 			PrintoutItemRenderer.onRenderInFrame(transform, render, frame, stack, light)
// 			return true
// 		}
//
// 		return false
// 	}
//
// 	fun onPlayStreaming(engine: SoundEngine?, channel: Channel?, stream: AudioStream?) {
// 		SpeakerManager.onPlayStreaming(engine, channel, stream)
// 	}
//
// 	/**
// 	 * Add additional information about the currently targeted block to the debug screen.
// 	 *
// 	 * @param addText A callback which adds a single line of text.
// 	 */
// 	fun addBlockDebugInfo(addText: Consumer<String?>) {
// 		val minecraft: Minecraft = Minecraft.getInstance()
// 		if (!minecraft.options.renderDebug || minecraft.level == null) return
// 		if (minecraft.hitResult == null || minecraft.hitResult.getType() != HitResult.Type.BLOCK) return
//
// 		val tile: BlockEntity? = minecraft.level.getBlockEntity((minecraft.hitResult as BlockHitResult).getBlockPos())
//
// 		if (tile is MonitorBlockEntity) {
// 			addText.accept("")
// 			addText.accept(
// 				String.format("Targeted monitor: (%d, %d), %d x %d", tile.getXIndex(), tile.getYIndex(), tile.getWidth(), tile.getHeight())
// 			)
// 		} else if (tile is TurtleBlockEntity) {
// 			addText.accept("")
// 			addText.accept("Targeted turtle:")
// 			addText.accept(String.format("Id: %d", tile.getComputerID()))
// 			addTurtleUpgrade(addText, tile, TurtleSide.LEFT)
// 			addTurtleUpgrade(addText, tile, TurtleSide.RIGHT)
// 		}
// 	}
//
// 	private fun addTurtleUpgrade(out: Consumer<kotlin.String?>, turtle: TurtleBlockEntity, side: TurtleSide?) {
// 		val upgrade: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = turtle.getUpgrade(side)
// 		if (upgrade != null) out.accept(String.format("Upgrade[%s]: %s", side, upgrade.getUpgradeID()))
// 	}
//
// 	/**
// 	 * Add additional information about the game to the debug screen.
// 	 *
// 	 * @param addText A callback which adds a single line of text.
// 	 */
// 	fun addGameDebugInfo(addText: Consumer<kotlin.String?>) {
// 		if (MonitorBlockEntityRenderer.hasRenderedThisFrame() && Minecraft.getInstance().options.renderDebug) {
// 			addText.accept("[CC:T] Monitor renderer: " + MonitorBlockEntityRenderer.currentRenderer())
// 		}
// 	}
//
// 	@Nullable
// 	fun getBlockBreakingState(state: BlockState, pos: BlockPos?): BlockState? {
// 		// Only apply to cables which have both a cable and modem
// 		if (state.getBlock() !== ModRegistry.Blocks.CABLE.get() || !state.getValue<T?>(CableBlock.CABLE) || state.getValue<T?>(CableBlock.MODEM) === CableModemVariant.None
// 		) {
// 			return null
// 		}
//
// 		val hit: HitResult? = Minecraft.getInstance().hitResult
// 		if (hit == null || hit.getType() != HitResult.Type.BLOCK) return null
// 		val hitPos: BlockPos = (hit as BlockHitResult).getBlockPos()
//
// 		if (hitPos != pos) return null
//
// 		return if (WorldUtil.isVecInside(
// 				CableShapes.getModemShape(state),
// 				hit.getLocation().subtract(pos.getX().toDouble(), pos.getY().toDouble(), pos.getZ().toDouble())
// 			)
// 		)
// 			state.getBlock().defaultBlockState().setValue<T?, V?>(CableBlock.MODEM, state.getValue<T?>(CableBlock.MODEM))
// 		else
// 			state.setValue<T?, T?>(CableBlock.MODEM, CableModemVariant.None)
// 	}
}
