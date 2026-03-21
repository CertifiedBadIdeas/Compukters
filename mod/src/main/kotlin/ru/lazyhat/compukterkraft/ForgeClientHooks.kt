/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ru.lazyhat.compukterkraft

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent

/**
 * Forge-specific dispatch for [ClientHooks].
 */
@Mod.EventBusSubscriber(modid = MOD_ID, value = [Dist.CLIENT])
object ForgeClientHooks {
    @SubscribeEvent
    @JvmStatic
    fun onClientSetup(event: FMLClientSetupEvent) {
        event.enqueueWork { ClientRegistry.registerMainThread() }
    }

    @SubscribeEvent
    @JvmStatic
    fun onTick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.START) ClientHooks.onTick()
    }

    @SubscribeEvent
    @JvmStatic
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
