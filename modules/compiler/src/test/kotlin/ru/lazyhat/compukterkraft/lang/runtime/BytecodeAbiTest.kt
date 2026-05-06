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

package ru.lazyhat.compukterkraft.lang.runtime

import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
import ru.lazyhat.compukterkraft.lang.frontend.FrontendSeverity
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.runtime.abi.BytecodeAbi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BytecodeAbiTest {
    private val frontend = LanguageFrontend()

    @Test
    fun encodedModuleStartsWithMagicAndVersion() {
        val module = compile("pub fun main() { return }")
        val bytes = BytecodeAbi.encode(module)

        assertContentEquals(
            byteArrayOf('C'.code.toByte(), 'K'.code.toByte(), 'V'.code.toByte(), 'M'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        assertEquals(1, bytes[4].toInt())
    }

    @Test
    fun encodedModuleIsDeterministic() {
        val module =
            compile(
                """
                pub fun add(a: Int, b: Int): Int { return a + b }
                pub fun main() { system::log("x=" + add(1, 2)); }
                """.trimIndent(),
            )

        assertContentEquals(BytecodeAbi.encode(module), BytecodeAbi.encode(module))
    }

    @Test
    fun encodedModuleContainsInstructionTagsForRepresentativeProgram() {
        val module =
            compile(
                """
                pub fun main() {
                    val x: Int = 1 + 2;
                    if (x == 3) { system::log("ok"); }
                }
                """.trimIndent(),
            )

        val bytes = BytecodeAbi.encode(module).toList().map(Byte::toInt)

        assertTrue(BytecodeAbi.Tags.PUSH_INT in bytes)
        assertTrue(BytecodeAbi.Tags.BINARY in bytes)
        assertTrue(BytecodeAbi.Tags.JUMP_IF_FALSE in bytes)
        assertTrue(BytecodeAbi.Tags.CALL_BUILTIN in bytes)
        assertTrue(BytecodeAbi.Tags.RETURN in bytes)
    }

    private fun compile(source: String): BytecodeModule =
        frontend.compile("abi.ck", source).also { artifact ->
            assertTrue(
                artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
                artifact.analysis.diagnostics.joinToString { it.message },
            )
        }.module ?: error("Expected bytecode module")
}
