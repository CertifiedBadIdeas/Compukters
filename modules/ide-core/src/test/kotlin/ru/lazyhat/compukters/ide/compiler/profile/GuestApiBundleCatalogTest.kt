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

package ru.lazyhat.compukters.ide.compiler.profile

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ResolvedModule
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GuestApiBundleCatalogTest {
    @Test
    fun `catalog verifies content and owns immutable bytes`() {
        val bytes = byteArrayOf(1, 2, 3)
        val entry = bundle("std:terminal", ApiBundleKind.API, bytes)
        val catalog = GuestApiBundleCatalog.of(listOf(entry))
        bytes[0] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), catalog.require(ModuleId.parse("std:terminal")).content.toByteArray())
        assertFailsWith<IllegalArgumentException> {
            GuestApiBundleCatalog.of(
                listOf(
                    ResolvedApiBundle(
                        entry.module.copy(contentHash = hash(byteArrayOf(4))),
                        entry.kind,
                        entry.content,
                    ),
                ),
            )
        }
    }

    @Test
    fun `catalog rejects duplicate IDs and applies independent bounds`() {
        val entry = bundle("std:terminal", ApiBundleKind.API, byteArrayOf(1, 2, 3))

        assertFailsWith<IllegalArgumentException> { GuestApiBundleCatalog.of(listOf(entry, entry)) }
        assertFailsWith<IllegalArgumentException> {
            GuestApiBundleCatalog.of(listOf(entry), GuestApiBundleCatalogLimits(entries = 0))
        }
        assertFailsWith<IllegalArgumentException> {
            GuestApiBundleCatalog.of(listOf(entry), GuestApiBundleCatalogLimits(entryBytes = 2, totalBytes = 3))
        }
        assertFailsWith<IllegalArgumentException> {
            GuestApiBundleCatalog.of(listOf(entry), GuestApiBundleCatalogLimits(entryBytes = 3, totalBytes = 2))
        }
    }

    @Test
    fun `catalog is canonically ordered by UTF-8 module ID`() {
        val terminal = bundle("std:terminal", ApiBundleKind.API, byteArrayOf(1))
        val create = bundle("create:sensors", ApiBundleKind.ADDON, byteArrayOf(2))
        val catalog = GuestApiBundleCatalog.of(listOf(terminal, create))

        assertEquals(listOf("create:sensors", "std:terminal"), catalog.entries.map { it.module.id.value })
    }

    private fun bundle(
        id: String,
        kind: ApiBundleKind,
        bytes: ByteArray,
    ): ResolvedApiBundle =
        ResolvedApiBundle(
            ResolvedModule(ModuleId.parse(id), ApiMajor(2), "2.1.0", hash(bytes)),
            kind,
            BinaryValue.of(bytes),
        )

    private fun hash(bytes: ByteArray) = Hash256.of(MessageDigest.getInstance("SHA-256").digest(bytes))
}
