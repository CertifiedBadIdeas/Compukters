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

package ru.lazyhat.compukters.impl.terminal

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import java.util.UUID
import java.util.WeakHashMap

@EventBusSubscriber(modid = MOD_ID)
object TerminalNetwork {
    private val viewers = mutableMapOf<UUID, Viewer>()

    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("2")
        registrar.playToClient(TerminalFullPayload.TYPE, TerminalFullPayload.STREAM_CODEC)
        registrar.playToClient(TerminalDeltaPayload.TYPE, TerminalDeltaPayload.STREAM_CODEC)
        registrar.playToServer(TerminalResyncPayload.TYPE, TerminalResyncPayload.STREAM_CODEC, ::handleResync)
        registrar.playToServer(TerminalClosePayload.TYPE, TerminalClosePayload.STREAM_CODEC, ::handleClose)
        registrar.playToServer(TerminalKeyPayload.TYPE, TerminalKeyPayload.STREAM_CODEC, ::handleKey)
        registrar.playToServer(TerminalTextPayload.TYPE, TerminalTextPayload.STREAM_CODEC, ::handleText)
    }

    fun open(
        player: ServerPlayer,
        entity: ComputerBlockEntity,
    ) {
        val state = entity.prepareTerminal() ?: return
        val machineId = entity.terminalMachineId ?: return
        viewers[player.uuid] = Viewer(player.level().dimension(), entity.blockPos, machineId, state.revision)
        PacketDistributor.sendToPlayer(player, TerminalFullPayload(entity.blockPos, machineId, state, openScreen = true))
    }

    internal fun isViewing(
        player: ServerPlayer,
        position: BlockPos,
        machineId: Long,
    ): Boolean {
        val viewer = viewers[player.uuid] ?: return false
        return viewer.dimension == player.level().dimension() &&
            viewer.position == position &&
            viewer.machineId == machineId &&
            player.isValidViewer(player.level(), position)
    }

    @JvmStatic
    @SubscribeEvent
    fun afterServerTick(event: ServerTickEvent.Post) {
        val grouped = linkedMapOf<Viewer, MutableList<ServerPlayer>>()
        val iterator = viewers.iterator()
        while (iterator.hasNext()) {
            val (playerId, viewer) = iterator.next()
            val player = event.server.playerList.getPlayer(playerId)
            val level = event.server.getLevel(viewer.dimension)
            if (player == null || level == null || !player.isValidViewer(level, viewer.position)) {
                iterator.remove()
                continue
            }
            grouped.getOrPut(viewer) { mutableListOf() } += player
        }
        grouped.forEach { (viewer, players) -> publishUpdate(viewer, players) }
    }

    private fun publishUpdate(
        viewer: Viewer,
        players: List<ServerPlayer>,
    ) {
        val level = players.first().level()
        val entity = level.getBlockEntity(viewer.position) as? ComputerBlockEntity ?: return
        val machineId = entity.terminalMachineId ?: return
        if (machineId != viewer.machineId) {
            val state = entity.terminalFullState() ?: return
            val payload = TerminalFullPayload(entity.blockPos, machineId, state, false)
            players.forEach { player ->
                viewers[player.uuid] = Viewer(player.level().dimension(), entity.blockPos, machineId, state.revision)
                PacketDistributor.sendToPlayer(player, payload)
            }
            return
        }
        when (val update = entity.terminalChangesSince(viewer.revision)) {
            is TerminalUpdate.Delta -> {
                val payload = TerminalDeltaPayload(entity.blockPos, machineId, update)
                players.forEach { player ->
                    viewers[player.uuid] = viewer.copy(revision = update.targetRevision)
                    PacketDistributor.sendToPlayer(player, payload)
                }
            }

            is TerminalUpdate.Full -> {
                val payload = TerminalFullPayload(entity.blockPos, machineId, update.state, false)
                players.forEach { player ->
                    viewers[player.uuid] = viewer.copy(revision = update.state.revision)
                    PacketDistributor.sendToPlayer(player, payload)
                }
            }

            is TerminalUpdate.Unchanged,
            null,
            -> {}
        }
    }

    private fun handleResync(
        payload: TerminalResyncPayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        val entity = player.computerAt(payload.position) ?: return
        val machineId = entity.terminalMachineId ?: return
        val update = if (machineId == payload.machineId) entity.terminalChangesSince(payload.revision) else null
        when (update) {
            is TerminalUpdate.Delta -> {
                viewers[player.uuid] = Viewer(player.level().dimension(), entity.blockPos, machineId, update.targetRevision)
                context.reply(TerminalDeltaPayload(entity.blockPos, machineId, update))
            }

            is TerminalUpdate.Unchanged -> {
                viewers[player.uuid] = Viewer(player.level().dimension(), entity.blockPos, machineId, update.revision)
            }

            is TerminalUpdate.Full -> {
                val state = update.state
                viewers[player.uuid] = Viewer(player.level().dimension(), entity.blockPos, machineId, state.revision)
                context.reply(TerminalFullPayload(entity.blockPos, machineId, state, false))
            }

            null -> {
                val state = entity.terminalFullState() ?: return
                viewers[player.uuid] = Viewer(player.level().dimension(), entity.blockPos, machineId, state.revision)
                context.reply(TerminalFullPayload(entity.blockPos, machineId, state, false))
            }
        }
    }

    private fun handleClose(
        payload: TerminalClosePayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        val viewer = viewers[player.uuid] ?: return
        if (viewer.position == payload.position && viewer.machineId == payload.machineId) viewers.remove(player.uuid)
    }

    private fun handleKey(
        payload: TerminalKeyPayload,
        context: IPayloadContext,
    ) {
        withInputTarget(payload.position, payload.machineId, context) { entity ->
            entity.submitTerminalKey(payload.key, payload.action, payload.modifiers)
        }
    }

    private fun handleText(
        payload: TerminalTextPayload,
        context: IPayloadContext,
    ) {
        withInputTarget(payload.position, payload.machineId, context) { entity ->
            entity.submitTerminalText(payload.text)
        }
    }

    private fun withInputTarget(
        position: BlockPos,
        machineId: Long,
        context: IPayloadContext,
        input: (ComputerBlockEntity) -> Unit,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        val viewer = viewers[player.uuid] ?: return
        if (viewer.position != position || viewer.machineId != machineId) return
        if (!TerminalInputAdmission.accept(
                player.uuid,
                player
                    .level()
                    .server.tickCount
                    .toLong(),
            )
        ) {
            return
        }
        val entity = player.computerAt(position) ?: return
        if (entity.terminalMachineId != machineId) return
        input(entity)
    }

    private fun ServerPlayer.computerAt(position: BlockPos): ComputerBlockEntity? {
        if (!isValidViewer(level(), position)) return null
        return level().getBlockEntity(position) as? ComputerBlockEntity
    }

    private fun ServerPlayer.isValidViewer(
        level: ServerLevel,
        position: BlockPos,
    ): Boolean =
        this.level() === level &&
            distanceToSqr(position.x + 0.5, position.y + 0.5, position.z + 0.5) <= MAXIMUM_DISTANCE_SQUARED &&
            level.getBlockEntity(position) is ComputerBlockEntity

    private data class Viewer(
        val dimension: ResourceKey<Level>,
        val position: BlockPos,
        val machineId: Long,
        val revision: Long,
    )

    private const val MAXIMUM_DISTANCE_SQUARED = 64.0
}

internal object TerminalInputAdmission {
    private val limiter = TerminalInputRateLimiter(MAXIMUM_INPUT_EVENTS_PER_TICK)

    fun accept(
        player: UUID,
        tick: Long,
    ): Boolean = limiter.accept(player, tick)

    private const val MAXIMUM_INPUT_EVENTS_PER_TICK = 64
}

internal class TerminalInputRateLimiter(
    private val maximumEventsPerTick: Int,
) {
    init {
        require(maximumEventsPerTick > 0) { "maximum input events per tick must be positive" }
    }

    private val windows = WeakHashMap<UUID, Window>()

    fun accept(
        player: UUID,
        tick: Long,
    ): Boolean {
        val previous = windows[player]
        if (previous == null || previous.tick != tick) {
            windows[player] = Window(tick, 1)
            return true
        }
        if (previous.events >= maximumEventsPerTick) return false
        windows[player] = previous.copy(events = previous.events + 1)
        return true
    }

    private data class Window(
        val tick: Long,
        val events: Int,
    )
}
