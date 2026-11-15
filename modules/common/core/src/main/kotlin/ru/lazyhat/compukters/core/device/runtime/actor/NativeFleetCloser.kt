/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.VmFleetBusyException
import ru.lazyhat.compukters.lang.runtime.vm.VmStoreBusyException
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture

/**
 * One lazy control worker per fleet, never a worker per machine. It owns stopping/checkpointing hosts and
 * their unsent command batches exclusively. Ordinary output and world callbacks stay on the owner.
 * Only the final generation escapes; canceled request payloads are not queued back to the JVM owner.
 */
internal class NativeFleetCloser(
    private val fleet: ExperimentalFleet,
) {
    private val monitor = Object()
    private val pending = ArrayDeque<Close>()
    private var worker: Thread? = null
    private var closing = false
    private val stopped = CompletableFuture<Unit>()

    fun checkHealthy() {
        check(!stopped.isCompletedExceptionally) { "native lifecycle worker failed" }
    }

    fun reserve(
        machine: Long,
        host: ProgramRuntimeHost,
        commands: NativeFleetOutbox.Handoff,
        result: CompletableFuture<Long>,
        checkpointTick: Long? = null,
        payload: NativeFleetOutbox.CheckpointPayload? = null,
        saveExecution: Boolean = true,
    ): () -> Unit {
        require((checkpointTick == null) == (payload == null))
        val checkpoint = checkpointTick?.let { Checkpoint(it, checkNotNull(payload), saveExecution) }
        val task = Close(machine, host, commands, result, checkpoint)
        synchronized(monitor) {
            checkHealthy()
            check(!closing)
            pending.addLast(task)
            if (worker == null) {
                worker =
                    Thread
                        .ofPlatform()
                        .daemon(true)
                        .name("compukters-native-fleet-close")
                        .start(::run)
            }
            monitor.notifyAll()
        }
        return {
            synchronized(monitor) {
                task.ready = true
                monitor.notifyAll()
            }
        }
    }

    fun closeAsync(): CompletableFuture<Unit> =
        synchronized(monitor) {
            closing = true
            if (worker == null) stopped.complete(Unit)
            monitor.notifyAll()
            stopped.copy()
        }

    private fun run() {
        var blocked = 0
        var active: Close? = null
        try {
            while (true) {
                val task =
                    synchronized(monitor) {
                        while (pending.isEmpty() && !closing) monitor.wait()
                        if (pending.isEmpty()) return
                        pending.removeFirst()
                    }
                active = task
                val progress = progress(task)
                active = null
                synchronized(monitor) {
                    if (progress != Progress.DONE) pending.addLast(task)
                    blocked = if (progress == Progress.BLOCKED) blocked + 1 else 0
                    // Back off only after an entire blocked round, never after accepted work.
                    if (pending.isNotEmpty() && blocked >= pending.size) {
                        monitor.wait(2)
                        blocked = 0
                    }
                }
            }
        } catch (failure: Throwable) {
            synchronized(monitor) {
                active?.let { pending.addFirst(it) }
                pending.forEach { it.result.completeExceptionally(failure) }
                stopped.completeExceptionally(failure)
            }
        } finally {
            stopped.complete(Unit)
        }
    }

    private fun progress(task: Close): Progress {
        if (!task.ready) return Progress.BLOCKED
        val checkpoint = task.checkpoint
        if (checkpoint != null &&
            (checkpoint.phase == CapturePhase.Recovered || checkpoint.phase == CapturePhase.Prepared)
        ) {
            return capture(task, checkpoint)
        }
        val event =
            try {
                if (task.commands.sendNext(fleet, task.machine)) return Progress.MORE
                if (!task.submitted) {
                    if (checkpoint == null) {
                        fleet.stop(task.machine, independent = true)
                    } else {
                        fleet.detachForCheckpoint(task.machine, checkpoint.tick)
                    }
                    task.submitted = true
                    return Progress.MORE
                }
                (checkpoint?.phase as? CapturePhase.Detached)?.event ?: fleet.pollLifecycle(task.machine) ?: return Progress.BLOCKED
            } catch (_: VmFleetBusyException) {
                return Progress.BLOCKED
            } catch (failure: Exception) {
                // An unaccepted head remains owned and charged. Do not silently drop acknowledged
                // effects or claim a close barrier while the machine is still native-owned.
                if (!task.reportedFailure) {
                    task.reportedFailure = true
                    LOGGER.warn(failure) { "Native close transport failed; retaining and retrying accepted work" }
                }
                return Progress.BLOCKED
            }
        if (checkpoint != null) return recoverCheckpoint(task, checkpoint, event)
        if (event is ExperimentalFleetEvent.Quiescent) return Progress.MORE
        try {
            check(event is ExperimentalFleetEvent.Stopped && event.machine == task.machine) { "unexpected native stop output: $event" }
            task.host.acknowledgeFleetStop(fleet, event, ProgramRuntimeState.Closed)
            task.result.complete(event.filesystemGeneration)
        } catch (failure: Throwable) {
            task.result.completeExceptionally(failure)
        }
        return Progress.DONE
    }

    private fun recoverCheckpoint(
        task: Close,
        checkpoint: Checkpoint,
        event: ExperimentalFleetEvent,
    ): Progress =
        when (event) {
            is ExperimentalFleetEvent.Detached -> {
                check(event.machine == task.machine)
                // Retain the final observation across recovery refusal, without detaching twice.
                checkpoint.phase = CapturePhase.Detached(event)
                try {
                    task.host.recoverCheckpointFromFleet(fleet, event, checkpoint.tick)
                    checkpoint.phase = CapturePhase.Recovered
                    Progress.MORE
                } catch (_: VmFleetBusyException) {
                    Progress.BLOCKED
                } catch (failure: Exception) {
                    if (!task.reportedFailure) {
                        task.reportedFailure = true
                        LOGGER.warn(failure) { "Native checkpoint recovery failed; retaining detached ownership" }
                    }
                    Progress.BLOCKED
                }
            }

            is ExperimentalFleetEvent.DetachRejected -> {
                task.submitted = false
                Progress.BLOCKED
            }

            // Prefixes are reconciled by authoritative detach state, never dispatched.
            else -> {
                Progress.MORE
            }
        }

    private fun capture(
        task: Close,
        checkpoint: Checkpoint,
    ): Progress {
        try {
            if (!checkpoint.saveExecution) {
                task.host.discardCheckpoint()
                task.host.close()
            } else {
                if (checkpoint.phase == CapturePhase.Recovered) {
                    val encoded = checkNotNull(task.host.prepareHibernation(checkpoint.tick, checkpoint.payload.bytes))
                    checkpoint.payload.replaceWithHostState(encoded)
                    checkpoint.phase = CapturePhase.Prepared
                }
                task.host.capturePreparedHibernation(checkpoint.payload.bytes)
            }
        } catch (_: VmStoreBusyException) {
            return Progress.BLOCKED
        } catch (failure: Throwable) {
            // Recovery already transferred the session to this worker. Capture failure must not
            // strand that live wrapper or let a later restore consume an older checkpoint.
            runCatching { task.host.discardCheckpoint() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { task.host.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            checkpoint.payload.close()
            task.result.completeExceptionally(failure)
            return Progress.DONE
        }
        checkpoint.payload.close()
        task.result.complete(checkNotNull(task.host.lastClosedFileSystemGeneration))
        return Progress.DONE
    }

    private sealed interface CapturePhase {
        data object Detaching : CapturePhase

        data class Detached(
            val event: ExperimentalFleetEvent.Detached,
        ) : CapturePhase

        data object Recovered : CapturePhase

        data object Prepared : CapturePhase
    }

    private class Checkpoint(
        val tick: Long,
        val payload: NativeFleetOutbox.CheckpointPayload,
        val saveExecution: Boolean,
    ) {
        var phase: CapturePhase = CapturePhase.Detaching
    }

    private enum class Progress { MORE, BLOCKED, DONE }

    private class Close(
        val machine: Long,
        val host: ProgramRuntimeHost,
        val commands: NativeFleetOutbox.Handoff,
        val result: CompletableFuture<Long>,
        val checkpoint: Checkpoint?,
    ) {
        @Volatile var ready = false
        var submitted = false
        var reportedFailure = false
    }
}
