/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompilationOutcome
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.VmCompilationRequest
import java.util.ArrayDeque

/** One entry per compiling actor, no idle-machine storage and no retained source snapshot here. */
internal class NativeFleetCompilations(
    private val outbox: NativeFleetOutbox,
    private val nanoTime: () -> Long,
) {
    private val pending = mutableMapOf<Long, Pending>()
    private val polling = ArrayDeque<Pending>()

    fun request(
        machine: Long,
        host: ProgramRuntimeHost,
        request: VmCompilationRequest,
    ) {
        val old = pending[machine]
        if (old?.token == request.token && host.isCompilationCurrent(request.token)) return
        if (!host.isNewCompilation(request.token)) return
        check(cancel(machine) == null) { "compiler cancellation failed" }
        val entry = Pending(machine, host, request.token, host.currentProgramScope, host.beginCompilation(request))
        pending[machine] = entry
        polling.addLast(entry)
    }

    /** Fencing always precedes handoff; a close worker can safely drop an obsolete queued result. */
    fun cancel(machine: Long): Throwable? {
        val entry = pending.remove(machine) ?: return null
        entry.live = false
        polling.removeIf { it === entry }
        return runCatching { entry.host.cancelCompilation() }.exceptionOrNull()
    }

    fun cancelScope(
        machine: Long,
        scope: Long,
    ) {
        if (pending[machine]?.scope == scope) check(cancel(machine) == null) { "compiler cancellation failed" }
    }

    fun pump(
        maximumAttempts: Int,
        deadlineNanos: Long,
    ) {
        var attempts = 0
        val count = minOf(maximumAttempts, polling.size)
        while (attempts < count && (deadlineNanos == Long.MAX_VALUE || nanoTime() - deadlineNanos < 0)) {
            val entry = polling.removeFirst()
            attempts++
            if (!entry.live || !entry.host.isCompilationCurrent(entry.token)) {
                cancel(entry.machine)
                continue
            }
            var outcome = entry.immediate ?: entry.host.pollCompilation(entry.token)
            if (outcome == null) {
                polling.addLast(entry)
                continue
            }
            var command = Result(entry, outcome)
            if (!outbox.fits(command)) {
                outcome = ComputerCompilationOutcome.Failure("compiler result exceeds native queue byte limit")
                command = Result(entry, outcome)
                if (!outbox.fits(command)) {
                    outcome = ComputerCompilationOutcome.Failure("") // Even a scalar-only queue must make progress.
                    command = Result(entry, outcome)
                }
                entry.immediate = outcome
            }
            if (outbox.offer(entry.machine, listOf(command))) {
                entry.host.claimCompilationResult(entry.token)
            } else {
                polling.addLast(entry)
            }
        }
    }

    private class Pending(
        val machine: Long,
        val host: ProgramRuntimeHost,
        val token: Long,
        val scope: Long,
        var immediate: ComputerCompilationOutcome.Failure?,
    ) {
        @Volatile var live = true
    }

    private inner class Result(
        private val entry: Pending,
        private val outcome: ComputerCompilationOutcome,
    ) : NativeFleetOutbox.Command {
        override val retainedBytes =
            64L +
                when (outcome) {
                    is ComputerCompilationOutcome.Success -> outcome.artifactSize.toLong()
                    is ComputerCompilationOutcome.Failure -> 2L * outcome.diagnostics.length
                }

        override fun send(
            fleet: ExperimentalFleet,
            machine: Long,
        ) {
            if (!entry.live) return // May run on the close worker after the owner fenced this entry.
            if (!entry.host.isCompilationCurrent(entry.token)) {
                cancel(machine)
                return
            }
            when (outcome) {
                is ComputerCompilationOutcome.Success -> fleet.completeCompilationArtifact(machine, entry.token, outcome.artifactBytes())
                is ComputerCompilationOutcome.Failure -> fleet.completeCompilationFailure(machine, entry.token, outcome.diagnostics)
            }
            entry.live = false
            pending.remove(machine)
            entry.host.finishCompilation(entry.token)
        }
    }
}
