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
}
