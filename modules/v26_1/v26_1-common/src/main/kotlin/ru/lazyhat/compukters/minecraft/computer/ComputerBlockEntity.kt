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
        filesystemContextSource = filesystemContextSource,
    )

    private var carrier: ComputerCarrier? = null
    private var filesystemLease: ComputerFileSystemLease? = null
    private var lastMachineId = 0L

    var terminalMachineId: Long? = null
        private set

    var runtimeState: ProgramComputerState = neverStarted()
        private set

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
        val payload = input.child(ROOT_KEY).orElse(null)
        identity.load(payload)
        runtimeState = neverStarted()
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        val payload = output.child(ROOT_KEY)
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
        this == ProgramComputerState.Running ||
            this == ProgramComputerState.WaitingForInput ||
            this == ProgramComputerState.WaitingForCompiler

    private companion object {
        const val ROOT_KEY = "compukters"

        fun neverStarted(): ProgramComputerState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)
    }
}
