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

package ru.lazyhat.compukters.lang.runtime.vm

internal interface LowLevelVmBridge {
    fun openTerminalTransport(): TerminalWireTransport = ByteArrayTerminalWireTransport(this)

    fun storeOpen(
        rootUtf8: ByteArray,
        limitsWire: ByteArray,
    ): ByteArray = error("filesystem store open is unavailable")

    fun storeHealth(handle: Long): ByteArray = error("filesystem store health is unavailable")

    fun storeDurableGeneration(
        handle: Long,
        id: ByteArray,
    ): ByteArray = error("filesystem durable generation is unavailable")

    fun storeFlush(
        handle: Long,
        id: ByteArray,
        generation: Long,
    ): Unit = error("filesystem flush is unavailable")

    fun storeTombstone(
        handle: Long,
        id: ByteArray,
    ): Unit = error("filesystem tombstone is unavailable")

    fun storeRecover(
        handle: Long,
        id: ByteArray,
    ): Unit = error("filesystem recovery is unavailable")

    fun storeClose(handle: Long): Unit = error("filesystem store close is unavailable")

    fun verifyArtifact(artifact: ByteArray): Boolean = error("artifact verification is unavailable")

    fun create(artifact: ByteArray): ByteArray

    fun createInStore(
        storeHandle: Long,
        id: ByteArray,
        rom: ByteArray,
        artifact: ByteArray,
    ): ByteArray = error("persistent VM creation is unavailable")

    fun createBootInStore(
        storeHandle: Long,
        id: ByteArray,
        rom: ByteArray,
    ): ByteArray = error("persistent VM boot is unavailable")

    fun filesystemGeneration(handle: Long): ByteArray = error("filesystem generation is unavailable")

    fun advance(
        handle: Long,
        guestBudget: Int,
        maintenanceBudget: Int,
    ): ByteArray

    fun compilationRequest(
        handle: Long,
        token: Long,
    ): ByteArray = error("compilation requests are unavailable")

    fun completeCompilationArtifact(
        handle: Long,
        token: Long,
        artifact: ByteArray,
    ): Unit = error("compilation completion is unavailable")

    fun completeCompilationFailure(
        handle: Long,
        token: Long,
        diagnostics: String,
    ): Unit = error("compilation completion is unavailable")

    fun resumeUnit(
        handle: Long,
        requestId: Long,
    )

    fun resumeString(
        handle: Long,
        requestId: Long,
        value: CharArray,
    )

    fun resumeFailure(
        handle: Long,
        requestId: Long,
        kind: Int,
        code: Long,
    )

    fun close(handle: Long)

    fun terminalCommit(handle: Long): Unit = error("terminal commit is unavailable")

    fun terminalFullState(handle: Long): ByteArray = error("terminal state is unavailable")

    fun terminalChangesSince(
        handle: Long,
        revision: Long,
    ): ByteArray = error("terminal changes are unavailable")

    fun terminalKey(
        handle: Long,
        key: Int,
        action: Int,
        modifiers: Int,
    ): Unit = error("terminal key input is unavailable")

    fun terminalText(
        handle: Long,
        codePoints: IntArray,
    ): Unit = error("terminal text input is unavailable")
}
