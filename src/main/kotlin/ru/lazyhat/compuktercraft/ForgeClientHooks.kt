// SPDX-FileCopyrightText: 2022 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

/**
 * Forge-specific dispatch for [ClientHooks].
 */
@Mod.EventBusSubscriber(modid = CompukterCraftMod.ID, value = [Dist.CLIENT])
object ForgeClientHooks {
    @SubscribeEvent
    fun onTick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.START) ClientHooks.onTick()
    }

    @SubscribeEvent
    fun onRenderTick(event: TickEvent.RenderTickEvent) {
        if (event.phase == TickEvent.Phase.START) ClientHooks.onRenderTick()
    }

// 	@SubscribeEvent
// 	fun onWorldUnload(event: LevelEvent.Unload) {
// 		if (event.getLevel().isClientSide()) ClientHooks.onWorldUnload()
// 	}
//
// 	@SubscribeEvent
// 	fun onDisconnect(event: LoggingOut?) {
// 		ClientHooks.onDisconnect()
// 	}
//
// 	@SubscribeEvent
// 	fun drawHighlight(event: RenderHighlightEvent.Block) {
// 		if (ClientHooks.drawHighlight(event.getPoseStack(), event.getMultiBufferSource(), event.getCamera(), event.getTarget())) {
// 			event.setCanceled(true)
// 		}
// 	}
//
// 	@SubscribeEvent
// 	fun onRenderText(event: DebugText) {
// 		ClientHooks.addGameDebugInfo(event.getLeft()::add)
// 		ClientHooks.addBlockDebugInfo(event.getRight()::add)
// 	}
//
// 	@SubscribeEvent
// 	fun onRenderInHand(event: RenderHandEvent) {
// 		if (ClientHooks.onRenderHeldItem(
// 				event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(),
// 				event.getHand(), event.getInterpolatedPitch(), event.getEquipProgress(), event.getSwingProgress(), event.getItemStack()
// 			)
// 		) {
// 			event.setCanceled(true)
// 		}
// 	}
//
// 	@SubscribeEvent
// 	fun onRenderInFrame(event: RenderItemInFrameEvent) {
// 		if (ClientHooks.onRenderItemFrame(
// 				event.getPoseStack(), event.getMultiBufferSource(), event.getItemFrameEntity(), event.getItemStack(), event.getPackedLight()
// 			)
// 		) {
// 			event.setCanceled(true)
// 		}
// 	}
//
// 	@SubscribeEvent
// 	fun playStreaming(event: PlayStreamingSourceEvent) {
// 		if (event.getSound() !is SpeakerSound || sound.getStream() == null) return
// 		ClientHooks.onPlayStreaming(event.getEngine(), event.getChannel(), sound.getStream())
// 	}
//
// 	@SubscribeEvent
// 	fun registerClientCommands(event: RegisterClientCommandsEvent) {
// 		ClientRegistry.registerClientCommands(
// 			event.getDispatcher(),
// 			{ obj: CommandSourceStack?, message: Component? -> obj.sendFailure(message) })
// 	}
}
