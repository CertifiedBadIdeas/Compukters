/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.compiler.worker.k2

import ru.lazyhat.compukters.compiler.k2.engine.library.LoadedPlatformLibraries
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class PreparedPlatformLibraryCacheTest {
    @Test
    fun `same module identities reuse preparation regardless of request order`() {
        val cache = PreparedPlatformLibraryCache()
        val modules = listOf(identity("stdlib:core"), identity("compukter:core"))
        var loads = 0

        fun load() = emptyLibraries().also { loads++ }

        val first = cache.get(modules, ::load)
        assertSame(first, cache.get(modules.reversed(), ::load))
        assertSame(first, cache.get(modules.map { identity(it.name) }, ::load))
        assertEquals(1, loads)
    }

    @Test
    fun `changing selection evicts the previous preparation`() {
        val cache = PreparedPlatformLibraryCache()
        val core = listOf(identity("stdlib:core"))
        val addon = core + identity("addon:example")
        var loads = 0

        fun load() = emptyLibraries().also { loads++ }

        val first = cache.get(core, ::load)
        val second = cache.get(addon, ::load)
        assertNotSame(first, second)
        assertSame(second, cache.get(addon, ::load))
        assertNotSame(first, cache.get(core, ::load))
        assertEquals(3, loads)
    }

    @Test
    fun `changed content identity reloads a same named module`() {
        val cache = PreparedPlatformLibraryCache()
        var loads = 0

        fun load() = emptyLibraries().also { loads++ }

        val first = cache.get(listOf(identity("addon:example", 1)), ::load)
        val second = cache.get(listOf(identity("addon:example", 2)), ::load)
        assertNotSame(first, second)
        assertSame(second, cache.get(listOf(identity("addon:example", 2)), ::load))
        assertEquals(2, loads)
    }

    @Test
    fun `failed replacement is retried and does not retain an older selection`() {
        val cache = PreparedPlatformLibraryCache()
        val core = listOf(identity("stdlib:core"))
        val addon = core + identity("addon:example")
        val first = cache.get(core, ::emptyLibraries)

        assertFailsWith<IllegalArgumentException> {
            cache.get(addon) { throw IllegalArgumentException("invalid library") }
        }
        assertNotSame(first, cache.get(core, ::emptyLibraries))
        var loads = 0
        val replacement = cache.get(addon) { emptyLibraries().also { loads++ } }
        assertSame(replacement, cache.get(addon) { emptyLibraries().also { loads++ } })
        assertEquals(1, loads)
    }

    private fun identity(
        name: String,
        version: Int = 0,
    ) = TrustedBundleIdentity.of(name, Hash256.of(ByteArray(32) { version.toByte() }))

    private fun emptyLibraries() = LoadedPlatformLibraries(emptyList(), emptyList(), emptyList(), emptyList())
}
