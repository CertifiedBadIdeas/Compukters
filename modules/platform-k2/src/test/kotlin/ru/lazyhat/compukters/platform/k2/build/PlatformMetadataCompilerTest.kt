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

package ru.lazyhat.compukters.platform.k2.build

import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlatformMetadataCompilerTest {
    private val compiler = PlatformMetadataCompiler()
    private val module = PlatformModuleId("test", "library")

    @Test
    fun `metadata and export indexes are deterministic`() {
        val first = source("z.kt", "package sample\npublic external fun zebra(value: Int): String")
        val second = source("a.kt", "package sample\npublic class Alpha")

        val forward = compiler.compile(module, listOf(first, second))
        val reverse = compiler.compile(module, listOf(second, first))

        assertContentEquals(forward.metadata.toByteArray(), reverse.metadata.toByteArray())
        assertEquals(listOf("sample.Alpha", "sample.zebra"), forward.exportedSymbols)
        assertEquals(forward.declarations, reverse.declarations)
    }

    @Test
    fun `declarations preserve exact source ranges and external state`() {
        val text = "package sample\n// 😀 keeps offsets UTF-16\npublic external fun ping(value: Int): String\n"
        val result = compiler.compile(module, listOf(source("api.kt", text)))
        val declaration = result.declarations.single()

        assertEquals("sample.ping", declaration.symbol)
        assertEquals("fun(Int):String", declaration.signature)
        assertEquals("api.kt", declaration.sourcePath)
        assertEquals(text.indexOf("public external fun ping"), declaration.startUtf16)
        assertEquals(text.indexOf("String") + "String".length, declaration.endUtf16)
        assertTrue(declaration.trustedExternal)
        assertEquals("public external fun ping(value: Int): String", text.substring(declaration.startUtf16, declaration.endUtf16))
    }

    @Test
    fun `duplicate canonical declarations are rejected`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                compiler.compile(
                    module,
                    listOf(
                        source("one.kt", "package sample\nfun duplicate(value: Int): Int = value"),
                        source("two.kt", "package sample\nfun duplicate(value: Int): Int = value"),
                    ),
                )
            }

        assertTrue(failure.message.orEmpty().contains("duplicate platform declaration sample.duplicate fun(Int):Int"))
    }

    @Test
    fun `metadata decode rejects a source signature mismatch`() {
        val compiled = compiler.compile(module, listOf(source("api.kt", "package sample\nexternal fun ping(): Int")))
        val decoded = PlatformMetadataCodec.decode(compiled.metadata)
        val mismatched = decoded.copy(declarations = decoded.declarations.map { it.copy(signature = "fun():String") })

        assertFailsWith<IllegalArgumentException> {
            PlatformMetadataCodec.validateAgainstSources(mismatched, listOf(source("api.kt", "package sample\nexternal fun ping(): Int")))
        }
    }

    @Test
    fun `explicit primary constructors and member properties are indexed`() {
        val result =
            compiler.compile(
                module,
                listOf(
                    source(
                        "Token.kt",
                        "package sample\nclass Token public constructor(val value: Int) { val doubled: Int get() = value + value }",
                    ),
                ),
            )

        assertEquals(
            listOf(
                "sample.Token" to "class(Int)",
                "sample.Token.<init>" to "constructor(Int)",
                "sample.Token.doubled" to "val():Int",
            ),
            result.declarations.map { it.symbol to it.signature },
        )
    }

    private fun source(
        path: String,
        content: String,
    ): PlatformSource = PlatformSource(path, ImmutableBytes.of(content.encodeToByteArray()))
}
