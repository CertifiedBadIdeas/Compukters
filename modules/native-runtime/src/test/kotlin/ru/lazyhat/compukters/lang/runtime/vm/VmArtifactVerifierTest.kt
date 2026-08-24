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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VmArtifactVerifierTest {
    @Test
    fun `verification delegates without creating a VM session`() {
        val bridge = RecordingBridge()
        val artifact = byteArrayOf(1, 2, 3)

        assertTrue(VmArtifactVerifier.verify(artifact, bridge))
        assertTrue(bridge.verified!!.contentEquals(artifact))
        assertFalse(bridge.created)
    }

    private class RecordingBridge : LowLevelVmBridge {
        var verified: ByteArray? = null
        var created = false

        override fun verifyArtifact(artifact: ByteArray): Boolean {
            verified = artifact
            return true
        }

        override fun create(artifact: ByteArray): ByteArray {
            created = true
            error("verification must not create a VM session")
        }

        override fun advance(
            handle: Long,
            guestBudget: Int,
            maintenanceBudget: Int,
        ): ByteArray = error("unused")

        override fun resumeUnit(
            handle: Long,
            requestId: Long,
        ) = error("unused")

        override fun resumeString(
            handle: Long,
            requestId: Long,
            value: CharArray,
        ) = error("unused")

        override fun resumeFailure(
            handle: Long,
            requestId: Long,
            kind: Int,
            code: Long,
        ) = error("unused")

        override fun close(handle: Long) = error("unused")
    }
}
