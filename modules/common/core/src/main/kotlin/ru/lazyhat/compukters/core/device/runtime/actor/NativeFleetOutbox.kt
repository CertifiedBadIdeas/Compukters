/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetResponseMemory
import ru.lazyhat.compukters.lang.runtime.vm.VmFleetBusyException
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import java.util.ArrayDeque

internal class NativeFleetCommandException(
    val machine: Long,
    cause: Exception,
) : IllegalStateException("native fleet command failed for machine $machine", cause)

/** Shared admission limits; no storage is preallocated for an idle machine. */
internal data class NativeFleetOutboxLimits(
    val totalBytes: Long = 16L * 1024 * 1024,
    val perMachineBytes: Long = 16L * 1024 * 1024,
) {
    init {
        require(totalBytes >= 64 && perMachineBytes >= 64) { "native queue must fit a scalar command" }
    }
}

/** Owner-thread queue between world effects and the bounded native worker mailboxes. */
internal class NativeFleetOutbox(
    private val fleet: ExperimentalFleet,
    private val maximumCommands: Int,
    private val maximumCommandsPerMachine: Int,
    private val checkOwner: () -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val byteLimits: NativeFleetOutboxLimits = NativeFleetOutboxLimits(),
) {
    init {
        require(maximumCommands > 0 && maximumCommandsPerMachine > 0)
    }

    internal sealed interface Command {
        val retainedBytes: Long get() = 64L

        fun send(
            fleet: ExperimentalFleet,
            machine: Long,
        )

        sealed interface Lifecycle : Command

        data class Reply(
            val identity: VmHostRequestIdentity,
            val response: HostResponse,
        ) : Command {
            override val retainedBytes: Long = 64L + ExperimentalFleetResponseMemory.retainedBytes(response)

            override fun send(
                fleet: ExperimentalFleet,
                machine: Long,
            ) = fleet.reply(machine, identity, response)
        }

        data class ResolveCost(
            val identity: VmHostRequestIdentity,
            val units: Int,
        ) : Command {
            override fun send(
                fleet: ExperimentalFleet,
                machine: Long,
            ) = fleet.resolveCost(machine, identity, units)
        }

        data class RedstoneInput(
            val packet: Int,
        ) : Command {
            override fun send(
                fleet: ExperimentalFleet,
                machine: Long,
            ) = fleet.redstoneInput(machine, packet)
        }

        data class ConfirmRedstoneOutput(
            val packed: Int,
        ) : Command {
            override fun send(
                fleet: ExperimentalFleet,
                machine: Long,
            ) = fleet.confirmRedstoneOutput(machine, packed)
        }

        data class Grant(
            val tick: Long,
            val retirements: Int,
            val deadlineNanos: Long,
        ) : Command {
            override fun send(
                fleet: ExperimentalFleet,
                machine: Long,
            ) = fleet.grant(machine, tick, retirements, deadlineNanos)
        }

        data object Stop : Lifecycle {
            override fun send(
                fleet: ExperimentalFleet,
                machine: Long,
            ) = fleet.stop(machine, independent = true)
        }

        data class DetachWithTimers(
            val tick: Long,
        ) : Lifecycle {
            override fun send(
                fleet: ExperimentalFleet,
                machine: Long,
            ) = fleet.detachWithTimers(machine, tick, independent = true)
        }
    }

    private class Queue {
        val commands = ArrayDeque<Command>()
        var bytes = 0L
    }

    private val queues = mutableMapOf<Long, Queue>()
    private val ready = ArrayDeque<Long>()
    private val closing = mutableMapOf<Long, Command.Lifecycle>()
    private val awaitingLifecycle = ArrayDeque<Long>()
    private val budget = Budget(maximumCommands, byteLimits.totalBytes)
    internal val retainedBytes: Long get() = budget.bytes

    /** Shared across an ownership handoff; no native call holds this admission lock. */
    internal class Budget(
        private val maximumEntries: Int,
        private val maximumBytes: Long,
    ) {
        private var entries = 0
        var bytes = 0L
            @Synchronized get
            private set

        @Synchronized fun reserve(
            count: Int,
            charge: Long,
        ): Boolean {
            if (count > maximumEntries - entries || charge > maximumBytes - bytes) return false
            entries += count
            bytes += charge
            return true
        }

        @Synchronized fun release(
            count: Int,
            charge: Long,
        ) {
            check(count <= entries && charge <= bytes)
            entries -= count
            bytes -= charge
        }
    }

    /** Exclusive ownership of unsent commands, retaining the original shared reservation. */
    internal class Handoff internal constructor(
        private val commands: ArrayDeque<Command>,
        private val budget: Budget,
    ) {
        fun sendNext(
            fleet: ExperimentalFleet,
            machine: Long,
        ): Boolean {
            val command = commands.peekFirst() ?: return false
            command.send(fleet, machine)
            commands.removeFirst()
            budget.release(1, command.retainedBytes)
            return true
        }
    }

    /** The caller fences the actor before transferring this batch to its lifecycle worker. */
    fun handoffForStop(machine: Long): Handoff {
        checkOwner()
        check(machine !in closing) { "native lifecycle already submitted" }
        val queue = queues.remove(machine)
        if (queue != null) {
            check(ready.remove(machine))
            // A terminal fence cancels unsent execution credit, but preserves acknowledged effects.
            val iterator = queue.commands.iterator()
            while (iterator.hasNext()) {
                val command = iterator.next()
                if (command is Command.Grant) {
                    iterator.remove()
                    budget.release(1, command.retainedBytes)
                }
            }
        }
        return Handoff(queue?.commands ?: ArrayDeque(), budget)
    }

    /** Either retain the entire batch in order, or retain none of it. */
    fun offer(
        machine: Long,
        commands: List<Command>,
    ): Boolean {
        checkOwner()
        if (commands.isEmpty()) return true
        check(machine !in closing) { "machine is closing" }
        val lifecycleIndex = commands.indexOfFirst { it is Command.Lifecycle }
        require(lifecycleIndex == -1 || lifecycleIndex == commands.lastIndex) { "lifecycle command must be last" }
        if (lifecycleIndex >= 0 && closing.size >= maximumCommands) return false
        val existing = queues[machine]
        if (commands.size > maximumCommandsPerMachine - (existing?.commands?.size ?: 0)) {
            return false
        }
        var bytes = 0L
        for (command in commands) {
            require(command.retainedBytes >= 64L) { "invalid native command byte charge" }
            if (command.retainedBytes > byteLimits.totalBytes - bytes ||
                command.retainedBytes > byteLimits.perMachineBytes - bytes
            ) {
                return false
            }
            bytes += command.retainedBytes
        }
        if (bytes > byteLimits.perMachineBytes - (existing?.bytes ?: 0L) || !budget.reserve(commands.size, bytes)) return false
        val queue =
            existing ?: Queue().also {
                queues[machine] = it
                ready.addLast(machine)
            }
        queue.commands.addAll(commands)
        queue.bytes += bytes
        if (lifecycleIndex >= 0) closing[machine] = commands[lifecycleIndex] as Command.Lifecycle
        return true
    }

    /**
     * Attempts are bounded even when every native mailbox is full. A busy machine stays queued,
     * while other machines still get a chance. Other failures propagate with the head intact.
     * Call again on the next owner tick as command consumption need not publish an event.
     */
    fun flush(
        maximumAttempts: Int,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): Int {
        checkOwner()
        require(maximumAttempts >= 0)
        var attempts = 0
        var sent = 0
        while (ready.isNotEmpty() && attempts < maximumAttempts &&
            (deadlineNanos == Long.MAX_VALUE || nanoTime() - deadlineNanos < 0)
        ) {
            val machine = ready.removeFirst()
            val queue = checkNotNull(queues[machine])
            val commands = queue.commands
            attempts++
            try {
                commands.first.send(fleet, machine)
                val accepted = commands.removeFirst()
                if (accepted is Command.Lifecycle) awaitingLifecycle.addLast(machine)
                queue.bytes -= accepted.retainedBytes
                budget.release(1, accepted.retainedBytes)
                sent++
            } catch (_: VmFleetBusyException) {
                // The operation was not accepted, so it remains at the head.
            } catch (error: Exception) {
                ready.addFirst(machine)
                throw NativeFleetCommandException(machine, error)
            } catch (error: Throwable) {
                ready.addFirst(machine)
                throw error
            }
            if (commands.isEmpty()) queues.remove(machine) else ready.addLast(machine)
        }
        return sent
    }

    /** Discard only after the owner has dealt with the machine's pending operations. */
    fun remove(machine: Long): List<Command> {
        checkOwner()
        check(machine !in closing || machine in queues) { "native lifecycle must complete before removal" }
        closing.remove(machine)
        val queue = queues.remove(machine) ?: return emptyList()
        check(ready.remove(machine))
        budget.release(queue.commands.size, queue.bytes)
        return queue.commands.toList()
    }

    /**
     * Probe at most [maximumAttempts] submitted closes, stopping after a full empty round.
     * Slots rotate after each probe so one large detach cannot monopolize delivery, while
     * a lone machine can drain more than one prefix event within the caller's allowance.
     * This never polls ordinary output. Prefixes belong to lifecycle reconciliation, not world
     * execution: already-published ordinary observations can still be waiting in that queue.
     */
    fun pumpLifecycle(
        maximumEvents: Int,
        maximumAttempts: Int,
        deadlineNanos: Long = Long.MAX_VALUE,
        deliver: (ExperimentalFleetEvent) -> Unit,
    ): Int {
        checkOwner()
        require(maximumEvents >= 0 && maximumAttempts >= 0)
        var attempted = 0
        var delivered = 0
        var emptyProbes = 0
        while (attempted < maximumAttempts && delivered < maximumEvents && awaitingLifecycle.isNotEmpty() &&
            (deadlineNanos == Long.MAX_VALUE || nanoTime() - deadlineNanos < 0)
        ) {
            val machine = awaitingLifecycle.removeFirst()
            val event =
                try {
                    fleet.pollLifecycle(machine)
                } catch (error: Throwable) {
                    awaitingLifecycle.addFirst(machine)
                    throw error
                }
            attempted++
            when (event) {
                is ExperimentalFleetEvent.Stopped, is ExperimentalFleetEvent.Detached,
                is ExperimentalFleetEvent.DetachRejected,
                -> acknowledgeLifecycle(machine, event)

                // Rotate before invoking owner code, which can synchronously close another machine.
                else -> awaitingLifecycle.addLast(machine)
            }
            if (event == null) {
                emptyProbes++
                if (emptyProbes >= awaitingLifecycle.size) break
                continue
            }
            emptyProbes = 0
            delivered++
            deliver(event)
        }
        return delivered
    }

    /** A lifecycle result may only arrive after its command and all earlier work were submitted. */
    private fun acknowledgeLifecycle(
        machine: Long,
        event: ExperimentalFleetEvent,
    ) {
        val eventMachine =
            when (event) {
                is ExperimentalFleetEvent.Stopped -> event.machine
                is ExperimentalFleetEvent.Detached -> event.machine
                is ExperimentalFleetEvent.DetachRejected -> event.machine
                else -> error("not a native lifecycle result")
            }
        val expected = closing[machine]
        check(
            machine == eventMachine && machine !in queues &&
                when (event) {
                    is ExperimentalFleetEvent.Stopped -> expected == Command.Stop
                    is ExperimentalFleetEvent.Detached, is ExperimentalFleetEvent.DetachRejected -> expected is Command.DetachWithTimers
                    else -> false
                },
        ) { "unexpected native lifecycle result" }
        closing.remove(machine)
    }
}
