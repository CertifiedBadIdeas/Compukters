/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.lang.runtime.vm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import ru.lazyhat.compukters.lang.runtime.fs.VmFileStat
import ru.lazyhat.compukters.lang.runtime.fs.VmDirectoryListing
import ru.lazyhat.compukters.lang.runtime.fs.VmFileChunk
import ru.lazyhat.compukters.lang.runtime.fs.VmFileSystemReadFailure

sealed interface ExperimentalFleetInputResult {
    data object Accepted : ExperimentalFleetInputResult
    data object TerminalRejected : ExperimentalFleetInputResult
    data class CanonicalRejected(val failure: VmCanonicalLineFailure) : ExperimentalFleetInputResult
}

sealed interface ExperimentalFleetDeploymentResult {
    data class Prepared(val candidate: Long) : ExperimentalFleetDeploymentResult
    data class Revision(val revision: VmExecutableRevision) : ExperimentalFleetDeploymentResult
    data class Installed(val revision: VmExecutableRevision.Present) : ExperimentalFleetDeploymentResult
    data object Discarded : ExperimentalFleetDeploymentResult
    data class Failed(val failure: ExperimentalFleetDeploymentFailure) : ExperimentalFleetDeploymentResult
}

enum class ExperimentalFleetDeploymentFailure {
    VERIFICATION, ADMISSION, INVALID_CANDIDATE, CAPACITY, WRONG_MACHINE, PROFILE_CHANGED,
    INVALID_PATH, NOT_FOUND, NOT_DIRECTORY, IS_DIRECTORY, READ_ONLY, PERMISSION,
    LIMIT, BUSY, STORAGE, CLOSED, FILESYSTEM,
}

data class ExperimentalFleetTimer(
    val identity: VmHostRequestIdentity,
    val scope: Long,
    val duration: Int,
    val remaining: Long,
) {
    init {
        require(identity.taskId > 0 && identity.requestId > 0 && scope >= 0 && duration >= 0)
        require(remaining in 0..maxOf(duration.toLong(), 1))
    }
}

/** Events from the opt-in native fleet transport. Machine identities are local to a fleet. */
sealed interface ExperimentalFleetEvent {
    data class Deployment(val machine: Long, val request: Long, val result: ExperimentalFleetDeploymentResult) : ExperimentalFleetEvent
    data class InputResult(val machine: Long, val request: Long, val result: ExperimentalFleetInputResult) : ExperimentalFleetEvent
    data class FileStat(val machine: Long, val request: Long, val stat: VmFileStat) : ExperimentalFleetEvent
    data class FileList(val machine: Long, val request: Long, val listing: VmDirectoryListing) : ExperimentalFleetEvent
    data class FileRead(val machine: Long, val request: Long, val chunk: VmFileChunk) : ExperimentalFleetEvent
    data class FileFailure(val machine: Long, val request: Long, val failure: VmFileSystemReadFailure) : ExperimentalFleetEvent
    data class Resources(val machine: Long, val request: Long, val snapshot: VmResourceSnapshot) : ExperimentalFleetEvent
    data class FilesystemGeneration(val machine: Long, val request: Long, val generation: Long) : ExperimentalFleetEvent
    data class Terminal(val machine: Long, val request: Long, val update: TerminalUpdate) : ExperimentalFleetEvent
    /** Retired instructions accumulated within the tick; later reports may increase this value. */
    data class Quiescent(val machine: Long, val tick: Long, val retired: Long) : ExperimentalFleetEvent
    data class Compilation(val machine: Long, val tick: Long, val request: VmCompilationRequest) : ExperimentalFleetEvent
    data class Outcome(val machine: Long, val tick: Long, val outcome: VmOutcome) : ExperimentalFleetEvent
    data class Inspection(val machine: Long, val tick: Long, val request: VmHostRequest) : ExperimentalFleetEvent
    data class Stopped(val machine: Long, val pending: List<VmHostRequestIdentity>) : ExperimentalFleetEvent
    data class ScopeStopped(val machine: Long, val scope: Long, val pending: List<VmHostRequestIdentity>) : ExperimentalFleetEvent
    data class Detached(val machine: Long, val timers: List<ExperimentalFleetTimer> = emptyList()) : ExperimentalFleetEvent
    data class DetachRejected(val machine: Long) : ExperimentalFleetEvent
    data class Error(val machine: Long?, val message: String) : ExperimentalFleetEvent
}

