/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ck.mod.computer.vm

import ck.lang.runtime.HostCall
import ck.lang.runtime.HostResult
import ck.lang.runtime.VmEvent
import java.nio.ByteBuffer
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VmRuntimeSupportTest {
    @Test
    fun resolvesRelativeAndAbsoluteWorkspacePaths() {
        val resolver = VmPathResolver("rom/bin")

        assertEquals("rom/bin", resolver.resolve("."))
        assertEquals("rom/shell.ck", resolver.resolve("../shell.ck"))
        assertEquals("boot/init.ck", resolver.resolve("/boot/init.ck"))
    }

    @Test
    fun decodesTypedAndPastedEventText() {
        val typed = VmEvent("char", listOf("hello".encodeToByteArray()))
        val pasted = VmEvent("paste", listOf(ByteBuffer.wrap("world".encodeToByteArray())))

        assertEquals("hello", VmEventTextDecoder.typedText(typed))
        assertEquals("world", VmEventTextDecoder.pastedText(pasted))
        assertNull(VmEventTextDecoder.typedText(VmEvent("char")))
    }

    @Test
    fun rejectsHostCallsWhenQueueIsFull() =
        runBlocking {
            val manager = HostCallManager(maxQueueSize = 1)

            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    manager.awaitHostCall<Boolean> { id ->
                        HostCall.FileExists(id, "boot.ck")
                    }
                }

            assertEquals(1, manager.pendingCallsCount())

            val failure =
                assertFailsWith<IllegalStateException> {
                    manager.awaitHostCall<Boolean> { id ->
                        HostCall.FileExists(id, "shell.ck")
                    }
                }

            assertTrue(failure.message?.contains("Host call queue is full") == true)

            manager.deliverHostResults(listOf(HostResult.Success(1L, true)))
            assertTrue(first.await())
        }
}
