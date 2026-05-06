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

package ru.lazyhat.compukterkraft.core.device.vm.api

import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VmDisplayApiTest {
    @Test
    fun exposesPrimaryDisplaySizeAndPublishesFrame() {
        val registry = DisplayRegistry()
        registry.attach(displayId = 3, width = 64, height = 32)
        val api = VmDisplayApi(registry)

        assertEquals(3, api.primary())
        assertEquals(true, api.isAttached(3))
        assertEquals(64, api.width(3))
        assertEquals(32, api.height(3))

        api.clear(3, 0x0000)
        api.fillRect(3, 4, 5, 6, 7, 0xF800)
        api.blitMono(3, 1, 1, 3, 2, "101010", 0x07E0, 0x0000)
        api.blitMono5x7(3, 2, 2, 0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001, 0x07E0, -1)
        api.copyRect(3, 1, 1, 3, 2, 8, 8)
        api.present(3)

        val frame = assertNotNull(registry.drainFrames().lastOrNull())
        assertEquals(3, frame.displayId)
        assertEquals(64, frame.width)
        assertEquals(32, frame.height)
    }
}
