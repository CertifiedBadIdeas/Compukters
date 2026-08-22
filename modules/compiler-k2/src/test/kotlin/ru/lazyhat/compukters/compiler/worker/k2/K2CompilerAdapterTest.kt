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

package ru.lazyhat.compukters.compiler.worker.k2

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.controller.TemporaryBudgetException
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticSeverity
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class K2CompilerAdapterTest {
    @Test
    fun `valid Kotlin source reaches IR and request files are removed`() =
        withAdapter { adapter, root ->
            val result = adapter.compile(request("val answer: Int = 42"))
            assertTrue(result.reachedIr)
            assertFalse(result.hasErrors)
            assertNotNull(result.artifact)
            Files.list(root).use { assertEquals(0, it.count()) }
        }

    @Test
    fun `syntax and type diagnostics use virtual paths and UTF-16 offsets`() =
        withAdapter { adapter, _ ->
            val syntax = adapter.compile(request("val emoji = \"😀\"\nval answer = )"))
            val syntaxError =
                syntax.diagnostics.first {
                    it.severity == DiagnosticSeverity.ERROR && it.category == DiagnosticCategory.SYNTAX
                }
            assertEquals(DiagnosticCategory.SYNTAX, syntaxError.category, syntax.diagnostics.toString())
            assertEquals("project/virtual.kt", syntaxError.path?.value)
            assertTrue(syntaxError.startUtf16 != null)
            assertNull(syntax.artifact)

            val type = adapter.compile(request("val answer: MissingType = 42"))
            assertTrue(type.diagnostics.any { it.category == DiagnosticCategory.TYPE && it.severity == DiagnosticSeverity.ERROR })
            assertNull(type.artifact)
        }

    @Test
    fun `dependency refinement and mod imports cannot expand compiler inputs`() =
        withAdapter { adapter, _ ->
            val dependency = adapter.compile(request("@file:DependsOn(\"evil:payload:1\")\nval answer = 42"))
            assertFalse(dependency.reachedIr)
            assertTrue(dependency.diagnostics.any { it.category == DiagnosticCategory.TARGET })

            val modImport = adapter.compile(request("import ru.lazyhat.compukters.Compukters\nval answer = Compukters"))
            assertFalse(modImport.reachedIr)
            assertTrue(modImport.diagnostics.any { it.category == DiagnosticCategory.TYPE })
        }

    @Test
    fun `cross-file reference participates in one K2 session before bounded lowering`() =
        withAdapter { adapter, _ ->
            val result =
                adapter.compile(
                    request(
                        listOf(
                            source("project/Helper.kt", "package project\nfun shared() = 41"),
                            source("project/Main.kt", "package project\nval answer = shared() + 1"),
                        ),
                    ),
                )

            assertTrue(result.reachedIr)
            val unsupported = result.diagnostics.single { it.code == "UNSUPPORTED_IR" }
            assertEquals("project/Main.kt", unsupported.path?.value)
            assertTrue(unsupported.startUtf16 != null)
            assertFalse(result.diagnostics.any { it.message.contains("unresolved reference", ignoreCase = true) })
        }

    @Test
    fun `diagnostic count text and physical paths are bounded`() {
        val physical = Path.of("private/request/source/main.kt")
        val collector =
            CompilerDiagnosticCollector(
                "val x = 1",
                physical,
                VirtualSourcePath.kotlin("project/main.kt"),
                WorkerLimits(diagnostics = 1, diagnosticTextBytes = 8),
            )
        repeat(3) {
            collector.report(
                CompilerMessageSeverity.ERROR,
                "$physical diagnostic text that is too long",
                CompilerMessageLocation.create(physical.toString(), 1, 5, "val x = 1"),
            )
        }

        assertEquals(1, collector.diagnostics.size)
        assertTrue(
            collector.diagnostics
                .single()
                .message
                .encodeToByteArray()
                .size <= 8,
        )
        assertFalse(
            collector.diagnostics
                .single()
                .message
                .contains("private/request"),
        )
        assertTrue(collector.hasErrors())
    }

    @Test
    fun `request temporary storage budget is enforced and cleaned`() =
        withAdapter { adapter, root ->
            assertFailsWith<TemporaryBudgetException> {
                adapter.compile(request("val answer: Int = 42", WorkerLimits(temporaryBytes = 0)))
            }
            Files.list(root).use { assertEquals(0, it.count()) }
        }

    private fun withAdapter(block: (K2CompilerAdapter, Path) -> Unit) {
        val root = createTempDirectory("compukters-k2-adapter-test-")
        try {
            val adapter =
                K2CompilerAdapter(
                    K2CompilerInputs(
                        temporaryRoot = root,
                        workerJar = Path.of(checkNotNull(System.getProperty("compukters.worker.jar"))),
                        standardLibrary =
                            Path.of(
                                Unit::class.java.protectionDomain.codeSource.location
                                    .toURI(),
                            ),
                        jdkHome = Path.of(System.getProperty("java.home")),
                        expectedIdentity = identity(),
                    ),
                )
            block(adapter, root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun request(
        source: String,
        limits: WorkerLimits = WorkerLimits(),
    ): CompileRequest = request(listOf(source("project/virtual.kt", source)), limits)

    private fun request(
        sources: List<ProjectSource>,
        limits: WorkerLimits = WorkerLimits(),
    ): CompileRequest =
        CompileRequest(
            RequestId.of(1u),
            sources,
            TargetSettings.KOTLIN_2_4_JVM_17,
            identity(),
            limits,
        )

    private fun source(
        path: String,
        content: String,
    ) = ProjectSource(VirtualSourcePath.kotlin(path), BinaryValue.of(content.encodeToByteArray()))

    private fun identity() = WorkerIdentity("2.4.10", "2.4", 1u, 1u, Hash256.zero(), Hash256.zero())
}
