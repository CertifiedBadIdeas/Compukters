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

package ru.lazyhat.compukters.ide.analysis.k2.query

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.editor.EditorRange
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeclarationQueryTest {
    @Test
    fun `navigation resolves local top level member and extension declarations`() {
        val declarations =
            """
            package sample

            fun top() = Unit
            class Box { fun member() = Unit }
            fun String.extension() = Unit
            """.trimIndent()
        val usage =
            """
            package sample

            fun use(box: Box) {
                val local = 1
                println(local)
                top()
                box.member()
                "value".extension()
            }
            """.trimIndent()
        K2QueryFixture.source("declarations.kt" to declarations, "usage.kt" to usage).use { fixture ->
            val expectations =
                listOf(
                    "local" to sourceLocation("usage.kt", usage.indexOf("local ="), "local"),
                    "top()" to sourceLocation("declarations.kt", declarations.indexOf("top"), "top"),
                    "member()" to sourceLocation("declarations.kt", declarations.indexOf("member"), "member"),
                    "extension()" to sourceLocation("declarations.kt", declarations.indexOf("extension"), "extension"),
                )

            expectations.forEach { (usageText, expected) ->
                val offset = usage.lastIndexOf(usageText) + 1
                val result =
                    fixture.execute(
                        AnalysisQuery.Declaration(fixture.identity, VirtualSourcePath.kotlin("usage.kt"), offset),
                    ) as AnalysisResult.Declaration

                assertEquals(listOf(expected), result.locations, usageText)
            }
        }
    }

    @Test
    fun `navigation keeps UTF-16 declaration ranges`() {
        val source = "fun привет() = Unit\nval 😀 = ::привет"
        K2QueryFixture.source("unicode.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.Declaration(
                        fixture.identity,
                        VirtualSourcePath.kotlin("unicode.kt"),
                        source.lastIndexOf("привет") + 1,
                    ),
                ) as AnalysisResult.Declaration

            assertEquals(
                listOf(sourceLocation("unicode.kt", source.indexOf("привет"), "привет")),
                result.locations,
            )
        }
    }

    @Test
    fun `navigation returns every declaration candidate for an ambiguous overload reference`() {
        val source =
            """
            fun choose(value: Int) = value
            fun choose(value: String) = value
            val unresolved = ::choose
            """.trimIndent()
        K2QueryFixture.source("overloads.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.Declaration(
                        fixture.identity,
                        VirtualSourcePath.kotlin("overloads.kt"),
                        source.lastIndexOf("choose") + 1,
                    ),
                ) as AnalysisResult.Declaration

            assertEquals(
                listOf(
                    sourceLocation("overloads.kt", source.indexOf("choose"), "choose"),
                    sourceLocation("overloads.kt", source.indexOf("choose", source.indexOf("choose") + 1), "choose"),
                ),
                result.locations,
            )
        }
    }

    @Test
    fun `navigation reports binary only Guest API source as unavailable`() {
        val source = "import compukter.terminal.Terminal\nfun main() = Terminal.write(\"ok\")"
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.Declaration(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        source.indexOf("write") + 1,
                    ),
                ) as AnalysisResult.Declaration

            val unavailable = assertIs<DeclarationLocation.SourceUnavailable>(result.locations.single())
            val bundle = assertIs<DeclarationOrigin.Bundle>(unavailable.origin)
            assertEquals("std.core", bundle.identity.name)
        }
    }

    @Test
    fun `navigation maps an attached Guest API declaration to its source`() {
        val source = "import compukter.terminal.Terminal\nfun main() = Terminal.write(\"ok\")"
        val sourcePath = "compukter-guest-api/compukter/terminal/Terminal.kt"
        val guestApi = Path.of(requireNotNull(System.getProperty("compukters.test.guestApi")))
        val guestSource =
            ZipFile(guestApi.toFile()).use { archive ->
                archive.getInputStream(requireNotNull(archive.getEntry(sourcePath))).reader().readText()
            }
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.Declaration(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        source.indexOf("write") + 1,
                    ),
                ) as AnalysisResult.Declaration

            assertEquals(
                listOf(
                    DeclarationLocation.Source(
                        DeclarationOrigin.Bundle(
                            fixture.snapshot.bundles
                                .single()
                                .identity,
                        ),
                        VirtualSourcePath.kotlin(sourcePath),
                        EditorRange(
                            guestSource.indexOf("write(payload"),
                            guestSource.indexOf("write(payload") + "write".length,
                        ),
                    ),
                ),
                result.locations,
            )
        }
    }

    private fun sourceLocation(
        path: String,
        start: Int,
        name: String,
    ) = DeclarationLocation.Source(
        DeclarationOrigin.Project,
        VirtualSourcePath.kotlin(path),
        EditorRange(start, start + name.length),
    )
}
