/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.api.commands.CommandBatchCodec
import ru.lazyhat.compukters.core.device.runtime.program.CommandBatchProgramHost
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalRequestInspection
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import java.util.ArrayDeque

/**
 * Rust serializes inspection at the first unclassified request of each machine. Until its scalar
 * cost is admitted, retain only identity, scope and cost. No packet is needed for retries.
 * Prepared packets are an optional bounded cache, never an authoritative pending-request registry.
 */
internal class NativeFleetCommandRequests(
    private val outbox: NativeFleetOutbox,
    private val maximumEntries: Int,
    private val maximumEntriesPerMachine: Int,
    private val byteLimits: NativeFleetOutboxLimits,
    private val nanoTime: () -> Long,
) {
    private data class Pending(
        val machine: Long,
        val scope: Long,
        val command: NativeFleetOutbox.Command.ResolveCost,
    )

    private data class Key(
        val machine: Long,
        val identity: VmHostRequestIdentity,
    )

    private data class Prepared(
        val request: ProgramAddonRequest,
        val bytes: Long,
    )

    private class Usage {
        val keys = LinkedHashSet<Key>()
        var bytes = 0L
    }

    private val pending = mutableMapOf<Long, Pending>()
    private val polling = ArrayDeque<Pending>()
    private val prepared = LinkedHashMap<Key, Prepared>()
    private val usage = mutableMapOf<Long, Usage>()
    internal var retainedBytes = 0L
        private set

    fun inspect(
        machine: Long,
        request: ProgramAddonRequest,
    ) {
        val previous = pending[machine]
        if (previous != null) {
            check(previous.command.identity == request.identity && previous.scope == request.programId) { "overlapping native inspections" }
            return
        }
        val commands =
            try {
                CommandBatchCodec.decode(request)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: ClassCastException) {
                null
            }
        // Invalid packets cost one, and dispatch produces the existing typed malformed-packet failure.
        val cost = commands?.size ?: 1
        val entry = Pending(machine, request.programId, NativeFleetOutbox.Command.ResolveCost(request.identity, cost))
        pending[machine] = entry
        polling.addLast(entry)
        if (commands == null) return
        // Conservative retained-heap accounting includes map/list nodes, wrappers and both packets.
        var bytes = 512L + (request.arguments.single() as VmValue.ByteArrayValue).value.size
        for (command in commands) {
            bytes += 192L + 2L * command.symbol.length
            for (argument in command.arguments) {
                bytes += 96L +
                    when (argument) {
                        is VmValue.StringValue -> 2L * argument.value.length
                        is VmValue.ByteArrayValue -> argument.value.size.toLong()
                        else -> 0L
                    }
            }
        }
        if (bytes > byteLimits.totalBytes || bytes > byteLimits.perMachineBytes) return
        val key = Key(machine, request.identity)
        remove(key)
        while (prepared.size >= maximumEntries || bytes > byteLimits.totalBytes - retainedBytes ||
            (usage[machine]?.keys?.size ?: 0) >= maximumEntriesPerMachine ||
            bytes > byteLimits.perMachineBytes - (usage[machine]?.bytes ?: 0L)
        ) {
            val localFull =
                (usage[machine]?.keys?.size ?: 0) >= maximumEntriesPerMachine ||
                    bytes > byteLimits.perMachineBytes - (usage[machine]?.bytes ?: 0L)
            val victim = if (localFull) usage.getValue(machine).keys.first() else prepared.keys.first()
            remove(victim)
        }
        prepared[key] = Prepared(request, bytes)
        usage.getOrPut(machine) { Usage() }.also {
            it.keys.add(key)
            it.bytes += bytes
        }
        retainedBytes += bytes
    }

    fun take(
        machine: Long,
        identity: VmHostRequestIdentity,
        scope: Long,
    ): ProgramAddonRequest? = remove(Key(machine, identity))?.request?.takeIf { it.programId == scope }

    fun cancel(
        machine: Long,
        scope: Long? = null,
    ) {
        pending[machine]?.takeIf { scope == null || it.scope == scope }?.let {
            pending.remove(machine)
            polling.remove(it)
        }
        // Visit only this machine's cached requests, not every other computer in the fleet.
        val keys = usage[machine]?.keys?.toList() ?: return
        for (key in keys) {
            if (scope == null || prepared.getValue(key).request.programId == scope) remove(key)
        }
    }

    fun pump(
        maximumAttempts: Int,
        deadlineNanos: Long,
    ) {
        repeat(minOf(maximumAttempts, polling.size)) {
            if (deadlineNanos != Long.MAX_VALUE && nanoTime() - deadlineNanos >= 0) return
            val entry = polling.removeFirst()
            if (outbox.offer(entry.machine, listOf(entry.command))) {
                pending.remove(entry.machine)
            } else {
                polling.addLast(entry)
            }
        }
    }

    private fun remove(key: Key): Prepared? = prepared.remove(key)?.also { release(key, it) }

    private fun release(
        key: Key,
        entry: Prepared,
    ) {
        retainedBytes -= entry.bytes
        val used = usage.getValue(key.machine)
        used.bytes -= entry.bytes
        check(used.keys.remove(key))
        if (used.keys.isEmpty()) usage.remove(key.machine)
    }

    companion object {
        val INSPECTION =
            ExperimentalRequestInspection(CommandBatchProgramHost.SCHEMA.identity, 0, "Command group exceeds per-tick host budget")
    }
}