/** Checks lengths and identities before an event is handed to the world-side dispatcher. */
object ExperimentalFleetEventWire {
    fun decode(bytes: ByteArray): ExperimentalFleetEvent {
        require(bytes.isNotEmpty() && bytes.size <= 1_048_576) { "invalid fleet event size" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun long(): Long = input.long.also { require(it >= 0) { "invalid fleet identity or tick" } }
        fun identities(): List<VmHostRequestIdentity> {
            val count = input.int
            require(count >= 0 && count <= input.remaining() / 12 && input.remaining() == count * 12) { "invalid fleet cancellation length" }
            val items = List(count) { VmHostRequestIdentity(input.int, input.long) }
            require(items.toSet().size == items.size) { "duplicate fleet cancellation identity" }
            return items
        }
        return when (input.get().toInt() and 0xff) {
            17 -> {
                val machine = long()
                val request = long()
                val kind = input.get().toInt() and 0xff
                val status = input.int
                val value = long()
                require(!input.hasRemaining()) { "trailing fleet deployment bytes" }
                val result = when (kind) {
                    0 -> { require(status == 0 && value > 0); ExperimentalFleetDeploymentResult.Prepared(value) }
                    1 -> { require(status == 0); ExperimentalFleetDeploymentResult.Revision(if (value == 0L) VmExecutableRevision.Absent else VmExecutableRevision.Present(value)) }
                    2 -> { require(status == 0 && value > 0); ExperimentalFleetDeploymentResult.Installed(VmExecutableRevision.Present(value)) }
                    3 -> { require(status == 0 && value == 0L); ExperimentalFleetDeploymentResult.Discarded }
                    4 -> {
                        require(value == 0L)
                        val failure = when (status) {
                            2 -> ExperimentalFleetDeploymentFailure.VERIFICATION
                            3 -> ExperimentalFleetDeploymentFailure.ADMISSION
                            6 -> ExperimentalFleetDeploymentFailure.INVALID_CANDIDATE
                            7 -> ExperimentalFleetDeploymentFailure.CAPACITY
                            20 -> ExperimentalFleetDeploymentFailure.WRONG_MACHINE
                            21 -> ExperimentalFleetDeploymentFailure.PROFILE_CHANGED
                            30 -> ExperimentalFleetDeploymentFailure.INVALID_PATH
                            31 -> ExperimentalFleetDeploymentFailure.NOT_FOUND
                            32 -> ExperimentalFleetDeploymentFailure.NOT_DIRECTORY
                            33 -> ExperimentalFleetDeploymentFailure.IS_DIRECTORY
                            34 -> ExperimentalFleetDeploymentFailure.READ_ONLY
                            35 -> ExperimentalFleetDeploymentFailure.PERMISSION
                            37 -> ExperimentalFleetDeploymentFailure.LIMIT
                            38 -> ExperimentalFleetDeploymentFailure.BUSY
                            39 -> ExperimentalFleetDeploymentFailure.STORAGE
                            40 -> ExperimentalFleetDeploymentFailure.CLOSED
                            41 -> ExperimentalFleetDeploymentFailure.FILESYSTEM
                            else -> throw IllegalArgumentException("invalid fleet deployment failure")
                        }
                        ExperimentalFleetDeploymentResult.Failed(failure)
                    }
                    else -> throw IllegalArgumentException("invalid fleet deployment result")
                }
                ExperimentalFleetEvent.Deployment(machine, request, result)
            }
            14 -> {
                val machine = long()
                val request = long()
                val result = when (input.int) {
                    0 -> ExperimentalFleetInputResult.Accepted
                    1 -> ExperimentalFleetInputResult.TerminalRejected
                    23 -> ExperimentalFleetInputResult.CanonicalRejected(VmCanonicalLineFailure.NO_PENDING_READ)
                    24 -> ExperimentalFleetInputResult.CanonicalRejected(VmCanonicalLineFailure.INPUT_BUSY)
                    25 -> ExperimentalFleetInputResult.CanonicalRejected(VmCanonicalLineFailure.PARTIAL_INPUT)
                    26 -> ExperimentalFleetInputResult.CanonicalRejected(VmCanonicalLineFailure.UNSUPPORTED_CODE_UNIT)
                    27 -> ExperimentalFleetInputResult.CanonicalRejected(VmCanonicalLineFailure.LINE_TOO_LONG)
                    28 -> ExperimentalFleetInputResult.CanonicalRejected(VmCanonicalLineFailure.TERMINAL)
                    29 -> ExperimentalFleetInputResult.CanonicalRejected(VmCanonicalLineFailure.RESUME)
                    else -> throw IllegalArgumentException("invalid input result status")
                }
                require(!input.hasRemaining()) { "trailing input result bytes" }
                ExperimentalFleetEvent.InputResult(machine, request, result)
            }
            13 -> {
                val machine = long()
                val request = long()
                val kind = input.get().toInt() and 0xff
                val status = input.int
                val body = ByteArray(input.remaining()).also(input::get)
                if (kind == 3) {
                    require(body.isEmpty()) { "trailing filesystem failure bytes" }
                    val failure = when (status) {
                        30 -> VmFileSystemReadFailure.INVALID_PATH
                        31 -> VmFileSystemReadFailure.NOT_FOUND
                        32 -> VmFileSystemReadFailure.NOT_DIRECTORY
                        33 -> VmFileSystemReadFailure.NOT_FILE
                        34, 35 -> VmFileSystemReadFailure.PERMISSION
                        36 -> VmFileSystemReadFailure.STALE_GENERATION
                        37 -> VmFileSystemReadFailure.LIMIT
                        38, 39, 40, 41, 42 -> VmFileSystemReadFailure.STORAGE
                        else -> throw IllegalArgumentException("invalid filesystem failure status")
                    }
                    ExperimentalFleetEvent.FileFailure(machine, request, failure)
                } else {
                    require(status == 0) { "invalid filesystem success status" }
                    val decoder = FileInspectionWireDecoder(body)
                    when (kind) {
                        0 -> ExperimentalFleetEvent.FileStat(machine, request, decoder.stat())
                        1 -> ExperimentalFleetEvent.FileList(machine, request, decoder.listing())
                        2 -> ExperimentalFleetEvent.FileRead(machine, request, decoder.chunk())
                        else -> throw IllegalArgumentException("invalid filesystem result kind")
                    }
                }
            }
            11 -> {
                val machine = long()
                val request = long()
                ExperimentalFleetEvent.Resources(machine, request, ResourceSnapshotWireDecoder(ByteArray(input.remaining()).also(input::get)).snapshot())
            }
            12 -> {
                val result = ExperimentalFleetEvent.FilesystemGeneration(long(), long(), long())
                require(!input.hasRemaining()) { "trailing filesystem generation bytes" }
                result
            }
            10 -> {
                val machine = long()
                val request = long()
                ExperimentalFleetEvent.Terminal(machine, request, TerminalWireDecoder(ByteArray(input.remaining()).also(input::get)).update())
            }
            9 -> {
                val machine = long()
                val tick = long()
                val request = CompilationWireDecoder(ByteArray(input.remaining()).also(input::get)).request()
                ExperimentalFleetEvent.Compilation(machine, tick, request)
            }
            8 -> {
                val result = ExperimentalFleetEvent.Quiescent(long(), long(), long())
                require(!input.hasRemaining()) { "trailing fleet accounting bytes" }
                result
            }
            0, 1 -> {
                val tag = bytes[0].toInt()
                val machine = long()
                val tick = long()
                val outcome = VmOutcomeWire.decode(ByteArray(input.remaining()).also(input::get))
                if (tag == 0) ExperimentalFleetEvent.Outcome(machine, tick, outcome)
                else {
                    require(outcome is VmOutcome.HostRequestBatch && outcome.requests.size == 1) { "invalid fleet inspection" }
                    ExperimentalFleetEvent.Inspection(machine, tick, outcome.requests.single())
                }
            }
            2 -> ExperimentalFleetEvent.Stopped(long(), identities())
            4 -> ExperimentalFleetEvent.ScopeStopped(long(), long(), identities())
            16 -> {
                val machine = long()
                val count = input.int
                require(count in 1..256 && input.remaining() == count * 32) { "invalid fleet timer length" }
                val timers = List(count) {
                    ExperimentalFleetTimer(VmHostRequestIdentity(input.int, input.long), long(), input.int, long())
                }
                require(timers.map { it.identity }.toSet().size == timers.size) { "duplicate fleet timer" }
                ExperimentalFleetEvent.Detached(machine, timers)
            }
            6, 7 -> {
                val machine = long()
                require(!input.hasRemaining()) { "trailing fleet detach bytes" }
                if (bytes[0].toInt() == 6) ExperimentalFleetEvent.Detached(machine) else ExperimentalFleetEvent.DetachRejected(machine)
            }
            3, 5 -> {
                val machine = if (bytes[0].toInt() == 3) long() else null
                val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                ExperimentalFleetEvent.Error(machine, decoder.decode(input).toString())
            }
            else -> throw IllegalArgumentException("invalid fleet event tag")
        }
    }
}

/** The transfer operations exchange opaque handles; the native machine has exactly one owner. */
interface ExperimentalFleetSessionOwner {
    fun adopt(bridge: LowLevelVmBridge, session: Long): Long
    fun adoptWithTimers(bridge: LowLevelVmBridge, session: Long, tick: Long, timers: List<ExperimentalFleetTimer>): Long
    fun recover(machine: Long): ExperimentalRestoredSession
}

data class ExperimentalRestoredSession(val bridge: LowLevelVmBridge, val handle: Long)

/** Uses the same checked host-response encoding as ordinary VM sessions. */
object ExperimentalFleetResponseWire {
    fun success(response: ru.lazyhat.compukters.lang.runtime.capability.HostResponse): ByteArray =
        ru.lazyhat.compukters.lang.runtime.capability.HostResponseWire.encode(response)

    fun failureKind(kind: HostFailureKind): Int = kind.wireCode

    fun failureDetail(detail: String): ByteArray = encodeHostFailureDetail(detail)
}
