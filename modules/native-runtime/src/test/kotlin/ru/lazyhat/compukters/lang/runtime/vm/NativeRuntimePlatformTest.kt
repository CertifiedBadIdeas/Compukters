/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.lang.runtime.vm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeRuntimePlatformTest {
    @Test
    fun `platform aliases resolve to stable resource paths`() {
        val cases =
            listOf(
                Triple("Linux", "amd64", "/META-INF/natives/linux/x86_64/libcompukter_ffi.so"),
                Triple(" linux ", "x86_64", "/META-INF/natives/linux/x86_64/libcompukter_ffi.so"),
                Triple("Windows 11", "AMD64", "/META-INF/natives/windows/x86_64/compukter_ffi.dll"),
                Triple("Mac OS X", "arm64", "/META-INF/natives/macos/aarch64/libcompukter_ffi.dylib"),
                Triple("macOS", "aarch64", "/META-INF/natives/macos/aarch64/libcompukter_ffi.dylib"),
            )

        cases.forEach { (os, arch, expected) ->
            val resolution = assertIs<NativePlatformResolution.Supported>(NativeRuntimePlatform.resolve(os, arch))
            assertEquals(expected, resolution.platform.resourcePath)
        }
    }

    @Test
    fun `unsupported operating system retains bounded single line inputs`() {
        val resolution =
            assertIs<NativePlatformResolution.Unsupported>(
                NativeRuntimePlatform.resolve("Plan9\nignored", "mips".repeat(100)),
            )

        assertTrue(resolution.osName.length <= MAXIMUM_DIAGNOSTIC_CODE_UNITS)
        assertTrue(resolution.osArch.length <= MAXIMUM_DIAGNOSTIC_CODE_UNITS)
        assertFalse(resolution.osName.contains('\n'))
    }

    @Test
    fun `unsupported architecture does not partially resolve a platform`() {
        val resolution =
            assertIs<NativePlatformResolution.Unsupported>(
                NativeRuntimePlatform.resolve("Linux", "riscv64"),
            )

        assertEquals("Linux", resolution.osName)
        assertEquals("riscv64", resolution.osArch)
    }

    @Test
    fun `load exception exposes the typed failure`() {
        val failure = VmRuntimeLoadFailure.MissingResource("/META-INF/natives/linux/x86_64/libcompukter_ffi.so")

        val exception = VmRuntimeLoadException(failure)

        assertEquals(failure, exception.failure)
        assertTrue(exception.message.orEmpty().contains("MissingResource"))
    }

    private companion object {
        const val MAXIMUM_DIAGNOSTIC_CODE_UNITS = 256
    }
}
