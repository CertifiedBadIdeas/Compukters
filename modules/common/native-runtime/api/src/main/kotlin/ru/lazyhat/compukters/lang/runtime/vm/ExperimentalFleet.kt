/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.lang.runtime.vm

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath

data class ExperimentalFleetConfig(
    val workers: Int,
    val mailboxCapacity: Int,
    val eventCapacity: Int,
    val guestBudget: Int,
    val maintenanceBudget: Int,
    val hostBudget: Int,
    val advancesPerTick: Int,
    val inspection: ExperimentalRequestInspection? = null,
    val coordinatedTurns: Boolean = false,
)

/** Common asynchronous transport for the experimental native scheduler. */
interface ExperimentalFleet : ExperimentalFleetSessionOwner, AutoCloseable {
    fun grant(machine: Long, tick: Long, retirements: Int, deadlineNanos: Long = Long.MAX_VALUE)
    fun reply(machine: Long, identity: VmHostRequestIdentity, response: HostResponse)
    fun resolveCost(machine: Long, identity: VmHostRequestIdentity, units: Int)
    fun completeCompilationArtifact(machine: Long, token: Long, artifact: ByteArray)
    fun completeCompilationFailure(machine: Long, token: Long, diagnostics: String)
    fun terminalKey(machine: Long, key: TerminalKey, action: TerminalKeyAction, modifiers: Set<TerminalModifier>, request: Long = -1)
    fun terminalText(machine: Long, text: String, request: Long = -1)
    fun canonicalLine(machine: Long, line: CharArray, request: Long = -1)
    fun terminalUpdate(machine: Long, request: Long, revision: Long)
    fun resourceSnapshot(machine: Long, request: Long)
    fun filesystemGeneration(machine: Long, request: Long)
    fun fileStat(machine: Long, request: Long, path: VmVirtualPath)
    fun fileList(machine: Long, request: Long, path: VmVirtualPath, cursor: String? = null, maximum: Int = 128)
    fun fileRead(machine: Long, request: Long, path: VmVirtualPath, offset: Long, maximum: Int, generation: Long)
    fun prepareDeployment(machine: Long, request: Long, artifact: ByteArray)
    fun deploymentRevision(machine: Long, request: Long, path: VmVirtualPath)
    fun installDeployment(machine: Long, request: Long, path: VmVirtualPath, candidate: Long, expected: VmExecutableRevision)
    fun discardDeployment(machine: Long, request: Long, candidate: Long)
    fun redstoneInput(machine: Long, packet: Int)
    fun confirmRedstoneOutput(machine: Long, packed: Int)
    /** Select independent=true to reserve delivery through pollLifecycle at submission. */
    fun stop(machine: Long, independent: Boolean = false)
    fun detach(machine: Long, independent: Boolean = false)
    fun detachWithTimers(machine: Long, tick: Long, independent: Boolean = false)
    /** Capture before draining poll(); a later publication changes the generation. */
    fun eventGeneration(): Long
    /** Wait independently of command submission; false means timeout or fleet closure. */
    fun waitEvents(generation: Long, timeoutMillis: Int): Boolean
    fun poll(): ExperimentalFleetEvent?
    /**
     * Claims one machine's stop/detach output independently of the ordinary queue.
     * Keep polling this path through Stopped, Detached or DetachRejected. Older
     * already-published ordinary observations remain available through poll().
     */
    fun pollLifecycle(machine: Long): ExperimentalFleetEvent?
}

/** Native fleet temporarily cannot accept an operation. The caller may retry when appropriate. */
class VmFleetBusyException(message: String) : VmBridgeException(message)

/** Native fleet handle is no longer valid. */
class VmFleetStaleException(message: String) : VmBridgeException(message)

/** Shared status decoding so JNI and FFM expose the same failure contract. */
fun requireFleetSuccess(operation: String, status: Int) {
    if (status == 0) return
    val message = "experimental fleet $operation failed with status $status"
    when (status) {
        7 -> throw VmFleetBusyException(message)
        6 -> throw VmFleetStaleException(message)
        else -> throw VmBridgeException(message)
    }
}
