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

package ru.lazyhat.compukters.compiler.k2.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompilationSessionTest {
    @Test
    fun `session callable lookup preserves overloads and snapshots each request selection`() {
        val first = PlatformFunctionLink("library.call", "fun(Int):Int", "integer", ByteArray(32))
        val second = PlatformFunctionLink("library.call", "fun(String):String", "string", ByteArray(32))
        val links = mutableListOf(first, second, first)
        val session = CompilationSession(irSink = { _, _ -> }, platformFunctions = links)
        assertEquals(listOf(first, second, first), session.platformFunctionsFor("library.call"))
        assertTrue(session.platformFunctionsFor("project.call").isEmpty())
        assertTrue(session.platformFunctionsFor(null).isEmpty())
        links.clear()
        assertEquals(listOf(first, second, first), session.platformFunctions)
        assertEquals(listOf(first, second, first), session.platformFunctionsFor("library.call"))
        val next = CompilationSession(irSink = { _, _ -> }, platformFunctions = links)
        assertTrue(next.platformFunctionsFor("library.call").isEmpty())
    }
}
