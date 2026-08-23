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

import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinimalScriptLoweringTest {
    @Test
    fun `ordinary and suspend zero argument Unit main lower deterministically`() =
        withAdapter { adapter ->
            listOf(false, true).forEach { suspending ->
                val source = if (suspending) "suspend fun main() {}" else "fun main() {}"
                val first = adapter.compile(request(source))
                val second = adapter.compile(request(source))
                val expected =
                    (ArtifactWriter.write(expectedMainArtifact(suspending)) as ArtifactWriteResult.Success)
                        .bytes

                assertContentEquals(expected, assertNotNull(first.artifact).toByteArray())
                assertContentEquals(expected, assertNotNull(second.artifact).toByteArray())
                assertTrue(first.diagnostics.none { it.severity.name == "ERROR" })
            }
        }

    @Test
    fun `entry policy rejects duplicate and invalid main functions`() =
        withAdapter { adapter ->
            val duplicate =
                adapter.compile(
                    request(
                        "a/Main.kt" to "package a\nfun main() {}",
                        "b/Main.kt" to "package b\nsuspend fun main() {}",
                    ),
                )
            val invalid = adapter.compile(request("fun main(value: String) {}"))

            listOf(duplicate, invalid).forEach { result ->
                assertNull(result.artifact)
                assertTrue(result.diagnostics.any { it.category == DiagnosticCategory.TARGET && it.code == "INVALID_ENTRY_POINT" })
            }
        }

    @Test
    fun `multi-file terminal program lowers through trusted symbols`() =
        withAdapter { adapter ->
            val request =
                request(
                    "project/greeting.kt" to
                        "fun greeting(name: String): String = \"Hello, \" + name + \"!\"",
                    "project/main.kt" to
                        """
                        suspend fun main() {
                            terminalWrite(greeting("Ada"))
                            terminalAwaitEvent()
                        }
                        """.trimIndent(),
                )
            val result = adapter.compile(request)
            val repeated = adapter.compile(request)

            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
            assertContentEquals(artifact.toByteArray(), assertNotNull(repeated.artifact).toByteArray())
            System.getProperty("compukter.vm.kotlinSubsetArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact.toByteArray())
            }
        }

    @Test
    fun `shell language subset lowers control flow scalars strings and raw terminal calls`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        suspend fun main() {
                            var line = ""
                            var running = true
                            var index = 0
                            terminalWrite("> ")
                            while (running) {
                                val kind = terminalAwaitEvent()
                                if (kind == 1) {
                                    val text = terminalEventText()
                                    while (index < text.length) {
                                        val character = text[index]
                                        if (character >= ' ' && character != '\u007f' && line.length < 256) {
                                            val next = text.substring(index, index + 1)
                                            line = line + next
                                            terminalWrite(next)
                                        }
                                        index = index + 1
                                    }
                                } else if (terminalEventKey() == 13 && terminalEventAction() == 1) {
                                    terminalWrite("\n")
                                    if (line == "clear") terminalClear() else terminalWrite(line)
                                    line = ""
                                } else if (terminalEventKey() == 8) {
                                    if (line.length > 0) {
                                        line = line.substring(0, line.length - 1)
                                        terminalErasePrevious()
                                    }
                                }
                                terminalEventModifiers()
                                terminalFinishEvent()
                            }
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `checked in shell compiles deterministically`() =
        withAdapter { adapter ->
            val source = Path.of("../..", "system/programs/shell.kt").readText()
            val first = adapter.compile(request("system/programs/shell.kt" to source))
            val second = adapter.compile(request("system/programs/shell.kt" to source))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukters.shell.artifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `same-named guest function remains an ordinary project call`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        "project/main.kt" to
                            "suspend fun main() { terminalWrite(readln(\"guest\")); terminalAwaitEvent() }",
                        "project/read.kt" to "fun readln(value: String): String = value",
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `typed Int constant lowers to deterministic minimal artifact`() =
        withAdapter { adapter ->
            val first = assertNotNull(adapter.compile(request("val answer: Int = 42")).artifact).toByteArray()
            val second = assertNotNull(adapter.compile(request("val answer: Int = 42")).artifact).toByteArray()
            val expected =
                (ArtifactWriter.write(expectedArtifact()) as ArtifactWriteResult.Success)
                    .bytes

            assertContentEquals(expected, first)
            assertContentEquals(first, second)
            assertContentEquals(sha256(first), sha256(second))
        }

    @Test
    fun `unsupported source IR produces one stable target diagnostic and no artifact`() =
        withAdapter { adapter ->
            listOf(
                "val answer: Long = 42L",
                "val answer: Int = 41 + 1",
                "val answer: Int = 42\nval other: Int = 7",
            ).forEach { source ->
                val result = adapter.compile(request(source))
                val target = result.diagnostics.filter { it.category == DiagnosticCategory.TARGET }

                assertNull(result.artifact, source)
                assertEquals(1, target.size, source)
                assertEquals("UNSUPPORTED_IR", target.single().code, source)
                assertEquals("source IR is outside the minimal script subset", target.single().message, source)
                assertTrue(result.hasErrors, source)
            }
        }

    @Test
    fun `artifact writer failure becomes a bounded internal diagnostic`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        source = "val answer: Int = 42",
                        limits = WorkerLimits(artifactBytes = 1, diagnostics = 1, diagnosticTextBytes = 32),
                    ),
                )

            assertNull(result.artifact)
            assertEquals(1, result.diagnostics.size)
            val diagnostic = result.diagnostics.single()
            assertEquals(DiagnosticCategory.INTERNAL, diagnostic.category)
            assertTrue(diagnostic.code?.startsWith("ARTIFACT_WRITE_") == true)
            assertTrue(diagnostic.message.encodeToByteArray().size <= 32)
        }

    private fun expectedArtifact(): Artifact =
        Artifact(
            manifest = Manifest.minimal(maximumBlockCost = 2u),
            entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
            modules =
                listOf(
                    Module(
                        name = StringId.of(0u),
                        kind = ModuleKind.APPLICATION,
                        strings = listOf(MetadataText.of("app"), MetadataText.of("entry")),
                        types =
                            listOf(
                                NominalType.Function(
                                    name = StringId.of(1u),
                                    suspending = false,
                                    result = ValueType.Unit,
                                    parameters = emptyList(),
                                ),
                            ),
                        constants = listOf(Constant.I32(42)),
                        functions =
                            listOf(
                                Function(
                                    owner = null,
                                    name = StringId.of(1u),
                                    signature = TypeRef.Local(TypeId.of(0u)),
                                    flags = setOf(FunctionFlag.STATIC),
                                    registers = listOf(ValueType.I32),
                                    parameterCount = 0u,
                                    firstBlock = BlockId.of(0u),
                                    blockCount = 1u,
                                    firstException = 0u,
                                    exceptionCount = 0u,
                                ),
                            ),
                        blocks =
                            listOf(
                                Block(
                                    owner = FunctionId.of(0u),
                                    loopHeaderSafepoint = false,
                                    instructions =
                                        listOf(
                                            Instruction.Const(RegisterId.of(0u), ConstantId.of(0u)),
                                            Instruction.Return(Destination.Unit),
                                        ),
                                ),
                            ),
                    ),
                ),
        )

    private fun expectedMainArtifact(suspending: Boolean): Artifact =
        Artifact(
            semanticFeatures =
                if (suspending) {
                    setOf(
                        ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature.COROUTINES,
                    )
                } else {
                    emptySet()
                },
            manifest = Manifest.minimal(maximumBlockCost = 1u),
            entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
            modules =
                listOf(
                    Module(
                        name = StringId.of(0u),
                        kind = ModuleKind.APPLICATION,
                        strings = listOf(MetadataText.of("app"), MetadataText.of("main")),
                        types =
                            listOf(
                                NominalType.Function(
                                    name = StringId.of(1u),
                                    suspending = suspending,
                                    result = ValueType.Unit,
                                    parameters = emptyList(),
                                ),
                            ),
                        functions =
                            listOf(
                                Function(
                                    owner = null,
                                    name = StringId.of(1u),
                                    signature = TypeRef.Local(TypeId.of(0u)),
                                    flags =
                                        setOfNotNull(
                                            FunctionFlag.STATIC,
                                            FunctionFlag.SUSPENDING.takeIf { suspending },
                                        ),
                                    registers = emptyList(),
                                    parameterCount = 0u,
                                    firstBlock = BlockId.of(0u),
                                    blockCount = 1u,
                                    firstException = 0u,
                                    exceptionCount = 0u,
                                ),
                            ),
                        blocks = listOf(Block(FunctionId.of(0u), false, listOf(Instruction.Return(Destination.Unit)))),
                    ),
                ),
        )

    private fun withAdapter(block: (K2CompilerAdapter) -> Unit) {
        val root = createTempDirectory("compukters-minimal-lowering-test-")
        try {
            block(
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
                ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun request(
        source: String,
        limits: WorkerLimits = WorkerLimits(),
    ): CompileRequest = request(listOf("project/main.kt" to source), limits)

    private fun request(vararg sources: Pair<String, String>): CompileRequest = request(sources.toList(), WorkerLimits())

    private fun request(
        sources: List<Pair<String, String>>,
        limits: WorkerLimits,
    ): CompileRequest =
        CompileRequest(
            RequestId.of(1u),
            sources.map { (path, source) ->
                ProjectSource(VirtualSourcePath.kotlin(path), BinaryValue.of(source.encodeToByteArray()))
            },
            TargetSettings.KOTLIN_2_4_JVM_17,
            identity(),
            limits,
        )

    private fun identity() = WorkerIdentity("2.4.10", "2.4", 1u, 1u, Hash256.zero(), Hash256.zero())

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
