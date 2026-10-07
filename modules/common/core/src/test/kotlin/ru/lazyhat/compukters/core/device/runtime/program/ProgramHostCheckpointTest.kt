/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProgramHostCheckpointTest {
    private val checkpoint =
        ProgramHostCheckpoint(
            true,
            GrantedResourceBudgetSnapshot(Long.MAX_VALUE, 901, true),
            0,
            listOf(0, 7, 12),
            listOf(ProgramTimerCheckpoint(VmHostRequestIdentity(2, 51), 7, 20, 9)),
            byteArrayOf(1, 2, -1),
        )

    @Test
    fun `portable host state preserves suspended parent timers and resource counters`() {
        val restored = ProgramHostCheckpoint.decode(checkpoint.encode())
        assertEquals(checkpoint.waitingForInput, restored.waitingForInput)
        assertEquals(checkpoint.granted, restored.granted)
        assertEquals(checkpoint.confirmedRedstoneOutput, restored.confirmedRedstoneOutput)
        assertEquals(checkpoint.programScopes, restored.programScopes)
        assertEquals(checkpoint.timers, restored.timers)
        assertContentEquals(checkpoint.addonState, restored.addonState)
    }

    @Test
    fun `malformed sizes versions booleans counts and trailing bytes are rejected`() {
        val encoded = checkpoint.encode()
        for (length in encoded.indices) {
            assertFailsWith<IllegalArgumentException> { ProgramHostCheckpoint.decode(encoded.copyOf(length)) }
        }
        assertFailsWith<IllegalArgumentException> { ProgramHostCheckpoint.decode(encoded + byteArrayOf(0)) }
        for ((offset, value) in listOf(4 to 2, 30 to Int.MAX_VALUE, 30 to -1)) {
            val invalid = encoded.copyOf()
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            assertFailsWith<IllegalArgumentException> { ProgramHostCheckpoint.decode(invalid) }
        }
        val invalid = encoded.copyOf().also { it[8] = 2 }
        assertFailsWith<IllegalArgumentException> { ProgramHostCheckpoint.decode(invalid) }
    }

    @Test
    fun `timer identities scopes and remaining durations are bounded`() {
        for (invalid in listOf(
            checkpoint.copy(programScopes = listOf(0, 7, 7)),
            checkpoint.copy(programScopes = listOf(1)),
            checkpoint.copy(timers = checkpoint.timers + checkpoint.timers),
            checkpoint.copy(timers = listOf(checkpoint.timers.single().copy(programId = 99))),
            checkpoint.copy(timers = listOf(checkpoint.timers.single().copy(remainingTicks = 21))),
            checkpoint.copy(granted = GrantedResourceBudgetSnapshot(-1, 0, false)),
        )) {
            assertFailsWith<IllegalArgumentException> { invalid.encode() }
        }
    }
}
