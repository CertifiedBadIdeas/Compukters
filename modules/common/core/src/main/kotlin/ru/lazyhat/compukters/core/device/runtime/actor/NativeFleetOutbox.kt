/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.VmFleetBusyException
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import java.util.ArrayDeque

internal class NativeFleetCommandException(
    val machine: Long,
    cause: Exception,
) : IllegalStateException("native fleet command failed for machine $machine", cause)

/** Owner-thread queue between world effects and the bounded native worker mailboxes. */
internal class NativeFleetOutbox(
    private val fleet: ExperimentalFleet,
    private val maximumCommands: Int,
    private val maximumCommandsPerMachine: Int,
    private val checkOwner: () -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(maximumCommands > 0 && maximumCommandsPerMachine > 0)
    }

    internal sealed interface Command {
        fun send(
            fleet: ExperimentalFleet,
            machine: Long,
        )

        sealed interface Lifecycle : Command

        data class Reply(
            val identity: VmHostRequestIdentity,
            val response: HostResponse,
        ) : Command {
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

    private val queues = mutableMapOf<Long, ArrayDeque<Command>>()
    private val ready = ArrayDeque<Long>()
    private val closing = mutableMapOf<Long, Command.Lifecycle>()
    private val awaitingLifecycle = ArrayDeque<Long>()
    private var count = 0

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
        if (commands.size > maximumCommands - count ||
            commands.size > maximumCommandsPerMachine - (existing?.size ?: 0)
        ) {
            return false
        }
        val queue =
            existing ?: ArrayDeque<Command>().also {
                queues[machine] = it
                ready.addLast(machine)
            }
        queue.addAll(commands)
        count += commands.size
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
            attempts++
            try {
                queue.first.send(fleet, machine)
                if (queue.removeFirst() is Command.Lifecycle) awaitingLifecycle.addLast(machine)
                count--
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
            if (queue.isEmpty()) queues.remove(machine) else ready.addLast(machine)
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
        count -= queue.size
        return queue.toList()
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
