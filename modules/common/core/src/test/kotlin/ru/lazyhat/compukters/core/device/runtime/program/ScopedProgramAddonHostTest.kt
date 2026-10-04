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

import ru.lazyhat.compukters.api.addon.ProgramAddonCompletion
import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopedProgramAddonHostTest {
    @Test fun `nested programs retain parents and release only the completed child`() {
        val created = mutableListOf<RecordingHost>()
        val host = programScopedAddonHostOf { RecordingHost().also { created += it } }
        host.dispatch(request(0, 1))
        host.programStarted(1)
        host.dispatch(request(1, 2))
        host.programStarted(2)
        host.dispatch(request(2, 3))
        assertEquals(3, created.size)
        host.programStopped(2)
        assertTrue(created[2].closed)
        assertFalse(created[1].closed)
        assertFalse(created[0].closed)
        host.dispatch(request(1, 4))
        assertEquals(2, created[1].calls)
        host.programStopped(1)
        assertTrue(created[1].closed)
        host.reset()
        assertTrue(created[0].closed)
    }

    @Test fun `retired scopes reject requests and cannot complete another process`() {
        val created = mutableListOf<RecordingHost>()
        val host = programScopedAddonHostOf { RecordingHost().also { created += it } }
        host.programStarted(1)
        host.dispatch(request(1, 10))
        created[1].pending += ProgramAddonCompletion(VmHostRequestIdentity(1, 10), HostResponse.UnitSuccess)
        host.programStopped(1)
        host.programStarted(2)
        host.dispatch(request(2, 11))
        created[2].pending += ProgramAddonCompletion(VmHostRequestIdentity(1, 11), HostResponse.UnitSuccess)
        assertFailsWith<IllegalArgumentException> { host.dispatch(request(1, 12)) }
        assertEquals(listOf(VmHostRequestIdentity(1, 11)), host.poll(10).map { it.identity })
        assertFailsWith<IllegalArgumentException> { host.programStarted(1) }
        host.close()
    }

    @Test fun `process scopes are bounded and allocate hosts only when used`() {
        var created = 0
        val host =
            programScopedAddonHostOf {
                created++
                RecordingHost()
            }
        (1L..31L).forEach(host::programStarted)
        assertEquals(1, created)
        assertFailsWith<IllegalArgumentException> { host.programStarted(32) }
        host.programStopped(31)
        host.programStarted(32)
        assertEquals(1, created)
        host.reset()
    }

    private class RecordingHost : ProgramAddonHost {
        override val capabilitySchemas = emptyList<ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema>()
        var closed = false
        var calls = 0
        val pending = ArrayDeque<ProgramAddonCompletion>()

        override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch {
            calls++
            return ProgramAddonDispatch.Pending
        }

        override fun poll(maximumCompletions: Int): List<ProgramAddonCompletion> =
            List(minOf(maximumCompletions, pending.size)) {
                pending.removeFirst()
            }

        override fun reset() {
            closed = true
            pending.clear()
        }
    }

    private fun request(
        program: Long,
        id: Long,
    ) = ProgramAddonRequest(VmHostRequestIdentity(1, id), CapabilityIdentity("test", "lease", 1, 0), 0, emptyList(), program)
}
