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

package ru.lazyhat.compukterkraft.core.device.vm.display

import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmBindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeDisplayRegistryTest {
    @Test
    fun nativeRegistryAttachQueuesFullRefreshWhenLibraryIsConfigured() {
        System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val kernelHandle = NativeVmBindings.createDeviceKernel(64, 4096)
        val registry = NativeDisplayRegistry(kernelHandle)

        try {
            registry.attach(displayId = 5, width = 18, height = 18)
            val frames = registry.drainFrames()

            assertEquals(1, frames.size)
            assertEquals(5, frames[0].displayId)
            assertTrue(frames[0].fullRefresh)
        } finally {
            NativeVmBindings.freeDeviceKernel(kernelHandle)
        }
    }

    @Test
    fun nativeRegistryFillRectPresentDrainsDirtyFrameWhenLibraryIsConfigured() {
        System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val kernelHandle = NativeVmBindings.createDeviceKernel(64, 4096)
        val registry = NativeDisplayRegistry(kernelHandle)

        try {
            registry.attach(displayId = 5, width = 18, height = 18)
            registry.drainFrames()
            registry.fillRect(displayId = 5, x = 0, y = 0, width = 2, height = 2, rgb565 = 0x07E0)
            registry.present(displayId = 5)
            val frames = registry.drainFrames()

            assertEquals(1, frames.size)
            assertEquals(2L, frames[0].sequence)
            assertTrue(frames[0].tiles.isNotEmpty())
        } finally {
            NativeVmBindings.freeDeviceKernel(kernelHandle)
        }
    }
}
