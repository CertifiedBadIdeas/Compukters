/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.impl.terminal

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import java.nio.file.Path
import kotlin.io.path.readText

object TerminalNetwork {
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToClient(TerminalSnapshotPayload.TYPE, TerminalSnapshotPayload.STREAM_CODEC)
        registrar.playToServer(TerminalRefreshPayload.TYPE, TerminalRefreshPayload.STREAM_CODEC, ::handleRefresh)
        registrar.playToServer(TerminalInputPayload.TYPE, TerminalInputPayload.STREAM_CODEC, ::handleInput)
    }

    fun open(
        player: ServerPlayer,
        entity: ComputerBlockEntity,
    ) {
        installDevFixtureIfAvailable(entity)
        PacketDistributor.sendToPlayer(player, entity.snapshotPayload(openScreen = true))
    }

    private fun handleRefresh(
        payload: TerminalRefreshPayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        val entity = player.computerAt(payload.position) ?: return
        context.reply(entity.snapshotPayload(openScreen = false))
    }

    private fun handleInput(
        payload: TerminalInputPayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        if (payload.line.length > TerminalInputPayload.MAXIMUM_INPUT_CODE_UNITS) return
        val entity = player.computerAt(payload.position) ?: return
        entity.submitTerminalLine(payload.line)
        context.reply(entity.snapshotPayload(openScreen = false))
    }

    private fun ServerPlayer.computerAt(position: BlockPos): ComputerBlockEntity? {
        if (distanceToSqr(position.x + 0.5, position.y + 0.5, position.z + 0.5) > MAXIMUM_DISTANCE_SQUARED) return null
        return level().getBlockEntity(position) as? ComputerBlockEntity
    }

    private fun ComputerBlockEntity.snapshotPayload(openScreen: Boolean): TerminalSnapshotPayload {
        val snapshot = terminalSnapshot()
        return TerminalSnapshotPayload(
            position = blockPos,
            text = snapshot.text,
            status = runtimeState.displayName(installedArtifact() != null),
            waitingForInput = runtimeState == ProgramComputerState.WaitingForInput,
            openScreen = openScreen,
        )
    }

    private fun ProgramComputerState.displayName(hasArtifact: Boolean): String =
        when (this) {
            ProgramComputerState.Running -> "Running"
            ProgramComputerState.WaitingForInput -> "Waiting for input"
            ProgramComputerState.Closed -> "Closed"
            is ProgramComputerState.PoweredOff -> displayName(hasArtifact)
        }.take(MAXIMUM_STATUS_CODE_UNITS)

    private fun ProgramComputerState.PoweredOff.displayName(hasArtifact: Boolean): String {
        val stopReason = reason
        return when (stopReason) {
            ProgramComputerStopReason.NeverStarted -> if (hasArtifact) "Starting" else "No program installed"
            ProgramComputerStopReason.Shutdown -> "Stopped"
            is ProgramComputerStopReason.Halted -> "Halted: ${stopReason.value ?: "Unit"}"
            is ProgramComputerStopReason.Failure -> "Failure: ${stopReason.failure}"
        }
    }

    private fun installDevFixtureIfAvailable(entity: ComputerBlockEntity) {
        if (FMLEnvironment.isProduction() || entity.installedArtifact() != null) return
        val fixturePath = System.getProperty(DEV_FIXTURE_PROPERTY) ?: return
        runCatching {
            val encoded = Path.of(fixturePath).readText().trim()
            require(encoded.length % 2 == 0) { "fixture contains incomplete hexadecimal byte" }
            ByteArray(encoded.length / 2) { index ->
                encoded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.onSuccess(entity::installArtifact)
            .onFailure { error -> LOGGER.error(error) { "Could not install the terminal dev fixture" } }
    }

    private const val DEV_FIXTURE_PROPERTY = "compukter.vm.devTerminalFixture"
    private const val MAXIMUM_DISTANCE_SQUARED = 64.0
    private const val MAXIMUM_STATUS_CODE_UNITS = 512
}
