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

internal object NativeBridge : LowLevelVmBridge {
    override fun create(artifact: ByteArray): ByteArray = nativeCreate(artifact)

    override fun advance(
        handle: Long,
        guestBudget: Int,
        maintenanceBudget: Int,
    ): ByteArray = nativeAdvance(handle, guestBudget, maintenanceBudget)

    override fun resumeUnit(
        handle: Long,
        requestId: Long,
    ) = nativeResumeUnit(handle, requestId)

    override fun resumeString(
        handle: Long,
        requestId: Long,
        value: CharArray,
    ) = nativeResumeString(handle, requestId, value)

    override fun resumeFailure(
        handle: Long,
        requestId: Long,
        kind: Int,
        code: Long,
    ) = nativeResumeFailure(handle, requestId, kind, code)

    override fun close(handle: Long) = nativeClose(handle)

    @JvmStatic private external fun nativeCreate(artifact: ByteArray): ByteArray

    @JvmStatic private external fun nativeAdvance(
        handle: Long,
        guestBudget: Int,
        maintenanceBudget: Int,
    ): ByteArray

    @JvmStatic private external fun nativeResumeUnit(
        handle: Long,
        requestId: Long,
    )

    @JvmStatic private external fun nativeResumeString(
        handle: Long,
        requestId: Long,
        value: CharArray,
    )

    @JvmStatic private external fun nativeResumeFailure(
        handle: Long,
        requestId: Long,
        kind: Int,
        code: Long,
    )

    @JvmStatic private external fun nativeClose(handle: Long)
}
