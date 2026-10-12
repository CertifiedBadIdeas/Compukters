/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.display

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.StreamDecoder
import net.minecraft.network.codec.StreamEncoder
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.core.display.DisplayPointerController
import ru.lazyhat.compukters.core.display.DisplayPointerTarget
import ru.lazyhat.compukters.impl.compat.Identifier
import ru.lazyhat.compukters.impl.compat.compuktersServer
import ru.lazyhat.compukters.minecraft.display.DisplayBlockEntity
import ru.lazyhat.compukters.minecraft.display.displayPointerTarget
import java.util.WeakHashMap

/** The only client assertion is whether use is held; all hit geometry and access is checked on the server. */
data class DisplayPointerHeldPayload(
    val held: Boolean,
    val cancelled: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<DisplayPointerHeldPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<DisplayPointerHeldPayload>(Identifier.fromNamespaceAndPath(MOD_ID, "display_pointer_held"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, DisplayPointerHeldPayload> =
            StreamCodec.of(
                StreamEncoder { buffer, payload ->
                    buffer.writeBoolean(payload.held)
                    buffer.writeBoolean(payload.cancelled)
                },
                StreamDecoder { buffer -> DisplayPointerHeldPayload(buffer.readBoolean(), buffer.readBoolean()) },
            )
    }
}

@EventBusSubscriber(modid = MOD_ID)
object DisplayPointerNetwork {
    private val controllers = WeakHashMap<MinecraftServer, DisplayPointerController>()

    fun register(event: RegisterPayloadHandlersEvent) {
        event.registrar("1").playToServer(DisplayPointerHeldPayload.TYPE, DisplayPointerHeldPayload.STREAM_CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val server = player.compuktersServer()
                val controller = controllers.getOrPut(server) { DisplayPointerController() }
                val tick = server.tickCount.toLong()
                if (payload.cancelled) {
                    controller.cancel(player.uuid, tick, payload.held)
                } else {
                    controller.update(player.uuid, tick, payload.held, if (payload.held) target(player) else null)
                }
            }
        }
    }

    private fun target(player: ServerPlayer): DisplayPointerTarget? {
        if (!player.isAlive || player.isSpectator || player.isShiftKeyDown || !player.mainHandItem.isEmpty) return null
        val hit = player.pick(player.blockInteractionRange(), 1f, false) as? BlockHitResult ?: return null
        if (hit.type != HitResult.Type.BLOCK) return null
        val level = player.level() as? ServerLevel ?: return null
        val entity = level.getBlockEntity(hit.blockPos) as? DisplayBlockEntity ?: return null
        val aim = displayPointerTarget(level, entity, hit) ?: return null
        return aim
    }

    @JvmStatic
    @SubscribeEvent
    fun tick(event: ServerTickEvent.Post) {
        val controller = controllers[event.server] ?: return
        controller.expire(
            event.server.tickCount.toLong(),
            event.server.playerList.players
                .mapTo(mutableSetOf<Any>()) { it.uuid },
        )
    }

    @JvmStatic
    @SubscribeEvent
    fun stop(event: ServerStoppingEvent) {
        controllers.remove(event.server)?.close()
    }
}
