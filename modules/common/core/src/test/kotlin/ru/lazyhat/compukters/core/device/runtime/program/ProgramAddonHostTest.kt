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
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProgramAddonHostTest {
    @Test
    fun `composite routes capabilities and shares one bounded completion budget`() {
        val first = RecordingHost(schema("first"))
        val second = RecordingHost(schema("second"))
        val composite = programAddonHostOf(listOf(first, second))
        val request = ProgramAddonRequest(VmHostRequestIdentity(2, 7), second.schema.identity, 0, emptyList())
        first.completions += ProgramAddonCompletion(VmHostRequestIdentity(1, 1), HostResponse.UnitSuccess)
        second.completions += ProgramAddonCompletion(VmHostRequestIdentity(2, 2), HostResponse.IntSuccess(4))

        assertEquals(ProgramAddonDispatch.Pending, composite.dispatch(request))
        assertEquals(listOf(request.identity), second.requests.map(ProgramAddonRequest::identity))
        assertEquals(1, composite.poll(1).size)
        assertEquals(1, composite.poll(1).size)
    }

    @Test
    fun `composite rejects duplicate capability majors before dispatch`() {
        val first = RecordingHost(schema("shared", minor = 0))
        val second = RecordingHost(schema("shared", minor = 1))

        assertFailsWith<IllegalStateException> { programAddonHostOf(listOf(first, second)) }
    }

    private class RecordingHost(
        val schema: HostCapabilitySchema,
    ) : ProgramAddonHost {
        override val capabilitySchemas: List<HostCapabilitySchema> = listOf(schema)
        val requests = mutableListOf<ProgramAddonRequest>()
        val completions = ArrayDeque<ProgramAddonCompletion>()

        override fun dispatch(request: ProgramAddonRequest): ProgramAddonDispatch =
            ProgramAddonDispatch.Pending.also { requests += request }

        override fun poll(maximumCompletions: Int): List<ProgramAddonCompletion> =
            List(minOf(maximumCompletions, completions.size)) { completions.removeFirst() }

        override fun reset() = completions.clear()
    }

    private companion object {
        fun schema(
            name: String,
            minor: Int = 0,
        ): HostCapabilitySchema =
            HostCapabilitySchema(
                CapabilityIdentity("test", name, 1, minor),
                listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, asynchronous = true)),
            )
    }
}
