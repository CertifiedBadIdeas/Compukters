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

import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.compiler.artifact.model.AbiVersion
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.ScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.StringValueType
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.Utf16Literal
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.read.ArtifactReader
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundlePayload
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
    fun `same-named guest calls preserve their resolved targets for vm conformance`() =
        withAdapter { adapter ->
            val source =
                """
                fun plus(left: Int, right: Int): Int = left * 10 + right
                fun get(value: String, index: Int): Char = 'Z'
                fun equals(left: String, right: String): Boolean = false
                fun less(left: Int, right: Int): Boolean = false
                fun iterator(value: Int): Int = value + 20
                fun next(value: Int): Int = value + 30

                fun main() {
                    println(plus(2, 3))
                    println(get("a", 0))
                    println(equals("a", "a"))
                    println(less(1, 2))
                    println(iterator(4))
                    println(next(5))
                    println(2 + 3)
                    println("a"[0])
                    println("a" == "a")
                    println(1 < 2)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()

            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
            System.getProperty("compukter.vm.namedCallsArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `user less function with compareTo argument lowers to a local call`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        fun less(value: Int, limit: Int): Boolean = false

                        fun main() {
                            println(less(1.compareTo(2), 0))
                        }
                        """.trimIndent(),
                    ),
                )
            val artifact = ArtifactReader.read(assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray())
            val application = artifact.modules.single { it.kind == ModuleKind.APPLICATION }
            val lessId =
                application.functions
                    .withIndex()
                    .single { (_, function) ->
                        application.strings[function.name.value.toInt()].toString() == "less"
                    }.index

            assertTrue(
                application.blocks.flatMap(Block::instructions).any { instruction ->
                    instruction is Instruction.Call && instruction.function == FunctionRef.Local(FunctionId.of(lessId.toUInt()))
                },
            )
        }

    @Test
    fun `unsupported nullable forms do not publish artifacts`() =
        withAdapter { adapter ->
            listOf(
                "fun main() { val value: Int? = null }",
                "fun main() { val text: String? = null; val length = text?.length }",
                "fun main() { val array: CharArray? = null }",
                "fun main() { val operation: (() -> Unit)? = null }",
                "fun main() { val text: String? = null; val value = text!! }",
            ).forEach { source ->
                val result = adapter.compile(request(source))
                val errors = result.diagnostics.filter { it.severity.name == "ERROR" }

                assertNull(result.artifact, source)
                assertEquals(1, errors.size, source)
                assertEquals(DiagnosticCategory.TARGET, errors.single().category, source)
                assertTrue(result.hasErrors, source)
            }
        }

    @Test
    fun `nullable references lower null comparisons Elvis and reference safe calls`() =
        withAdapter { adapter ->
            val source =
                """
                class Node(val name: String?) {
                    fun read(): String? {
                        println("read")
                        return name
                    }
                }
                class Box<T>(val value: T)

                val global: String? = null
                val globalPresent: String? = "global-present"

                fun choose(node: Node?): String? = node?.read()
                fun empty(): String? = null
                fun prefix(text: String?): String? = text?.substring(0, 2)
                fun unbox(box: Box<String>?): String? = box?.value
                fun fresh(): Node? {
                    println("fresh")
                    return Node("fresh-value")
                }
                fun missingNode(): Node? {
                    println("missing-node")
                    return null
                }

                fun fallback(): String {
                    println("fallback")
                    return "missing"
                }

                fun main() {
                    val absent: Node? = null
                    val present: Node? = Node("ready")
                    println(absent == null)
                    println(present != null)
                    println(choose(absent) ?: fallback())
                    println(choose(present) ?: fallback())
                    println(choose(Node(null)) ?: fallback())
                    val first: String? = "same"
                    val second: String? = "sa" + "me"
                    println(first == second)
                    println(global ?: "global")
                    println(globalPresent ?: "global")
                    println(global == null)
                    println(global != globalPresent)
                    println(empty() ?: "empty")
                    var changing: String? = null
                    changing = "later"
                    println(changing ?: "absent")
                    changing = null
                    println(changing ?: "again")
                    println(prefix(null) ?: "none")
                    println(prefix("ready") ?: "none")
                    println(unbox(null) ?: "none")
                    println(unbox(Box("boxed")) ?: "none")
                    println(fresh()?.read() ?: fallback())
                    println(missingNode()?.read() ?: fallback())
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val artifact = ArtifactReader.read(bytes)
            val instructions = artifact.modules.flatMap { module -> module.blocks.flatMap(Block::instructions) }

            assertTrue(instructions.any { it is Instruction.Null })
            assertTrue(instructions.any { it is Instruction.RefEqual || it is Instruction.RefNotEqual })
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
            System.getProperty("compukter.vm.nullableReferenceArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `task tick sleep lowers to one asynchronous timer request`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.concurrent.Tasks

                fun main() {
                    Tasks.sleepTicks(12)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val artifact = ArtifactReader.read(bytes)
            val module = artifact.modules.first()
            val timer =
                artifact.capabilities.single { capability ->
                    module.strings[capability.namespace.value.toInt()].toString() == "compukter" &&
                        module.strings[capability.name.value.toInt()].toString() == "timer"
                }

            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
            assertEquals(AbiVersion(1u, 0u), timer.abi)
            assertEquals(1u, timer.operationCount)
            assertEquals(1, allOpcodes(bytes).count { it == 0xe9 })
            System.getProperty("compukter.vm.timerArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `Long arithmetic conversions comparisons and text lower for vm conformance`() =
        withAdapter { adapter ->
            val source =
                """
                val base = 3_000_000_000L

                fun calculate(value: Int): Long {
                    val arithmetic = ((((value + base) + 0L) - 1L) * 2L / 3) % 7L
                    val bits = (((arithmetic or 8L) xor 1L) and 15L) shl 2
                    return ((bits shr 1) ushr 1).inv().inv()
                }

                fun leftOperand(): Int {
                    println("left")
                    return Int.MAX_VALUE
                }

                fun rightOperand(): Long {
                    println("right")
                    return Long.MAX_VALUE
                }

                fun main() {
                    val result = calculate(5)
                    val ordered = 5 < base && base > 5 && result >= 8L
                    println(result)
                    println("value=" + result)
                    println("${'$'}{-result}:${'$'}ordered:${'$'}{Long.MIN_VALUE}:${'$'}{Long.MAX_VALUE}:${'$'}{result.toInt()}:${'$'}{7.toLong()}")
                    println(Int.MIN_VALUE.compareTo(Int.MAX_VALUE))
                    println(Int.MAX_VALUE.compareTo(Int.MIN_VALUE))
                    println(7.compareTo(7))
                    println(Long.MIN_VALUE.compareTo(Long.MAX_VALUE))
                    println(Long.MAX_VALUE.compareTo(Long.MIN_VALUE))
                    println(7L.compareTo(7L))
                    println(Int.MAX_VALUE.compareTo(Long.MAX_VALUE))
                    println(Long.MIN_VALUE.compareTo(Int.MIN_VALUE))
                    println(7L.compareTo(7))
                    println(leftOperand().compareTo(rightOperand()))
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val bytes = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            val artifact = ArtifactReader.read(bytes)
            val instructions = artifact.modules.flatMap { module -> module.blocks.flatMap(Block::instructions) }

            assertContentEquals(bytes, assertNotNull(second.artifact).toByteArray())
            assertEquals(AbiVersion(1u, 3u), artifact.minimumRuntimeAbi)
            assertTrue(instructions.any { it is Instruction.Add && it.type == ScalarValueType.I64 })
            assertTrue(instructions.any { it is Instruction.Subtract && it.type == ScalarValueType.I64 })
            assertTrue(instructions.any { it is Instruction.Multiply && it.type == ScalarValueType.I64 })
            assertTrue(instructions.any { it is Instruction.Divide && it.type == ScalarValueType.I64 })
            assertTrue(instructions.any { it is Instruction.Remainder && it.type == ScalarValueType.I64 })
            assertTrue(instructions.any { it is Instruction.BitAnd && it.type == ScalarValueType.I64 })
            assertTrue(instructions.any { it is Instruction.ShiftRight && it.type == ScalarValueType.I64 })
            assertTrue(instructions.any { it is Instruction.StringValueOf && it.type == StringValueType.I64 })
            assertTrue(instructions.any { it is Instruction.Convert })
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())

            val numericOnly =
                adapter.compile(
                    request("fun main() { val value = 7.toLong() + 3_000_000_000L; value > 0L }"),
                )
            val numericArtifact =
                ArtifactReader.read(
                    assertNotNull(numericOnly.artifact, numericOnly.diagnostics.joinToString()).toByteArray(),
                )
            assertEquals(AbiVersion(1u, 0u), numericArtifact.minimumRuntimeAbi)

            val consoleOnly = adapter.compile(request("fun main() { println(7L) }"))
            val consoleArtifact =
                ArtifactReader.read(
                    assertNotNull(consoleOnly.artifact, consoleOnly.diagnostics.joinToString()).toByteArray(),
                )
            assertEquals(AbiVersion(1u, 3u), consoleArtifact.minimumRuntimeAbi)

            System.getProperty("compukter.vm.longArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `Float arithmetic conversions comparisons and text lower for vm conformance`() =
        withAdapter { adapter ->
            val source =
                """
                val base = 1.5F

                fun calculate(value: Int): Float {
                    val arithmetic = ((((value + base) - 0.5F) * 2L) / 3) % 7.0F
                    return -arithmetic
                }

                fun floatOperand(): Float {
                    println("float-left")
                    return -0.0F
                }

                fun integerOperand(): Int {
                    println("int-right")
                    return 0
                }

                fun main() {
                    val result = calculate(5)
                    val ordered = 1 < base && base < 2L && result <= -4.0F
                    println(result)
                    println("value=" + result)
                    println("${'$'}ordered:${'$'}{3.toFloat()}:${'$'}{4L.toFloat()}:${'$'}{3.9F.toInt()}:${'$'}{(-3.9F).toLong()}")
                    println("${'$'}{Float.MIN_VALUE}:${'$'}{Float.MAX_VALUE}:${'$'}{Float.POSITIVE_INFINITY}:${'$'}{Float.NEGATIVE_INFINITY}:${'$'}{Float.NaN}:${'$'}{-0.0F}")
                    println(Float.NaN.compareTo(Float.NaN))
                    println(Float.NaN.compareTo(Float.POSITIVE_INFINITY))
                    println(Float.NEGATIVE_INFINITY.compareTo(Float.NaN))
                    println((-0.0F).compareTo(0.0F))
                    println(0.0F.compareTo(-0.0F))
                    println(0.0F.compareTo(0.0F))
                    println(1.5F.compareTo(2))
                    println(2.compareTo(1.5F))
                    println(2L.compareTo(2.0F))
                    println(Float.NaN.compareTo(1L))
                    println(1L.compareTo(Float.NaN))
                    println(0.compareTo(-0.0F))
                    println((-0.0F).compareTo(0L))
                    println(floatOperand().compareTo(integerOperand()))
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val bytes = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            val artifact = ArtifactReader.read(bytes)
            val instructions = artifact.modules.flatMap { module -> module.blocks.flatMap(Block::instructions) }

            assertContentEquals(bytes, assertNotNull(second.artifact).toByteArray())
            assertEquals(AbiVersion(1u, 4u), artifact.minimumRuntimeAbi)
            assertTrue(instructions.any { it is Instruction.Add && it.type == ScalarValueType.F32 })
            assertTrue(instructions.any { it is Instruction.Subtract && it.type == ScalarValueType.F32 })
            assertTrue(instructions.any { it is Instruction.Multiply && it.type == ScalarValueType.F32 })
            assertTrue(instructions.any { it is Instruction.Divide && it.type == ScalarValueType.F32 })
            assertTrue(instructions.any { it is Instruction.Remainder && it.type == ScalarValueType.F32 })
            assertTrue(instructions.any { it is Instruction.StringValueOf && it.type == StringValueType.F32 })
            assertTrue(instructions.any { it is Instruction.Convert })
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())

            val numericOnly = adapter.compile(request("fun main() { val value = 1.toFloat() + 2L; value > 0.0F }"))
            val numericArtifact =
                ArtifactReader.read(
                    assertNotNull(numericOnly.artifact, numericOnly.diagnostics.joinToString()).toByteArray(),
                )
            assertEquals(AbiVersion(1u, 0u), numericArtifact.minimumRuntimeAbi)

            val consoleOnly = adapter.compile(request("fun main() { println(1.5F) }"))
            val consoleArtifact =
                ArtifactReader.read(
                    assertNotNull(consoleOnly.artifact, consoleOnly.diagnostics.joinToString()).toByteArray(),
                )
            assertEquals(AbiVersion(1u, 4u), consoleArtifact.minimumRuntimeAbi)

            System.getProperty("compukter.vm.floatArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `String compareTo and ordering operators lower UTF-16 order for vm conformance`() =
        withAdapter { adapter ->
            val source =
                """
                fun leftOperand(): String {
                    println("left")
                    return "abc"
                }

                fun rightOperand(): String {
                    println("right")
                    return "abd"
                }

                fun main() {
                    println("".compareTo(""))
                    println("".compareTo("a"))
                    println("ab".compareTo("abcd"))
                    println("abcd".compareTo("ab"))
                    println("c".compareTo("a"))
                    println("abc".compareTo("abc"))
                    println("😀".compareTo("😁"))
                    println("😀".compareTo("🀄"))
                    println(leftOperand().compareTo(rightOperand()))
                    println("a" < "b")
                    println("a" <= "a")
                    println("b" <= "a")
                    println("a" > "b")
                    println("b" >= "b")
                    println("a" >= "b")
                    println("ab" < "abc")
                    println("abc" > "ab")
                    println("😀" < "😁")
                    println("😀" > "🀄")
                    println(leftOperand() < rightOperand())
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val bytes = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            val artifact = ArtifactReader.read(bytes)
            val instructions = artifact.modules.flatMap { module -> module.blocks.flatMap(Block::instructions) }

            assertContentEquals(bytes, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            assertTrue(instructions.any { it is Instruction.StringLength })
            assertTrue(instructions.any { it is Instruction.StringGet })
            assertTrue(instructions.any { it is Instruction.Convert })
            val operatorsOnly = adapter.compile(request("fun main() { println(\"a\" < \"b\") }"))
            assertNotNull(operatorsOnly.artifact, operatorsOnly.diagnostics.joinToString())
            System.getProperty("compukter.vm.stringCompareArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `top level IntChannel lowers to VM owned bounded handoff`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.concurrent.IntChannel
                import compukter.concurrent.Tasks

                val changes = IntChannel(1)
                val level = 13

                fun producer() {
                    changes.send(level)
                }

                fun main() {
                    val task = Tasks.launch(::producer)
                    println(changes.receive())
                    task.join()
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val bytes = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            val artifact = ArtifactReader.read(bytes)
            val opcodes = applicationCodeOpcodes(bytes)

            assertContentEquals(bytes, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            assertTrue(0x52 in opcodes, "top-level initializer must create a VM channel: $opcodes")
            assertTrue(0xea in opcodes, "send must stay inside the VM: $opcodes")
            assertTrue(0xeb in opcodes, "receive must stay inside the VM: $opcodes")
            assertTrue(0x37 in opcodes && 0x38 in opcodes, "top-level state must use static storage: $opcodes")
            assertEquals(AbiVersion(1u, 2u), artifact.minimumRuntimeAbi)
            assertEquals(1u, artifact.manifest.maximumChannels)
            assertEquals(1u, artifact.manifest.maximumChannelValues)
            assertTrue(SemanticFeature.CHANNELS in artifact.semanticFeatures)
            System.getProperty("compukter.vm.channelArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `IntChannel construction rejects unsupported ownership and capacity`() =
        withAdapter { adapter ->
            val sources =
                listOf(
                    """
                    import compukter.concurrent.IntChannel
                    fun main() { val channel = IntChannel(1); channel.receive() }
                    """.trimIndent() to "IntChannel must be initialized directly in a top-level val",
                    """
                    import compukter.concurrent.IntChannel
                    var channel = IntChannel(1)
                    fun main() { channel.receive() }
                    """.trimIndent() to "top-level state must be an immutable property with a default getter",
                    """
                    import compukter.concurrent.IntChannel
                    val channel = IntChannel(0)
                    fun main() { channel.receive() }
                    """.trimIndent() to "IntChannel capacity must be a positive Int constant",
                )
            sources.forEach { (source, message) ->
                val result = adapter.compile(request(source))

                assertNull(result.artifact, source)
                assertTrue(
                    result.diagnostics.any { it.severity.name == "ERROR" && message in it.message },
                    result.diagnostics.toString(),
                )
            }
        }

    @Test
    fun `direct top level ordinary task lowers to spawn and join`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.concurrent.Tasks
                        import compukter.redstone.Redstone

                        fun reader() {
                            readln()
                        }

                        fun writer() {
                            Redstone.right.set(13)
                        }

                        fun main() {
                            val readerTask = Tasks.launch(::reader)
                            val writerTask = Tasks.launch(::writer)
                            readerTask.join()
                            writerTask.join()
                        }
                        """.trimIndent(),
                    ),
                )
            val artifactBytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val artifact = ArtifactReader.read(artifactBytes)
            val opcodes = allOpcodes(artifactBytes)

            assertTrue(0x50 in opcodes, "task launch must lower to task.spawn: $opcodes")
            assertTrue(0xe8 in opcodes, "task join must lower to task.join: $opcodes")
            assertEquals(AbiVersion(1u, 1u), artifact.minimumRuntimeAbi)
            assertEquals(64u, artifact.manifest.maximumCoroutines)
            assertTrue(SemanticFeature.COROUTINES in artifact.semanticFeatures)
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
            System.getProperty("compukter.vm.tasksArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifactBytes)
            }
        }

    @Test
    fun `supported function values lower to managed closures and shared capture cells`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.concurrent.Task
                import compukter.concurrent.Tasks

                class Box(val value: Int)
                class Empty { fun value(): Int = 30 }
                class Duo(val first: Int, val second: Int)

                fun make(value: Int): () -> Unit = {
                    println(value)
                }

                fun run(block: () -> Unit) {
                    block()
                }

                fun counter(start: Int): () -> Unit {
                    var value = start
                    return {
                        value = value + 1
                        println(value)
                    }
                }

                fun spawnAfterReturn(value: Int): Task {
                    val block: () -> Unit = { println(value) }
                    return Tasks.launch(block)
                }

                fun transform(value: Int, operation: (Int) -> Int): Int = operation(value)

                fun multiplier(factor: Int): (Int) -> Int = { it * factor }

                fun accumulator(): (Int) -> Int {
                    var total = 0
                    return {
                        total += it
                        total
                    }
                }

                fun combine(a: Int, b: Int, operation: (Int, Int) -> Int): Int = operation(a, b)

                fun offsetAdder(offset: Int): (Int, Int) -> Int = { a, b -> a + b + offset }

                fun foldThree(operation: (Int, Int, Int) -> Int): Int = operation(1, 2, 3)

                fun foldFour(operation: (Int, Int, Int, Int) -> Int): Int = operation(1, 2, 3, 4)

                fun wide(operation: (${List(23) { "Int" }.joinToString(", ")}) -> Int): Int =
                    operation(${(1..23).joinToString(", ")})

                fun presenter(prefix: String): (Boolean, Int) -> String =
                    { enabled, value -> if (enabled) "${'$'}prefix${'$'}value" else "off" }

                fun render(block: (Boolean, Int) -> String): String = block(true, 8)

                fun applyWith(value: Int, block: ((Int) -> Int, Int) -> Int, transform: (Int) -> Int): Int =
                    block(transform, value)

                fun nested(offset: Int): (Int) -> (Int) -> Int = { x -> { y -> offset + x + y } }

                fun deep(seed: Int): () -> () -> () -> Int = { { { seed } } }

                fun nestedCounter(): () -> () -> Int {
                    var total = 0
                    return { { total += 1; total } }
                }

                fun sibling(): () -> Int {
                    val outer: () -> () -> Int = {
                        var total = 1
                        val first: () -> Int = { total += 1; total }
                        val second: () -> Int = { total += 10; total }
                        second()
                        first
                    }
                    return outer()
                }

                fun doubled(value: Int): Int = value * 2

                fun doubledReference(): (Int) -> Int = ::doubled

                fun announce() { println(19) }

                open class Base {
                    open fun value(): Int = 1
                }

                class Child : Base() {
                    override fun value(): Int = 2
                }

                class Adder(val base: Int) {
                    fun add(value: Int): Int = base + value
                }

                interface Reader {
                    fun read(): Int
                }

                class ReaderImpl(val number: Int) : Reader {
                    override fun read(): Int = number
                }

                fun retain(reader: Reader): () -> Int = reader::read

                fun unbound(): (Adder, Int) -> Int = Adder::add

                fun applyUnbound(operation: (Adder, Int) -> Int, receiver: Adder): Int = operation(receiver, 5)

                fun boxMaker(): (Int) -> Box = ::Box

                fun applyBox(make: (Int) -> Box): Int = make(31).value

                fun main() {
                    val first = make(3)
                    val second = make(4)
                    first()
                    second()

                    val box = Box(9)
                    run {
                        println(box.value)
                    }
                    run {
                        println(11)
                    }

                    val firstCounter = counter(0)
                    val secondCounter = counter(10)
                    firstCounter()
                    secondCounter()
                    firstCounter()

                    var shared = 20
                    val increment: () -> Unit = { shared = shared + 1 }
                    val addTen: () -> Unit = { shared = shared + 10 }
                    increment()
                    addTen()
                    println(shared)

                    spawnAfterReturn(42).join()
                    Tasks.launch(increment).join()
                    println(shared)
                    Tasks.launch { println(7) }.join()

                    val twice: (Int) -> Int = { it * 2 }
                    println(twice(6))
                    println(transform(5, multiplier(3)))
                    val accumulate = accumulator()
                    println(accumulate(4))
                    println(accumulate(7))
                    val sum: (Int, Int) -> Int = { a, b -> a + b }
                    println(sum(2, 8))
                    println(combine(3, 4, offsetAdder(5)))
                    println(foldThree { a, b, c -> a + b + c })
                    println(foldFour { a, b, c, d -> a + b + c + d })
                    println(wide { ${(1..23).joinToString(", ") { "p$it" }} -> p1 + p23 })
                    val answer: () -> Int = { 42 }
                    println(answer())
                    val textLength: (String) -> Int = { it.length }
                    println(textLength("guest"))
                    println(render(presenter("v")))
                    val boxValue: (Box) -> Int = { it.value }
                    println(boxValue(Box(13)))
                    val nextLong: (Long) -> Long = { it + 1L }
                    println(nextLong(5L))
                    val step: (Int) -> Int = { it + 1 }
                    val runner: ((Int) -> Int, Int) -> Int = { operation, value -> operation(value) * 2 }
                    println(applyWith(4, runner, step))
                    val add = nested(2)(3)
                    println(add(4))
                    println(deep(17)()()())
                    val makeCounter = nestedCounter()
                    val nestedFirst = makeCounter()
                    val nestedSecond = makeCounter()
                    println(nestedFirst())
                    println(nestedSecond())
                    println(sibling()())
                    val storedReference: (Int) -> Int = ::doubled
                    println(storedReference(7))
                    println(transform(5, ::doubled))
                    println(doubledReference()(9))
                    val unitReference: () -> Unit = ::announce
                    unitReference()
                    val inferredReference = ::doubled
                    println(inferredReference(6))
                    Tasks.launch(unitReference).join()
                    val adder = Adder(7)
                    val bound: (Int) -> Int = adder::add
                    println(bound(5))
                    println(transform(5, Adder(11)::add))
                    println(retain(ReaderImpl(23))())
                    var evaluations = 0
                    val supplier: () -> Base = { evaluations += 1; Child() }
                    val virtual = supplier()::value
                    println(evaluations)
                    println(virtual())
                    val unboundAdder = Adder::add
                    println(unboundAdder(Adder(4), 6))
                    println(unbound()(Adder(8), 3))
                    println(applyUnbound(Adder::add, Adder(9)))
                    val unboundVirtual: (Base) -> Int = Base::value
                    println(unboundVirtual(Child()))
                    val unboundInterface: (Reader) -> Int = Reader::read
                    println(unboundInterface(ReaderImpl(29)))
                    val makeEmpty: () -> Empty = ::Empty
                    println(makeEmpty().value())
                    println(applyBox(::Box))
                    println(boxMaker()(32).value)
                    val makeDuo = ::Duo
                    val firstDuo = makeDuo(7, 8)
                    val secondDuo = makeDuo(9, 4)
                    println(firstDuo.first * 10 + secondDuo.second)
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val bytes = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            val artifact = ArtifactReader.read(bytes)
            val instructions =
                artifact.modules
                    .single { it.kind.name == "APPLICATION" }
                    .blocks
                    .flatMap { it.instructions }

            assertContentEquals(bytes, assertNotNull(second.artifact).toByteArray())
            assertTrue(instructions.count { it is Instruction.NewObject } >= 4, instructions.toString())
            assertTrue(instructions.any { it is Instruction.FieldSet }, instructions.toString())
            assertTrue(instructions.any { it is Instruction.FieldGet }, instructions.toString())
            assertTrue(instructions.any { it is Instruction.CallInterface }, instructions.toString())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())

            val higherOrderDeclarationOnly =
                adapter.compile(
                    request(
                        """
                        fun run(block: () -> Unit) { block() }
                        fun main() { println(0) }
                        """.trimIndent(),
                    ),
                )
            assertNotNull(higherOrderDeclarationOnly.artifact, higherOrderDeclarationOnly.diagnostics.joinToString())
            val integerFunctionsOnly =
                adapter.compile(
                    request(
                        """
                        fun useOne(block: (Int) -> Int): Int = block(2)
                        fun useTwo(block: (Int, Int) -> Int): Int = block(3, 4)
                        fun main() {
                            println(useOne { it + 1 })
                            println(useTwo { a, b -> a * b })
                        }
                        """.trimIndent(),
                    ),
                )
            assertNotNull(integerFunctionsOnly.artifact, integerFunctionsOnly.diagnostics.joinToString())
            val nestedSignatureOnly =
                adapter.compile(
                    request(
                        """
                        fun use(block: ((Int) -> Int) -> Int): Int = 0
                        fun main() { println(0) }
                        """.trimIndent(),
                    ),
                )
            assertNotNull(nestedSignatureOnly.artifact, nestedSignatureOnly.diagnostics.joinToString())
            System.getProperty("compukter.vm.functionValuesArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `unsupported closure shapes produce stable diagnostics`() =
        withAdapter { adapter ->
            val argumentLambda =
                adapter.compile(
                    request(
                        """
                        fun run(block: ((Int) -> Int)?) { println(0) }
                        fun main() { run { it + 1 } }
                        """.trimIndent(),
                    ),
                )
            assertNull(argumentLambda.artifact)
            assertTrue(
                argumentLambda.diagnostics.any {
                    it.severity.name == "ERROR" && "unsupported" in it.message
                },
                argumentLambda.diagnostics.toString(),
            )
        }

    @Test
    fun `function value variance conversion is rejected before artifact publication`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        open class Base
                        class Child : Base()
                        fun make(): (Base) -> Int = { 1 }
                        fun use(block: (Child) -> Int): Int = block(Child())
                        fun main() { println(use(make())) }
                        """.trimIndent(),
                    ),
                )
            assertNull(result.artifact)
            assertTrue(
                result.diagnostics.any { it.severity.name == "ERROR" && "function-value variance" in it.message },
                result.diagnostics.toString(),
            )
        }

    @Test
    fun `supported constructor references lower to ordinary function values`() =
        withAdapter { adapter ->
            val source =
                """
                class Reader { fun read(): Int = 1 }
                class Pair(val first: Int, val second: Int)
                fun create(): (Int, Int) -> Pair = ::Pair
                fun apply(make: (Int, Int) -> Pair): Pair = make(2, 3)
                fun main() {
                    val make: () -> Reader = ::Reader
                    println(make().read())
                    val inferred = ::Pair
                    println(inferred(4, 5).first)
                    println(apply(create()).second)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            assertContentEquals(bytes, assertNotNull(adapter.compile(request(source)).artifact).toByteArray())
        }

    @Test
    fun `default adapted constructor references lower to managed function values`() =
        withAdapter { adapter ->
            val source =
                """
                fun mark(value: Int): Int { println(value); return value }
                class Box(val first: Int = mark(4), var second: Int = first + mark(5)) {
                    init { println(first * 10 + second) }
                }
                fun provide(): () -> Box = ::Box
                fun call(factory: () -> Box): Box = factory()
                fun main() {
                    val zero = provide()
                    val a = call(zero)
                    val b = call(zero)
                    a.second = 99
                    println(a.second)
                    println(b.second)
                    val one: (Int) -> Box = ::Box
                    println(one(7).second)
                    val full: (Int, Int) -> Box = ::Box
                    println(full(1, 2).second)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            assertContentEquals(bytes, assertNotNull(adapter.compile(request(source)).artifact).toByteArray())
            System.getProperty("compukter.vm.adaptedConstructorsArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `unsupported constructor reference is rejected before artifact publication`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.concurrent.IntChannel
                        fun main() {
                            val make: (Int) -> IntChannel = ::IntChannel
                            make(2)
                        }
                        """.trimIndent(),
                    ),
                )
            assertNull(result.artifact)
            assertTrue(
                result.diagnostics.any {
                    it.severity.name == "ERROR" &&
                        "constructor reference target is outside the supported Guest project subset" in it.message
                },
                result.diagnostics.toString(),
            )
        }

    @Test
    fun `task launch accepts direct stored and returned Unit lambdas`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.concurrent.Tasks

                        fun identity(block: () -> Unit): () -> Unit = block

                        fun worker() {}

                        fun main() {
                            var count = 0
                            val stored: () -> Unit = { count += 1 }
                            val direct = Tasks.launch { count += 1 }
                            val fromLocal = Tasks.launch(stored)
                            val returned = Tasks.launch(identity(stored))
                            val static = Tasks.launch(::worker)
                            direct.join()
                            fromLocal.join()
                            returned.join()
                            static.join()
                            println(count)
                        }
                        """.trimIndent(),
                    ),
                )
            val artifact = ArtifactReader.read(assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray())
            val instructions =
                artifact.modules
                    .single { it.kind.name == "APPLICATION" }
                    .blocks
                    .flatMap { it.instructions }
            assertEquals(4, instructions.count { it is Instruction.TaskSpawn })
            assertTrue(instructions.any { it is Instruction.CallInterface })
        }

    @Test
    fun `task launch rejects unsupported local and bound references`() =
        withAdapter { adapter ->
            val unsupported =
                listOf(
                    "fun local() {}\n    Tasks.launch(::local)" to
                        "Tasks.launch requires a direct reference to a top-level, zero-argument function",
                    "Tasks.launch(Reader()::read)" to "Tasks.launch does not support bound function references",
                )
            unsupported.forEach { (launch, expectedDiagnostic) ->
                val result =
                    adapter.compile(
                        request(
                            """
                            import compukter.concurrent.Tasks

                            fun reader() {}
                            fun ordinary() {}
                            class Reader { fun read() {} }

                            fun main() {
                                $launch
                            }
                            """.trimIndent(),
                        ),
                    )

                assertNull(result.artifact, launch)
                assertTrue(
                    result.diagnostics.any {
                        it.severity.name == "ERROR" &&
                            it.message.contains(expectedDiagnostic)
                    },
                    "$launch: ${result.diagnostics}",
                )
            }
        }

    @Test
    fun `sound beep lowers deterministically to a blocking Boolean capability operation`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.sound.Sound

                fun main() {
                    println(Sound.beep(12))
                    println(Sound.beep(24, 50))
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            val opcodes = allOpcodes(artifact)

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            assertEquals(1, opcodes.count { it == 0xe9 }, "the shared beep implementation must block on its VM task: $opcodes")
            System.getProperty("compukter.vm.soundArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `text display API lowers named and adjacent writes to blocking capability operations`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.display.Display

                fun main() {
                    val named = Display.open("warehouse")
                    named.writeAt(0, 0, "Iron: 128")
                    named.clear()
                    Display.left.open().writeAt(1, 2, "Ready")
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            val second = adapter.compile(request(source))

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            assertTrue(allOpcodes(artifact).contains(0xe9), "display writes must suspend for the world host")
        }

    @Test
    fun `text display program lowers deterministically for GameTest`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.concurrent.Tasks
                import compukter.display.Display

                fun main() {
                    val screen = Display.open("panel")
                    screen.writeAt(0, 0, "Ready")
                    while (true) {
                        Tasks.sleepTicks(20)
                    }
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            val second = adapter.compile(request(source))

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.displayArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `redstone program lowers deterministically for vm conformance`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.redstone.Redstone

                fun main() {
                    Redstone.left.awaitAtLeast(7)
                    Redstone.right.set(15)
                    Redstone.front.await(15)
                    Redstone.top.set(15, Redstone.Power.DIRECT)
                    Redstone.bottom.set(0)
                    var writes = 0
                    while (writes < 80) {
                        Redstone.right.set(15, Redstone.Power.DIRECT)
                        writes = writes + 1
                    }
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.redstoneArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `Float variable equality lowers from the K2 IEEE intrinsic`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        fun main() {
                            val previous = 0.0F
                            val current = 1.0F
                            println(previous != current)
                        }
                        """.trimIndent(),
                    ),
                )

            val artifact = ArtifactReader.read(assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray())
            val instructions = artifact.modules.flatMap { module -> module.blocks.flatMap(Block::instructions) }
            assertTrue(instructions.any { it is Instruction.Equal && it.type == ScalarValueType.F32 })
        }

    @Test
    fun `addon fixture typed API and built in timer lower with their own capability descriptors`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.concurrent.Tasks
                import fixture.kinetics.Kinetics

                fun main() {
                    val speedometer = Kinetics.front.speedometer()
                    println(speedometer.speed())
                    println(speedometer.awaitSpeedChange())
                    val stressometer = Kinetics.left.stressometer()
                    println(stressometer.stress())
                    println(stressometer.capacity())
                    stressometer.awaitChange()
                    val controller = Kinetics.back.rotationController()
                    println(controller.targetSpeed())
                    println(controller.setTargetSpeed(32))
                    Tasks.sleepTicks(1)
                }
                """.trimIndent()
            val first = adapter.compile(request(source, includeAddonFixture = true))
            val second = adapter.compile(request(source, includeAddonFixture = true))
            val bytes = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            val artifact = ArtifactReader.read(bytes)
            val opcodes = allOpcodes(bytes)

            assertContentEquals(bytes, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            assertEquals(
                10u,
                artifact.capabilities
                    .single { capability ->
                        val module = artifact.modules.first()
                        module.strings[capability.namespace.value.toInt()].toString() == "fixture" &&
                            module.strings[capability.name.value.toInt()].toString() == "fixture"
                    }.operationCount,
            )
            assertEquals(
                1u,
                artifact.capabilities
                    .single { capability ->
                        val module = artifact.modules.first()
                        module.strings[capability.namespace.value.toInt()].toString() == "compukter" &&
                            module.strings[capability.name.value.toInt()].toString() == "timer"
                    }.operationCount,
            )
            assertEquals(11, opcodes.count { it == 0xe9 }, "every blocking capability access must yield its VM task: $opcodes")
            assertTrue(0x35 !in opcodes, "kinetics value classes must not load fields: $opcodes")
        }

    @Test
    fun `Int for loop supplies its generated increment constant`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.redstone.Redstone

                        fun main() {
                            while (true) {
                                for (i in 0..15) {
                                    Redstone.right.set(i)
                                }
                            }
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `ordinary zero argument Unit main lowers deterministically`() =
        withAdapter { adapter ->
            val first = adapter.compile(request("fun main() {}"))
            val second = adapter.compile(request("fun main() {}"))
            assertContentEquals(assertNotNull(first.artifact).toByteArray(), assertNotNull(second.artifact).toByteArray())
            val application = ArtifactReader.read(first.artifact.toByteArray()).modules.single { it.kind == ModuleKind.APPLICATION }
            assertTrue(application.blocks.any { block -> block.instructions == listOf(Instruction.Return(Destination.Unit)) })
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" })
        }

    @Test
    fun `ordinary platform function is linked from its precompiled fragment`() =
        withAdapter { adapter ->
            val result = adapter.compile(request("fun main() { require(true) }"))
            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val decoded =
                ru.lazyhat.compukters.compiler.artifact.read.ArtifactReader
                    .read(artifact)

            assertTrue(decoded.modules.count { it.kind == ModuleKind.LIBRARY } >= 2)
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `platform scalar side property lowers without static state`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.redstone.Redstone

                fun main() {
                    Redstone.left
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val opcodes = allOpcodes(artifact)

            assertTrue(0x38 !in opcodes, "platform scalar constant must not read static state: $opcodes")
        }

    @Test
    fun `redstone side set preserves its bounded Int precondition`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.redstone.Redstone

                fun main() {
                    Redstone.left.set(16)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val opcodes = allOpcodes(artifact)

            assertTrue(0xe4 in opcodes, "platform scalar range failure must throw: $opcodes")
            assertTrue(0x13 !in opcodes, "platform scalar range failure must not masquerade as division: $opcodes")
            assertTrue(0x30 in opcodes, "platform scalar range failure must construct IllegalArgumentException: $opcodes")
            System.getProperty("compukter.vm.platformScalarArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `redstone side get remains an ordinary library call`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.redstone.Redstone

                fun main() {
                    Redstone.left.get()
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()

            assertTrue(0x40 in applicationCodeOpcodes(artifact), "computed getter must call its library implementation")
        }

    @Test
    fun `guest object subset lowers sealed results data values enum identity and type branches`() =
        withAdapter { adapter ->
            val source =
                """
                sealed interface Result
                data class Exited(val code: Int) : Result
                data class Failed(val reason: Reason, val diagnostic: String) : Result
                enum class Reason { NOT_FOUND, TRAPPED }

                fun classify(value: Result): Int = when (value) {
                    is Exited -> value.code
                    is Failed -> if (value.reason == Reason.NOT_FOUND) value.diagnostic.length else -1
                }

                fun main() {
                    println(classify(Exited(7)))
                    println(classify(Failed(Reason.NOT_FOUND, "missing")))
                    println(classify(Failed(Reason.TRAPPED, "ignored")))
                    println(Reason.NOT_FOUND == Reason.TRAPPED)
                }
                """.trimIndent()

            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(artifact).modules.single { it.kind == ModuleKind.APPLICATION }
            val typeTags = indexedSectionRecords(artifact, 0x0101).map { it.first().toInt() and 0xff }
            val opcodes = applicationCodeOpcodes(artifact)

            val reasonTypeIndex =
                application.types.indexOfFirst { type ->
                    type is NominalType.Class && application.strings[type.name.value.toInt()].toString() == "Reason"
                }
            val reasonType = assertNotNull(application.types[reasonTypeIndex] as? NominalType.Class)
            val initializerId = assertNotNull(reasonType.initializer)
            val initializer = application.functions[initializerId.value.toInt()]
            val initializerBlocks =
                application.blocks.subList(
                    initializer.firstBlock.value.toInt(),
                    (initializer.firstBlock.value + initializer.blockCount).toInt(),
                )

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertEquals(TypeRef.Local(TypeId.of(reasonTypeIndex.toUInt())), initializer.owner)
            assertTrue(FunctionFlag.STATIC in initializer.flags)
            assertTrue(initializerBlocks.flatMap(Block::instructions).any { it is Instruction.NewObject })
            assertEquals(2, initializerBlocks.flatMap(Block::instructions).count { it is Instruction.StaticSet })
            assertTrue(
                application.functions
                    .filterIndexed { index, _ -> index != initializerId.value.toInt() }
                    .flatMap { function ->
                        application.blocks.subList(
                            function.firstBlock.value.toInt(),
                            (function.firstBlock.value + function.blockCount).toInt(),
                        )
                    }.flatMap(Block::instructions)
                    .none { it is Instruction.StaticSet },
            )
            assertTrue(typeTags.count { it == 3 } >= 5, "source functions, both data constructors, and enum initializer need signatures")
            assertEquals(3, typeTags.count { it == 0 }, "Exited, Failed and Reason classes")
            assertEquals(1, typeTags.count { it == 1 }, "sealed Result interface")
            assertEquals(5, indexedSectionRecords(artifact, 0x0105).size, "three properties and two enum roots")
            setOf(0x26, 0x30, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x40).forEach { opcode ->
                assertTrue(opcode in opcodes, "missing expected guest object opcode 0x${opcode.toString(16)} in $opcodes")
            }
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.objectArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `mutable constructor properties lower to instance field writes`() =
        withAdapter { adapter ->
            val source =
                """
                class Counter(var value: Int) {
                    fun add(amount: Int) { value = value + amount }
                }
                class Holder(var current: Counter)
                class Probe(var order: Int, val holder: Holder) {
                    fun receiver(): Holder {
                        order = order * 10 + 1
                        return holder
                    }
                    fun next(): Counter {
                        order = order * 10 + 2
                        return Counter(90)
                    }
                }
                fun main() {
                    val first = Counter(1)
                    val alias = first
                    val second = Counter(10)
                    alias.add(4)
                    println(first.value)
                    println(second.value)
                    second.value = 7
                    println(second.value)
                    val holder = Holder(first)
                    holder.current = second
                    println(holder.current.value)
                    val probe = Probe(0, holder)
                    probe.receiver().current = probe.next()
                    println(probe.order)
                    println(holder.current.value)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(bytes).modules.single { it.kind == ModuleKind.APPLICATION }
            assertTrue(application.blocks.flatMap(Block::instructions).any { it is Instruction.FieldSet })
            assertContentEquals(bytes, assertNotNull(adapter.compile(request(source)).artifact).toByteArray())
            System.getProperty("compukter.vm.mutableFieldsArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `class body properties and init blocks lower in construction order`() =
        withAdapter { adapter ->
            val source =
                """
                open class Base(val seed: Int) {
                    var trace = seed
                    init {
                        println("base")
                        trace = trace * 10 + 1
                    }
                }
                class Child(value: Int) : Base(value) {
                    val before = trace
                    init {
                        println("child")
                        trace = trace * 10 + 2
                    }
                    val after = trace
                }
                class Counter(var value: Int) {
                    fun next(): Int {
                        value = value + 1
                        return value
                    }
                }
                fun main() {
                    val counter = Counter(0)
                    val child = Child(counter.next())
                    println(child.before)
                    println(child.after)
                    println(counter.value)
                    val make: (Int) -> Child = ::Child
                    val other = make(4)
                    println(other.before)
                    println(other.after)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            assertContentEquals(bytes, assertNotNull(adapter.compile(request(source)).artifact).toByteArray())
            System.getProperty("compukter.vm.classInitializationArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `computed and custom class accessors lower with backing fields and override dispatch`() =
        withAdapter { adapter ->
            val source =
                """
                class Meter(start: Int) {
                    var raw = start
                    var measured: Int = 0
                        get() = field + raw
                        set(value) { field = value * 2 }
                    val computed: Int get() = measured + 1
                    var twice: Int
                        get() = raw * 2
                        set(value) { raw = value / 2 }
                }
                open class Base {
                    open val signal: Int get() = 1
                    open var state: Int = 0
                        get() = field + 1
                        set(value) { field = value + 1 }
                }
                class Child : Base() {
                    override val signal: Int get() = 2
                    override var state: Int = 0
                        get() = field + 10
                        set(value) { field = value * 2 }
                }
                open class Ancestor { open val inherited: Int get() = 7 }
                class Descendant : Ancestor()
                open class Plain(open var value: Int)
                class Fancy : Plain(1) {
                    override var value: Int = 2
                        get() = field + 20
                        set(next) { field = next * 3 }
                }
                class Probe {
                    var order = 0
                    val meter = Meter(1)
                    fun receiver(): Meter {
                        order = order * 10 + 1
                        return meter
                    }
                    fun next(): Int {
                        order = order * 10 + 2
                        return 4
                    }
                }
                fun main() {
                    val meter = Meter(3)
                    meter.measured = 4
                    println(meter.measured)
                    println(meter.computed)
                    meter.twice = 10
                    println(meter.twice)
                    val base: Base = Base()
                    base.state = 4
                    println(base.state)
                    val child: Base = Child()
                    child.state = 4
                    println(child.state)
                    println(child.signal)
                    println(Descendant().inherited)
                    val plain: Plain = Fancy()
                    plain.value = 4
                    println(plain.value)
                    val probe = Probe()
                    probe.receiver().measured = probe.next()
                    println(probe.order)
                    println(probe.meter.measured)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            assertContentEquals(bytes, assertNotNull(adapter.compile(request(source)).artifact).toByteArray())
            System.getProperty("compukter.vm.propertyAccessorsArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `abstract class and interface properties lower to dispatched accessors without fields`() =
        withAdapter { adapter ->
            val source =
                """
                abstract class Counter {
                    abstract var value: Int
                    abstract val label: Int
                    fun increment() { value = value + 1 }
                }
                class Backed(override var value: Int) : Counter() {
                    override val label: Int get() = value * 10
                }
                interface Reading {
                    val magnitude: Int
                    var stamp: Int
                }
                class Device(var raw: Int) : Reading {
                    override val magnitude: Int get() = raw * 2
                    override var stamp: Int = 0
                }
                class Adapted : Reading {
                    var state = 0
                    override val magnitude: Int get() = state * 3
                    override var stamp: Int
                        get() = state
                        set(value) { state = value + 1 }
                }
                fun main() {
                    val counter: Counter = Backed(2)
                    println(counter.value)
                    counter.increment()
                    println(counter.value)
                    println(counter.label)
                    val reading: Reading = Device(4)
                    println(reading.magnitude)
                    reading.stamp = 7
                    println(reading.stamp)
                    val adapted: Reading = Adapted()
                    adapted.stamp = 5
                    println(adapted.stamp)
                    println(adapted.magnitude)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(bytes).modules.single { it.kind == ModuleKind.APPLICATION }
            val counter =
                application.types.filterIsInstance<NominalType.Class>().single { type ->
                    application.strings[type.name.value.toInt()].toString() == "Counter"
                }
            val reading =
                application.types.filterIsInstance<NominalType.Interface>().single { type ->
                    application.strings[type.name.value.toInt()].toString() == "Reading"
                }
            assertEquals(0u, counter.fieldCount)
            assertEquals(3u, reading.methodCount)
            assertContentEquals(bytes, assertNotNull(adapter.compile(request(source)).artifact).toByteArray())
            System.getProperty("compukter.vm.abstractPropertiesArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `interface defaults lower to methods and computed accessors`() =
        withAdapter { adapter ->
            val source =
                """
                interface Root {
                    fun seed(): Int = 1
                    val amount: Int get() = seed() * 10
                }
                interface Branch : Root {
                    override fun seed(): Int = 2
                }
                class Box : Branch
                class Custom : Branch {
                    override fun seed(): Int = 3
                    override val amount: Int get() = seed() * 100
                }
                interface Sink {
                    var signal: Int
                        get() = 1
                        set(value) { println(value) }
                }
                class Device : Sink
                fun main() {
                    val box: Root = Box()
                    println(box.seed())
                    println(box.amount)
                    val custom: Root = Custom()
                    println(custom.seed())
                    println(custom.amount)
                    val device: Sink = Device()
                    println(device.signal)
                    device.signal = 7
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(bytes).modules.single { it.kind == ModuleKind.APPLICATION }
            val root =
                application.types.filterIsInstance<NominalType.Interface>().single { type ->
                    application.strings[type.name.value.toInt()].toString() == "Root"
                }
            val sink =
                application.types.filterIsInstance<NominalType.Interface>().single { type ->
                    application.strings[type.name.value.toInt()].toString() == "Sink"
                }
            assertEquals(2u, root.methodCount)
            assertEquals(2u, sink.methodCount)
            assertContentEquals(bytes, assertNotNull(adapter.compile(request(source)).artifact).toByteArray())
            System.getProperty("compukter.vm.interfaceDefaultsArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `qualified interface super calls use direct default bodies`() =
        withAdapter { adapter ->
            val source =
                """
                interface Base {
                    fun base(): Int = 10
                    val value: Int get() = base() + 1
                    var signal: Int
                        get() = 0
                        set(value) { println(value + 100) }
                }
                class Child : Base {
                    override fun base(): Int = super<Base>.base() + 2
                    override val value: Int get() = super<Base>.value + 3
                    override var signal: Int
                        get() = super<Base>.signal + 4
                        set(value) { super<Base>.signal = value + 1 }
                }
                interface Ancestor { fun inherited(): Int = 20 }
                interface Middle : Ancestor
                class Descendant : Middle {
                    override fun inherited(): Int = super<Middle>.inherited() + 1
                }
                fun main() {
                    val child: Base = Child()
                    println(child.base())
                    println(child.value)
                    println(child.signal)
                    child.signal = 5
                    println(Descendant().inherited())
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(bytes).modules.single { it.kind == ModuleKind.APPLICATION }
            assertTrue(application.blocks.flatMap(Block::instructions).any { it is Instruction.Call })
            assertContentEquals(bytes, assertNotNull(adapter.compile(request(source)).artifact).toByteArray())
            System.getProperty("compukter.vm.interfaceSuperArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `qualified interface super call requires a concrete body`() =
        withAdapter { adapter ->
            val source =
                """
                interface Base { fun value(): Int }
                class Child : Base {
                    override fun value(): Int = super<Base>.value()
                }
                fun main() { println(Child().value()) }
                """.trimIndent()
            val result = adapter.compile(request(source))
            assertNull(result.artifact)
            assertTrue(result.diagnostics.any { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `primary constructor defaults preserve argument order and earlier parameters`() =
        withAdapter { adapter ->
            val source =
                """
                fun mark(value: Int): Int { println(value); return value }
                class Packet(
                    val first: Int = mark(1),
                    val second: Int = first + mark(2),
                    var third: Int = mark(3),
                ) {
                    init { println(first * 100 + second * 10 + third) }
                }
                fun main() {
                    val one = Packet(third = mark(30), first = mark(10))
                    val two = Packet()
                    val factory: (Int, Int, Int) -> Packet = ::Packet
                    factory(4, 5, 6)
                    println(one.second)
                    one.third = 99
                    println(one.third)
                    println(two.third)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            assertContentEquals(bytes, assertNotNull(adapter.compile(request(source)).artifact).toByteArray())
            System.getProperty("compukter.vm.constructorDefaultsArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `unsupported omitted constructor default publishes no artifact`() =
        withAdapter { adapter ->
            val source = "class Box(val value: Int = 1 as Int)\nfun main() { Box() }"
            val result = adapter.compile(request(source))
            assertNull(result.artifact)
            assertTrue(result.diagnostics.any { it.code == "UNSUPPORTED_IR" }, result.diagnostics.toString())
        }

    @Test
    fun `generic identity specializes primitive and reference calls`() =
        withAdapter { adapter ->
            val source =
                """
                fun <T> identity(value: T): T = value
                fun <T> forward(value: T): T = identity(value)
                fun main() {
                    println(forward(42))
                    println(forward<String>("hello"))
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val artifact = ArtifactReader.read(bytes)
            val module = artifact.modules.first()
            val signatures = module.types.filterIsInstance<NominalType.Function>()
            assertTrue(signatures.any { it.parameters == listOf(ValueType.I32) && it.result == ValueType.I32 })
            assertTrue(signatures.any { it.parameters.singleOrNull() is ValueType.Ref && it.result is ValueType.Ref })
            System.getProperty("compukter.vm.genericFunctionsArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `generic cell specializes field layout and preserves aliases`() =
        withAdapter { adapter ->
            val source =
                """
                fun <T> identity(value: T): T = value
                class Cell<T>(var value: T) {
                    fun replace(next: T) { value = identity(next) }
                }
                fun main() {
                    val number = Cell(7)
                    val alias = number
                    alias.replace(42)
                    println(number.value)
                    val text = Cell<String>("first")
                    text.value = "before"
                    text.replace("second")
                    println(text.value)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            assertContentEquals(bytes, assertNotNull(adapter.compile(request(source)).artifact).toByteArray())
            val artifact = ArtifactReader.read(bytes)
            val fields = artifact.modules.first().fields
            assertTrue(fields.any { it.type == ValueType.I32 })
            assertTrue(fields.any { it.type is ValueType.Ref })
            System.getProperty("compukter.vm.genericCellArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `generic interface dispatch retains concrete Int and reference types`() =
        withAdapter { adapter ->
            val source =
                """
                interface Reader<out T> { fun read(): T }
                class Cell<T>(val value: T) : Reader<T> {
                    override fun read(): T = value
                }
                fun main() {
                    val number: Reader<Int> = Cell(7)
                    require(number.read() == 7)
                    val text: Reader<String> = Cell("hello")
                    require(text.read() == "hello")
                    println(number.read())
                    println(text.read())
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(bytes).modules.single { it.kind == ModuleKind.APPLICATION }
            assertTrue(application.blocks.flatMap(Block::instructions).any { it is Instruction.CallInterface })
            System.getProperty("compukter.vm.genericInterfaceArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `read only lists retain typed Int String and guest references`() =
        withAdapter { adapter ->
            val source =
                """
                import kotlin.collections.emptyList
                import kotlin.collections.listOf

                class Token(val name: String)
                class Counter(var value: Int) {
                    fun next(): Int { value += 1; return value }
                }
                fun main() {
                    val numbers = listOf(7, 9)
                    val alias = numbers
                    println(numbers.size)
                    println(numbers[1])
                    println(alias == numbers)
                    val empty = emptyList<Int>()
                    println(empty.size)
                    println(listOf<Int>().size)
                    val words = listOf("first", "second")
                    println(words[0])
                    val tokens = listOf(Token("red"), Token("blue"))
                    println(tokens[1].name)
                    val counter = Counter(0)
                    val ordered = listOf(counter.next(), counter.next())
                    println(ordered[0])
                    println(ordered[1])
                    println(counter.value)
                    var total = 0
                    for (number in numbers) { total += number }
                    println(total)
                    for (word in words) { println(word) }
                    for (number in empty) { println(number) }
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(bytes).modules.single { it.kind == ModuleKind.APPLICATION }
            val intList =
                application.types.filterIsInstance<NominalType.Class>().single { type ->
                    application.strings[type.name.value.toInt()].toString() == "kotlin.collections.IntArrayBackedList"
                }
            val backingType = application.fields[intList.fieldStart.toInt()].type as ValueType.Ref
            val backingImport = application.imports[((backingType.type as TypeRef.Imported).id.value).toInt()]
            assertEquals("kotlin.IntArray", application.strings[backingImport.targetName.value.toInt()].toString())
            val intGet =
                application.functions
                    .subList(intList.methodStart.toInt(), (intList.methodStart + intList.methodCount).toInt())
                    .single { function ->
                        application.strings[function.name.value.toInt()].toString() == "get" &&
                            (application.types[(function.signature as TypeRef.Local).id.value.toInt()] as NominalType.Function).result ==
                            ValueType.I32
                    }
            val intGetSignature = application.types[(intGet.signature as TypeRef.Local).id.value.toInt()] as NominalType.Function
            assertEquals(ValueType.I32, intGetSignature.result)
            System.getProperty("compukter.vm.listArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `list Int covariance to Any preserves the list and boxes reads`() =
        withAdapter { adapter ->
            val source =
                """
                import kotlin.collections.List
                import kotlin.collections.contains
                import kotlin.collections.emptyList
                import kotlin.collections.indexOf
                import kotlin.collections.listOf

                data class Token(val name: String)
                class Custom(val code: Int) {
                    override fun equals(other: Any?): Boolean = other is Custom && code == other.code
                    override fun hashCode(): Int = code
                }
                class SearchProbe(val code: Int) {
                    var calls: Int = 0
                    override fun equals(other: Any?): Boolean {
                        calls = calls + 1
                        return other is SearchProbe && code == other.code
                    }
                    override fun hashCode(): Int = code
                }
                fun main() {
                    val numbers: List<Int> = listOf(7, 9)
                    val all: List<Any> = numbers
                    println(all === numbers)
                    println(numbers[0])
                    val first: Any = all[0]
                    println(first is Int)
                    println(first as Int)
                    println(all.size)
                    for (element in all) { println(element as Int) }
                    val words: List<String> = listOf("word")
                    val broad: List<Any> = words
                    println(broad === words)
                    println(broad[0] === words[0])
                    for (element in broad) { println(element === words[0]) }
                    val tokens: List<Token> = listOf(Token("owned"))
                    val objects: List<Any> = tokens
                    println(objects[0] === tokens[0])
                    val word = "mixed"
                    val token = Token("same")
                    val mixed: List<Any> = listOf<Any>(7, word, token)
                    println(mixed.size)
                    println(mixed[0] is Int)
                    println(mixed[0] as Int)
                    println(mixed[0] === mixed[0])
                    println(mixed[1] === word)
                    println(mixed[2] === token)
                    for (element in mixed) { println(element is Int) }
                    println(emptyList<Any>().size)
                    val anotherBox = listOf<Any>(7)[0]
                    val otherBox = listOf<Any>(8)[0]
                    val anotherWord: Any = word.substring(0, 2) + word.substring(2, word.length)
                    val anotherToken: Any = Token("same")
                    val differentToken: Any = Token("different")
                    require(!(mixed[0] === anotherBox))
                    require(!(mixed[1] === anotherWord))
                    require(mixed[0] == anotherBox)
                    require(mixed[0].equals(anotherBox))
                    require(mixed[0] != otherBox)
                    require(mixed[0] == 7)
                    require(7 == mixed[0])
                    require(mixed[1] == anotherWord)
                    require(mixed[0] != mixed[1])
                    require(mixed[2] == token)
                    require(mixed[2] == anotherToken)
                    require(mixed[2] != differentToken)
                    val custom: Any = Custom(4)
                    val matchingCustom: Any = Custom(4)
                    require(custom == matchingCustom)
                    require(Token("same") == Token("same"))
                    require(Token("same") != Token("different"))
                    require(Custom(4) == Custom(4))
                    require(7 in numbers)
                    require(8 !in numbers)
                    require(numbers.indexOf(9) == 1)
                    require(listOf(7, 9, 7).indexOf(7) == 0)
                    require(numbers.indexOf(8) == -1)
                    require(7 in all)
                    require(all.indexOf(9) == 1)
                    require("word" in words)
                    require(words.indexOf("missing") == -1)
                    require(Token("owned") in tokens)
                    require(objects.indexOf(Token("owned")) == 0)
                    require(mixed.indexOf(Token("same")) == 2)
                    require(Custom(4) in listOf(Custom(4)))
                    require(emptyList<Int>().indexOf(1) == -1)
                    val storedProbe = SearchProbe(5)
                    val searchedProbe = SearchProbe(5)
                    val probes = listOf(storedProbe)
                    require(probes.indexOf(searchedProbe) == 0)
                    require(searchedProbe in probes)
                    require(searchedProbe.calls == 2)
                    require(storedProbe.calls == 0)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.listAnyArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `other primitive to Any conversions report diagnostics without artifacts`() =
        withAdapter { adapter ->
            listOf(
                "fun main() { val value: Any = true }",
                "fun main() { val value: Any = 1L }",
                "fun main() { val value: Any = 1.0f }",
                "fun main() { val value: Any = 1; println(value == true) }",
            ).forEach { source ->
                val result = adapter.compile(request(source))
                assertNull(result.artifact, source)
                assertTrue(result.diagnostics.any { it.severity.name == "ERROR" && it.path != null }, result.diagnostics.toString())
            }
        }

    @Test
    fun `list index outside bounds compiles to trapped array access`() =
        withAdapter { adapter ->
            val source = "import kotlin.collections.listOf\nfun main() { val values = listOf(7); values[1] }"
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(bytes).modules.single { it.kind == ModuleKind.APPLICATION }
            assertTrue(application.blocks.flatMap(Block::instructions).any { it is Instruction.ArrayLoad })
            System.getProperty("compukter.vm.listBoundsArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `list iterator resumes across quota slices`() =
        withAdapter { adapter ->
            val source =
                """
                import kotlin.collections.listOf
                fun main() {
                    val numbers = listOf(1, 2, 3)
                    var total = 0
                    for (round in 0 until 100) {
                        for (number in numbers) { total += number }
                    }
                    require(total == 600)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.listQuotaArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `list Any boxes survive quota slices and garbage collection`() =
        withAdapter { adapter ->
            val source =
                """
                import kotlin.collections.List
                import kotlin.collections.listOf
                fun main() {
                    val numbers: List<Int> = listOf(1, 2, 3)
                    val all: List<Any> = numbers
                    var total = 0
                    for (round in 0 until 1000) {
                        for (element in all) {
                            require(element == 1 || element == 2 || element == 3)
                            total += element as Int
                        }
                    }
                    require(total == 6000)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.listAnyQuotaArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `unsupported list element and spread forms report diagnostics`() =
        withAdapter { adapter ->
            val unsupported =
                listOf(
                    "import kotlin.collections.listOf\nfun main() { listOf<Int?>(null) }",
                    "import kotlin.collections.listOf\nfun main() { listOf(true) }",
                    "import kotlin.collections.listOf\nfun main() { listOf<Any>(true) }",
                    "import kotlin.collections.List\nimport kotlin.collections.listOf\nfun main() { val values: List<Int> = listOf(1); val nullable: List<Any?> = values; println(nullable.size) }",
                    "import kotlin.collections.listOf\nfun main() { val array = arrayOf(\"a\"); listOf(*array) }",
                )
            unsupported.forEach { source ->
                val result = adapter.compile(request(source))
                assertNull(result.artifact, source)
                assertTrue(result.diagnostics.any { it.severity.name == "ERROR" && it.path != null }, result.diagnostics.toString())
            }
        }

    @Test
    fun `unsupported generic forms report source diagnostics without artifacts`() =
        withAdapter { adapter ->
            val unsupported =
                listOf(
                    "inline fun <reified T> keep(value: T): T = value\nfun main() { keep(1) }",
                    "interface Writer<in T> { fun write(value: T) }\nfun main() {}",
                    "class Box<out T>(val value: T)\nfun main() { Box(1) }",
                    "class Box<T>(val value: T)\nfun main() { Box<Int?>(null) }",
                    "fun <T> erase(value: T): Any = value\nfun main() { erase(1) }",
                )
            unsupported.forEach { source ->
                val result = adapter.compile(request(source))
                assertNull(result.artifact, source)
                assertTrue(
                    result.diagnostics.any { diagnostic ->
                        diagnostic.severity.name == "ERROR" && diagnostic.path != null
                    },
                    result.diagnostics.toString(),
                )
            }
        }

    @Test
    fun `polymorphic recursion stops at specialization limit`() =
        withAdapter { adapter ->
            val source =
                """
                class Wrap<T>(val value: T)
                fun <T> grow(value: T) { grow(Wrap(value)) }
                fun main() { grow(1) }
                """.trimIndent()
            val result = adapter.compile(request(source))
            assertNull(result.artifact)
            assertTrue(
                result.diagnostics.any { diagnostic ->
                    diagnostic.code == "UNSUPPORTED_IR" && "exceeds 256" in diagnostic.message && diagnostic.path != null
                },
                result.diagnostics.toString(),
            )
        }

    @Test
    fun `guest object subset rejects generic secondary uninitialized stateful and explicit cast shapes`() =
        withAdapter { adapter ->
            val unsupported =
                listOf(
                    "data class Generic<T>(val value: T)\nfun main() { Generic(1) }",
                    "class Secondary(val value: Int) { constructor() : this(0) }\nfun main() { Secondary() }",
                    "class Uninitialized { lateinit var value: String }\nfun main() { Uninitialized() }",
                    "enum class Stateful(val code: Int) { ONE(1) }\nfun main() { Stateful.ONE }",
                    "sealed interface Value\ndata class NumberValue(val value: Int) : Value\nfun read(value: Value): Int = (value as NumberValue).value\nfun main() { read(NumberValue(1)) }",
                )

            unsupported.forEach { source ->
                val result = adapter.compile(request(source))
                assertNull(result.artifact, source)
                assertTrue(
                    result.diagnostics.any { it.code == "UNSUPPORTED_IR" },
                    result.diagnostics.toString(),
                )
            }
        }

    @Test
    fun `immutable constructor property assignment remains rejected`() =
        withAdapter { adapter ->
            val result = adapter.compile(request("class Box(val value: Int)\nfun main() { val box = Box(1); box.value = 2 }"))
            assertNull(result.artifact)
            assertTrue(result.diagnostics.any { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `guest instance methods lower with deterministic owners flags and method ranges`() =
        withAdapter { adapter ->
            val source =
                """
                open class Base {
                    open fun value(): Int = 1
                }
                class Child : Base() {
                    override fun value(): Int = 2
                }
                interface Reader {
                    fun read(): Int
                }
                class Box : Reader {
                    override fun read(): Int = 3
                }

                fun classValue(value: Base): Int = value.value()
                fun interfaceValue(value: Reader): Int = value.read()

                fun main() {
                    classValue(Child()) + interfaceValue(Box())
                }
                """.trimIndent()

            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val bytes = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(bytes).modules.single { it.kind == ModuleKind.APPLICATION }
            val namedTypes =
                application.types.withIndex().associateBy(
                    keySelector = { (_, type) -> application.strings[type.name.value.toInt()].toString() },
                    valueTransform = { it },
                )

            fun methods(name: String): List<ru.lazyhat.compukters.compiler.artifact.model.Function> {
                val (_, type) = assertNotNull(namedTypes[name], "missing $name in ${namedTypes.keys}")
                val start =
                    when (type) {
                        is NominalType.Class -> type.methodStart
                        is NominalType.Interface -> type.methodStart
                        else -> error("$name is not nominal")
                    }.toInt()
                val count =
                    when (type) {
                        is NominalType.Class -> type.methodCount
                        is NominalType.Interface -> type.methodCount
                        else -> error("$name is not nominal")
                    }.toInt()
                return application.functions.subList(start, start + count)
            }

            assertContentEquals(bytes, assertNotNull(second.artifact).toByteArray())
            assertEquals(setOf(FunctionFlag.VIRTUAL), methods("Base").single().flags)
            assertEquals(setOf(FunctionFlag.VIRTUAL), methods("Child").single().flags)
            assertEquals(setOf(FunctionFlag.ABSTRACT), methods("Reader").single().flags)
            assertEquals(setOf(FunctionFlag.VIRTUAL), methods("Box").single().flags)
            assertTrue(methods("Reader").single().blockCount == 0u)
            assertTrue(listOf("Base", "Child", "Reader", "Box").flatMap(::methods).all { it.owner != null })
            val instructions = application.blocks.flatMap(Block::instructions)
            assertTrue(instructions.any { it is Instruction.CallVirtual })
            assertTrue(instructions.any { it is Instruction.CallInterface })
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.dispatchArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `guest instance methods reject unsupported callable shapes`() =
        withAdapter { adapter ->
            val unsupported =
                listOf(
                    "class Worker { suspend fun run() {} }\nfun main() {}" to "suspend functions are unsupported",
                    "class Box { fun <T> keep(value: T): T = value }\nfun main() { Box() }" to "generic instance methods",
                    "class Scope { fun String.sizeAgain(): Int = length }\nfun main() { Scope() }" to "member extension functions",
                )

            unsupported.forEach { (source, message) ->
                val result = adapter.compile(request(source))

                assertNull(result.artifact, source)
                assertTrue(
                    result.diagnostics.any { it.code == "UNSUPPORTED_IR" && message in it.message },
                    result.diagnostics.toString(),
                )
            }
        }

    @Test
    fun `both legal main forms lower deterministically with an explicit entry contract`() =
        withAdapter { adapter ->
            val sources =
                listOf(
                    "fun main() {}" to 0,
                    "fun main(args: Array<String>) {}" to 1,
                )

            sources.forEach { (source, expectedTag) ->
                val first = adapter.compile(request(source))
                val second = adapter.compile(request(source))
                val firstBytes = assertNotNull(first.artifact).toByteArray()
                val secondBytes = assertNotNull(second.artifact).toByteArray()

                assertContentEquals(firstBytes, secondBytes)
                assertEquals(expectedTag, firstBytes[48].toInt() and 0xff)
                assertTrue(first.diagnostics.none { it.severity.name == "ERROR" })
            }
        }

    @Test
    fun `suspend declarations are rejected because Guest tasks suspend transparently`() =
        withAdapter { adapter ->
            val result = adapter.compile(request("suspend fun main() {}"))

            assertNull(result.artifact)
            assertTrue(
                result.diagnostics.any {
                    it.code == "UNSUPPORTED_IR" &&
                        "suspend functions are unsupported; Guest tasks suspend transparently" in it.message
                },
                result.diagnostics.toString(),
            )
        }

    @Test
    fun `string array entry lowers deterministically for vm argv conformance`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.terminal.Terminal

                fun main(args: Array<String>) {
                    Terminal.write(args[0] + ":" + args[1])
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertEquals(1, artifact[48].toInt() and 0xff)
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.argvArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `entry policy rejects duplicate and invalid main functions`() =
        withAdapter { adapter ->
            val duplicate =
                adapter.compile(
                    request(
                        "a/Main.kt" to "package a\nfun main() {}",
                        "b/Main.kt" to "package b\nfun main() {}",
                    ),
                )
            val invalid =
                listOf(
                    adapter.compile(request("fun main(value: String) {}")),
                    adapter.compile(request("fun main(value: Array<Int>) {}")),
                    adapter.compile(request("fun main(value: Array<String>?) {}")),
                    adapter.compile(request("fun main(): Int = 0")),
                )

            (listOf(duplicate) + invalid).forEach { result ->
                assertNull(result.artifact)
                assertTrue(result.diagnostics.any { it.category == DiagnosticCategory.TARGET && it.code == "INVALID_ENTRY_POINT" })
            }
        }

    @Test
    fun `entry policy rejects a project without main`() =
        withAdapter { adapter ->
            val result = adapter.compile(request("val answer: Int = 42"))

            assertNull(result.artifact)
            assertTrue(
                result.diagnostics.any {
                    it.category == DiagnosticCategory.TARGET && it.code == "INVALID_ENTRY_POINT"
                },
                result.diagnostics.toString(),
            )
        }

    @Test
    fun `string arrays can be constructed read and written`() =
        withAdapter { adapter ->
            val source =
                """
                fun main(args: Array<String>) {
                    val empty = emptyArray<String>()
                    val values = arrayOf(args[0], "")
                    values[1] = args[1]
                    empty.size
                    values[0]
                    values[1]
                }
                """.trimIndent()

            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
        }

    @Test
    fun `reference arrays preserve Guest class elements and aliases`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        class Node(val value: Int)
                        class Box(val text: String)
                        class Cell<T>(val value: T)

                        fun <T> single(value: T): Array<T> = arrayOf(value)
                        fun <T> readFirst(values: Array<T>): T = values[0]
                        fun <T> replaceFirst(values: Array<T>, value: T) { values[0] = value }

                        fun main() {
                            val first = Node(1)
                            val nodes = arrayOf(first, Node(2))
                            val alias = nodes
                            require(nodes.size == 2)
                            require(alias[0].value == 1)
                            alias[1] = first
                            require(nodes[1].value == 1)
                            val boxes = arrayOf(Box("a"), Box("b"))
                            require(boxes[0].text == "a")
                            require(boxes[1].text == "b")
                            require(emptyArray<Node>().size == 0)
                            val genericNodes = single(Node(5))
                            require(readFirst(genericNodes).value == 5)
                            replaceFirst(genericNodes, Node(6))
                            require(genericNodes[0].value == 6)
                            val genericBoxes = single(Box("c"))
                            require(readFirst(genericBoxes).text == "c")
                            val cells = arrayOf(Cell(7), Cell(8))
                            require(cells[1].value == 8)
                            val mixedNode = Node(9)
                            val mixed = arrayOf<Any>(7, mixedNode, "word")
                            val mixedAlias = mixed
                            require(mixed.size == 3)
                            require(mixed[0] is Int)
                            require((mixed[0] as Int) == 7)
                            require(mixed[0] === mixedAlias[0])
                            require(mixed[1] === mixedNode)
                            mixedAlias[0] = 11
                            mixedAlias[1] = Box("changed")
                            mixedAlias[2] = mixedNode
                            require((mixed[0] as Int) == 11)
                            require(mixed[2] === mixedNode)
                            require(emptyArray<Any>().size == 0)
                            var allocation = 0
                            while (allocation < 60000) {
                                Node(allocation)
                                allocation = allocation + 1
                            }
                            require(nodes[0].value == 1)
                            require(boxes[1].text == "b")
                            require((mixed[0] as Int) == 11)
                            require(mixed[2] === mixedNode)
                        }
                        """.trimIndent(),
                    ),
                )

            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.referenceArrayArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `reference arrays reject unsupported element representations`() =
        withAdapter { adapter ->
            listOf(
                "fun main() { arrayOf(1, 2) }",
                "fun main() { arrayOf<Any>(true) }",
                "fun main() { arrayOf<Any>(1L) }",
                "fun main() { val values = arrayOf<Any>(1); values[0] = true }",
                "fun main() { val values = arrayOf<Any>(1); values[0] = 1L }",
                "class Node(val value: Int)\nfun main() { arrayOf<Node?>(null) }",
            ).forEach { source ->
                val result = adapter.compile(request(source))
                assertNull(result.artifact, result.diagnostics.joinToString())
                assertTrue(result.diagnostics.any { it.category == DiagnosticCategory.TARGET })
            }
        }

    @Test
    fun `native builtins expose only the supported string and array operations`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        fun main(args: Array<String>) {
                            val values = arrayOf(args[0], "")
                            val tail = values.copyOfRange(1, values.size)
                            val chars = CharArray(2)
                            chars[0] = 'o'
                            chars[1] = 'k'
                            val text = chars.concatToString(0, chars.size)
                            if (text.substring(0, 1) == tail[0]) return
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `string arrays support copyOfRange and supported default arguments`() =
        withAdapter { adapter ->
            val source =
                """
                fun select(args: Array<String> = emptyArray()): Array<String> =
                    args.copyOfRange(1, args.size)

                fun main(args: Array<String>) {
                    select()
                    select(arrayOf("prefix", args[0]))[0]
                }
                """.trimIndent()

            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
        }

    @Test
    fun `string array entry supports bounded loop access`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        fun main(args: Array<String>) {
                            var index = 0
                            while (index < args.size) {
                                val value = args[index]
                                index = index + value.length
                            }
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
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

                        fun main() {
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
    fun `ordinary main lowers trusted terminal wait as vm blocking`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.terminal.Terminal

                fun main() {
                    val event = Terminal.awaitEvent()
                    if (event == 1) Terminal.write("event")
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.blockingCallArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `primitive char array lowers deterministically for exact utf16 materialization`() =
        withAdapter { adapter ->
            val request =
                request(
                    "project/main.kt" to
                        """
                        import compukter.redstone.Redstone
                        import compukter.terminal.Terminal
                        import kotlin.text.contains
                        import kotlin.text.endsWith
                        import kotlin.text.indexOf
                        import kotlin.text.startsWith

                        fun main() {
                            val value = CharArray(5)
                            value[0] = 'A'
                            value[1] = '\uD83D'
                            value[2] = '\uDE00'
                            value[3] = 'Z'
                            value[4] = '!'
                            val last = value[value.size - 1]
                            if (last == '!') Terminal.write(value.concatToString(0, 4))
                            val number = 2
                            val enabled = true
                            val marker = 'x'
                            Terminal.write("${'$'}number/${'$'}enabled/${'$'}marker/${'$'}{Redstone.left}")
                            require("banana".startsWith("ban"))
                            require(!"banana".startsWith("ana"))
                            require(!"ban".startsWith("banana"))
                            require("banana".endsWith("ana"))
                            require(!"banana".endsWith("ban"))
                            require(!"ana".endsWith("banana"))
                            require("banana".startsWith("") && "banana".endsWith(""))
                            require("banana".contains("nan"))
                            require(!"banana".contains("none"))
                            require("banana".indexOf("ana") == 1)
                            require("banana".indexOf("ana", 2) == 3)
                            require("banana".indexOf("ana", -8) == 1)
                            require("banana".indexOf("ana", 99) == -1)
                            require("banana".indexOf("") == 0)
                            require("banana".indexOf("", -8) == 0)
                            require("banana".indexOf("", 99) == 6)
                            require("".indexOf("") == 0)
                            require("".indexOf("x") == -1)
                            require("\uD83D\uDE00".indexOf("\uDE00") == 1)
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
    fun `specialized IntArray lowers to unboxed primitive array instructions`() =
        withAdapter { adapter ->
            val source =
                """
                fun next(value: Int): Int = value + 1

                fun main() {
                    val allocated = IntArray(2)
                    allocated[0] = next(6)
                    val literal = intArrayOf(next(8), next(10))
                    allocated[1] = literal[0] + literal[1] + literal.size
                    require(allocated.size == 2)
                    require(allocated[0] == 7)
                    require(allocated[1] == 22)
                    IntArray(0)
                    intArrayOf()
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(artifact).modules.single { it.kind == ModuleKind.APPLICATION }
            val instructions = application.blocks.flatMap(Block::instructions)

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            assertTrue(instructions.count { it is Instruction.NewArray } >= 4)
            assertTrue(instructions.any { it is Instruction.ArrayLength })
            assertTrue(instructions.any { it is Instruction.ArrayLoad })
            assertTrue(instructions.any { it is Instruction.ArrayStore })
            assertTrue(instructions.none { it is Instruction.NewObject })
        }

    @Test
    fun `unsupported IntArray forms publish no artifact`() =
        withAdapter { adapter ->
            listOf(
                "fun main() { IntArray(2) { it } }",
                "fun main() { arrayOf(1, 2) }",
                "fun main() { val values = intArrayOf(1); values.iterator() }",
                "fun main() { val values = intArrayOf(1); values.indices }",
                "fun main() { val values = intArrayOf(1); intArrayOf(*values) }",
                "fun main() { LongArray(1) }",
            ).forEach { source ->
                val result = adapter.compile(request(source))

                assertNull(result.artifact, source)
                assertTrue(result.hasErrors, source)
            }
        }

    @Test
    fun `specialized IntArray lowers deterministically for vm conformance`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.terminal.Terminal

                fun marked(value: Int): Int {
                    Terminal.write("${'$'}value")
                    return value
                }

                fun verify(actual: Int, expected: Int) {
                    if (actual != expected) {
                        val zero = actual - actual
                        1 / zero
                    }
                }

                fun main() {
                    val mode = Terminal.eventKey()
                    if (mode == 0) {
                        val empty = IntArray(0)
                        val emptyLiteral = intArrayOf()
                        verify(empty.size, 0)
                        verify(emptyLiteral.size, 0)
                        var emptyVisits = 0
                        for (value in empty) emptyVisits = emptyVisits + value + 1
                        verify(emptyVisits, 0)

                        val values = intArrayOf(marked(7), marked(11), marked(13))
                        verify(values.size, 3)
                        verify(values[0], 7)
                        values[1] = values[1] + values[2]
                        verify(values[1], 24)

                        var selected = values
                        var traversed = 0
                        for (value in selected) {
                            if (value == 7) {
                                selected = intArrayOf(100)
                                values[1] = 25
                                continue
                            }
                            if (value == 13) break
                            traversed = traversed + value
                        }
                        verify(traversed, 25)
                        verify(selected[0], 100)

                        var nested = 0
                        for (outer in intArrayOf(1, 2)) {
                            for (inner in values) nested = nested + outer * inner
                        }
                        verify(nested, 135)

                        val filled = IntArray(256)
                        var index = 0
                        while (index < filled.size) {
                            filled[index] = index
                            index = index + 1
                        }
                        verify(filled[255], 255)
                        var checksum = 0
                        for (value in filled) checksum = checksum + value
                        verify(checksum, 32640)
                    } else if (mode == 1) {
                        IntArray(-1)
                    } else if (mode == 2) {
                        IntArray(Int.MAX_VALUE)
                    } else if (mode == 3) {
                        intArrayOf(1)[1]
                    } else {
                        val values = IntArray(1)
                        values[1] = 1
                    }
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.intArrayArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `ordinary project call resumes transparently across host blocking`() =
        withAdapter { adapter ->
            val request =
                request(
                    "project/main.kt" to
                        """
                        import compukter.terminal.Terminal

                        fun readKey(): Int {
                            Terminal.awaitEvent()
                            return Terminal.eventKey()
                        }

                        fun main() {
                            Terminal.write(if (readKey() == 13) "enter" else "other")
                        }
                        """.trimIndent(),
                )
            val first = adapter.compile(request)
            val second = adapter.compile(request)

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.transparentCallArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `bounded when lowers deterministically for vm execution`() =
        withAdapter { adapter ->
            val request =
                request(
                    "project/main.kt" to
                        """
                        import compukter.terminal.Terminal

                        fun key(): Int {
                            Terminal.awaitEvent()
                            return Terminal.eventKey()
                        }

                        fun main() {
                            val text = when (key()) {
                                13 -> "enter"
                                27 -> "escape"
                                else -> "other"
                            }
                            Terminal.write(text)
                        }
                        """.trimIndent(),
                )
            val first = adapter.compile(request)
            val second = adapter.compile(request)

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.whenArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `bounded when forms compile for admitted scalar types`() =
        withAdapter { adapter ->
            listOf(
                "fun classify(value: Int): String = when (value) { 1 -> \"one\"; else -> \"other\" }; fun main() { classify(1) }",
                "fun classify(value: Char): Int = when (value) { 'x' -> 1; else -> 0 }; fun main() { classify('x') }",
                "fun classify(value: Boolean): String = when (value) { true -> \"yes\"; else -> \"no\" }; fun main() { classify(true) }",
                "fun classify(value: String): Int = when (value) { \"run\" -> 1; else -> 0 }; fun main() { classify(\"run\") }",
                "fun classify(value: Int): String = when { value == 1 -> \"one\"; else -> \"other\" }; fun main() { classify(1) }",
                "fun main() { var value = 0; when (1) { 1 -> value = 1; else -> value = 2 }; when { value == 1 -> value = 3; else -> value = 4 } }",
            ).forEach { source ->
                val result = adapter.compile(request(source))

                assertNotNull(result.artifact, "$source\n${result.diagnostics.joinToString()}")
                assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, "$source\n${result.diagnostics}")
            }
        }

    @Test
    fun `unsupported when patterns produce no artifact`() =
        withAdapter { adapter ->
            listOf(
                "fun main() { when (2) { in 1..3 -> Unit; else -> Unit } }",
                "fun main() { when (2) { 1, 2 -> Unit; else -> Unit } }",
            ).forEach { source ->
                val result = adapter.compile(request(source))

                assertNull(result.artifact, source)
                assertTrue(result.hasErrors, source)
                assertTrue(
                    result.diagnostics.any {
                        it.category != DiagnosticCategory.TARGET || it.code == "UNSUPPORTED_IR"
                    },
                    "$source\n${result.diagnostics}",
                )
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
    fun `typed redstone side API lowers deterministically to scalar capability operations`() =
        withAdapter { adapter ->
            val sources =
                listOf(
                    """
                    import compukter.redstone.Redstone

                    fun main() {
                        val current = Redstone.left.get()
                        Redstone.left.await()
                        Redstone.left.await(current)
                        Redstone.left.awaitAtLeast(7)
                    }
                    """.trimIndent(),
                    """
                    import compukter.redstone.Redstone

                    fun main() {
                        Redstone.bottom.set(0)
                        Redstone.top.set(15, Redstone.Power.DIRECT)
                    }
                    """.trimIndent(),
                )
            val artifacts =
                sources.map { source ->
                    val first = adapter.compile(request(source))
                    val second = adapter.compile(request(source))
                    val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

                    assertContentEquals(
                        artifact,
                        assertNotNull(second.artifact, second.diagnostics.joinToString()).toByteArray(),
                    )
                    artifact
                }
            val opcodes = artifacts.flatMap(::allOpcodes)

            assertEquals(1, opcodes.count { it == 0x51 }, "get must be a synchronous scalar call: $opcodes")
            assertEquals(4, opcodes.count { it == 0xe9 }, "await overloads and set must be VM-task-blocking: $opcodes")
            assertTrue(0x35 !in opcodes, "redstone value classes must not load fields: $opcodes")
            assertTrue(0x17 in opcodes, "redstone output packing must retain scalar or: $opcodes")
        }

    @Test
    fun `ordinary Kotlin standard streams lower to stdio capability operations`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.io.Stderr

                        fun main() {
                            print("name: ")
                            val name = readln()
                            println()
                            println(name)
                            println(7)
                            println(true)
                            println('x')
                            Stderr.write("done\n")
                        }
                        """.trimIndent(),
                    ),
                )

            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val opcodes = allOpcodes(artifact)

            assertEquals(1, opcodes.count { it == 0xe9 }, "readln must be the only async capability call")
            assertTrue(0x51 in opcodes, "standard output must lower through a sync capability call")
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())

            listOf(
                "fun main() { print(7) }",
                "import compukter.io.Stderr\nfun main() { Stderr.write(\"error\") }",
            ).forEach { source ->
                val endpoint = adapter.compile(request(source))
                val endpointArtifact = assertNotNull(endpoint.artifact, endpoint.diagnostics.joinToString()).toByteArray()
                val endpointOpcodes = allOpcodes(endpointArtifact)
                assertTrue(0x51 in endpointOpcodes, "$source: $endpointOpcodes")
                assertTrue(endpoint.diagnostics.none { it.severity.name == "ERROR" }, endpoint.diagnostics.toString())
            }
        }

    @Test
    fun `string templates lower scalar platform and Unit parts to canonical strings`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.redstone.Redstone

                        fun sideEffect(): Unit {}

                        fun main() {
                            val number = 2
                            val enabled = true
                            val marker = 'x'
                            println("${'$'}number")
                            println("value=${'$'}number/${'$'}enabled/${'$'}marker")
                            println("${'$'}{Redstone.left}")
                            println("${'$'}{sideEffect()}")
                        }
                        """.trimIndent(),
                    ),
                )

            val artifactBytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(artifactBytes).modules.single { it.kind == ModuleKind.APPLICATION }
            val conversions =
                application.blocks
                    .flatMap(Block::instructions)
                    .filterIsInstance<Instruction.StringValueOf>()

            assertEquals(
                listOf(StringValueType.I32, StringValueType.I32, StringValueType.BOOL, StringValueType.CHAR, StringValueType.I32),
                conversions.map(Instruction.StringValueOf::type),
            )
            assertTrue(Utf16Literal.fromString("kotlin.Unit") in application.utf16Literals)
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `string template rejects arbitrary objects until virtual dispatch exists`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        class Box(val value: Int)

                        fun main() {
                            println("${'$'}{Box(1)}")
                        }
                        """.trimIndent(),
                    ),
                )

            assertNull(result.artifact)
            assertTrue(result.hasErrors)
            assertTrue(
                result.diagnostics.any { "object string conversion requires virtual dispatch" in it.message },
                result.diagnostics.toString(),
            )
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
    fun `while loop jumps lower locally and reject outer targets`() =
        withAdapter { adapter ->
            val local =
                adapter.compile(
                    request(
                        """
                        fun main() {
                            var outer = 0
                            var sum = 0
                            while (outer < 4) {
                                outer = outer + 1
                                var inner = 0
                                while (inner < 5) {
                                    inner = inner + 1
                                    if (inner == 2) continue
                                    if (inner == 4) break
                                    sum = sum + inner
                                }
                            }
                            require(sum == 16)
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(local.artifact, local.diagnostics.joinToString())
            assertTrue(local.diagnostics.none { it.severity.name == "ERROR" }, local.diagnostics.toString())

            val outerTarget =
                adapter.compile(
                    request(
                        """
                        fun main() {
                            outer@ while (true) {
                                while (true) break@outer
                            }
                        }
                        """.trimIndent(),
                    ),
                )

            assertNull(outerTarget.artifact)
            assertTrue(outerTarget.hasErrors)
            assertTrue(
                outerTarget.diagnostics.any { "outer loop jump" in it.message },
                outerTarget.diagnostics.toString(),
            )
        }

    @Test
    fun `inclusive Int for loops lower without range or iterator allocation`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        fun main() {
                            var sum = 0
                            for (value in 1..4) {
                                sum = sum + value
                            }
                            require(sum == 10)

                            var empty = 0
                            for (value in 4..1) {
                                empty = empty + value
                            }
                            require(empty == 0)

                            var singleton = 0
                            for (value in 7..7) {
                                singleton = singleton + value
                            }
                            require(singleton == 7)

                            var maximum = 0
                            for (value in Int.MAX_VALUE..Int.MAX_VALUE) {
                                maximum = maximum + 1
                            }
                            require(maximum == 1)

                            var filtered = 0
                            for (value in 0..10) {
                                if (value == 2) continue
                                if (value == 5) break
                                filtered = filtered + value
                            }
                            require(filtered == 8)
                        }
                        """.trimIndent(),
                    ),
                )
            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(artifact).modules.single { it.kind == ModuleKind.APPLICATION }
            val instructions = application.blocks.flatMap(Block::instructions)

            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
            assertTrue(instructions.none { it is Instruction.NewObject || it is Instruction.NewArray })
            assertTrue(instructions.any { it is Instruction.Add })
            assertTrue(application.blocks.any(Block::loopHeaderSafepoint))
        }

    @Test
    fun `exclusive Int for loops lower without range or iterator allocation`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        fun main() {
                            var untilSum = 0
                            for (value in 0 until 5) {
                                untilSum = untilSum + value
                            }
                            require(untilSum == 10)

                            var rangeUntilSum = 0
                            for (value in 0..<5) {
                                rangeUntilSum = rangeUntilSum + value
                            }
                            require(rangeUntilSum == 10)

                            var empty = 0
                            for (value in Int.MIN_VALUE until Int.MIN_VALUE) {
                                empty = empty + 1
                            }
                            require(empty == 0)

                            var nearMaximum = 0
                            for (value in (Int.MAX_VALUE - 2) until Int.MAX_VALUE) {
                                nearMaximum = nearMaximum + 1
                            }
                            require(nearMaximum == 2)
                        }
                        """.trimIndent(),
                    ),
                )
            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(artifact).modules.single { it.kind == ModuleKind.APPLICATION }
            val instructions = application.blocks.flatMap(Block::instructions)

            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
            assertTrue(instructions.none { it is Instruction.NewObject || it is Instruction.NewArray })
            assertTrue(instructions.any { it is Instruction.Add })
            assertTrue(application.blocks.any(Block::loopHeaderSafepoint))
        }

    @Test
    fun `allocation free Int loops lower deterministically for vm execution`() =
        withAdapter { adapter ->
            val source =
                """
                fun verify(actual: Int, expected: Int) {
                    if (actual == expected) {
                        actual + expected
                    } else {
                        val zero = actual - actual
                        1 / zero
                    }
                }

                fun main(args: Array<String>) {
                    var inclusive = 0
                    for (value in -2..2) {
                        inclusive = inclusive + value
                    }
                    verify(inclusive, 0)

                    var reversed = 0
                    for (value in 2..-2) {
                        reversed = reversed + 1
                    }
                    verify(reversed, 0)

                    var maximum = 0
                    for (value in Int.MAX_VALUE..Int.MAX_VALUE) {
                        maximum = maximum + 1
                    }
                    verify(maximum, 1)

                    var start = 1
                    var end = 3
                    var snapshot = 0
                    for (value in start..end) {
                        start = 100
                        end = 0
                        snapshot = snapshot + value
                    }
                    verify(snapshot, 6)

                    var exclusive = 0
                    for (value in 0 until 5) {
                        exclusive = exclusive + value
                    }
                    verify(exclusive, 10)

                    var rangeUntil = 0
                    for (value in 0..<5) {
                        rangeUntil = rangeUntil + value
                    }
                    verify(rangeUntil, 10)

                    var nested = 0
                    for (outer in 0 until 4) {
                        for (inner in 0..5) {
                            if (inner == 2) continue
                            if (inner == 4) break
                            nested = nested + outer + inner
                        }
                    }
                    verify(nested, 34)

                    var quota = 0
                    for (value in 0 until 1024) {
                        quota = quota + 1
                    }
                    verify(quota, 1024)

                    var descending = 0
                    for (value in 5 downTo -5 step 3) {
                        descending = descending + value
                    }
                    verify(descending, 2)

                    var ascendingStep = 0
                    for (value in 1..8 step 3) {
                        ascendingStep = ascendingStep + value
                    }
                    verify(ascendingStep, 12)

                    var exclusiveStep = 0
                    for (value in 1 until 8 step 3) {
                        exclusiveStep = exclusiveStep + value
                    }
                    verify(exclusiveStep, 12)

                    var stride = 2
                    var snapshotStep = 0
                    for (value in 1..5 step stride) {
                        stride = 5
                        snapshotStep = snapshotStep + value
                    }
                    verify(snapshotStep, 9)

                    var descendingJumps = 0
                    for (value in 5 downTo 1) {
                        if (value == 4) continue
                        if (value == 2) break
                        descendingJumps = descendingJumps + value
                    }
                    verify(descendingJumps, 8)

                    var descendingEmpty = 0
                    for (value in 1 downTo 3) descendingEmpty = descendingEmpty + 1
                    verify(descendingEmpty, 0)

                    var descendingMinimum = 0
                    for (value in Int.MIN_VALUE downTo Int.MIN_VALUE step 2) descendingMinimum = descendingMinimum + 1
                    verify(descendingMinimum, 1)

                    var ascendingMaximum = 0
                    for (value in (Int.MAX_VALUE - 1)..Int.MAX_VALUE step 2) ascendingMaximum = ascendingMaximum + 1
                    verify(ascendingMaximum, 1)

                    if (args.size > 0) {
                        val invalidStep = args.size - args.size
                        for (value in 1..3 step invalidStep) verify(value, 0)
                    }
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.intLoopsArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `unsupported loop forms publish no artifact`() =
        withAdapter { adapter ->
            listOf(
                "fun main() { val values = 3 downTo 1; for (value in values) { value + 1 } }",
                "fun main() { for (value in (1..5 step 2) step 3) { value + 1 } }",
                "fun main() { for (value in arrayOf(1)) { value + 1 } }",
                "fun main() { var value = 0; do { value = value + 1 } while (value < 2) }",
            ).forEach { source ->
                val result = adapter.compile(request(source))

                assertNull(result.artifact, source)
                assertTrue(result.hasErrors, source)
            }
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
    fun `typed process v2 facade lowers without public capability masks or suspend calls`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.process.Process
                import compukter.process.ProcessFailureReason
                import compukter.process.ProcessResult

                fun main() {
                    when (val result = Process.run("/rom/tool", arrayOf("a", ""))) {
                        is ProcessResult.Exited -> if (result.code != 0) Process.exit(result.code)
                        is ProcessResult.Failed -> if (result.reason == ProcessFailureReason.NOT_FOUND) {
                            Process.exit(2)
                        } else if (result.diagnostic != "") {
                            Process.exit(1)
                        }
                    }
                }
                """.trimIndent()

            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            val application = ArtifactReader.read(artifact).modules.single { it.kind == ModuleKind.APPLICATION }
            val imports =
                application.imports.groupBy { it.kind }.mapValues { (_, values) ->
                    values.map { application.strings[it.targetName.value.toInt()].toString() }.toSet()
                }
            assertTrue("compukter.process.ProcessResult" in imports.getValue(SymbolKind.TYPE))
            assertTrue("compukter.process.ProcessResult.Exited" in imports.getValue(SymbolKind.TYPE))
            assertTrue("compukter.process.ProcessResult.Exited.code" in imports.getValue(SymbolKind.FIELD))
            assertTrue("compukter.process.ProcessResult.Failed.reason" in imports.getValue(SymbolKind.FIELD))
            assertTrue("compukter.process.ProcessResult.Failed.diagnostic" in imports.getValue(SymbolKind.FIELD))
            assertTrue("compukter.process.ProcessFailureReason.NOT_FOUND" in imports.getValue(SymbolKind.FIELD))

            listOf(
                "import compukter.process.Process\nfun main() { Process.run(\"/rom/tool\", 1) }",
                "import compukter.process.Process\nfun main() { Process.commandLine() }",
                "import compukter.process.ProcessBindings\nfun main() { ProcessBindings.takeFailureDiagnostic() }",
            ).forEach { forbiddenSource ->
                val forbidden = adapter.compile(request(forbiddenSource))
                assertNull(forbidden.artifact, forbiddenSource)
                assertTrue(forbidden.hasErrors, forbiddenSource)
            }
        }

    @Test
    fun `shell language subset lowers control flow scalars strings and raw terminal calls`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.terminal.Terminal

                        fun main() {
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
            val source = repositoryFile("system/programs/shell.kt").readText()
            val lexer = repositoryFile("system/programs/shell/Lexer.kt").readText()
            val sources =
                arrayOf(
                    "system/programs/shell.kt" to source,
                    "system/programs/shell/Lexer.kt" to lexer,
                )
            val first = adapter.compile(request(*sources))
            val second = adapter.compile(request(*sources))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertOrdinaryEntry(artifact)
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukters.shell.artifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `checked in boot compiles deterministically with process intrinsic`() =
        withAdapter { adapter ->
            val source = repositoryFile("system/programs/boot.kt").readText()
            val first = adapter.compile(request("system/programs/boot.kt" to source))
            val second = adapter.compile(request("system/programs/boot.kt" to source))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertOrdinaryEntry(artifact)
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukters.boot.artifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `checked in kotlinc compiles deterministically`() =
        withAdapter { adapter ->
            val source = repositoryFile("system/programs/kotlinc.kt").readText()
            val first = adapter.compile(request("system/programs/kotlinc.kt" to source))
            val second = adapter.compile(request("system/programs/kotlinc.kt" to source))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertOrdinaryEntry(artifact)
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukters.kotlinc.artifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `checked in vm benchmark compiles deterministically`() =
        withAdapter { adapter ->
            val source = repositoryFile("system/programs/vmbench.kt").readText()
            val workload = repositoryFile("system/programs/vmbench-workload.kt").readText()
            val first = adapter.compile(request("system/programs/vmbench-workload.kt" to workload, "system/programs/vmbench.kt" to source))
            val second = adapter.compile(request("system/programs/vmbench-workload.kt" to workload, "system/programs/vmbench.kt" to source))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertOrdinaryEntry(artifact)
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukters.vmbench.artifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `checked in vm benchmark agent compiles deterministically`() =
        withAdapter { adapter ->
            val source = repositoryFile("system/programs/vmbench-agent.kt").readText()
            val workload = repositoryFile("system/programs/vmbench-workload.kt").readText()
            val first =
                adapter.compile(
                    request("system/programs/vmbench-agent.kt" to source, "system/programs/vmbench-workload.kt" to workload),
                )
            val second =
                adapter.compile(
                    request("system/programs/vmbench-agent.kt" to source, "system/programs/vmbench-workload.kt" to workload),
                )

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertOrdinaryEntry(artifact)
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukters.vmbenchAgent.artifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `checked in editor compiles deterministically`() =
        withAdapter { adapter ->
            val source = repositoryFile("system/programs/edit.kt").readText()
            val first = adapter.compile(request("system/programs/edit.kt" to source))
            val second = adapter.compile(request("system/programs/edit.kt" to source))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertOrdinaryEntry(artifact)
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
                            "import compukter.terminal.Terminal\nfun main() { Terminal.write(readln(\"guest\")); Terminal.awaitEvent() }",
                        "project/read.kt" to "fun readln(value: String): String = value",
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `platform callable lookalike remains an ordinary project call`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        package kotlin.io

                        fun readln(): String = "guest"

                        fun main() {
                            readln().length
                        }
                        """.trimIndent(),
                    ),
                )

            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()

            assertTrue(0xe9 !in allOpcodes(artifact), "player lookalike must not become an async capability call")
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `unsupported unsigned and Double source produces a stable diagnostic and no artifact`() =
        withAdapter { adapter ->
            listOf(
                "fun main() { val answer: UInt = 42u }",
                "fun main() { val answer: Double = 42.0 }",
            ).forEach { source ->
                val result = adapter.compile(request(source))
                val errors = result.diagnostics.filter { it.severity.name == "ERROR" }

                assertNull(result.artifact, source)
                assertEquals(1, errors.size, source)
                assertTrue(errors.single().category in setOf(DiagnosticCategory.TYPE, DiagnosticCategory.TARGET), source)
                assertTrue(result.hasErrors, source)
            }
        }

    @Test
    fun `artifact writer failure becomes a bounded internal diagnostic`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        source = "fun main() { val answer: Int = 42 }",
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

    private fun withAdapter(block: (K2CompilerAdapter) -> Unit) {
        val root = createTempDirectory("compukters-minimal-lowering-test-")
        try {
            block(
                K2CompilerAdapter(
                    K2CompilerInputs(
                        temporaryRoot = root,
                        workerJar = Path.of(checkNotNull(System.getProperty("compukters.worker.jar"))),
                        expectedIdentity = identity(),
                    ),
                ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun repositoryFile(relativePath: String): Path =
        Path.of(checkNotNull(System.getProperty("compukters.repository.root"))).resolve(relativePath)

    private fun request(
        source: String,
        limits: WorkerLimits = WorkerLimits(),
        includeAddonFixture: Boolean = false,
    ): CompileRequest = request(listOf("project/main.kt" to source), limits, includeAddonFixture)

    private fun request(vararg sources: Pair<String, String>): CompileRequest = request(sources.toList(), WorkerLimits(), false)

    private fun request(
        sources: List<Pair<String, String>>,
        limits: WorkerLimits,
        includeAddonFixture: Boolean = false,
    ): CompileRequest {
        val addon =
            if (includeAddonFixture) {
                AddonGuestApiBundleCodec.decode(
                    java.nio.file.Files
                        .readAllBytes(Path.of(checkNotNull(System.getProperty("compukters.addonGuestApiFixture")))),
                )
            } else {
                null
            }
        val addonModuleIdentity =
            addon?.let {
                TrustedBundleIdentity.of(it.moduleDescriptor.id.toString(), Hash256.of(it.identity.contentHash.toByteArray()))
            }
        return CompileRequest(
            RequestId.of(1u),
            sources.map { (path, source) ->
                ProjectSource(VirtualSourcePath.kotlin(path), BinaryValue.of(source.encodeToByteArray()))
            },
            TargetSettings.KOTLIN_2_4_JVM_17,
            identity(),
            limits,
            K2CompilerAdapter.loadPackagedPlatform().modules.map { module ->
                TrustedBundleIdentity.of(
                    module.id.toString(),
                    Hash256.of(
                        ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
                            .moduleContentHash(module)
                            .toByteArray(),
                    ),
                )
            } + listOfNotNull(addonModuleIdentity),
            addon?.let { bundle ->
                listOf(
                    TrustedBundlePayload(
                        TrustedBundleIdentity.of(bundle.identity.id, requireNotNull(addonModuleIdentity).hash),
                        BinaryValue.of(AddonGuestApiBundleCodec.encode(bundle)),
                    ),
                )
            } ?: emptyList(),
        )
    }

    private fun identity() =
        WorkerIdentity(
            "2.4.10",
            "2.4",
            1u,
            1u,
            Hash256.zero(),
            Hash256.of(
                K2CompilerAdapter
                    .loadPackagedPlatform()
                    .identity.contentHash
                    .toByteArray(),
            ),
        )

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

private fun applicationCodeOpcodes(artifact: ByteArray): List<Int> =
    indexedSectionRecords(artifact, 0x0108).flatMap { record ->
        buildList {
            var cursor = 0
            while (cursor < record.size) {
                add(record[cursor].toInt() and 0xff)
                val length = record.u16(cursor + 2)
                require(length >= 4 && cursor + length <= record.size)
                cursor += length
            }
        }
    }

private fun allOpcodes(artifact: ByteArray): List<Int> {
    val sectionCount = artifact.u32(16)
    return (0 until sectionCount)
        .map { 64 + it * 32 }
        .filter { offset -> artifact.u16(offset) == 0x0108 }
        .flatMap { entry ->
            val sectionOffset = artifact.u64(entry + 8)
            val sectionLength = artifact.u64(entry + 16)
            val payload = artifact.copyOfRange(sectionOffset, sectionOffset + sectionLength)
            indexedPayloadRecords(payload).flatMap { record ->
                buildList {
                    var cursor = 0
                    while (cursor < record.size) {
                        add(record[cursor].toInt() and 0xff)
                        val length = record.u16(cursor + 2)
                        require(length >= 4 && cursor + length <= record.size)
                        cursor += length
                    }
                }
            }
        }
}

private fun assertOrdinaryEntry(artifact: ByteArray) {
    val entryFunction = artifact.u32(44)
    val flags = indexedSectionRecords(artifact, 0x0106)[entryFunction].u32(12)
    assertEquals(0, flags and 1, "checked-in program entry must be an ordinary Kotlin function")
}

private fun indexedSectionRecords(
    artifact: ByteArray,
    kind: Int,
): List<ByteArray> {
    val sectionCount = artifact.u32(16)
    val entry =
        (0 until sectionCount)
            .map { 64 + it * 32 }
            .single { offset -> artifact.u16(offset) == kind && artifact.u32(offset + 4) == 1 }
    val sectionOffset = artifact.u64(entry + 8)
    val sectionLength = artifact.u64(entry + 16)
    return indexedPayloadRecords(artifact.copyOfRange(sectionOffset, sectionOffset + sectionLength))
}

private fun indexedPayloadRecords(payload: ByteArray): List<ByteArray> {
    val count = payload.u32(0)
    val dataStart = align8(16 + (count + 1) * 4)
    return (0 until count).map { index ->
        val start = payload.u32(16 + index * 4)
        val end = payload.u32(16 + (index + 1) * 4)
        payload.copyOfRange(dataStart + start, dataStart + end)
    }
}

private fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.u32(offset: Int): Int =
    (0 until 4).fold(0) { value, byte -> value or ((this[offset + byte].toInt() and 0xff) shl (byte * 8)) }

private fun ByteArray.u64(offset: Int): Int {
    val value = (0 until 8).fold(0L) { result, byte -> result or ((this[offset + byte].toLong() and 0xffL) shl (byte * 8)) }
    require(value in 0..Int.MAX_VALUE)
    return value.toInt()
}

private fun align8(value: Int): Int = (value + 7) and -8
