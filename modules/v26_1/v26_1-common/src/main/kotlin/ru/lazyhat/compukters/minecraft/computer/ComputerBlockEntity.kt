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
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate

open class ComputerBlockEntity internal constructor(
    type: BlockEntityType<*>,
    position: BlockPos,
    blockState: BlockState,
    private val carrierFactory: ComputerCarrierFactory,
    private val storage: InstalledProgramStorage,
    private val bootImageSource: () -> ByteArray,
    private val identity: ComputerIdentityStorage = ComputerIdentityStorage(),
    private val filesystemContextSource: ComputerFileSystemContextSource? = null,
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
        SystemProgramImage::shell,
    )

    constructor(
        type: BlockEntityType<*>,
        position: BlockPos,
        blockState: BlockState,
        filesystemContextSource: ComputerFileSystemContextSource,
    ) : this(
        type,
        position,
        blockState,
        RuntimeComputerCarrierFactory,
        InstalledProgramStorage(),
        SystemProgramImage::shell,
        filesystemContextSource = filesystemContextSource,
    )

    private var carrier: ComputerCarrier? = null
    private var filesystemLease: ComputerFileSystemLease? = null
    private var lastMachineId = 0L

    var terminalMachineId: Long? = null
        private set

    var runtimeState: ProgramComputerState = neverStarted()
        private set

    fun installArtifact(artifact: ByteArray) {
        storage.install(artifact)
        setChanged()
    }

    fun removeArtifact() {
        if (!storage.clear()) return
        setChanged()
    }

    fun installedArtifact(): ByteArray? = storage.artifact()

    fun computerId() = identity.id()

    fun terminalFullState(): TerminalState? = carrier?.terminalFullState()

    fun prepareTerminal(): TerminalState? {
        val current = carrier ?: createCarrier().also { carrier = it }
        if (current.state == neverStarted()) runtimeState = current.turnOn()
        return current.terminalFullState()
    }

    fun terminalChangesSince(revision: Long): TerminalUpdate? = carrier?.terminalChangesSince(revision)

    fun submitTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier> = emptySet(),
    ): Boolean = carrier?.sendTerminalKey(key, action, modifiers) == true

    fun submitTerminalText(value: String): Boolean = carrier?.sendTerminalText(value) == true

    internal fun serverTick() {
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

    internal fun destroyFileSystem() {
        val serverLevel = level as? ServerLevel ?: return
        val source = filesystemContextSource ?: return
        closeCarrier()
        source.tombstone(serverLevel, identity.id())
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        closeCarrier()
        val payload = input.child(InstalledProgramStorage.ROOT_KEY).orElse(null)
        storage.loadPayload(payload)
        identity.load(payload)
        runtimeState = neverStarted()
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        val payload = output.child(InstalledProgramStorage.ROOT_KEY)
        storage.savePayload(payload)
        identity.save(payload)
    }

    private fun createCarrier(): ComputerCarrier {
        val deviceId = blockPos.hashCode()
        terminalMachineId = nextMachineId()
        val filesystem =
            (level as? ServerLevel)?.let { serverLevel ->
                filesystemContextSource?.create(serverLevel, identity.id(), SystemRomImage.packaged())
            }
        val created =
            carrierFactory.create(
                deviceId = deviceId,
                imageSource = { bootImageSource() },
                stateSink = { _, state -> runtimeState = state },
                filesystem = filesystem,
            )
        filesystemLease = filesystem?.attach(created::filesystemGeneration, ::drainCarrier)
        return created
    }

    private fun closeCarrier() {
        val current = carrier
        val generation = current?.filesystemGeneration()
        current?.close()
        carrier = null
        terminalMachineId = null
        filesystemLease?.release(generation)
        filesystemLease = null
    }

    private fun drainCarrier(): Long? {
        val current = carrier
        val generation = current?.filesystemGeneration()
        current?.close()
        carrier = null
        terminalMachineId = null
        filesystemLease = null
        return generation
    }

    private fun nextMachineId(): Long {
        lastMachineId = Math.incrementExact(lastMachineId)
        return lastMachineId
    }

    private fun ProgramComputerState.isPoweredOn(): Boolean =
        this == ProgramComputerState.Running || this == ProgramComputerState.WaitingForInput

    private companion object {
        fun neverStarted(): ProgramComputerState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)
    }
}
