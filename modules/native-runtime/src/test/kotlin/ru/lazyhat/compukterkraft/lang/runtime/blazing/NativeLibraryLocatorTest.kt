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

package ru.lazyhat.compukterkraft.lang.runtime.blazing

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeLibraryLocatorTest {
    @Test
    fun nativeLibraryPropertiesUseK16NamespaceWithoutRuxFallbacks() {
        assertEquals("k16.vm.native.library", NativeLibraryLocator.LIBRARY_PROPERTY)
        assertEquals("k16.vm.native.extract.dir", NativeLibraryLocator.EXTRACT_DIR_PROPERTY)
    }

    @Test
    fun normalizesLinuxAmd64Platform() {
        val platform = NativeLibraryLocator.platform(osName = "Linux", osArch = "amd64")

        assertEquals(
            NativeLibraryPlatform(
                id = "linux-x86_64",
                libraryName = "librux_vm.so",
            ),
            platform,
        )
    }

    @Test
    fun normalizesWindowsAndMacPlatforms() {
        assertEquals(
            NativeLibraryPlatform(id = "windows-x86_64", libraryName = "rux_vm.dll"),
            NativeLibraryLocator.platform(osName = "Windows 11", osArch = "x86_64"),
        )
        assertEquals(
            NativeLibraryPlatform(id = "macos-aarch64", libraryName = "librux_vm.dylib"),
            NativeLibraryLocator.platform(osName = "Mac OS X", osArch = "aarch64"),
        )
    }

    @Test
    fun configuredPropertyWinsWithoutReadingBundledResource() {
        var resourceRead = false

        val resolution =
            NativeLibraryLocator.resolve(
                configuredPath = "/dev/librux_vm.so",
                platform = NativeLibraryPlatform(id = "linux-x86_64", libraryName = "librux_vm.so"),
                cacheRoot = createTempDirectory("ck-native-test"),
            ) {
                resourceRead = true
                byteArrayOf(1, 2, 3)
            }

        assertEquals(NativeLibraryResolution.Configured(path = "/dev/librux_vm.so"), resolution)
        assertFalse(resourceRead)
    }

    @Test
    fun extractsBundledResourceToHashAddressedCachePath() {
        val cacheRoot = createTempDirectory("ck-native-test")
        val bytes = byteArrayOf(1, 1, 2, 3, 5, 8)
        val platform = NativeLibraryPlatform(id = "linux-x86_64", libraryName = "librux_vm.so")

        val resolution =
            assertIs<NativeLibraryResolution.Bundled>(
                NativeLibraryLocator.resolve(
                    configuredPath = " ",
                    platform = platform,
                    cacheRoot = cacheRoot,
                ) { path ->
                    assertEquals("natives/linux-x86_64/librux_vm.so", path)
                    bytes
                },
            )

        assertEquals("natives/linux-x86_64/librux_vm.so", resolution.resourcePath)
        assertEquals(platform, resolution.platform)
        assertTrue(resolution.path.startsWith(cacheRoot.toAbsolutePath().toString()))
        assertTrue(resolution.path.endsWith("/linux-x86_64/${resolution.sha256}/librux_vm.so"))
        assertEquals(bytes.toList(), resolution.path.let { Files.readAllBytes(java.nio.file.Path.of(it)).toList() })
    }

    @Test
    fun reusesExistingBundledExtraction() {
        val cacheRoot = createTempDirectory("ck-native-test")
        val bytes = byteArrayOf(13, 21, 34)
        val platform = NativeLibraryPlatform(id = "linux-x86_64", libraryName = "librux_vm.so")

        val first =
            assertNotNull(
                NativeLibraryLocator.resolve(
                    configuredPath = null,
                    platform = platform,
                    cacheRoot = cacheRoot,
                ) { bytes },
            )
        val second =
            assertNotNull(
                NativeLibraryLocator.resolve(
                    configuredPath = null,
                    platform = platform,
                    cacheRoot = cacheRoot,
                ) { bytes },
            )

        assertEquals(first, second)
        assertEquals(bytes.toList(), java.nio.file.Path.of(second.path).readBytes().toList())
    }

    @Test
    fun returnsNullWhenNoConfiguredPathAndNoBundledResource() {
        val resolution =
            NativeLibraryLocator.resolve(
                configuredPath = null,
                platform = NativeLibraryPlatform(id = "linux-x86_64", libraryName = "librux_vm.so"),
                cacheRoot = createTempDirectory("ck-native-test"),
            ) {
                null
            }

        assertNull(resolution)
    }

    @Test
    fun returnsNullForUnsupportedPlatform() {
        val resolution =
            NativeLibraryLocator.resolve(
                configuredPath = null,
                platform = null,
                cacheRoot = createTempDirectory("ck-native-test"),
            ) {
                error("unsupported platforms must not read resources")
            }

        assertNull(resolution)
    }
}
