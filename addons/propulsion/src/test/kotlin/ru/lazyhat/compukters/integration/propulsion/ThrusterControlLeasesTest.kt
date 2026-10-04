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

package ru.lazyhat.compukters.integration.propulsion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThrusterControlLeasesTest {
    @Test fun exclusiveOwnershipAndRelease() {
        val leases = ThrusterControlLeases<Any>()
        val engine = Any()
        val first = Any()
        val second = Any()
        var releases = 0
        assertTrue(leases.claim(engine, first, { true }) { releases++ })
        assertFalse(leases.claim(engine, second, { true }) { error("not an owner") })
        assertTrue(leases.claim(engine, first, { true }) { error("do not replace lease") })
        leases.release(engine, second)
        assertEquals(0, releases)
        leases.releaseOwner(first)
        leases.releaseOwner(first)
        assertEquals(1, releases)
        assertTrue(leases.claim(engine, second, { true }) { releases++ })
        leases.releaseAll()
        assertEquals(2, releases)
    }

    @Test fun disconnectedEngineIsReleasedBeforeAnotherOwnerClaimsIt() {
        val leases = ThrusterControlLeases<Any>()
        val engine = Any()
        var connected = true
        var restored = false
        assertTrue(leases.claim(engine, Any(), { connected }) { restored = true })
        connected = false
        assertTrue(leases.claim(engine, Any(), { true }) { })
        assertTrue(restored)
    }

    @Test fun idleProgramStillLosesDisconnectedControlOnTick() {
        val leases = ThrusterControlLeases<Any>()
        var connected = true
        var releases = 0
        leases.claim(Any(), Any(), { connected }) { releases++ }
        leases.reap()
        assertEquals(0, releases)
        connected = false
        leases.reap()
        leases.reap()
        assertEquals(1, releases)
    }
}
