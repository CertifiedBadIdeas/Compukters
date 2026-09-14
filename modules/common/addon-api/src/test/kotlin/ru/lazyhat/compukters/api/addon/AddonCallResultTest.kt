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

package ru.lazyhat.compukters.api.addon

import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.MAXIMUM_HOST_FAILURE_DETAIL_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AddonCallResultTest {
    @Test
    fun `failure retains its bounded detail`() {
        val detail = "Peripheral at 1, 2, 3 was replaced"

        assertEquals(AddonCallResult.Failed(HostFailureKind.UNAVAILABLE, detail), addonFailed(HostFailureKind.UNAVAILABLE, detail))
        assertEquals(AddonPollResult.Failed(HostFailureKind.UNAVAILABLE, detail), addonPollFailed(HostFailureKind.UNAVAILABLE, detail))
    }

    @Test
    fun `failure rejects an empty detail`() {
        assertFailsWith<IllegalArgumentException> { addonFailed(HostFailureKind.OTHER, "") }
        assertFailsWith<IllegalArgumentException> { addonPollFailed(HostFailureKind.OTHER, "") }
    }

    @Test
    fun `failure rejects a detail beyond the native bound`() {
        val oversized = "x".repeat(MAXIMUM_HOST_FAILURE_DETAIL_BYTES + 1)

        assertFailsWith<IllegalArgumentException> { addonFailed(HostFailureKind.OTHER, oversized) }
        assertFailsWith<IllegalArgumentException> { addonPollFailed(HostFailureKind.OTHER, oversized) }
    }
}
