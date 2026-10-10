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

package ru.lazyhat.compukters.core.network

import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComputerNetworkTest {
    @Test fun `binary messages are copied bidirectional and FIFO including empty messages`() {
        val link = ComputerMessageLink()
        val first = ByteArray(256) { it.toByte() }
        link.ports[0].send(first)
        first.fill(0)
        link.ports[0].send(byteArrayOf())
        link.ports[1].send(byteArrayOf(-128, -1, 0))
        assertContentEquals(ByteArray(256) { it.toByte() }, link.ports[1].receive())
        assertContentEquals(byteArrayOf(), link.ports[1].receive())
        assertNull(link.ports[1].receive())
        assertContentEquals(byteArrayOf(-128, -1, 0), link.ports[0].receive())
    }

    @Test fun `queue and shared byte budgets reject overflow and recover after receive or disconnect`() {
        val budget = ComputerMessageBudget(4096)
        val link = ComputerMessageLink(budget)
        val other = ComputerMessageLink(budget)
        assertFailsWith<ComputerNetworkFailure> { link.ports[0].send(ByteArray(4097)) }
        link.ports[0].send(ByteArray(4096))
        assertFailsWith<ComputerNetworkFailure> { other.ports[0].send(byteArrayOf(1)) }
        link.ports[1].receive()
        repeat(16) { link.ports[0].send(byteArrayOf()) }
        assertFailsWith<ComputerNetworkFailure> { link.ports[0].send(byteArrayOf()) }
        other.ports[0].send(ByteArray(4096))
        other.disconnect()
        val fresh = ComputerMessageLink(budget)
        fresh.ports[0].send(ByteArray(4096))
        assertFalse(other.ports[0].connected)
    }

    @Test fun `pending receive completes exactly once with original identity`() {
        val link = ComputerMessageLink()
        val host = ComputerNetworkProgramHost { link.ports[0] }
        assertEquals(ProgramAddonDispatch.Pending, host.dispatch(request(2)))
        assertTrue(host.poll(1).isEmpty())
        link.ports[1].send(byteArrayOf(0, -128, -1))
        val completion = host.poll(1).single()
        assertEquals(identity, completion.identity)
        assertContentEquals(byteArrayOf(0, -128, -1), (completion.response as HostResponse.ByteArraySuccess).value)
        assertTrue(host.poll(1).isEmpty())
        assertFailsWith<IllegalArgumentException> { host.poll(0) }
    }

    @Test fun `cut and reconnect fails an old receive without consuming new peer messages`() {
        var link = ComputerMessageLink()
        val host = ComputerNetworkProgramHost { link.ports[0] }
        host.dispatch(request(2))
        link.disconnect()
        link = ComputerMessageLink()
        link.ports[1].send(byteArrayOf(42))
        val failure = host.poll(1).single().response as HostResponse.Failure
        assertEquals(HostFailureKind.INPUT_OUTPUT, failure.kind)
        assertContentEquals(
            byteArrayOf(42),
            ((host.dispatch(request(2)) as ProgramAddonDispatch.Completed).response as HostResponse.ByteArraySuccess).value,
        )
    }

    @Test fun `program reset cancels its receives but preserves computer inbox and other program`() {
        val link = ComputerMessageLink()
        val first = ComputerNetworkProgramHost { link.ports[0] }
        val second = ComputerNetworkProgramHost { link.ports[0] }
        first.dispatch(request(2))
        second.dispatch(request(2))
        link.ports[1].send(byteArrayOf(7))
        first.close()
        assertTrue(first.poll(1).isEmpty())
        assertContentEquals(byteArrayOf(7), (second.poll(1).single().response as HostResponse.ByteArraySuccess).value)
    }

    @Test fun `hibernated receive reports interruption and rejects malformed checkpoint atomically`() {
        val link = ComputerMessageLink()
        val host = ComputerNetworkProgramHost { link.ports[0] }
        host.dispatch(request(2))
        val saved = host.checkpoint()
        val restored = ComputerNetworkProgramHost { link.ports[0] }
        restored.restoreCheckpoint(saved)
        assertFailsWith<IllegalArgumentException> { restored.restoreCheckpoint(saved + byteArrayOf(0)) }
        assertEquals(HostFailureKind.INPUT_OUTPUT, (restored.poll(1).single().response as HostResponse.Failure).kind)
        assertTrue(restored.poll(1).isEmpty())
        val empty = ComputerNetworkProgramHost { null }
        empty.restoreCheckpoint(empty.checkpoint())
        assertEquals(HostResponse.BoolSuccess(false), (empty.dispatch(request(0)) as ProgramAddonDispatch.Completed).response)
        assertEquals(
            HostFailureKind.INPUT_OUTPUT,
            ((empty.dispatch(request(2)) as ProgramAddonDispatch.Completed).response as HostResponse.Failure).kind,
        )
    }

    private fun request(operation: Int) = ProgramAddonRequest(identity, ComputerNetworkProgramHost.SCHEMA.identity, operation, emptyList())

    private val identity = VmHostRequestIdentity(2, 17)
}
