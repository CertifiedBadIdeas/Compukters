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

package ru.lazyhat.compukterkraft.core.computer.vm.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PeripheralCallTest {
    @Test
    fun successFactoryWrapsVarargsIntoValuesList() {
        val result = PeripheralCallResult.success(42, "ok")

        assertEquals(listOf<Any?>(42, "ok"), result.values)
    }

    @Test
    fun failureFactoryProducesFailureWithMessage() {
        val result = PeripheralCallResult.failure("boom")

        val failure = assertIs<PeripheralCallResult.Failure>(result)
        assertEquals("boom", failure.message)
    }

    @Test
    fun noneMethodsAlwaysFailsWithExplanatoryMessage() {
        val result = PeripheralMethods.NONE.call("doStuff", emptyList())

        val failure = assertIs<PeripheralCallResult.Failure>(result)
        assertTrue("doStuff" in failure.message, "Failure message should mention the called method, was: ${failure.message}")
    }

    @Test
    fun customMethodsRouteByName() {
        val methods =
            PeripheralMethods { method, args ->
                when (method) {
                    "echo" -> PeripheralCallResult.success(args.joinToString(separator = "|"))
                    else -> PeripheralCallResult.failure("unknown: $method")
                }
            }

        val ok = methods.call("echo", listOf("a", "b"))
        val bad = methods.call("nope", emptyList())

        assertEquals(PeripheralCallResult.Success(listOf("a|b")), ok)
        assertIs<PeripheralCallResult.Failure>(bad)
    }
}
