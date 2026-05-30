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

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuxComputerRuntimeFactoryTest {
    private val root = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { it.resolve("gradle/libs.versions.toml").toFile().exists() }

    @Test
    fun runtimeFactoryDoesNotExposeResourceOrImageStartup() {
        val methodNames = RuxComputerRuntimeFactory::class.java.methods.map { it.name }.toSet()

        assertFalse("createFromResource" in methodNames)
        assertFalse("loadFirmwareResource" in methodNames)
        val createMethods = RuxComputerRuntimeFactory::class.java.methods.filter { it.name == "create" }
        assertEquals(emptyList(), createMethods)
    }

    @Test
    fun createFromBiosFlashAcceptsOnlyPathInputsForFirmwareAndStorage() {
        val createMethods = RuxComputerRuntimeFactory::class.java.methods.filter { it.name == "createFromBiosFlash" }
        val hasBiosFlashAndStorage0Paths =
            createMethods.any { method ->
                val pathParameterCount = method.parameterTypes.count { parameterType -> parameterType == Path::class.java }
                val byteArrayParameterCount = method.parameterTypes.count { parameterType -> parameterType == ByteArray::class.java }
                pathParameterCount >= 2 && byteArrayParameterCount == 0
            }

        assertEquals(true, hasBiosFlashAndStorage0Paths)
    }

    @Test
    fun restoreFromBiosFlashSnapshotAcceptsSnapshotBytesExplicitly() {
        val restoreMethods = RuxComputerRuntimeFactory::class.java.methods.filter { it.name == "restoreFromBiosFlashSnapshot" }
        val hasExplicitSnapshotRestore =
            restoreMethods.any { method ->
                val pathParameterCount = method.parameterTypes.count { parameterType -> parameterType == Path::class.java }
                val byteArrayParameterCount = method.parameterTypes.count { parameterType -> parameterType == ByteArray::class.java }
                pathParameterCount >= 2 && byteArrayParameterCount == 1
            }

        assertEquals(true, hasExplicitSnapshotRestore)
    }

    @Test
    fun runtimeUsesK16KotlinBindingSurface() {
        val runtimeSource =
            root
                .resolve(
                    Path
                        .of(
                            "modules",
                            "native-runtime",
                            "src",
                            "main",
                            "kotlin",
                            "ru",
                            "lazyhat",
                            "compukterkraft",
                            "lang",
                            "runtime",
                            "blazing",
                            "RuxComputerRuntime.kt",
                        ),
                )
                .readText()
        val bindingsSource =
            root
                .resolve(
                    Path
                        .of(
                            "modules",
                            "native-runtime",
                            "src",
                            "main",
                            "kotlin",
                            "ru",
                            "lazyhat",
                            "compukterkraft",
                            "lang",
                            "runtime",
                            "blazing",
                            "NativeVmBindings.kt",
                        ),
                )
                .readText()

        assertTrue(runtimeSource.contains("NativeK16ComputerRuntimeBindings"))
        assertFalse(runtimeSource.contains("NativeRux16ComputerRuntimeBindings"))
        assertTrue(bindingsSource.contains("fun runK16ComputerUntilSignal(handle: Long)"))
        assertFalse(bindingsSource.contains("fun runRux16ComputerUntilSignal(handle: Long)"))
    }

    @Test
    fun nativeRuntimeMainDoesNotExposeLowImageAbiPackage() {
        val lowImagePackage =
            Path.of(
                "modules",
                "native-runtime",
                "src",
                "main",
                "kotlin",
                "ru",
                "lazyhat",
                "compukterkraft",
                "lang",
                "runtime",
                "image",
                "low",
            )

        assertFalse(lowImagePackage.toFile().exists())
    }
}
