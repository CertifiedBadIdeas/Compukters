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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class K2CompilerAdapterTest {
    @Test
    fun `valid script reaches IR and request files are removed`() =
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
            assertEquals("project/virtual.kts", syntaxError.path?.value)
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
    fun `diagnostic count text and physical paths are bounded`() {
        val physical = Path.of("private/request/source/main.kts")
        val collector =
            CompilerDiagnosticCollector(
                "val x = 1",
                physical,
                VirtualSourcePath.of("project/main.kts"),
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

    private fun request(source: String): CompileRequest =
        CompileRequest(
            RequestId.of(1u),
            VirtualSourcePath.of("project/virtual.kts"),
            BinaryValue.of(source.encodeToByteArray()),
            TargetSettings.KOTLIN_2_4_JVM_17,
            identity(),
            WorkerLimits(),
        )

    private fun identity() = WorkerIdentity("2.4.10", "2.4", 1u, 1u, Hash256.zero(), Hash256.zero())
}
