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
 */

package ru.lazyhat.compukters.impl.ide.target

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import ru.lazyhat.compukters.core.MOD_ID
import java.util.WeakHashMap

@EventBusSubscriber(modid = MOD_ID)
internal object IdeTargetNetwork {
    private val servers = WeakHashMap<MinecraftServer, ServerTransport>()

    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToServer(IdeTargetRequestPayload.TYPE, IdeTargetRequestPayload.STREAM_CODEC, ::handle)
        registrar.playToClient(IdeTargetReplyPayload.TYPE, IdeTargetReplyPayload.STREAM_CODEC)
    }

    private fun handle(
        payload: IdeTargetRequestPayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        val server = player.level().server
        val transport = servers.getOrPut(server) { ServerTransport(server) }
        context.reply(
            IdeTargetReplyPayload(
                payload.requestId,
                transport.processor.handle(player.uuid, payload.request, server.tickCount.toLong()),
            ),
        )
    }

    @JvmStatic
    @SubscribeEvent
    fun afterServerTick(event: ServerTickEvent.Post) {
        servers[event.server]?.expire(event.server.tickCount.toLong())
    }

    @JvmStatic
    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        servers.remove(event.server)?.close()
    }

    private class ServerTransport(server: MinecraftServer) : AutoCloseable {
        private val leases = IdeTargetLeaseService(NeoForgeIdeTargetResolver(server))
        private val deployments = IdeTargetDeploymentService(leases)
        val processor = IdeTargetRequestProcessor(leases, deployments)

        fun expire(tick: Long) {
            leases.expire(tick)
            deployments.expire(tick)
        }

        override fun close() {
            deployments.close()
            leases.close()
        }
    }
}
