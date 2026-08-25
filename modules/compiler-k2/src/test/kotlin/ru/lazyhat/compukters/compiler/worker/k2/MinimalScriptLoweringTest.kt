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

package ru.lazyhat.compukters.compiler.worker.k2

import compukter.system.kotlinc.kotlincError
import compukter.system.kotlinc.kotlincOutput
import compukter.system.kotlinc.kotlincSource
import compukter.system.shell.shellCommand
import compukter.system.shell.shellCommandLine
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
    fun `shell separates one executable name from its bounded raw tail`() {
        assertEquals("kotlinc", shellCommand("kotlinc foo.kt -o hello"))
        assertEquals("foo.kt -o hello", shellCommandLine("kotlinc foo.kt -o hello"))
        assertEquals("hello", shellCommand("hello"))
        assertEquals("", shellCommandLine("hello"))
        assertEquals("/rom/hello", shellCommand("/rom/hello raw tail"))
        assertEquals("raw tail", shellCommandLine("/rom/hello raw tail"))
    }

    @Test
    fun `kotlinc command line accepts one source and optional output`() {
        assertEquals("usage: kotlinc <source.kt> [-o output]", kotlincError(""))
        assertEquals("/home/foo.kt", kotlincSource("foo.kt"))
        assertEquals("/home/foo", kotlincOutput("foo.kt"))
        assertEquals("", kotlincError("foo.kt"))
        assertEquals("/home/foo.kt", kotlincSource("foo.kt -o bin/hello"))
        assertEquals("/home/bin/hello", kotlincOutput("foo.kt -o bin/hello"))
        assertEquals("/src/foo.kt", kotlincSource("/src/foo.kt -o /bin/hello"))
        assertEquals("/bin/hello", kotlincOutput("/src/foo.kt -o /bin/hello"))
    }

    @Test
    fun `kotlinc command line rejects ambiguous or unsupported arguments`() {
        assertTrue(kotlincError("foo.kt bar.kt").isNotEmpty())
        assertTrue(kotlincError("foo.txt").contains(".kt"))
        assertTrue(kotlincError("foo.kt -o").isNotEmpty())
        assertTrue(kotlincError("foo.kt -o out -o other").contains("duplicate"))
        assertTrue(kotlincError("-o out").isNotEmpty())
    }

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
                        import compukter.terminal.Terminal

                        suspend fun main() {
                            Terminal.write(greeting("Ada"))
                            Terminal.awaitEvent()
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
    fun `primitive char array lowers deterministically for exact utf16 materialization`() =
        withAdapter { adapter ->
            val request =
                request(
                    "project/main.kt" to
                        """
                        import compukter.terminal.Terminal

                        fun main() {
                            val value = CharArray(5)
                            value[0] = 'A'
                            value[1] = '\uD83D'
                            value[2] = '\uDE00'
                            value[3] = 'Z'
                            value[4] = '!'
                            val last = value[value.size - 1]
                            if (last == '!') Terminal.write(value.concatToString(0, 4))
                        }
                        """.trimIndent(),
                )
            val first = adapter.compile(request)
            val second = adapter.compile(request)

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.kotlinSubsetArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `suspend project call lowers deterministically for vm execution`() =
        withAdapter { adapter ->
            val request =
                request(
                    "project/main.kt" to
                        """
                        import compukter.terminal.Terminal

                        suspend fun readKey(): Int {
                            Terminal.awaitEvent()
                            return Terminal.eventKey()
                        }

                        suspend fun main() {
                            Terminal.write(if (readKey() == 13) "enter" else "other")
                        }
                        """.trimIndent(),
                )
            val first = adapter.compile(request)
            val second = adapter.compile(request)

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.suspendCallArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `same-named char array helper remains an ordinary project call`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        fun CharArray.concatToString(startIndex: Int, endIndex: Int): String = "guest"

                        fun main() {
                            val value = CharArray(1)
                            value.concatToString(0, 1)
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `positional terminal facade lowers through exact trusted signatures`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.terminal.Terminal

                        fun main() {
                            Terminal.setCursor(1, 2)
                            Terminal.setCursorVisible(false)
                            Terminal.setColors(15, 0)
                            Terminal.writeAt(1, 2, "text")
                            Terminal.fill(0, 3, 51, 1, ' ')
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `unbounded terminal loop compiles without executing guest code`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.terminal.Terminal

                        fun main() {
                            while (true) {
                                Terminal.write("yes")
                            }
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `filesystem text facade lowers through exact trusted signatures`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.filesystem.FileSystem

                        fun main() {
                            val contents = FileSystem.readText("notes.txt")
                            FileSystem.writeText("copy.txt", contents)
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `shell language subset lowers control flow scalars strings and raw terminal calls`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.terminal.Terminal

                        suspend fun main() {
                            var line = ""
                            var running = true
                            var index = 0
                            Terminal.write("> ")
                            while (running) {
                                val kind = Terminal.awaitEvent()
                                if (kind == 1) {
                                    val text = Terminal.eventText()
                                    while (index < text.length) {
                                        val character = text[index]
                                        if (character >= ' ' && character != '\u007f' && line.length < 256) {
                                            val next = text.substring(index, index + 1)
                                            line = line + next
                                            Terminal.write(next)
                                        }
                                        index = index + 1
                                    }
                                } else if (Terminal.eventKey() == 13 && Terminal.eventAction() == 1) {
                                    Terminal.write("\n")
                                    if (line == "clear") Terminal.clear() else Terminal.write(line)
                                    line = ""
                                } else if (Terminal.eventKey() == 8) {
                                    if (line.length > 0) {
                                        line = line.substring(0, line.length - 1)
                                        Terminal.erasePrevious()
                                    }
                                }
                                Terminal.eventModifiers()
                                Terminal.finishEvent()
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
    fun `checked in boot compiles deterministically with process intrinsic`() =
        withAdapter { adapter ->
            val source = Path.of("../..", "system/programs/boot.kt").readText()
            val first = adapter.compile(request("system/programs/boot.kt" to source))
            val second = adapter.compile(request("system/programs/boot.kt" to source))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukters.boot.artifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `checked in kotlinc compiles deterministically`() =
        withAdapter { adapter ->
            val source = Path.of("../..", "system/programs/kotlinc.kt").readText()
            val first = adapter.compile(request("system/programs/kotlinc.kt" to source))
            val second = adapter.compile(request("system/programs/kotlinc.kt" to source))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukters.kotlinc.artifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `checked in editor compiles deterministically`() =
        withAdapter { adapter ->
            val source = Path.of("../..", "system/programs/edit.kt").readText()
            val first = adapter.compile(request("system/programs/edit.kt" to source))
            val second = adapter.compile(request("system/programs/edit.kt" to source))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukters.edit.artifact")?.let { output ->
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
                            "import compukter.terminal.Terminal\nsuspend fun main() { Terminal.write(readln(\"guest\")); Terminal.awaitEvent() }",
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
