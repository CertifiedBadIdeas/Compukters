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

package ru.lazyhat.compukters.api.addon.minecraft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CompuktersPeripheralContractTest {
    @Test
    fun `legacy registration retains its JVM constructor and optional metadata`() {
        val legacy = CompuktersPeripheralContract<CompuktersPeripheralEndpoint>("addon:device", "device") { null }
        assertNull(legacy.guestLookupType)
        val constructor =
            CompuktersPeripheralContract::class.java.getConstructor(
                String::class.java,
                String::class.java,
                kotlin.jvm.functions.Function1::class.java,
            )
        assertEquals(3, constructor.parameterCount)
    }

    @Test
    fun `qualified lookup metadata rejects source fragments`() {
        val typed = CompuktersPeripheralContract<CompuktersPeripheralEndpoint>("addon:device", "device", "addon.devices.Device") { null }
        assertEquals("addon.devices.Device", typed.guestLookupType)
        for (name in listOf("Device", "addon.Device()", "addon.device;evil", "a." + "x".repeat(256))) {
            assertFailsWith<IllegalArgumentException> {
                CompuktersPeripheralContract<CompuktersPeripheralEndpoint>("addon:device", "device", name) { null }
            }
        }
    }
}
