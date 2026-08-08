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
import kotlin.io.path.absolute
import kotlin.io.path.createTempFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class K16ComputerRuntimeFactoryTest {
    private val root = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { it.resolve("gradle/libs.versions.toml").toFile().exists() }

    @Test
    fun runtimeFactoryDoesNotExposeResourceOrImageStartup() {
        val methodNames = K16ComputerRuntimeFactory::class.java.methods.map { it.name }.toSet()

        assertFalse("createFromResource" in methodNames)
        assertFalse("loadFirmwareResource" in methodNames)
        val createMethods = K16ComputerRuntimeFactory::class.java.methods.filter { it.name == "create" }
        assertEquals(emptyList(), createMethods)
    }

    @Test
    fun createFromBiosFlashAcceptsOnlyPathInputsForFirmwareAndStorage() {
        val createMethods = K16ComputerRuntimeFactory::class.java.methods.filter { it.name == "createFromBiosFlash" }
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
        val restoreMethods = K16ComputerRuntimeFactory::class.java.methods.filter { it.name == "restoreFromBiosFlashSnapshot" }
        val hasExplicitSnapshotRestore =
            restoreMethods.any { method ->
                val pathParameterCount = method.parameterTypes.count { parameterType -> parameterType == Path::class.java }
                val byteArrayParameterCount = method.parameterTypes.count { parameterType -> parameterType == ByteArray::class.java }
                pathParameterCount >= 2 && byteArrayParameterCount == 1
            }

        assertEquals(true, hasExplicitSnapshotRestore)
    }

    @Test
    fun defaultMemorySizeLeavesRoomForNestedTranslatedShells() {
        assertEquals(1024 * 1024, K16ComputerRuntimeFactory.DEFAULT_MEMORY_SIZE)
    }

    @Test
    fun minimumBootMemorySizeDocumentsProductionBootFloor() {
        assertEquals(256 * 1024, K16ComputerRuntimeFactory.MINIMUM_BOOT_MEMORY_SIZE)
        assertTrue(K16ComputerRuntimeFactory.DEFAULT_MEMORY_SIZE > K16ComputerRuntimeFactory.MINIMUM_BOOT_MEMORY_SIZE)
    }

    @Test
    fun defaultMaxTurnsPerTickDocumentsStandaloneRuntimeBudget() {
        assertEquals(8, K16ComputerRuntimeFactory.DEFAULT_MAX_TURNS_PER_TICK)
    }

    @Test
    fun handleFactoryPassesNormalizedOptionalStorage1WithoutChangingMemory() {
        val bindings = RecordingK16ComputerHandleFactoryBindings()
        val factory = K16ComputerRuntimeHandleFactory(bindings, libraryPath = "native-test")
        val bios = createTempFile("k16-bios-")
        val storage0 = createTempFile("k16-storage0-")
        val storage1 = createTempFile("k16-storage1-")

        val handle =
            factory.createFromBiosFlash(
                biosFlashPath = bios,
                storage0Path = storage0,
                storage1 = K16StaticStorageAttachment(storage1.resolve("../${storage1.fileName}")),
                memorySize = 1024 * 1024,
                maxSteps = 77,
            )

        assertEquals(41, handle)
        assertEquals(1024 * 1024, bindings.lastMemorySize)
        assertEquals(storage1.absolute().normalize(), bindings.lastStorage1Path)
    }

    @Test
    fun handleFactoryPreservesAbsentStorage1() {
        val bindings = RecordingK16ComputerHandleFactoryBindings()
        val factory = K16ComputerRuntimeHandleFactory(bindings, libraryPath = "native-test")

        factory.createFromBiosFlash(
            biosFlashPath = createTempFile("k16-bios-"),
            storage0Path = createTempFile("k16-storage0-"),
            storage1 = null,
            memorySize = 512 * 1024,
            maxSteps = 33,
        )

        assertNull(bindings.lastStorage1Path)
    }

    @Test
    fun restoreCarriesStorage1ToTheRetainedGpuGuard() {
        val bindings =
            RecordingK16ComputerHandleFactoryBindings(
                restoreFailure =
                    "K16 runtime snapshot resume is rejected because K16SNAP v1 " +
                        "cannot preserve retained gpu0 state",
            )
        val factory = K16ComputerRuntimeHandleFactory(bindings, libraryPath = "native-test")
        val storage1 = createTempFile("k16-storage1-")

        val error =
            assertFailsWith<IllegalArgumentException> {
                factory.restoreFromBiosFlashSnapshot(
                    biosFlashPath = createTempFile("k16-bios-"),
                    storage0Path = createTempFile("k16-storage0-"),
                    storage1 = K16StaticStorageAttachment(storage1),
                    snapshot = byteArrayOf(1),
                    memorySize = 1024 * 1024,
                )
            }

        assertTrue(error.message.orEmpty().contains("cannot preserve retained gpu0 state"))
        assertEquals(storage1.absolute().normalize(), bindings.lastStorage1Path)
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
                            "K16ComputerRuntime.kt",
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
        assertFalse(runtimeSource.contains("NativeRuxComputerRuntimeBindings"))
        assertTrue(bindingsSource.contains("fun createK16ComputerFromBiosFlash("))
        assertFalse(bindingsSource.contains("fun createRuxComputerFromBiosFlash("))
        assertTrue(bindingsSource.contains("fun restoreK16ComputerFromBiosFlashSnapshot("))
        assertFalse(bindingsSource.contains("fun restoreRuxComputerFromBiosFlashSnapshot("))
        assertTrue(bindingsSource.contains("fun runK16ComputerUntilSignal(handle: Long)"))
        assertFalse(bindingsSource.contains("fun runRuxComputerUntilSignal(handle: Long)"))

        for (requiredName in
            listOf(
                "createK16ComputerFromBiosFlashNative",
                "restoreK16ComputerFromBiosFlashSnapshotNative",
                "advanceK16ComputerGameTickNative",
                "runK16ComputerUntilSignalNative",
                "k16ComputerControlNative",
                "k16ComputerDebugOutputNative",
                "drainK16ComputerDebugOutputNative",
                "k16ComputerStorage0MediaSnapshotNative",
                "k16ComputerMachineSnapshotNative",
                "k16ComputerStatsSnapshotNative",
                "pushK16ComputerSerialInputNative",
                "attachK16ComputerRetainedDisplayViewerNative",
                "detachK16ComputerRetainedDisplayViewerNative",
                "acceptK16ComputerRetainedDisplayServerboundNative",
                "drainK16ComputerRetainedDisplayPayloadNative",
                "drainK16ComputerRetainedDisplayPayloadsNative",
                "freeK16ComputerNative",
            )
        ) {
            assertTrue(bindingsSource.contains(requiredName), "NativeVmBindings.kt should expose $requiredName")
        }

        for (legacyName in
            listOf(
                "createRuxComputerFromBiosFlashNative",
                "restoreRuxComputerFromBiosFlashSnapshotNative",
                "runRuxComputerUntilSignalNative",
                "ruxComputerControlNative",
                "ruxComputerDebugOutputNative",
                "drainRuxComputerDebugOutputNative",
                "k16ComputerDisplay0SnapshotNative",
                "ruxComputerDisplay0SnapshotNative",
                "ruxComputerStorage0MediaSnapshotNative",
                "ruxComputerMachineSnapshotNative",
                "ruxComputerStatsSnapshotNative",
                "pushRuxComputerSerialInputNative",
                "freeRuxComputerNative",
            )
        ) {
            assertFalse(bindingsSource.contains(legacyName), "NativeVmBindings.kt should not expose $legacyName")
        }

        val rustJniSource =
            root
                .resolve(Path.of("host", "k16-vm", "src", "jni.rs"))
                .readText()
        assertTrue(
            rustJniSource.contains("NativeVmBindings_k16ComputerStatsSnapshotNative"),
            "jni.rs should export k16ComputerStatsSnapshotNative",
        )
        assertFalse(rustJniSource.contains("decode_serverbound"))
        assertFalse(rustJniSource.contains("NETWORK_MAGIC"))
        assertFalse(rustJniSource.contains("ResourceDamage"))
    }

    @Test
    fun runtimeApiUsesK16TypeNames() {
        val k16RuntimePath =
            root.resolve(
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
                    "blazing",
                    "K16ComputerRuntime.kt",
                ),
            )
        val legacyRuntimePath =
            root.resolve(
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
                    "blazing",
                    "RuxComputerRuntime.kt",
                ),
            )

        assertTrue(k16RuntimePath.exists())
        assertFalse(legacyRuntimePath.exists())

        val source = k16RuntimePath.readText()
        assertTrue(source.contains("interface K16ComputerRuntimeBindings"))
        assertTrue(source.contains("NativeK16ComputerControl"))
        assertTrue(source.contains("NativeK16ComputerSignal"))
        assertFalse(source.contains("NativeK16ComputerDisplaySnapshot"))
        assertTrue(source.contains("object K16ComputerRuntimeFactory"))
        assertTrue(source.contains("interface K16ComputerEndpoint"))
        assertTrue(source.contains("class K16ComputerRuntime"))
        assertFalse(source.contains("NativeRuxComputer"))
        assertFalse(source.contains("RuxComputerRuntime"))
        assertFalse(source.contains("RuxComputerEndpoint"))
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

    @Test
    fun nativeRuntimeIdeStubsUseK16Branding() {
        val source =
            root
                .resolve(
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
                        "IdeStubs.kt",
                    ),
                )
                .readText()

        assertTrue(source.contains("K16 IDE"))
        assertFalse(source.contains("Rux IDE"))
    }
}

private class RecordingK16ComputerHandleFactoryBindings(
    private val restoreFailure: String? = null,
) : K16ComputerHandleFactoryBindings {
    var lastMemorySize: Int? = null
    var lastStorage1Path: Path? = null

    override fun createFromBiosFlash(
        libraryPath: String,
        biosFlashPath: Path,
        memorySize: Int,
        maxSteps: Long,
        storage0Path: Path,
        storage1Path: Path?,
    ): Long {
        lastMemorySize = memorySize
        lastStorage1Path = storage1Path
        return 41
    }

    override fun restoreFromBiosFlashSnapshot(
        libraryPath: String,
        biosFlashPath: Path,
        memorySize: Int,
        storage0Path: Path,
        storage1Path: Path?,
        snapshot: ByteArray,
    ): Long {
        lastMemorySize = memorySize
        lastStorage1Path = storage1Path
        restoreFailure?.let { throw IllegalArgumentException(it) }
        return 42
    }
}
