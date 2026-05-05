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

package ru.lazyhat.compukterkraft.core.device.vm

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventPayloadStoreTest {
    @Test
    fun storesPrimitiveArgumentsAndDecodesTextBuffers() {
        val store = EventPayloadStore(maxEvents = 8)
        val id = store.capture(listOf(65, true, "plain", "Ж".toByteArray(), ByteBuffer.wrap("paste".toByteArray()))).first

        assertEquals(5, store.argCount(id))
        assertEquals(65, store.argInt(id, 0))
        assertTrue(store.argBool(id, 1))
        assertEquals("plain", store.argString(id, 2))
        assertEquals("Ж", store.argString(id, 3))
        assertEquals("paste", store.argString(id, 4))
        assertFalse(store.argBool(id, 0))
    }
}