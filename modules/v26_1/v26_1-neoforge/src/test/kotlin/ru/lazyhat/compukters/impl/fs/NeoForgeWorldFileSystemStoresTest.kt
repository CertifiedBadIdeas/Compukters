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

package ru.lazyhat.compukters.impl.fs

import org.junit.jupiter.api.io.TempDir
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class NeoForgeWorldFileSystemStoresTest {
    @TempDir
    lateinit var world: Path

    @Test
    fun `store root is exact and each world opens once`() {
        val opened = mutableListOf<Path>()
        val registry =
            WorldFileSystemStoreRegistry(
                opener = { path ->
                    opened.add(path)
                    FakeStore()
                },
                flusher = { _, _, _ -> },
                closer = { it.closeCalls++ },
            )

        registry.store(world)
        registry.store(world.resolve(".").normalize())

        assertEquals(listOf(world.toRealPath().resolve("compukters/filesystems").toRealPath()), opened)
    }

    @Test
    fun `save flushes active generations and stop drains before closing once`() {
        val events = mutableListOf<String>()
        val registry =
            WorldFileSystemStoreRegistry(
                opener = { FakeStore() },
                flusher = { _, id, generation -> events += "flush:$id:$generation" },
                closer = { store ->
                    store.closeCalls++
                    events += "close"
                },
            )
        val id = ComputerId.fromLongs(1, 2)
        val store = registry.store(world)
        val lifecycle = registry.lifecycle(world)
        var generation = 3L
        lifecycle.attach(id, { generation }, {
            events += "drain"
            5L
        })

        registry.save(world)
        generation = 4
        registry.stop(world)
        registry.stop(world)

        assertEquals(listOf("flush:$id:3", "drain", "flush:$id:5", "close"), events)
        assertEquals(1, store.closeCalls)
    }

    @Test
    fun `releasing a carrier flushes it without closing world storage`() {
        val events = mutableListOf<String>()
        val registry =
            WorldFileSystemStoreRegistry(
                opener = { FakeStore() },
                flusher = { _, _, generation -> events += "flush:$generation" },
                closer = { it.closeCalls++ },
            )
        val store = registry.store(world)
        val lease = registry.lifecycle(world).attach(ComputerId.fromLongs(7, 8), { 9 }, { 9 })

        lease.release(10)
        registry.save(world)

        assertEquals(listOf("flush:10"), events)
        assertEquals(0, store.closeCalls)
    }

    private class FakeStore {
        var closeCalls = 0
    }
}
