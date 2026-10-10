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
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.core.device.runtime.program.ProgramDeploymentCandidate
import ru.lazyhat.compukters.core.device.runtime.program.ProgramResourceSnapshot
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneHostPort
import ru.lazyhat.compukters.core.device.runtime.program.SoundCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.SoundHostPort
import ru.lazyhat.compukters.core.device.runtime.program.SoundRequest
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import ru.lazyhat.compukters.minecraft.network.ComputerCableMessages
import java.util.concurrent.CompletableFuture
import kotlin.math.pow

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
    private var resumeExpected = false
    private var powerOffReason: String? = null

    @Volatile
    internal var peripheralCheckpointPending: Boolean = false
        private set

    internal fun peripheralResourcesAvailable(): Boolean = runtimeState.isPoweredOn() || carrier?.restoring == true

    private var filesystemLease: ComputerFileSystemLease? = null
    private var committedRedstoneOutput = 0
    private var sampledRedstoneInputs = IntArray(RedstoneWire.SIDE_COUNT)
    private var dirtyRedstoneInputs = RedstoneWire.ALL_SIDES_MASK
    private var pendingInitialRedstoneInput: Int? = null
    private var carrierRetryTicks = 0
    private val soundCooldown = SoundCooldown(SOUND_COOLDOWN_TICKS)
    private val redstoneHostPort = RedstoneHostPort(::commitRedstoneOutput)
    private val soundHostPort = SoundHostPort(::emitSounds)

    var terminalMachineId: Long? = null
        private set

    var runtimeState: ProgramComputerState = neverStarted()
        private set

    fun computerId() = identity.id()

    fun terminalFullStateAsync(): CompletableFuture<TerminalState?> =
        carrier?.terminalFullStateAsync() ?: CompletableFuture.completedFuture(null)

    fun prepareTerminalAsync(): CompletableFuture<TerminalState?> {
        if (powerOffReason != null) return CompletableFuture.completedFuture(null)
        val current = carrier ?: attachCarrier() ?: return CompletableFuture.completedFuture(null)
        if (current.state == neverStarted()) runtimeState = current.restore(resumeExpected)
        return current.terminalFullStateAsync()
    }

    fun terminalChangesSinceAsync(revision: Long): CompletableFuture<TerminalUpdate?> =
        carrier?.terminalChangesSinceAsync(revision) ?: CompletableFuture.completedFuture(null)

    fun resourceSnapshotAsync(): CompletableFuture<ProgramResourceSnapshot?> =
        carrier?.resourceSnapshotAsync() ?: CompletableFuture.completedFuture(null)

    fun submitTerminalKeyAsync(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier> = emptySet(),
    ): CompletableFuture<Boolean> = carrier?.sendTerminalKeyAsync(key, action, modifiers) ?: CompletableFuture.completedFuture(false)

    fun submitTerminalTextAsync(value: String): CompletableFuture<Boolean> =
        carrier?.sendTerminalTextAsync(value) ?: CompletableFuture.completedFuture(false)

    fun verifyForDeployAsync(artifact: ByteArray) =
        carrier?.verifyForDeployAsync(artifact) ?: CompletableFuture.completedFuture<ProgramDeploymentCandidate?>(null)

    fun executableRevisionAsync(path: String) =
        carrier?.executableRevisionAsync(path) ?: CompletableFuture.completedFuture<VmExecutableRevision?>(null)

    fun fileStatAsync(path: ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath) =
        carrier?.fileStatAsync(path) ?: CompletableFuture.completedFuture<ru.lazyhat.compukters.lang.runtime.fs.VmFileStat?>(null)

    fun fileListAsync(
        path: ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ) = carrier?.fileListAsync(path, startAfter, maximumEntries)
        ?: CompletableFuture.completedFuture<ru.lazyhat.compukters.lang.runtime.fs.VmDirectoryListing?>(null)

    fun fileReadAsync(
        path: ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ) = carrier?.fileReadAsync(path, offset, maximumBytes, expectedGeneration)
        ?: CompletableFuture.completedFuture<ru.lazyhat.compukters.lang.runtime.fs.VmFileChunk?>(null)

    fun deployAsync(
        path: String,
        expected: VmExecutableRevision,
        candidate: ProgramDeploymentCandidate,
    ) = carrier?.deployAsync(path, expected, candidate) ?: CompletableFuture.completedFuture<VmExecutableRevision?>(null)

    fun submitCanonicalLineAsync(line: CharArray): CompletableFuture<Boolean> =
        carrier?.submitCanonicalLineAsync(line) ?: CompletableFuture.completedFuture(false)

    internal fun redstoneOutput(direction: Direction): Int =
        redstoneOutputField(committedRedstoneOutput, localSide(blockState.getValue(ComputerBlock.FACING), direction))

    internal fun markRedstoneInputDirty(direction: Direction? = null) {
        dirtyRedstoneInputs =
            if (direction == null) {
                RedstoneWire.ALL_SIDES_MASK
            } else {
                dirtyRedstoneInputs or (1 shl localSide(blockState.getValue(ComputerBlock.FACING), direction).ordinal)
            }
    }

    internal fun sampleRedstoneInputs(sample: (Direction) -> Int): Int? {
        val dirty = dirtyRedstoneInputs
        var changed = 0
        LocalRedstoneSide.entries.forEach { side ->
            val bit = 1 shl side.ordinal
            if (dirty and bit == 0) return@forEach
            val level = sample(worldDirection(blockState.getValue(ComputerBlock.FACING), side))
            require(level in 0..RedstoneWire.SIGNAL_MASK) { "vanilla redstone level is out of range" }
            if (sampledRedstoneInputs[side.ordinal] != level) {
                sampledRedstoneInputs[side.ordinal] = level
                changed = changed or bit
            }
        }
        dirtyRedstoneInputs = 0
        return changed.takeIf { it != 0 }?.let { RedstoneWire.packInput(it, sampledRedstoneInputs) }
    }

    internal fun serverTick() {
        if (powerOffReason != null) return
        if (carrier == null && carrierRetryTicks > 0) {
            carrierRetryTicks--
            return
        }
        val current = carrier ?: attachCarrier() ?: return
        if (current.state == neverStarted()) {
            runtimeState = current.restore(resumeExpected)
        }
        val serverLevel = level as? ServerLevel
        val redstoneInput =
            serverLevel?.let {
                sampleRedstoneInputs { direction ->
                    serverLevel.getSignal(blockPos.relative(direction), direction)
                }
            }
        if (runtimeState.isPoweredOn()) {
            // A sampled startup signal may predate the asynchronous actor's boot completion.
            // Replay its latest complete snapshot once, then resume ordinary changed-side packets.
            val initialInput = pendingInitialRedstoneInput
            val input =
                if (initialInput != null) {
                    RedstoneWire.withAllInputSidesChanged(redstoneInput ?: initialInput)
                } else {
                    redstoneInput
                }
            pendingInitialRedstoneInput = null
            runtimeState = current.serverTick(serverLevel?.server?.tickCount?.toLong() ?: 0L, input)
        } else if (redstoneInput != null) {
            pendingInitialRedstoneInput = redstoneInput
        }
    }

    override fun setRemoved() {
        closeCarrier()
        super.setRemoved()
    }

    /** Discards execution state while retaining the computer identity and /home files. */
    fun reboot(): ProgramComputerState {
        powerOffReason = null
        resumeExpected = false
        setChanged()
        val current = carrier ?: attachCarrier() ?: return runtimeState
        runtimeState = current.reboot()
        return runtimeState
    }

    internal fun destroyFileSystem() {
        ru.lazyhat.compukters.minecraft.peripheral.PeripheralNetworkAccess
            .detach(this)
        val serverLevel = level as? ServerLevel ?: return
        val source = filesystemContextSource ?: return
        closeCarrier(hibernate = false)
        source.tombstone(serverLevel, identity.id())
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        closeCarrier()
        val payload = input.child(ROOT_KEY).orElse(null)
        identity.load(payload)
        committedRedstoneOutput =
            payload?.getInt(REDSTONE_OUTPUT_KEY)?.orElse(0)?.let { packed ->
                runCatching { RedstoneWire.requireOutputRegister(packed) }.getOrDefault(0)
            } ?: 0
        resumeExpected = payload?.getBooleanOr(RESUME_EXPECTED_KEY, false) ?: false
        powerOffReason = payload?.getStringOr(POWER_OFF_KEY, "")?.takeIf { it == "halted" || it == "shutdown" }
        sampledRedstoneInputs = IntArray(RedstoneWire.SIDE_COUNT)
        pendingInitialRedstoneInput = null
        dirtyRedstoneInputs = RedstoneWire.ALL_SIDES_MASK
        runtimeState =
            when (powerOffReason) {
                "halted" -> ProgramComputerState.PoweredOff(ProgramComputerStopReason.Halted(null))
                "shutdown" -> ProgramComputerState.PoweredOff(ProgramComputerStopReason.Shutdown)
                else -> neverStarted()
            }
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        val payload = output.child(ROOT_KEY)
        identity.save(payload)
        payload.putInt(REDSTONE_OUTPUT_KEY, committedRedstoneOutput)
        payload.putBoolean(RESUME_EXPECTED_KEY, resumeExpected)
        powerOffReason?.let { payload.putString(POWER_OFF_KEY, it) }
    }

    private fun createCarrier(): ComputerCarrier? {
        val deviceId = blockPos.hashCode()
        val machineId = ComputerMachineEpochs.next()
        val filesystem =
            (level as? ServerLevel)?.let { serverLevel ->
                filesystemContextSource?.create(serverLevel, identity.id(), SystemRomImage.packaged())
            }
        val created =
            carrierFactory.create(
                deviceId = deviceId,
                machineEpoch = machineId,
                stateSink = { _, state ->
                    if (!state.isPoweredOn() && runtimeState.isPoweredOn()) {
                        level?.let { ComputerCableMessages.changed(it, blockPos, false) }
                    }
                    runtimeState = state
                    if (state.isPoweredOn()) {
                        powerOffReason = null
                        resumeExpected = true
                        setChanged()
                    } else if (state is ProgramComputerState.PoweredOff &&
                        (state.reason is ProgramComputerStopReason.Halted || state.reason == ProgramComputerStopReason.Shutdown)
                    ) {
                        powerOffReason = if (state.reason == ProgramComputerStopReason.Shutdown) "shutdown" else "halted"
                        resumeExpected = false
                        setChanged()
                    }
                },
                filesystem = filesystem,
                redstoneHostPort = redstoneHostPort,
                soundHostPort = soundHostPort,
                addonHost = (level as? ServerLevel)?.let { ComputerAddonHosts.create(it, blockPos, blockState) },
                initialRedstoneOutput = committedRedstoneOutput,
            )
        if (created == null) return null
        terminalMachineId = machineId
        filesystemLease = filesystem?.attach(created::filesystemGeneration, ::drainCarrier)
        return created
    }

    private fun attachCarrier(): ComputerCarrier? {
        if (!filesystemAvailable()) {
            scheduleCarrierRetry()
            return null
        }
        return createCarrier()?.also {
            carrier = it
            carrierRetryTicks = 0
        } ?: run {
            scheduleCarrierRetry()
            null
        }
    }

    private fun scheduleCarrierRetry() {
        // A moving/reloading carrier may briefly wait for its predecessor's close barrier.
        // Retry saved execution next tick rather than adding a full second at ordinary 20 TPS.
        carrierRetryTicks = if (resumeExpected) 0 else CARRIER_RETRY_INTERVAL_TICKS - 1
    }

    private fun filesystemAvailable(): Boolean =
        (level as? ServerLevel)?.let { filesystemContextSource?.available(it, identity.id()) } != false

    private fun commitRedstoneOutput(packed: Int): RedstoneCommitResult {
        val serverLevel = requireNotNull(level as? ServerLevel) { "redstone output requires a server level" }
        check(serverLevel.server.isSameThread) { "redstone output must be committed on the server thread" }
        val validated = RedstoneWire.requireOutputRegister(packed)
        if (validated == committedRedstoneOutput) return RedstoneCommitResult.Committed
        val previous = committedRedstoneOutput
        committedRedstoneOutput = validated
        setChanged()
        LocalRedstoneSide.entries.forEach { side ->
            if (redstoneOutputField(previous, side) != redstoneOutputField(validated, side)) {
                val direction = worldDirection(blockState.getValue(ComputerBlock.FACING), side)
                serverLevel.neighborChanged(blockPos.relative(direction), blockState.block, null)
            }
        }
        return RedstoneCommitResult.Committed
    }

    private fun emitSounds(requests: List<SoundRequest>): SoundCommitResult {
        val serverLevel = requireNotNull(level as? ServerLevel) { "sound emission requires a server level" }
        check(serverLevel.server.isSameThread) { "sound emission must run on the server thread" }
        val tick = serverLevel.server.tickCount.toLong()
        val admissions =
            requests.map { request ->
                if (!soundCooldown.isReady(tick) || !ComputerSoundBudget.tryAcquire(serverLevel, tick)) {
                    false
                } else {
                    serverLevel.playSound(
                        null,
                        blockPos,
                        SoundEvents.NOTE_BLOCK_PLING.value(),
                        SoundSource.BLOCKS,
                        request.volume / 100.0f,
                        2.0.pow((request.note - 12) / 12.0).toFloat(),
                    )
                    soundCooldown.markEmitted(tick)
                    true
                }
            }
        return SoundCommitResult.Completed(admissions)
    }

    private fun closeCarrier(hibernate: Boolean = true) {
        val closed = closeRuntime(carrier, hibernate)
        carrier = null
        carrierRetryTicks = 0
        level?.let { ComputerCableMessages.changed(it, blockPos, false) }
        terminalMachineId = null
        filesystemLease?.release(closed)?.whenComplete { _, _ -> }
        filesystemLease = null
    }

    private fun drainCarrier(): CompletableFuture<Long?> {
        val closed = closeRuntime(carrier, hibernate = true)
        carrier = null
        carrierRetryTicks = 0
        level?.let { ComputerCableMessages.changed(it, blockPos, false) }
        terminalMachineId = null
        filesystemLease = null
        return closed
    }

    private fun closeRuntime(
        current: ComputerCarrier?,
        hibernate: Boolean,
    ): CompletableFuture<Long?> {
        if (current == null) return CompletableFuture.completedFuture(null)
        peripheralCheckpointPending = hibernate
        val closed: CompletableFuture<Long?> =
            try {
                if (hibernate) current.hibernateAsync((level as? ServerLevel)?.server?.tickCount?.toLong() ?: 0L) else current.closeAsync()
            } catch (failure: Exception) {
                current.closeAsync().handle { _, closing ->
                    if (closing != null) failure.addSuppressed(closing)
                    throw java.util.concurrent.CompletionException(failure)
                }
            }
        return closed.whenComplete { _, _ -> peripheralCheckpointPending = false }
    }

    private fun ProgramComputerState.isPoweredOn(): Boolean =
        this == ProgramComputerState.Running ||
            this == ProgramComputerState.WaitingForInput ||
            this == ProgramComputerState.WaitingForCompiler

    private companion object {
        const val ROOT_KEY = "compukters"
        const val POWER_OFF_KEY = "powerOffReason"
        const val RESUME_EXPECTED_KEY = "resumeExpected"
        const val REDSTONE_OUTPUT_KEY = "redstoneOutput"
        const val CARRIER_RETRY_INTERVAL_TICKS = 20
        const val SOUND_COOLDOWN_TICKS = 4

        fun neverStarted(): ProgramComputerState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)
    }
}
