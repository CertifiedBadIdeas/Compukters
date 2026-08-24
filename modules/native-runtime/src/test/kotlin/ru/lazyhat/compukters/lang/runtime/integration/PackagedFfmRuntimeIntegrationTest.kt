/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.lang.runtime.integration

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.FileSystemStoreHealth
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntimeLoadResult
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PackagedFfmRuntimeIntegrationTest {
    @Test
    fun `packaged FFM resource loads and executes terminal fixture`() =
        runBlocking {
            assertIs<VmRuntimeLoadResult.Loaded>(VmRuntime.ensureLoaded())
            ShellProgram.run(Path.of(requiredProperty("compukters.shell.artifact")))
        }

    @Test
    fun `packaged FFM resource exposes ABI v2 store lifecycle`() {
        assertIs<VmRuntimeLoadResult.Loaded>(VmRuntime.ensureLoaded())
        val root = Files.createTempDirectory("compukters-packaged-store-").toRealPath()
        try {
            WorldFileSystemStore.open(root).use { store ->
                val computerId = ComputerId.fromLongs(41, 42)
                assertEquals(FileSystemStoreHealth.ACTIVE, store.health())
                VmSession
                    .openInStore(
                        Files.readAllBytes(Path.of(requiredProperty("compukters.shell.artifact"))),
                        store,
                        computerId,
                        emptyRom(),
                    ).use { }
                assertEquals(0, store.durableGeneration(computerId))
                store.flush(computerId, 0)
                store.tombstone(computerId)
                store.recover(computerId)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing test system property $name" }

    private fun emptyRom(): ByteArray {
        val header =
            ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTROM\u0000".encodeToByteArray())
                .putShort(1.toShort())
                .putShort(0.toShort())
                .putInt(0)
                .array()
        return header + MessageDigest.getInstance("SHA-256").digest(header)
    }
}
