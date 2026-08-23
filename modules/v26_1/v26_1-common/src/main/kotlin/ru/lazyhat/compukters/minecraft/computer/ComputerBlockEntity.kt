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

package ru.lazyhat.compukters.minecraft.computer

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason

open class ComputerBlockEntity internal constructor(
    type: BlockEntityType<*>,
    position: BlockPos,
    blockState: BlockState,
    private val carrierFactory: ComputerCarrierFactory,
    private val storage: InstalledProgramStorage,
    private val transcript: TerminalTranscript,
) : BlockEntity(type, position, blockState) {
    constructor(
        type: BlockEntityType<*>,
        position: BlockPos,
        blockState: BlockState,
    ) : this(
        type,
        position,
        blockState,
        RuntimeComputerCarrierFactory,
        InstalledProgramStorage(),
        TerminalTranscript(),
    )

    private var carrier: ComputerCarrier? = null

    var runtimeState: ProgramComputerState = neverStarted()
        private set

    fun installArtifact(artifact: ByteArray) {
        storage.install(artifact)
        transcript.clear()
        setChanged()
        carrier?.let { current ->
            runtimeState = current.reboot()
        }
    }

    fun removeArtifact() {
        if (!storage.clear()) return
        carrier?.let { current ->
            current.shutdown()
            runtimeState = current.state
        }
        setChanged()
    }

    fun installedArtifact(): ByteArray? = storage.artifact()

    fun terminalSnapshot(): TerminalTranscript.Snapshot = transcript.snapshot()

    internal fun serverTick() {
        if (!storage.hasArtifact()) return
        val current = carrier ?: createCarrier().also { carrier = it }
        if (current.state == neverStarted()) {
            runtimeState = current.turnOn()
        }
        if (runtimeState.isPoweredOn()) {
            runtimeState = current.serverTick()
        }
    }

    override fun setRemoved() {
        closeCarrier()
        super.setRemoved()
    }

    override fun loadAdditional(
        tag: CompoundTag,
        registries: HolderLookup.Provider,
    ) {
        super.loadAdditional(tag, registries)
        closeCarrier()
        storage.load(tag)
        transcript.clear()
        runtimeState = neverStarted()
    }

    override fun saveAdditional(
        tag: CompoundTag,
        registries: HolderLookup.Provider,
    ) {
        super.saveAdditional(tag, registries)
        storage.save(tag)
    }

    private fun createCarrier(): ComputerCarrier {
        val deviceId = blockPos.hashCode()
        return carrierFactory.create(
            deviceId = deviceId,
            imageSource = { storage.artifact() },
            terminalSink = { _, text -> transcript.append(text) },
            stateSink = { _, state -> runtimeState = state },
        )
    }

    private fun closeCarrier() {
        carrier?.close()
        carrier = null
    }

    private fun ProgramComputerState.isPoweredOn(): Boolean =
        this == ProgramComputerState.Running || this == ProgramComputerState.WaitingForInput

    private companion object {
        fun neverStarted(): ProgramComputerState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)
    }
}
