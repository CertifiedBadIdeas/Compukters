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
    fun create(artifact: ByteArray): ByteArray

    fun advance(
        handle: Long,
        guestBudget: Int,
        maintenanceBudget: Int,
    ): ByteArray

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

    fun terminalCompatibilityLine(
        handle: Long,
        value: CharArray,
    ): Unit = error("terminal compatibility input is unavailable")
}
