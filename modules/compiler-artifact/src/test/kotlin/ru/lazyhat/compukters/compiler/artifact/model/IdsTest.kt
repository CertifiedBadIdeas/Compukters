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

package ru.lazyhat.compukters.compiler.artifact.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdsTest {
    @Test
    fun `module-local identities reserve the import selector bit`() {
        assertEquals(0x7fff_ffffu, TypeId.of(0x7fff_ffffu).value)
        assertFailsWith<IllegalArgumentException> { TypeId.of(0x8000_0000u) }
    }

    @Test
    fun `register identity reserves the absent destination sentinel`() {
        assertEquals(65534u.toUShort(), RegisterId.of(65534u).value)
        assertFailsWith<IllegalArgumentException> { RegisterId.of(65535u) }
    }

    @Test
    fun `local and imported references remain distinct`() {
        val local = TypeRef.Local(TypeId.of(7u))
        val imported = TypeRef.Imported(ImportId.of(7u))

        assertEquals(7u, local.id.value)
        assertEquals(7u, imported.id.value)
    }
}
