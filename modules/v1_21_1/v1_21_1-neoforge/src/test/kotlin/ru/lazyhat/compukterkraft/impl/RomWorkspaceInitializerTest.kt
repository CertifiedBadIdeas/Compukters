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
package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.core.device.vm.DeviceWorkspaceInitializer
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RomWorkspaceInitializerTest {
    @Test
    fun initializesUserWorkspaceWithBootFileButWithoutBiosFirmware() {
        val root = createTempDirectory("compukterkraft-rom-init")
        try {
            val deviceRoot = DeviceWorkspaceInitializer(root).ensureInitialized(7)

            assertTrue(deviceRoot.resolve("boot.ck").exists(), "boot.ck should be copied into user workspace")
            assertTrue(deviceRoot.resolve("shell.ck").exists(), "shell.ck should be copied into user workspace")
            assertTrue(deviceRoot.resolve("bin/yes.ck").exists(), "yes.ck should be copied into user workspace bin")
            assertTrue(deviceRoot.resolve("bin/touch.ck").exists(), "touch.ck should be copied into user workspace bin")
            assertFalse(deviceRoot.resolve("bios.ck").exists(), "bios.ck must stay hidden in firmware storage")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
