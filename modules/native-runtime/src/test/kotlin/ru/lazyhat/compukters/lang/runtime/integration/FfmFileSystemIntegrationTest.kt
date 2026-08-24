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

import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.FileSystemStoreHealth
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.FfmBridge
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FfmFileSystemIntegrationTest {
    @Test
    fun `JDK 25 FFM owns the Rust world store lifecycle`() {
        val root = Files.createTempDirectory("compukters-ffm-store-").toRealPath()
        try {
            FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
                val store = WorldFileSystemStore.open(root, bridge)
                val id = ComputerId.fromLongs(1, 2)
                assertEquals(FileSystemStoreHealth.ACTIVE, store.health())
                store.tombstone(id)
                store.recover(id)
                store.close()
                store.close()
                assertFailsWith<IllegalStateException> { store.health() }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing $name test property" }
}
