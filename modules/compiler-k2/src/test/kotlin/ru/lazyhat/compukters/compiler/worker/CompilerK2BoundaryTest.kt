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

package ru.lazyhat.compukters.compiler.worker

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import kotlin.test.Test
import kotlin.test.assertEquals

class CompilerK2BoundaryTest {
    @Test
    fun `worker can decode client protocol source`() {
        assertEquals("val answer = 42", BinaryValue.of("val answer = 42".encodeToByteArray()).decodeUtf8())
    }

    @Test
    fun `worker owns the pinned K2 compiler`() {
        val compiler = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
        val version = Class.forName("org.jetbrains.kotlin.config.KotlinCompilerVersion").getMethod("getVersion").invoke(null)

        assertEquals("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", compiler.name)
        assertEquals("2.4.10", version)
    }
}
