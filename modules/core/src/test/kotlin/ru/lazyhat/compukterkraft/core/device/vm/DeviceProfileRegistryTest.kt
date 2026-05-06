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

import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class DeviceProfileRegistryTest {
    @Test
    fun computerFamiliesDoNotExposeTerminalCapability() {
        DeviceFamily.entries.forEach { family ->
            val profile = DeviceProfileRegistry.forFamily(family)

            assertFalse(DeviceCapability.entries.any { it.name == "TERMINAL" })
            assertFalse(profile.allowedCapabilities.any { it.name == "TERMINAL" }, "$family must not expose removed terminal capability")
        }
    }

    @Test
    fun allComputerFamiliesAllowIpcForBundledRomTerminal() {
        DeviceFamily.entries.forEach { family ->
            val profile = DeviceProfileRegistry.forFamily(family)

            assertContains(profile.allowedCapabilities, DeviceCapability.IPC, "$family must allow IPC for terminal.ck")
        }
    }
}
