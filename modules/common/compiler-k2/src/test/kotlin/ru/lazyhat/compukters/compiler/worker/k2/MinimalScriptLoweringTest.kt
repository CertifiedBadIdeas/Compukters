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
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinimalScriptLoweringTest {
    @Test
    fun `text stdlib preserves UTF16 cleanup search extraction and transformations`() =
        withAdapter { adapter ->
            val source =
                """
                package example
                import kotlin.collections.*
                import String as RootString

                class String(val value: kotlin.String)
                class Word(val value: kotlin.String)
                fun checkParts(actual: List<kotlin.String>, expected: List<kotlin.String>) {
                    require(actual.size == expected.size)
                    var index = 0
                    while (index < actual.size) {
                        require(actual[index] == expected[index])
                        index += 1
                    }
                }
                fun checkText(text: kotlin.String) {
                    require(text.trim() == "alpha::beta::")
                    require(text.trimStart() == "alpha::beta:: \n")
                    require(text.trimEnd() == "\t alpha::beta::")
                    val clean = text.trim()
                    require(clean.isNotEmpty() && clean.isNotBlank())
                    require(clean.indexOf(':', -10) == 5 && clean.indexOf(':', 99) == -1)
                    require(clean.contains(':') && !clean.contains('z'))
                    require(clean.lastIndexOf(':') == 12 && clean.lastIndexOf(':', 6) == 6)
                    require(clean.lastIndexOf("::") == 11 && clean.lastIndexOf("::", 10) == 5)
                    require(clean.lastIndexOf("::", -1) == -1)
                    require(clean.substringBefore("::") == "alpha")
                    require(clean.substringAfter("::") == "beta::")
                    require(clean.substringBeforeLast("::") == "alpha::beta")
                    require(clean.substringAfterLast("::").isEmpty())
                    require(clean.substringBefore(':') == "alpha")
                    require(clean.substringAfter(':') == ":beta::")
                    require(clean.substringBeforeLast(':') == "alpha::beta:")
                    require(clean.substringAfterLast(':').isEmpty())
                    require(clean.substringBefore("!") == clean && clean.substringAfter('!') == clean)
                    require(clean.substringBefore('!', "fallback") == "fallback")
                    require(clean.substringAfterLast("!", "fallback") == "fallback")
                    require(clean.removePrefix("alpha") == "::beta::")
                    require(clean.removeSuffix("::") == "alpha::beta")
                    require(clean.removePrefix("!") == clean && clean.removeSuffix("") == clean)
                }
                fun main() {
                    checkText("\t alpha::beta:: \n")
                    require("".isEmpty() && "".isBlank() && !"".isNotBlank())
                    require(" \t\r\n\u00a0\u1680\u2000\u200a\u2028\u2029\u202f\u205f\u3000".isBlank())
                    require(!'\u0085'.isWhitespace() && !'\u180e'.isWhitespace())
                    require(!'\u200b'.isWhitespace() && !'\ufeff'.isWhitespace())
                    require(" \t".trim().isEmpty())
                    require("abc".lastIndexOf("") == 2)
                    require("abc".lastIndexOf("", 99) == 3)
                    require("".lastIndexOf("") == -1 && "".lastIndexOf("", 0) == 0)
                    require("abc".substringBefore("").isEmpty())
                    require("abc".substringAfter("") == "abc")
                    require("abc".substringBeforeLast("") == "ab")
                    require("abc".substringAfterLast("") == "c")
                    val utf16 = "a\uD83D\uDE00a"
                    require(utf16.indexOf('\uDE00') == 2 && utf16.lastIndexOf('a') == 3)
                    require(utf16.lastIndexOf("\uD83D\uDE00") == 1)
                    checkParts("a,,b,".split(','), listOf("a", "", "b", ""))
                    checkParts("a,,b,".split(',', 1), listOf("a,,b,"))
                    checkParts("a,,b,".split(',', 2), listOf("a", ",b,"))
                    checkParts("a,,b,".split(',', 3), listOf("a", "", "b,"))
                    checkParts("a,,b,".split(',', 4), listOf("a", "", "b", ""))
                    checkParts("".split(','), listOf(""))
                    checkParts("plain".split("absent"), listOf("plain"))
                    checkParts("aaaaa".split("aa"), listOf("", "", "a"))
                    checkParts("a::b::".split("::", 2), listOf("a", "b::"))
                    checkParts("ab".split(""), listOf("", "a", "b", ""))
                    checkParts("ab".split("", 1), listOf("ab"))
                    checkParts("ab".split("", 2), listOf("", "ab"))
                    checkParts("ab".split("", 3), listOf("", "a", "b"))
                    checkParts("".split(""), listOf("", ""))
                    checkParts(utf16.split(""), listOf("", "a", "\uD83D", "\uDE00", "a", ""))
                    checkParts("a\r\nb\nc\r".lines(), listOf("a", "b", "c", ""))
                    checkParts("\r\n\n\r".lines(), listOf("", "", "", ""))
                    checkParts("".lines(), listOf(""))
                    checkParts("plain".lines(), listOf("plain"))
                    require("banana".replace('a', 'o') == "bonono")
                    require("banana".replace('a', 'a') == "banana")
                    require("banana".replace('x', 'o') == "banana")
                    require("aaaaa".replace("aa", "b") == "bba")
                    require("banana".replace("ana", "") == "bna")
                    require("ab".replace("", "-") == "-a-b-")
                    require("".replace("", "-") == "-")
                    require("ab".replace("", "") == "ab")
                    require("ab".replace("absent", "!") == "ab")
                    require(utf16.replace("a", "xy") == "xy\uD83D\uDE00xy")
                    require(utf16.replace("", "-") == "-a-\uD83D-\uDE00-a-")
                    require(utf16.replace('\uDE00', 'x') == "a\uD83Dxa")
                    val local = ArrayList<kotlin.String>()
                    local.add("one")
                    local.add("two")
                    checkParts(local, listOf("one", "two"))
                    val mutable = "a,b".split(',') as MutableList<kotlin.String>
                    mutable.add("c")
                    mutable[0] = "z"
                    checkParts(mutable, listOf("z", "b", "c"))
                    checkParts(" a , b ".split(',').map { it.trim() }, listOf("a", "b"))
                    val widened: List<Any> = "a,b".split(',')
                    require(widened[0] == "a" && widened.contains("b"))
                    val widenedIterator = widened.iterator()
                    require(widenedIterator.next() == "a" && widenedIterator.next() == "b")
                    val nullableView: List<kotlin.String?> = "a,b".split(',')
                    require(nullableView[1] == "b" && !nullableView.contains(null))
                    val ints = ArrayList<Int>()
                    ints.add(42)
                    require(ints[0] == 42)
                    val words = ArrayList<Word>()
                    words.add(Word("local variant"))
                    require(words[0].value == "local variant")
                    val namedLikeBuiltin = ArrayList<String>()
                    namedLikeBuiltin.add(String("guest class"))
                    require(namedLikeBuiltin[0].value == "guest class")
                    val rootNamedLikeBuiltin = ArrayList<RootString>()
                    rootNamedLikeBuiltin.add(RootString("root guest class"))
                    require(rootNamedLikeBuiltin[0].value == "root guest class")
                    println("text stdlib ok")
                }
                """.trimIndent()
            val result =
                adapter.compile(
                    request(
                        "project/RootString.kt" to "class String(val value: kotlin.String)",
                        "project/main.kt" to source,
                    ),
                )
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val parsed = ArtifactReader.read(bytes)
            for (name in listOf("kotlin.collections.ArrayList<String>", "kotlin.collections.List<String>")) {
                assertEquals(
                    1,
                    parsed.modules.sumOf { module ->
                        module.types.count { module.strings[it.name.value.toInt()].toString() == name }
                    },
                    "precompiled specialization must retain a single nominal owner: $name",
                )
            }
            assertTrue(
                parsed.modules.single { it.kind == ModuleKind.APPLICATION }.let { module ->
                    listOf(
                        "kotlin.collections.ArrayList<example.Word>",
                        "kotlin.collections.ArrayList<example.String>",
                        "kotlin.collections.ArrayList<<root>.String>",
                    ).all { name ->
                        module.types.any { module.strings[it.name.value.toInt()].toString() == name }
                    }
                },
                "a variant absent from libraries must remain locally specialized",
            )
            System.getProperty("compukter.vm.textStdlibArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
            val failureSource =
                """
                fun main(args: Array<String>) {
                    val mode = args[0]
                    if (mode == "negative-char-limit") { "abc".split(',', -1); return }
                    if (mode == "negative-string-limit") { "abc".split("", -1); return }
                    val size = if (mode == "overflow") 50000 else if (mode == "split-quota") 10000 else 1000
                    val chars = CharArray(size)
                    var index = 0
                    while (index < size) { chars[index] = 'a'; index += 1 }
                    val text = String(chars, 0, chars.size)
                    if (mode == "split-quota") {
                        val parts = text.split("")
                        require(parts.size == size + 2)
                        return
                    }
                    val result = text.replace("a", text)
                    require(result.length == 1000000)
                }
                """.trimIndent()
            val failures = adapter.compile(request(failureSource))
            val failureBytes = assertNotNull(failures.artifact, failures.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.textStdlibArtifact")?.let { output ->
                Path.of("$output.failure.cpkt").writeBytes(failureBytes)
            }
        }

    @Test
    fun `stdlib scope functions execute with inline receiver and nullable semantics`() =
        withAdapter { adapter ->
            val source =
                """
                import kotlin.collections.*

                class Box(var value: Int)
                fun early(): Int { 3.let { return it + 4 }; return -1 }
                fun earlyReceiver(): Int { Box(9).run { return value }; return -1 }
                fun earlyBlock(): Int { run { return 11 }; return -1 }
                fun earlyEach(): Int { listOf(1, 4, 6).forEach { if (it == 4) return it }; return -1 }
                fun earlyIndexed(): Int {
                    listOf(3, 5, 7).forEachIndexed { index, value -> if (value == 7) return index }
                    return -1
                }
                fun earlyRepeat(): Int { repeat(5) { if (it == 2) return it }; return -1 }
                fun main() {
                    var calls = 0
                    val box = Box(1).apply { value = 2 }.also { it.value += 3 }
                    require(box.value == 5)
                    require(box.run { value + 1 } == 6)
                    require(run { 4 + 5 } == 9)
                    require(with(box) { value + 2 } == 7)
                    require(box.let { it.value } == 5)
                    require(box.takeIf { it.value == 5 } === box)
                    require(box.takeUnless { it.value == 5 } == null)
                    require(box.takeIf { calls += 1; false } == null)
                    require(box.takeUnless { calls += 1; false } === box)
                    require(calls == 2)
                    require(8.takeIf { it > 5 } == 8)
                    require(8.takeUnless { it > 5 } == null)
                    require(8.let { "v" + it } == "v8")
                    val nullable: String? = null
                    require(nullable.let { it == null })
                    require(nullable.run { this == null })
                    require(nullable.takeIf { it == null } == null)
                    val text: String? = "abc"
                    require(text?.let { it.length } == 3)
                    require(early() == 7)
                    require(earlyReceiver() == 9)
                    require(earlyBlock() == 11)
                    var order = 0
                    val values: Iterable<Int> = listOf(1, 2, 3)
                    values.forEach { order = order * 10 + it }
                    emptyList<Int>().forEach { order = -1 }
                    require(order == 123)
                    var indexed = 0
                    listOf(4, 5).forEachIndexed { index, value -> indexed += index * 10 + value }
                    emptyList<Int>().forEachIndexed { _, _ -> indexed = -1 }
                    require(indexed == 19)
                    val references = listOf(box, box)
                    references.forEachIndexed { index, value -> require(index < 2 && value === box) }
                    var repeated = 0
                    repeat(3) { repeated = repeated * 10 + it }
                    repeat(0) { repeated = -1 }
                    repeat(-2) { repeated = -1 }
                    require(repeated == 12)
                    require(earlyEach() == 4)
                    require(earlyIndexed() == 2)
                    require(earlyRepeat() == 2)
                    println("scope ok")
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.scopeArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `source generic inline callbacks eliminate closures through production worker`() =
        withAdapter { adapter ->
            val source =
                """
                inline fun <T, R> transform(value: T, block: (T) -> R): R = block(value)
                fun early(): Int = transform(3) { return 7 }
                fun main() { val result = transform(3) { it + 2 }; val returned = early() }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val bytes = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(bytes, assertNotNull(adapter.compile(request(source)).artifact).toByteArray())
            assertTrue(
                ArtifactReader
                    .read(bytes)
                    .modules
                    .flatMap { it.blocks }
                    .flatMap { it.instructions }
                    .none { it is Instruction.NewObject },
            )
        }

    @Test
    fun `source inline expansion work limit is a located target diagnostic`() =
        withAdapter { adapter ->
            val source =
                buildString {
                    append("inline fun f0(): Int = 1\n")
                    for (i in 1..20) append("inline fun f$i(): Int = f${i - 1}() + f${i - 1}()\n")
                    append("fun main() { val value = f20() }")
                }
            val result = adapter.compile(request(source))
            assertNull(result.artifact)
            val diagnostic = result.diagnostics.single { it.code == "UNSUPPORTED_IR" }
            assertEquals(DiagnosticCategory.TARGET, diagnostic.category)
            assertNotNull(diagnostic.path)
            assertNotNull(diagnostic.startUtf16)
            assertTrue(diagnostic.message.contains("work limit"), diagnostic.toString())
        }

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
                "fun main() { val value: Long? = null }",
                "class Node(val value: Boolean)\nfun main() { val node: Node? = null; val value = node?.value }",
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
            // Integer arithmetic and stdoutInt retain the arithmetic exception factory.
            assertEquals(AbiVersion(1u, 9u), artifact.minimumRuntimeAbi)
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
            assertEquals(AbiVersion(1u, 9u), consoleArtifact.minimumRuntimeAbi)

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
            // Floating division itself is nonthrowing; stdoutInt uses integer division.
            assertEquals(AbiVersion(1u, 9u), artifact.minimumRuntimeAbi)
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
            assertEquals(AbiVersion(1u, 9u), consoleArtifact.minimumRuntimeAbi)

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
    fun `Char and Boolean compareTo preserve scalar ordering for vm conformance`() =
        withAdapter { adapter ->
            val source =
                """
                fun charLeft(): Char {
                    println("char-left")
                    return 'A'
                }

                fun charRight(): Char {
                    println("char-right")
                    return 'C'
                }

                fun booleanLeft(): Boolean {
                    println("boolean-left")
                    return false
                }

                fun booleanRight(): Boolean {
                    println("boolean-right")
                    return true
                }

                fun main() {
                    println('A'.compareTo('C'))
                    println('C'.compareTo('A'))
                    println('A'.compareTo('A'))
                    println(65535.toChar().compareTo(0.toChar()))
                    println(0.toChar().compareTo(65535.toChar()))
                    println(charLeft().compareTo(charRight()))
                    println('A' < 'C')
                    println(false.compareTo(false))
                    println(false.compareTo(true))
                    println(true.compareTo(false))
                    println(true.compareTo(true))
                    println(booleanLeft().compareTo(booleanRight()))
                    println(false < true)
                    println(false <= false)
                    println(true > false)
                    println(true >= true)
                    println(true < false)
                    println(booleanLeft() < booleanRight())
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val bytes = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(bytes, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            val operatorsOnly = adapter.compile(request("fun main() { println(false < true) }"))
            assertNotNull(operatorsOnly.artifact, operatorsOnly.diagnostics.joinToString())
            System.getProperty("compukter.vm.scalarCompareArtifact")?.let { output ->
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
            // Printing the received Int retains stdoutInt's arithmetic exception factory.
            assertEquals(AbiVersion(1u, 9u), artifact.minimumRuntimeAbi)
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
            // Tasks.launch retains IllegalArgumentException's verified factory role through require.
            assertEquals(AbiVersion(1u, 9u), artifact.minimumRuntimeAbi)
            assertEquals(64u, artifact.manifest.maximumCoroutines)
            assertTrue(SemanticFeature.COROUTINES in artifact.semanticFeatures)
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
            System.getProperty("compukter.vm.tasksArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifactBytes)
            }
        }

    @Test
    fun `read-only captured vars avoid cells while reassigned vars retain them`() =
        withAdapter { adapter ->
            fun captureCellCount(body: String): Int {
                val result = adapter.compile(request("fun main() { $body }"))
                val application =
                    ArtifactReader
                        .read(assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray())
                        .modules
                        .single { it.kind == ModuleKind.APPLICATION }
                return application.types.filterIsInstance<NominalType.Class>().count { type ->
                    application.strings[type.name.value.toInt()].toString().startsWith("app.<capture-cell-")
                }
            }
            assertEquals(0, captureCellCount("var value = 1; val read: () -> Int = { value }; println(read())"))
            assertEquals(1, captureCellCount("var value = 1; val read: () -> Int = { value }; value = 2; println(read())"))
            assertEquals(
                1,
                captureCellCount("var value = 1; val outer: () -> () -> Int = { { value += 1; value } }; println(outer()())"),
            )
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

                class MutableBox(var value: Int)

                fun readOnly(seed: Int): () -> Int {
                    var value = seed
                    return { value }
                }

                fun readOnlyWide(seed: Long): () -> () -> Long {
                    var value = seed
                    return { { value } }
                }

                fun readOnlyNullable(seed: Int?): () -> Int? {
                    var value = seed
                    return { value }
                }

                fun readOnlyReference(box: MutableBox): () -> Int {
                    var reference = box
                    return { reference.value }
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

                inline fun inlineMarker() {}
                fun main() {
                    val readFirst = readOnly(3)
                    val readSecond = readOnly(4)
                    require(readFirst() == 3 && readSecond() == 4 && readFirst() == 3)
                    val wideRead = readOnlyWide(5000000000L)()
                    require(wideRead() == 5000000000L)
                    require(readOnlyNullable(null)() == null)
                    require(readOnlyNullable(7)() == 7)
                    val mutableBox = MutableBox(1)
                    val readBox = readOnlyReference(mutableBox)
                    mutableBox.value = 2
                    require(readBox() == 2)
                    var reassigned = 1
                    val readReassigned: () -> Int = { reassigned }
                    reassigned = 2
                    require(readReassigned() == 2)
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

                    inlineMarker()
                    val twice: (Int) -> Int = { it * 2 }
                    val aliased = twice
                    if (aliased !== twice) { val zero = 0; val failure = 1 / zero }
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
                fun <T> identity(value: T): T = Wrapper(value).read()
                class Wrapper<T>(val value: T) {
                    fun read(): T = unwrap(value)
                }
                fun <T> unwrap(value: T): T = Payload(value).value
                class Payload<T>(val value: T)
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
                    val mutableNumbers = numbers as kotlin.collections.MutableList<Int>
                    mutableNumbers.add(4)
                    require(numbers.size == 3 && numbers[2] == 4)
                    val arrayList = numbers as kotlin.collections.ArrayList<Int>
                    arrayList[0] = 10
                    require(numbers[0] == 10)
                    val mutableWords = words as kotlin.collections.MutableList<String>
                    mutableWords.add("third")
                    require(words.size == 3 && words[2] == "third")
                    val mutableTokens = tokens as kotlin.collections.MutableList<Token>
                    val token = Token("green")
                    mutableTokens.add(token)
                    require(tokens[2] === token)
                    val otherEmpty = emptyList<Int>()
                    val mutableEmpty = empty as kotlin.collections.MutableList<Int>
                    mutableEmpty.add(5)
                    require(empty[0] == 5 && otherEmpty.isEmpty())
                    require((listOf<Int>() as kotlin.collections.ArrayList<Int>).isEmpty())
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(bytes).modules.single { it.kind == ModuleKind.APPLICATION }
            val intList =
                application.types.filterIsInstance<NominalType.Class>().single { type ->
                    application.strings[type.name.value.toInt()].toString() == "kotlin.collections.ArrayList<Int>"
                }
            val storage =
                application.types.filterIsInstance<NominalType.Class>().single { type ->
                    application.strings[type.name.value.toInt()].toString() == "kotlin.collections.IntMutableListStorage"
                }
            val backingType = application.fields[storage.fieldStart.toInt()].type as ValueType.Ref
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
                import kotlin.collections.Collection
                import kotlin.collections.Iterable
                import kotlin.collections.Iterator
                import kotlin.collections.all
                import kotlin.collections.any
                import kotlin.collections.contains
                import kotlin.collections.emptyList
                import kotlin.collections.indexOf
                import kotlin.collections.isNotEmpty
                import kotlin.collections.lastIndexOf
                import kotlin.collections.listOf
                import kotlin.collections.none

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
                class TwoNumbers : Iterable<Int> {
                    override fun iterator(): Iterator<Int> = TwoNumbersIterator()
                }
                class TwoNumbersIterator : Iterator<Int> {
                    var nextValue: Int = 1
                    override fun hasNext(): Boolean = nextValue <= 2
                    override fun next(): Int {
                        val value = nextValue
                        nextValue += 1
                        return value
                    }
                }
                class TwoNumbersCollection : Collection<Int> {
                    override val size: Int get() = 2
                    override fun isEmpty(): Boolean = false
                    override fun contains(element: Int): Boolean = element == 1 || element == 2
                    override fun iterator(): Iterator<Int> = TwoNumbersIterator()
                }
                class TwoNumbersList : List<Int> {
                    override val size: Int get() = 2
                    override fun isEmpty(): Boolean = false
                    override fun contains(element: Int): Boolean = indexOf(element) >= 0
                    override fun get(index: Int): Int {
                        require(index == 0 || index == 1)
                        return index + 1
                    }
                    override fun indexOf(element: Int): Int =
                        if (element == 1) 0 else if (element == 2) 1 else -1
                    override fun lastIndexOf(element: Int): Int = indexOf(element)
                    override fun iterator(): Iterator<Int> = TwoNumbersIterator()
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
                    require(!numbers.isEmpty())
                    require(numbers.isNotEmpty())
                    require(emptyList<Int>().isEmpty())
                    require(!emptyList<Any>().isNotEmpty())
                    require(listOf(7, 9, 7).lastIndexOf(7) == 2)
                    require(numbers.lastIndexOf(8) == -1)
                    require(7 in all)
                    require(all.indexOf(9) == 1)
                    require(all.lastIndexOf(7) == 0)
                    require(all.isNotEmpty())
                    val repeated: List<Any> = listOf<Any>(7, 9, 7)
                    require(repeated.lastIndexOf(7) == 2)
                    require("word" in words)
                    require(words.indexOf("missing") == -1)
                    require(listOf("word", "other", "word").lastIndexOf("word") == 2)
                    require(Token("owned") in tokens)
                    require(objects.indexOf(Token("owned")) == 0)
                    require(mixed.indexOf(Token("same")) == 2)
                    require(mixed.lastIndexOf(Token("same")) == 2)
                    require(Custom(4) in listOf(Custom(4)))
                    require(emptyList<Int>().indexOf(1) == -1)
                    require(emptyList<Int>().lastIndexOf(1) == -1)
                    val storedProbe = SearchProbe(5)
                    val searchedProbe = SearchProbe(5)
                    val probes = listOf(storedProbe)
                    require(probes.indexOf(searchedProbe) == 0)
                    require(searchedProbe in probes)
                    require(searchedProbe.calls == 2)
                    require(storedProbe.calls == 0)
                    val lastStoredProbe = SearchProbe(5)
                    val lastSearchedProbe = SearchProbe(5)
                    require(listOf(storedProbe, lastStoredProbe).lastIndexOf(lastSearchedProbe) == 1)
                    require(lastSearchedProbe.calls == 1)
                    require(lastStoredProbe.calls == 0)
                    var visits = 0
                    require(numbers.any { value -> visits += 1; value == 7 })
                    require(visits == 1)
                    visits = 0
                    require(!numbers.any { value -> visits += 1; value == 8 })
                    require(visits == 2)
                    visits = 0
                    require(!numbers.all { value -> visits += 1; value == 7 })
                    require(visits == 2)
                    visits = 0
                    require(!numbers.none { value -> visits += 1; value == 7 })
                    require(visits == 1)
                    require(numbers.all { value -> value > 0 })
                    require(numbers.none { value -> value < 0 })
                    visits = 0
                    val empty = emptyList<Int>()
                    require(!empty.any { visits += 1; true })
                    require(empty.all { visits += 1; false })
                    require(empty.none { visits += 1; true })
                    require(visits == 0)
                    require(all.all { value -> value is Int })
                    require(mixed.any { value -> value is String })
                    require(!mixed.all { value -> value is Int })
                    require(!mixed.none { value -> value is String })
                    val iterableNumbers: Iterable<Int> = numbers
                    require(iterableNumbers.any { value -> value == 9 })
                    require(iterableNumbers.all { value -> value > 0 })
                    val iterableAll: Iterable<Any> = all
                    require(iterableAll.none { value -> value is String })
                    require(7 in iterableAll)
                    require(iterableAll.indexOf(9) == 1)
                    val customIterable: Iterable<Int> = TwoNumbers()
                    require(customIterable.any { value -> value == 2 })
                    require(customIterable.all { value -> value > 0 })
                    require(customIterable.none { value -> value > 2 })
                    require(2 in customIterable)
                    require(3 !in customIterable)
                    require(customIterable.indexOf(2) == 1)
                    require(customIterable.lastIndexOf(1) == 0)
                    val repeatedIterable: Iterable<Int> = listOf(7, 9, 7)
                    require(repeatedIterable.indexOf(7) == 0)
                    require(repeatedIterable.lastIndexOf(7) == 2)
                    val collectionNumbers: Collection<Int> = numbers
                    require(collectionNumbers.size == 2)
                    require(!collectionNumbers.isEmpty())
                    require(collectionNumbers.isNotEmpty())
                    require(9 in collectionNumbers)
                    val collectionAll: Collection<Any> = collectionNumbers
                    require(7 in collectionAll)
                    require("missing" !in collectionAll)
                    val collectionWords: Collection<Any> = words
                    require("word" in collectionWords)
                    require(7 !in collectionWords)
                    require(emptyList<Int>().isEmpty())
                    val customCollection: Collection<Int> = TwoNumbersCollection()
                    require(customCollection.size == 2)
                    require(!customCollection.isEmpty())
                    require(customCollection.isNotEmpty())
                    require(2 in customCollection)
                    require(customCollection.indexOf(2) == 1)
                    val customList: List<Int> = TwoNumbersList()
                    require(customList.size == 2)
                    require(customList[1] == 2)
                    require(customList.indexOf(2) == 1)
                    require(customList.lastIndexOf(2) == 1)
                    require(customList.indexOf(3) == -1)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.listAnyArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `mutable ArrayList preserves growth mutation and read only views`() =
        withAdapter { adapter ->
            val source =
                """
                import kotlin.collections.*

                class Item(val value: Int)
                fun <T> append(list: MutableList<T>, value: T) { list.add(value) }
                fun main() {
                    val ints = ArrayList<Int>(0)
                    val mutable: MutableList<Int> = ints
                    var index = 0
                    while (index < 40) { append(mutable, index); index += 1 }
                    require(ints.size == 40 && ints[39] == 39)
                    val read: List<Int> = mutable
                    val universal: List<Any?> = read
                    mutable.add(0, 100)
                    mutable.add(20, 200)
                    mutable.add(mutable.size, 300)
                    require(read.size == 43 && read[0] == 100 && read[20] == 200 && read[42] == 300)
                    require(mutable.set(0, 101) == 100)
                    require(universal[0] == 101 && universal.contains(200))
                    require(mutable.removeAt(20) == 200)
                    require(mutable.remove(300) && !mutable.remove(999))
                    val iterator = mutable.iterator()
                    require(iterator.next() == 101)
                    iterator.remove()
                    require(iterator.next() == 0)
                    mutable[1] = 700
                    require(iterator.next() == 700)
                    require(mutable.size == 40)
                    require(mutable.fold(0) { total, value -> total + value } == 1479)
                    mutable.clear()
                    require(read.isEmpty() && universal.size == 0)
                    append(mutable, 1000)
                    require(mutable[0] == 1000)
                    val first = Item(1)
                    val refs = ArrayList<Item>(1)
                    refs.add(first)
                    refs.add(Item(2))
                    refs.add(0, Item(3))
                    require(refs.removeAt(1) === first)
                    require(refs.size == 2 && refs[1].value == 2)
                    val refsAny: List<Any> = refs
                    require(refsAny[1] === refs[1])
                    val nullable = ArrayList<Int?>(0)
                    nullable.add(1000)
                    nullable.add(null)
                    nullable.add(2000)
                    val nullableAny: List<Any?> = nullable
                    require(nullableAny[0] === (nullable[0] as Any?))
                    require(nullable[1] == null && nullable.remove(null))
                    require(nullable.set(0, null) == 1000)
                    require(nullable[0] == null && nullable[1] == 2000)
                    val strings = ArrayList<String?>()
                    strings.add("a")
                    strings.add(null)
                    strings.add("b")
                    require(strings.indexOf(null) == 1 && strings.lastIndexOf("b") == 2)
                    val mixed = ArrayList<Any?>()
                    mixed.add(5)
                    mixed.add(first)
                    mixed.add(null)
                    require(mixed[0] == 5 && mixed[1] === first && mixed[2] == null)
                    val slots = arrayOfNulls<Item>(3)
                    require(slots.size == 3 && slots[0] == null && slots[2] == null)
                    slots[1] = first
                    require(slots[1] === first)
                    val intSlots = arrayOfNulls<Int>(2)
                    intSlots[0] = 1000
                    require(intSlots[0] == 1000 && intSlots[1] == null)
                    println("mutable list ok")
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.mutableListArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
            val failures =
                """
                import kotlin.collections.*
                fun main(args: Array<String>) {
                    val mode = args[0]
                    if (mode == "negative-capacity") { ArrayList<Int>(-1); return }
                    if (mode == "negative-reference-capacity") { ArrayList<String>(-1); return }
                    if (mode == "negative-array") { arrayOfNulls<Int>(-1); return }
                    if (mode == "quota") {
                        val growing = ArrayList<Int>(0)
                        var index = 0
                        while (index < 100000) { growing.add(index); index += 1 }
                        return
                    }
                    val list = ArrayList<Int>(10)
                    list.add(1)
                    if (mode == "read") { list[1]; return }
                    if (mode == "insert") { list.add(2, 7); return }
                    if (mode == "negative-insert") { list.add(-1, 7); return }
                    if (mode == "set") { list[1] = 7; return }
                    if (mode == "remove") { list.removeAt(1); return }
                    val iterator = list.iterator()
                    if (mode == "remove-before-next") { iterator.remove(); return }
                    iterator.next()
                    if (mode == "double-remove") { iterator.remove(); iterator.remove(); return }
                    if (mode == "exhausted") { iterator.next(); return }
                    list.add(2)
                    iterator.next()
                }
                """.trimIndent()
            val failureResult = adapter.compile(request(failures))
            val failureBytes = assertNotNull(failureResult.artifact, failureResult.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.mutableListArtifact")?.let { output ->
                Path.of("$output.failure.cpkt").writeBytes(failureBytes)
            }
        }

    @Test
    fun `mutable list element types remain invariant`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import kotlin.collections.*
                        fun main() {
                            val ints: MutableList<Int> = ArrayList<Int>()
                            val widened: MutableList<Any> = ints
                            widened.add("invalid")
                        }
                        """.trimIndent(),
                    ),
                )
            assertNull(result.artifact)
            assertTrue(result.diagnostics.any { it.severity.name == "ERROR" })
        }

    @Test
    fun `inline collection literals avoid closures while stored callbacks retain ownership`() =
        withAdapter { adapter ->
            fun closureCount(callback: String): Int {
                val result = adapter.compile(request("import kotlin.collections.*\nfun main() { $callback }"))
                val module =
                    ArtifactReader
                        .read(assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray())
                        .modules
                        .single { it.kind == ModuleKind.APPLICATION }
                return module.types.filterIsInstance<NominalType.Class>().count {
                    module.strings[it.name.value.toInt()].toString().startsWith("app.<lambda-")
                }
            }
            assertEquals(0, closureCount("val result = listOf(1, 2).map { it + 1 }"))
            assertEquals(1, closureCount("val transform: (Int) -> Int = { it + 1 }; val result = listOf(1, 2).map(transform)"))
        }

    @Test
    fun `Iterable filterNotNull narrows boxed Int and reference elements`() =
        withAdapter { adapter ->
            val source =
                """
                import kotlin.collections.*
                class Item(val value: Int)
                class Values(val values: List<Int?>) : Iterable<Int?> {
                    override fun iterator(): Iterator<Int?> = values.iterator()
                }
                fun <T : Any> present(values: Iterable<T?>): List<T> = values.filterNotNull()
                fun main() {
                    require(emptyList<Int?>().filterNotNull().isEmpty())
                    require(listOf<Int?>(null, null).filterNotNull().isEmpty())
                    val original = listOf<Int?>(null, 1000, null, 2000, 1000, null)
                    val input: Iterable<Int?> = Values(original)
                    val ints: List<Int> = present(input)
                    require(ints.size == 3 && ints[0] == 1000 && ints[1] == 2000 && ints[2] == 1000)
                    require(ints.fold(0) { total, value -> total + value } == 4000)
                    val first = Item(1)
                    val second = Item(2)
                    val objects: List<Item> = listOf<Item?>(null, first, null, second, first).filterNotNull()
                    require(objects.size == 3 && objects[0] === first && objects[1] === second && objects[2] === first)
                    require(objects.map { item -> item.value }.fold(0) { total, value -> total + value } == 4)
                    val strings: List<String> = listOf<String?>(null, "ab", "", null, "c").filterNotNull()
                    require(strings.size == 3 && strings[0] == "ab" && strings[1] == "" && strings[2] == "c")
                    require(strings.map { text -> text.length }.fold(0) { total, value -> total + value } == 3)
                    val mixedSource = listOf<Any?>(null, 1000, first, null, "x")
                    val mixed: List<Any> = present(mixedSource)
                    require(mixed.size == 3 && mixed[0] == 1000 && mixed[1] === first && mixed[2] == "x")
                    require(mixed[0] === mixedSource[1])
                    val universal: List<Any> = ints
                    require(universal[0] == 1000 && universal[1] == 2000)
                    val nonNullable: List<Int> = listOf(1, 2, 3).filterNotNull()
                    require(nonNullable.size == 3 && nonNullable[2] == 3)
                    val widenedInts: List<Int?> = listOf(1, 2, 1)
                    require(widenedInts[0] == 1 && !widenedInts.contains(null))
                    require(widenedInts.indexOf(1) == 0 && widenedInts.lastIndexOf(1) == 2)
                    require(widenedInts.indexOf(null) == -1 && widenedInts.lastIndexOf(null) == -1)
                    require(widenedInts.filterNotNull().fold(0) { total, value -> total + value } == 4)
                    val refs: List<Item> = listOf(first, second)
                    val widenedRefs: List<Item?> = refs
                    require(widenedRefs[0] === first && !widenedRefs.contains(null))
                    require(widenedRefs.indexOf(second) == 1 && widenedRefs.lastIndexOf(first) == 0)
                    require(refs.filterNotNull()[1] === second)
                    val ordinaryTexts: List<String> = listOf("ab", "c")
                    require(ordinaryTexts.filterNotNull().size == 2)
                    val mutableInts = ArrayList<Int>(0)
                    mutableInts.add(1000)
                    mutableInts.add(2000)
                    val nullableView: List<Int?> = mutableInts
                    require(nullableView[0] == 1000 && nullableView.indexOf(null) == -1)
                    require(nullableView.lastIndexOf(2000) == 1 && nullableView.contains(1000))
                    require(mutableInts.filterNotNull()[1] == 2000)
                    val mutableRefs = ArrayList<Item>()
                    mutableRefs.add(first)
                    val nullableRefs: List<Item?> = mutableRefs
                    require(nullableRefs[0] === first && nullableRefs.filterNotNull()[0] === first)
                    val growing = ArrayList<Int?>(0)
                    var index = 0
                    while (index < 100) {
                        growing.add(if (index % 2 == 0) index else null)
                        index += 1
                    }
                    val compact: List<Int> = growing.filterNotNull()
                    require(compact.size == 50 && compact[0] == 0 && compact[49] == 98)
                    require(growing.size == 100 && growing[1] == null)
                    println("filterNotNull ok")
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.filterNotNullArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `Iterable filter preserves traversal nullable elements and identity`() =
        withAdapter { adapter ->
            val source =
                """
                import kotlin.collections.*
                class Item(val value: Int)
                class Values(val values: List<Int>) : Iterable<Int> {
                    override fun iterator(): Iterator<Int> = values.iterator()
                }
                fun <T> select(values: Iterable<T>, predicate: (T) -> Boolean): List<T> = values.filter(predicate)
                fun main() {
                    var calls = 0
                    require(emptyList<Int>().filter { value -> calls += 1; value > 0 }.isEmpty())
                    require(calls == 0)
                    val input: Iterable<Int> = Values(listOf(1, 2, 3, 2))
                    var order = 0
                    val selected = select(input) { value ->
                        calls += 1
                        order = order * 10 + value
                        value == 2
                    }
                    require(calls == 4 && order == 1232)
                    require(selected.size == 2 && selected[0] == 2 && selected[1] == 2)
                    require(input.filter { false }.isEmpty())
                    require(input.filter { true }.size == 4)
                    val first = Item(1)
                    val second = Item(2)
                    val refs = listOf(first, second, first).filter { item -> item.value == 1 }
                    require(refs.size == 2 && refs[0] === first && refs[1] === first)
                    val nullable = listOf<Int?>(1000, null, 2000, null)
                    val boxes: List<Int?> = nullable.filter { value -> value != null }
                    require(boxes.size == 2 && boxes[1] == 2000)
                    require((boxes[0] as Any?) === (nullable[0] as Any?))
                    val nulls = nullable.filter { value -> value == null }
                    require(nulls.size == 2 && nulls[0] == null && nulls[1] == null)
                    val all = nullable.filter { true }
                    require(all.size == 4 && all[1] == null && all[3] == null)
                    val texts = listOf<String?>("ab", null, "", "c").filter { text -> text == null || text.length > 0 }
                    require(texts.size == 3 && texts[0] == "ab" && texts[1] == null && texts[2] == "c")
                    val optional = listOf<Item?>(first, null, second).filter { item -> item == null || item.value == 1 }
                    require(optional.size == 2 && optional[0] === first && optional[1] == null)
                    val mixed = listOf<Any?>(1000, first, null, "x").filter { value -> value is Int || value == null }
                    require(mixed.size == 2 && mixed[0] == 1000 && mixed[1] == null)
                    val universal: List<Any?> = boxes
                    require(universal[0] == 1000)
                    val growing = ArrayList<Int>(0)
                    var index = 0
                    while (index < 100) { growing.add(index); index += 1 }
                    calls = 0
                    val even = growing.filter { value -> calls += 1; value % 2 == 0 }
                    require(calls == 100 && even.size == 50 && even[49] == 98)
                    require(growing.size == 100 && growing[99] == 99)
                    require(even.map { value -> value + 1 }.fold(0) { total, value -> total + value } == 2500)
                    println("filter ok")
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.filterArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `Iterable destination operations append preserve types and return identity`() =
        withAdapter { adapter ->
            val source =
                """
                import kotlin.collections.*
                class Item(var value: Int)
                fun absent(item: Item?): Item? = null
                class Values(val values: List<Int?>) : Iterable<Int?> {
                    override fun iterator(): Iterator<Int?> = values.iterator()
                }
                fun <T, R, C : MutableCollection<in R>> append(
                    source: Iterable<T>, destination: C, transform: (T) -> R
                ): C = source.mapTo(destination, transform)
                fun main() {
                    val source: Iterable<Int?> = Values(listOf(1000, null, 2000))
                    val ints = ArrayList<Int>(0)
                    ints.add(7)
                    var calls = 0
                    var order = 0
                    val mapped: ArrayList<Int> = append(source, ints) { value ->
                        calls += 1
                        order = order * 10 + (value ?: 0) / 1000
                        value ?: 9
                    }
                    require(mapped === ints && calls == 3 && order == 102)
                    require(ints.size == 4 && ints[0] == 7 && ints[1] == 1000 && ints[2] == 9 && ints[3] == 2000)
                    val nullable = ArrayList<Int?>()
                    require(source.mapTo(nullable) { value -> value } === nullable)
                    require(nullable.size == 3 && nullable[1] == null)
                    val first = Item(1)
                    val second = Item(2)
                    val refs = listOf<Item?>(first, null, second, first)
                    val selected = ArrayList<Item?>()
                    selected.add(second)
                    calls = 0
                    require(refs.filterTo(selected) { item -> calls += 1; item !== second } === selected)
                    require(calls == 4 && selected.size == 4 && selected[0] === second)
                    require(selected[1] === first && selected[2] == null && selected[3] === first)
                    val objects = ArrayList<Item>()
                    objects.add(second)
                    calls = 0
                    require(refs.mapNotNullTo(objects) { item -> calls += 1; item } === objects)
                    require(calls == 4 && objects.size == 4 && objects[0] === second && objects[3] === first)
                    val texts = ArrayList<String>()
                    require(source.mapNotNullTo(texts) { value -> if (value == null) null else "v" + value } === texts)
                    require(texts.size == 2 && texts[0] == "v1000" && texts[1] == "v2000")
                    calls = 0
                    require(emptyList<Int>().mapTo(ints) { value -> calls += 1; value } === ints)
                    require(emptyList<Item>().filterTo(objects) { item -> calls += 1; true } === objects)
                    require(emptyList<Item>().mapNotNullTo(objects) { item -> calls += 1; item } === objects)
                    require(refs.mapNotNullTo(objects) { item -> calls += 1; absent(item) } === objects)
                    require(calls == 4 && objects.size == 4 && ints.size == 4)
                    val universal = ArrayList<Any?>()
                    require(source.mapTo(universal) { value -> value } === universal)
                    require(universal[0] == 1000 && universal[1] == null)
                    val destination: MutableCollection<Any?> = universal
                    refs.filterTo(destination) { item -> item != null }
                    require(universal.size == 6 && universal[3] === first && universal[5] === first)
                    val boxes: Iterable<Any?> = nullable
                    val nonNull = ArrayList<Any>()
                    val nonNullDestination: MutableCollection<Any> = nonNull
                    boxes.mapNotNullTo(nonNullDestination) { value -> value }
                    require(nonNull.size == 2)
                    require((nonNull[0] as Any?) === (nullable[0] as Any?))
                    var round = 0
                    while (round < 100) {
                        ints.clear()
                        source.mapNotNullTo(ints) { value -> value }
                        require(ints.size == 2 && ints[0] == 1000 && ints[1] == 2000)
                        round += 1
                    }
                    require(refs[0] === first && refs[1] == null)
                    val pool = ArrayList<Item>()
                    pool.add(first)
                    objects.clear()
                    refs.mapNotNullTo(objects) { item -> if (item === first) pool[0] else null }
                    require(objects.size == 2 && objects[0] === first && objects[1] === first)
                    var active = pool
                    val readActive = { active[0] }
                    require(readActive() === first)
                    val otherPool = ArrayList<Item>()
                    otherPool.add(second)
                    active = otherPool
                    require(readActive() === second)
                    println("destination ok")
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.destinationArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `Iterable mapNotNull preserves traversal narrowing and identity`() =
        withAdapter { adapter ->
            val source =
                """
                import kotlin.collections.*
                class Item(val value: Int)
                class Values(val values: List<Int?>) : Iterable<Int?> {
                    override fun iterator(): Iterator<Int?> = values.iterator()
                }
                fun <T, R : Any> present(values: Iterable<T>, transform: (T) -> R?): List<R> =
                    values.mapNotNull(transform)
                fun main() {
                    var calls = 0
                    require(emptyList<Int>().mapNotNull { value -> calls += 1; value }.isEmpty())
                    require(calls == 0)
                    val input: Iterable<Int?> = Values(listOf(1000, null, 2000, 3000))
                    var order = 0
                    val ints: List<Int> = present(input) { value ->
                        calls += 1
                        order = order * 10 + (value ?: 0) / 1000
                        if (value == 2000) null else value
                    }
                    require(calls == 4 && order == 1023)
                    require(ints.size == 2 && ints[0] == 1000 && ints[1] == 3000)
                    calls = 0
                    require(input.mapNotNull<Int?, Item> { value -> calls += 1; null }.isEmpty())
                    require(calls == 4)
                    val first = Item(1000)
                    val second = Item(2000)
                    val refs = listOf<Item?>(first, null, second, first)
                    val objects: List<Item> = refs.mapNotNull { item -> item }
                    require(objects.size == 3 && objects[0] === first && objects[1] === second)
                    require(objects[2] === first && refs[1] == null)
                    val texts: List<String> = listOf<String?>("ab", null, "", "c").mapNotNull { text -> text }
                    require(texts.size == 3 && texts[0] == "ab" && texts[1] == "" && texts[2] == "c")
                    val changed: List<String> = listOf(1, 2, 3).mapNotNull { value ->
                        if (value == 2) null else "v" + value
                    }
                    require(changed.size == 2 && changed[0] == "v1" && changed[1] == "v3")
                    val nullable = listOf<Int?>(1000, null, 2000)
                    val widened: Iterable<Any?> = nullable
                    val universal: List<Any> = widened.mapNotNull { value -> value }
                    require(universal.size == 2 && universal[0] == 1000 && universal[1] == 2000)
                    require((universal[0] as Any?) === (nullable[0] as Any?))
                    val growing = ArrayList<Int>(0)
                    var index = 0
                    while (index < 100) { growing.add(index); index += 1 }
                    calls = 0
                    val transformed: List<Item> = growing.mapNotNull { value ->
                        calls += 1
                        if (value % 2 == 0) Item(value + 1) else null
                    }
                    require(calls == 100 && transformed.size == 50 && transformed[49].value == 99)
                    require(growing.size == 100 && growing[99] == 99)
                    println("mapNotNull ok")
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.mapNotNullArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `Iterable map preserves order independent types and nullable identity`() =
        withAdapter { adapter ->
            val source =
                """
                import kotlin.collections.*
                class Item(val value: Int)
                class Values(val values: List<Int>) : Iterable<Int> {
                    override fun iterator(): Iterator<Int> = values.iterator()
                }
                fun <T, R> convert(values: Iterable<T>, transform: (T) -> R): List<R> = values.map(transform)
                fun earlyMap(values: List<Int>): Int { values.map { if (it == 2) return it; it }; return -1 }
                fun earlyFilter(values: List<Int>): Int { values.filter { if (it == 2) return it; false }; return -1 }
                fun earlyMapNotNull(values: List<Int>): Int { values.mapNotNull<Int, Int> { if (it == 2) return it; null }; return -1 }
                fun earlyFold(values: List<Int>): Int { values.fold(0) { total, it -> if (it == 2) return total; total + it }; return -1 }
                fun earlyAny(values: List<Int>): Int { values.any { return it }; return -1 }
                fun earlyAll(values: List<Int>): Int { values.all { return it }; return -1 }
                fun earlyNone(values: List<Int>): Int { values.none { return it }; return -1 }
                fun earlyFirst(values: List<Int>): Int { values.firstOrNull { return it }; return -1 }
                fun earlyLast(values: List<Int>): Int { values.lastOrNull { return it }; return -1 }
                fun earlyMapTo(values: List<Int>, destination: ArrayList<Int>): Int {
                    values.mapTo(destination) { if (it == 2) return it; it }; return -1
                }
                fun earlyFilterTo(values: List<Int>, destination: ArrayList<Int>): Int {
                    values.filterTo(destination) { if (it == 2) return it; true }; return -1
                }
                fun earlyMapNotNullTo(values: List<Int>, destination: ArrayList<Int>): Int {
                    values.mapNotNullTo(destination) { if (it == 2) return it; it }; return -1
                }
                fun receiver(values: List<Int>, calls: IntArray): List<Int> { calls[0] += 1; return values }
                class GenericMap<T>(val value: T) {
                    fun repeat(): List<T> = listOf(value).map { it }
                }
                fun main() {
                    var calls = 0
                    val empty = emptyList<Int>().map { value -> calls += 1; value }
                    require(empty.isEmpty() && calls == 0)
                    val input: Iterable<Int> = Values(listOf(1, 2, 3))
                    var order = 0
                    val strings = convert(input) { value ->
                        calls += 1
                        order = order * 10 + value
                        "v" + value
                    }
                    require(calls == 3 && order == 123)
                    require(strings.size == 3 && strings[0] == "v1" && strings[2] == "v3")
                    require(strings.map { text -> text.length }.fold(0) { total, value -> total + value } == 6)
                    val first = Item(1000)
                    val refs = listOf(first, Item(2000))
                    val identities = refs.map { item -> item }
                    require(identities[0] === first && identities[1] === refs[1])
                    val nullable = listOf<Int?>(1000, null, 2000)
                    val boxes = nullable.map { value -> value }
                    require(boxes[1] == null && boxes[2] == 2000)
                    require((boxes[0] as Any?) === (nullable[0] as Any?))
                    require(nullable.map { value -> value ?: 7 }.fold(0) { total, value -> total + value } == 3007)
                    val optional = input.map { value -> if (value == 2) null else value }
                    require(optional[0] == 1 && optional[1] == null && optional[2] == 3)
                    val objects = input.map { value -> Item(value) }
                    require(objects[2].value == 3)
                    val nullableObjects = input.map { value -> if (value == 2) null else first }
                    require(nullableObjects[0] === first && nullableObjects[1] == null)
                    val texts = listOf<String?>("ab", null, "c")
                    require(texts.map { text -> text?.length ?: 0 }.fold(0) { total, value -> total + value } == 3)
                    val universal: List<Any?> = boxes
                    require(universal[0] == 1000 && universal[1] == null)
                    val mixed = input.map<Int, Any?> { value -> if (value == 2) null else value }
                    require(mixed[0] == 1 && mixed[1] == null)
                    val growing = ArrayList<Int>(0)
                    var index = 0
                    while (index < 100) { growing.add(index); index += 1 }
                    calls = 0
                    val mapped = growing.map { value -> calls += 1; value + 1 }
                    require(calls == 100 && mapped.size == 100 && mapped[99] == 100)
                    require(growing[99] == 99 && growing.size == 100)
                    val collection: Collection<Item> = refs
                    calls = 0
                    order = 0
                    val collectionMapped = collection.map { item ->
                        calls += 1
                        order = order * 10 + item.value / 1000
                        item
                    }
                    require(calls == 2 && order == 12)
                    require(collectionMapped[0] === first && collectionMapped[1] === refs[1])
                    val genericCollection: Collection<Int> = growing
                    require(genericCollection.map { value -> value + 2 }[99] == 101)
                    require((emptyList<Item>() as Collection<Item>).map { item -> calls += 1; item }.isEmpty())
                    require(calls == 2)
                    val earlyInput = listOf(1, 2, 3)
                    require(earlyMap(earlyInput) == 2 && earlyFilter(earlyInput) == 2 && earlyMapNotNull(earlyInput) == 2)
                    require(earlyFold(earlyInput) == 1 && earlyAny(earlyInput) == 1 && earlyAll(earlyInput) == 1)
                    require(earlyNone(earlyInput) == 1 && earlyFirst(earlyInput) == 1 && earlyLast(earlyInput) == 3)
                    val destination = ArrayList<Int>()
                    require(earlyMapTo(earlyInput, destination) == 2 && destination.size == 1 && destination[0] == 1)
                    destination.clear()
                    require(earlyFilterTo(earlyInput, destination) == 2 && destination.size == 1 && destination[0] == 1)
                    destination.clear()
                    require(earlyMapNotNullTo(earlyInput, destination) == 2 && destination.size == 1 && destination[0] == 1)
                    require(GenericMap(3).repeat()[0] == 3 && GenericMap(first).repeat()[0] === first)
                    val evaluations = IntArray(1)
                    receiver(earlyInput, evaluations).map { it + 1 }
                    require(evaluations[0] == 1)
                    println("map ok")
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.mapArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `Iterable fold specializes independent element and accumulator types`() =
        withAdapter { adapter ->
            val source =
                """
                import kotlin.collections.*

                class State(val total: Int)
                class Values(val values: List<Int>) : Iterable<Int> {
                    override fun iterator(): Iterator<Int> = values.iterator()
                }
                fun <T, R> accumulate(values: Iterable<T>, initial: R, operation: (R, T) -> R): R =
                    values.fold(initial, operation)
                fun main() {
                    var calls = 0
                    val initial = State(7)
                    val empty = emptyList<Int>()
                    val unchanged = empty.fold(initial) { state, value -> calls += 1; State(state.total + value) }
                    require(unchanged === initial)
                    require(calls == 0)
                    require(empty.fold<Int, Int?>(null) { sum, value -> (sum ?: 0) + value } == null)
                    val values: Iterable<Int> = Values(listOf(1, 2, 3))
                    calls = 0
                    var order = 0
                    val sum = values.fold(10) { total, value ->
                        calls += 1
                        order = order * 10 + value
                        total + value
                    }
                    require(sum == 16)
                    require(calls == 3 && order == 123)
                    require(values.fold(100) { total, value -> total - value } == 94)
                    require(values.fold(0L) { total, value -> total + value } == 6L)
                    require(values.fold(true) { valid, value -> valid && value > 0 })
                    require(values.fold("") { text, value -> text + value } == "123")
                    val strings: Iterable<String?> = listOf("ab", null, "c")
                    require(strings.fold(0) { count, text -> count + (text?.length ?: 0) } == 3)
                    require(accumulate(strings, 0) { count, text -> count + (text?.length ?: 0) } == 3)
                    val nullable = listOf<Int?>(1, null, 3)
                    require(nullable.fold(0) { total, value -> total + (value ?: 0) } == 4)
                    require(values.fold<Int, Int?>(null) { total, value -> (total ?: 0) + value } == 6)
                    val nullableResult = values.fold<Int, Int?>(7) { total, value ->
                        if (value == 3) null else (total ?: 0) + value
                    }
                    require(nullableResult == null)
                    val state = values.fold(State(0)) { acc, value -> State(acc.total + value) }
                    require(state.total == 6)
                    val nullableState = values.fold<Int, State?>(null) { acc, value -> State((acc?.total ?: 0) + value) }
                    require((nullableState?.total ?: 0) == 6)
                    val mixed: Iterable<Any?> = listOf<Any?>(2, null, 4)
                    require(mixed.fold(0) { total, value -> if (value is Int) total + value else total } == 6)
                    val boxed = listOf<Int?>(1000, null, 2000)
                    val selected: Any? = boxed.fold<Int?, Int?>(null) { _, value -> value }
                    val stored: Any? = boxed[2]
                    require(selected === stored)
                    println("fold ok")
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.foldArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `collection nullable selection preserves traversal values and identity`() =
        withAdapter { adapter ->
            val source =
                """
                import kotlin.collections.*

                class Node(val value: Int)
                class Probe(val values: List<Int>) : Iterable<Int> {
                    var visits: Int = 0
                    override fun iterator(): Iterator<Int> = ProbeIterator(this)
                }
                class ProbeIterator(val probe: Probe) : Iterator<Int> {
                    var index: Int = 0
                    override fun hasNext(): Boolean = index < probe.values.size
                    override fun next(): Int {
                        probe.visits += 1
                        val value = probe.values[index]
                        index += 1
                        return value
                    }
                }
                fun main() {
                    val empty = emptyList<Int>()
                    require(empty.firstOrNull() == null)
                    require(empty.lastOrNull() == null)
                    require(empty.getOrNull(0) == null)
                    require(empty.firstOrNull { true } == null)
                    require(empty.lastOrNull { true } == null)
                    val ints = listOf(1, 2, 3)
                    require(ints.firstOrNull() == 1)
                    require(ints.lastOrNull() == 3)
                    require(ints.getOrNull(1) == 2)
                    require(ints.getOrNull(-1) == null)
                    require(ints.getOrNull(-2147483647 - 1) == null)
                    require(ints.getOrNull(3) == null)
                    require(ints.getOrNull(2147483647) == null)
                    val probe = Probe(ints)
                    require(probe.firstOrNull() == 1)
                    require(probe.visits == 1)
                    probe.visits = 0
                    var order = 0
                    require(probe.firstOrNull { order = order * 10 + it; it == 2 } == 2)
                    require(probe.visits == 2 && order == 12)
                    probe.visits = 0
                    order = 0
                    require(probe.lastOrNull { order = order * 10 + it; it < 3 } == 2)
                    require(probe.visits == 3 && order == 123)
                    probe.visits = 0
                    require(probe.lastOrNull() == 3)
                    require(probe.visits == 3)
                    require(probe.firstOrNull { it == 9 } == null)
                    require(probe.lastOrNull { it == 9 } == null)
                    order = 0
                    require(ints.lastOrNull { order = order * 10 + it; it < 3 } == 2)
                    require(order == 32)
                    val noValues = Probe(empty)
                    require(noValues.firstOrNull() == null)
                    require(noValues.lastOrNull() == null)
                    require(noValues.visits == 0)
                    val nullable = listOf<Int?>(1000, null, 2000, null)
                    var calls = 0
                    require(nullable.firstOrNull { calls += 1; it == null } == null)
                    require(calls == 2)
                    calls = 0
                    require(nullable.lastOrNull { calls += 1; it == null } == null)
                    require(calls == 1)
                    val nullableIterable: Iterable<Int?> = nullable
                    calls = 0
                    require(nullableIterable.lastOrNull { calls += 1; it == null } == null)
                    require(calls == 4)
                    val first: Any? = nullable.firstOrNull()
                    val stored: Any? = nullable[0]
                    require(first === stored)
                    val indexed: Any? = nullable.getOrNull(0)
                    require(indexed === stored)
                    val matched: Any? = nullable.lastOrNull { it != null }
                    val lastStored: Any? = nullable[2]
                    require(matched === lastStored)
                    require(nullable.lastOrNull() == null)
                    val node = Node(7)
                    val nodes = listOf<Node?>(node, null)
                    require(nodes.firstOrNull() === node)
                    require(nodes.lastOrNull() == null)
                    require(nodes.getOrNull(0) === node)
                    val strings: Iterable<String?> = listOf(null, "a", "b")
                    require(strings.firstOrNull() == null)
                    require(strings.firstOrNull { it != null } == "a")
                    require(strings.lastOrNull() == "b")
                    val universal: List<Any?> = nullable
                    require(universal.firstOrNull() === stored)
                    require(universal.lastOrNull { it != null } === lastStored)
                    require(universal.getOrNull(0) === stored)
                    println("selection ok")
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.collectionSelectionArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `nullable Int and collection elements preserve values and nulls`() =
        withAdapter { adapter ->
            val source =
                """
                import kotlin.collections.listOf
                import kotlin.collections.emptyList

                fun echo(value: Int?): Int? = value
                fun box(value: Int): Int? = value
                class Slot(var value: Int?)
                class Box<T>(val value: T)
                fun <T> single(value: T): kotlin.collections.List<T> = listOf(value)
                fun main() {
                    val missing: Int? = null
                    val first: Int? = 7
                    require(missing == null)
                    require(first != null)
                    require((first ?: 0) == 7)
                    require((missing ?: 9) == 9)
                    require(echo(8) == 8)
                    require(echo(null) == null)
                    require(box(1000) == box(1000))
                    require(box(1000) != box(1001))
                    val text: String? = "seven"
                    val length: Int? = text?.length
                    require((length ?: 0) == 5)
                    val noText: String? = null
                    require(noText?.length == null)
                    var changing: Int? = null
                    changing = 7
                    require(changing == first)
                    changing = null
                    require(changing == null)
                    val slot = Slot(7)
                    require(slot.value == first)
                    slot.value = null
                    require(slot.value == null)
                    val broad: Any? = first
                    require(broad is Int)
                    require((broad as Int) == 7)
                    val absent: Any? = missing
                    require(absent == null)
                    require(absent is Int?)
                    require(null is Int?)
                    require((null as Int?) == null)
                    require(broad is Int?)
                    require(broad == box(7))
                    require(broad != absent)
                    val restored: Int? = broad as Int?
                    require(restored == 7)
                    val restoredNull: Int? = absent as Int?
                    require(restoredNull == null)
                    require(Box<Int?>(null).value == null)
                    require((Box<Int?>(4).value ?: 0) == 4)
                    require(single<Int?>(null)[0] == null)
                    require((single<Int?>(4)[0] ?: 0) == 4)
                    val ints = listOf<Int?>(7, null, 9, null)
                    require(ints.size == 4)
                    require((ints[0] ?: 0) == 7)
                    require(ints[1] == null)
                    require(ints.contains(null))
                    require(ints.indexOf(null) == 1)
                    require(ints.lastIndexOf(null) == 3)
                    require(ints.indexOf(9) == 2)
                    var total = 0
                    for (value in ints) total += value ?: 0
                    require(total == 16)
                    val universal: kotlin.collections.List<Any?> = ints
                    require(universal[0] == 7)
                    require(universal[1] == null)
                    require(universal.contains(null))
                    require(universal.indexOf(9) == 2)
                    val scalars: kotlin.collections.List<Any?> = listOf(7, 9)
                    require(scalars[0] == 7)
                    require(!scalars.contains(null))
                    require(emptyList<Int?>().isEmpty())
                    val strings = listOf<String?>("seven", null)
                    require(strings[1] == null)
                    require(strings.contains(null))
                    val equalText: String? = "seven!".substring(0, 5)
                    require(strings.contains(equalText))
                    require(strings.indexOf(equalText) == 0)
                    require(strings.lastIndexOf(equalText) == 0)
                    val broadStrings: kotlin.collections.List<Any?> = strings
                    var stringCount = 0
                    for (value in broadStrings) {
                        if (value == null) stringCount += 1
                        else require(value == "seven")
                    }
                    require(stringCount == 1)
                    val slots = listOf<Slot?>(slot, null)
                    require(slots[0] == slot)
                    require(slots[1] == null)
                    val array = arrayOf<Int?>(null, 4)
                    array[0] = 7
                    require((array[0] ?: 0) == 7)
                    array[1] = null
                    require(array[1] == null)
                    val mixed = listOf<Any?>(1000, null, "seven", slot)
                    require(mixed[0] == 1000)
                    require(mixed[1] == null)
                    require(!(mixed[2] is Int?))
                    require(mixed.contains(null))
                    require(mixed.indexOf(slot) == 3)
                    require((mixed[2] as String) == "seven")
                    require((mixed[3] as Slot) === slot)
                    val mixedArray = arrayOf<Any?>(1000, null, slot)
                    require(mixedArray[0] == 1000)
                    require(mixedArray[1] == null)
                    require(mixedArray[0] === mixedArray[0])
                    require(mixedArray[1] === null)
                    require((7 as Any?) == 7)
                    val textArray = arrayOf<String?>(null, "seven")
                    textArray[1] = null
                    require(textArray[0] == null && textArray[1] == null)
                    val slotArray = arrayOf<Slot?>(slot, null)
                    slotArray[0] = null
                    require(slotArray[0] == null && slotArray[1] == null)
                    var allocations = 0
                    while (allocations < 60000) {
                        box(allocations)
                        allocations += 1
                    }
                    require(universal[0] == 7)
                    require(universal[1] == null)
                    require(mixed[0] == 1000)
                    require(mixed[3] == slot)
                    println("nullable ok")
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            System.getProperty("compukter.vm.nullableCollectionsArtifact")?.let { output ->
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
                    "import kotlin.collections.listOf\nfun main() { listOf<Long?>(null) }",
                    "import kotlin.collections.listOf\nfun main() { listOf(true) }",
                    "import kotlin.collections.listOf\nfun main() { listOf<Any>(true) }",
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
                    "class Box<T>(val value: T)\nfun main() { Box<Long?>(null) }",
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
                    "sealed interface Value\ndata class NumberValue(val value: Int) : Value\nfun read(value: Value): Int = (value as? NumberValue)?.value ?: 0\nfun main() { read(NumberValue(1)) }",
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
                "fun main() { arrayOf<Long?>(null) }",
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
                        import kotlin.text.toIntOrNull

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
                            require("0".toIntOrNull() == 0)
                            require("+00123".toIntOrNull() == 123)
                            require("-0".toIntOrNull() == 0)
                            require("2147483647".toIntOrNull() == Int.MAX_VALUE)
                            require("-2147483648".toIntOrNull() == Int.MIN_VALUE)
                            require("".toIntOrNull() == null)
                            require("+".toIntOrNull() == null)
                            require("-".toIntOrNull() == null)
                            require("2147483648".toIntOrNull() == null)
                            require("-2147483649".toIntOrNull() == null)
                            require(" 12".toIntOrNull() == null)
                            require("12 ".toIntOrNull() == null)
                            require("1.2".toIntOrNull() == null)
                            require("\u0661".toIntOrNull() == null)
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
    fun `same nominal identity and null comparison retain baseline runtime ABI`() =
        withAdapter { adapter ->
            val source =
                """
                class Box
                fun main() {
                    val value = Box()
                    val missing: Box? = null
                    val first = value === value
                    val second = value !== missing
                    val third = missing === null
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val bytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            assertEquals(AbiVersion(1u, 0u), ArtifactReader.read(bytes).minimumRuntimeAbi)
        }

    @Test
    fun `specialized IntArray lowers deterministically for vm conformance`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.terminal.Terminal

                class CopyItem(val value: Int)
                class AnyHolder(val value: Any)
                fun eraseArray(value: Any): Any = value
                fun isIntArray(value: Any): Boolean = value is IntArray
                fun isCharArray(value: Any): Boolean = value is CharArray
                fun <T> retain(value: T): T = value
                fun copySource(copyReceiverCalls: IntArray): IntArray {
                    copyReceiverCalls[0] += 1
                    return intArrayOf(5, 6, 7)
                }
                fun <T> copyGeneric(values: Array<T>): Array<T> = values.copyOf()
                fun identityOperand(values: IntArray, steps: IntArray, expected: Int): IntArray {
                    require(steps[0] == expected)
                    steps[0] += 1
                    return values
                }
                fun verifyBulkCopy() {
                    val original = IntArray(700)
                    var index = 0
                    while (index < original.size) { original[index] = index; index += 1 }
                    val grown = original.copyOf(710)
                    require(grown !== original && original === original)
                    val originalAlias = original
                    require(originalAlias === original && originalAlias !== original.copyOf())
                    val erased: Any = original
                    val nullableErased: Any? = original
                    require(erased === original && nullableErased === original)
                    require((erased as IntArray) === original)
                    require((eraseArray(original) as IntArray) === original)
                    require((AnyHolder(original).value as IntArray) === original)
                    require((retain<Any>(original) as IntArray) === original)
                    require(isIntArray(erased) && isCharArray(erased) == false)
                    (eraseArray(original) as IntArray)[0] = 42
                    require(original[0] == 42)
                    original[0] = 0
                    val identitySteps = intArrayOf(0)
                    require(identityOperand(original, identitySteps, 0) === identityOperand(originalAlias, identitySteps, 1))
                    require(identitySteps[0] == 2)
                    require(grown[699] == 699 && grown[700] == 0)
                    grown[0] = -1
                    require(original[0] == 0)
                    grown[0] = 0
                    require(original.copyOf()[699] == 699 && original.copyOf(2)[1] == 1)
                    require(original.copyOf(0).size == 0)
                    val returned = grown.copyInto(grown, destinationOffset = 1, endIndex = 700)
                    require(returned === grown)
                    returned[0] = -1
                    require(grown[0] == -1)
                    grown[0] = 0
                    require(grown[1] == 0 && grown[700] == 699)
                    grown.copyInto(grown, startIndex = 1, endIndex = 701)
                    require(grown[0] == 0 && grown[699] == 699)
                    val destination = IntArray(3)
                    val copyReceiverCalls = intArrayOf(0)
                    copySource(copyReceiverCalls).copyInto(destination)
                    require(copyReceiverCalls[0] == 1 && destination[2] == 7)
                    original.copyInto(destination, endIndex = 0)

                    val chars = CharArray(2)
                    val erasedChars = eraseArray(chars)
                    require(isCharArray(erasedChars) && isIntArray(erasedChars) == false)
                    require((erasedChars as CharArray) === chars)
                    chars[0] = 'A'; chars[1] = '\uD800'
                    val moreChars = chars.copyOf(3)
                    require(moreChars !== chars && chars === chars)
                    require(moreChars[0] == 'A' && moreChars[1] == '\uD800' && moreChars[2] == '\u0000')
                    chars.copyInto(moreChars, 1)
                    require(moreChars[2] == '\uD800' && chars.copyOf()[0] == 'A')

                    val first = CopyItem(1)
                    val nullableItem: CopyItem? = first
                    val missingItem: CopyItem? = null
                    require(nullableItem === first && nullableItem !== missingItem && missingItem === null)
                    val second = CopyItem(2)
                    val objects = arrayOf(first, second)
                    require((eraseArray(objects) as Array<CopyItem>) === objects)
                    val strings = arrayOf("first", "second")
                    require((eraseArray(strings) as Array<String>) === strings)
                    require(copyGeneric(objects)[0] === first)
                    val nullable = objects.copyOf(3)
                    require(nullable !== objects && objects === objects)
                    require(nullable[0] === first && nullable[2] == null)
                    objects.copyInto(nullable, 1)
                    require(nullable[1] === first && nullable[2] === second)
                    nullable.copyInto(nullable, 1, 0, 2)
                    require(nullable[2] === first)
                    val anyValues = arrayOfNulls<Any>(3)
                    objects.copyInto(anyValues)
                    require(anyValues[0] === first && anyValues[1] === second)
                    anyValues[2] = original
                    require((anyValues[2] as IntArray) === original)
                    val boxes = arrayOfNulls<Int>(2)
                    boxes[0] = 42
                    val copiedBoxes = boxes.copyOf(3)
                    require(copiedBoxes[0] == 42 && copiedBoxes[1] == null && copiedBoxes[2] == null)
                    require(arrayOf("a", "b").copyOfRange(1, 2)[0] == "b")

                    val ints = ArrayList<Int>()
                    val references = ArrayList<CopyItem?>()
                    index = 0
                    while (index < 100) { ints.add(index); references.add(first); index += 1 }
                    require(ints[99] == 99 && references[99] === first)
                }

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
                        verifyBulkCopy()
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
                    } else if (mode == 4) {
                        val values = IntArray(1)
                        values[1] = 1
                    } else if (mode == 5) {
                        intArrayOf(1).copyOf(-1)
                    } else if (mode == 6) {
                        intArrayOf(1).copyInto(IntArray(1), destinationOffset = -1)
                    } else if (mode == 7) {
                        intArrayOf(1).copyInto(IntArray(1), startIndex = 1, endIndex = 0)
                    } else {
                        intArrayOf(1, 2).copyInto(IntArray(1))
                    }
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            val decoded = ArtifactReader.read(artifact)
            // Output formatting retains the native arithmetic exception factory.
            assertEquals(AbiVersion(1u, 9u), decoded.minimumRuntimeAbi)
            assertTrue(SemanticFeature.ARRAY_COPY in decoded.semanticFeatures)
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

                        import kotlin.collections.*

                        inline fun invoke(block: () -> Int): Int = block()
                        fun readKey(): Int {
                            Terminal.awaitEvent()
                            return Terminal.eventKey()
                        }

                        fun main() {
                            Terminal.write(if (invoke { kotlin.collections.listOf(1).map { readKey() }[0] } == 13) "enter" else "other")
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
    fun `explicit exception preserves class and message for vm execution`() =
        withAdapter { adapter ->
            val source = "fun fail() { throw IllegalArgumentException(\"bad argument\") }; fun main() { fail() }"
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertEquals(AbiVersion(1u, 9u), ArtifactReader.read(artifact).minimumRuntimeAbi)
            System.getProperty("compukter.vm.exceptionsArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `caught exception preserves identity hierarchy cause and expression result for vm execution`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.io.IOException
                class Problem(message: String?, cause: Throwable?) : RuntimeException(message, cause)
                class State { var order: Int = 0 }
                open class InputProblem(message: String?, cause: Throwable? = null) : IOException(message, cause)
                class DetailedProblem(state: State, message: String?, cause: Throwable?) : InputProblem(message, cause) {
                    val detail: String? = message
                    init { state.order = state.order * 10 + 3 }
                }
                fun message(state: State): String { state.order = state.order * 10 + 2; return "input" }
                fun cause(state: State, error: Throwable): Throwable { state.order = state.order * 10 + 1; return error }
                fun fail(error: Throwable) { throw error }
                fun main() {
                    val cause = IllegalArgumentException("root")
                    val error = Problem("detail", cause)
                    var matched = false
                    val value = try {
                        fail(error)
                        0
                    } catch (wrong: IllegalArgumentException) {
                        -1
                    } catch (caught: RuntimeException) {
                        require(caught === error)
                        require(caught.message == "detail")
                        require(caught.cause === cause)
                        matched = true
                        42
                    }
                    require(matched && value == 42)
                    try {
                        try { fail(error) } catch (caught: Exception) { throw caught }
                    } catch (caught: Throwable) {
                        require(caught === error)
                    }
                    val state = State()
                    val input = DetailedProblem(cause = cause(state, cause), message = message(state), state = state)
                    require(state.order == 123 && input.detail == "input")
                    try { fail(input) } catch (caught: IOException) {
                        require(caught === input && caught.message == "input" && caught.cause === cause)
                    }
                    val plain = InputProblem("plain")
                    require(plain.message == "plain" && plain.cause == null)
                    val direct = IOException(cause = cause, message = "direct")
                    require(direct.message == "direct" && direct.cause === cause)
                    require(IOException("no cause").cause == null)
                    require(NoWhenBranchMatchedException().message == null)
                    println("exceptions ok")
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(ArtifactReader.read(artifact).modules.any { it.exceptions.isNotEmpty() })
            System.getProperty("compukter.vm.exceptionsArtifact")?.let { output ->
                Path.of("$output.caught.cpkt").also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `finally preserves normal exceptional and nonlocal exits for vm execution`() =
        withAdapter { adapter ->
            val source =
                """
                class State { var log: Int = 0 }
                fun mark(state: State, digit: Int) { state.log = state.log * 10 + digit }
                fun nestedReturn(state: State): Int {
                    try { try { return 7 } finally { mark(state, 1) } }
                    finally { mark(state, 2) }
                }
                fun replaceReturn(): Int { try { return 1 } finally { return 2 } }
                fun snapshotReturn(): Int {
                    var value = 1
                    try { return value } finally { value = 2 }
                }
                inline fun invoke(block: () -> Int): Int = block()
                fun inlineReturn(state: State): Int {
                    try { invoke { return 3 } } finally { mark(state, 4) }
                    return 0
                }
                fun main() {
                    val state = State()
                    require(nestedReturn(state) == 7 && state.log == 12)
                    require(replaceReturn() == 2)
                    require(snapshotReturn() == 1)
                    state.log = 0
                    require(inlineReturn(state) == 3 && state.log == 4)
                    state.log = 0
                    val value = try { 42 } finally { mark(state, 1) }
                    require(value == 42 && state.log == 1)
                    require((try { 1 } catch (ignored: Throwable) { 2 }) == 1)
                    state.log = 0
                    var index = 0
                    while (index < 3) {
                        index = index + 1
                        try {
                            if (index == 1) continue
                            if (index == 2) break
                        } finally { mark(state, index) }
                    }
                    require(state.log == 12)
                    state.log = 0
                    try {
                        while (true) { break }
                        val local = invoke { return@invoke 5 }
                        require(local == 5)
                        mark(state, 1)
                    } finally { mark(state, 2) }
                    require(state.log == 12)
                    val original = RuntimeException(null, null)
                    val replacement = Exception("replacement", original)
                    state.log = 0
                    try {
                        try { throw original }
                        catch (caught: RuntimeException) { mark(state, 1); throw caught }
                        finally { mark(state, 2) }
                    } catch (caught: Throwable) {
                        require(caught === original && state.log == 12)
                    }
                    try {
                        try { throw original } finally { throw replacement }
                    } catch (caught: Throwable) { require(caught === replacement) }
                    println("finally ok")
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            System.getProperty("compukter.vm.exceptionsArtifact")?.let { output ->
                Path.of("$output.finally.cpkt").also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `assertions and exhaustive reference when share exceptions for vm execution`() =
        withAdapter { adapter ->
            val source =
                """
                enum class Choice { FIRST, SECOND }
                fun text(value: Boolean): String = when (value) { true -> "yes"; false -> "no" }
                fun text(value: Choice): String = when (value) { Choice.FIRST -> "first"; Choice.SECOND -> "second" }
                fun main() {
                    check(text(true) == "yes" && text(false) == "no")
                    check(text(Choice.FIRST) == "first" && text(Choice.SECOND) == "second")
                    var evaluated = false
                    require(true) { evaluated = true; "unused" }
                    check(!evaluated)
                    check(true) { evaluated = true; "unused" }
                    require(!evaluated)
                    try { require(false) { "custom requirement" } }
                    catch (caught: IllegalArgumentException) { check(caught.message == "custom requirement") }
                    try { check(false) }
                    catch (caught: IllegalStateException) { require(caught.message == "Check failed.") }
                    try { check(false) { "custom check" } }
                    catch (caught: IllegalStateException) { require(caught.message == "custom check") }
                    try { error("boom") }
                    catch (caught: IllegalStateException) { require(caught.message == "boom") }
                    println("assertions ok")
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            System.getProperty("compukter.vm.exceptionsArtifact")?.let { output ->
                Path.of("$output.stdlib.cpkt").also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `exceptions preserve failed task joins and cleanup across suspension for vm execution`() =
        withAdapter { adapter ->
            val sources =
                mapOf(
                    "tasks" to
                        """
                        import compukter.concurrent.Tasks
                        fun fail() { throw RuntimeException("task failure") }
                        fun main() {
                            val task = Tasks.launch(::fail)
                            var original: Throwable? = null
                            var cleanups = 0
                            try { task.join() }
                            catch (caught: RuntimeException) {
                                require(caught.message == "task failure")
                                original = caught
                            } finally { cleanups = cleanups + 1 }
                            require(original != null)
                            try { task.join() }
                            catch (caught: RuntimeException) { require(caught === original) }
                            finally { cleanups = cleanups + 1 }
                            require(cleanups == 2)
                            Tasks.launch(::fail)
                            val healthy = Tasks.launch { }
                            healthy.join()
                            println("tasks ok")
                        }
                        """.trimIndent(),
                    "suspend" to
                        """
                        fun main() {
                            try {
                                require(readln() == "resume")
                                println("resumed")
                            } finally { println("finally") }
                        }
                        """.trimIndent(),
                )
            for ((name, source) in sources) {
                val first = adapter.compile(request(source))
                val second = adapter.compile(request(source))
                val artifact = assertNotNull(first.artifact, "$name: ${first.diagnostics}").toByteArray()
                assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
                System.getProperty("compukter.vm.exceptionsArtifact")?.let { output ->
                    Path.of("$output.$name.cpkt").also { it.parent.createDirectories() }.writeBytes(artifact)
                }
            }
        }

    @Test
    fun `integer arithmetic errors are catchable across calls and preserve finally for vm execution`() =
        withAdapter { adapter ->
            val source =
                """
                fun quotient(value: Int, divisor: Int): Int = value / divisor
                fun remainder(value: Long, divisor: Long): Long = value % divisor
                fun main() {
                    var cleanups = 0
                    require(quotient(8, 2) == 4)
                    try { quotient(1, 0); error("division did not throw") }
                    catch (caught: ArithmeticException) {
                        require(caught.message == "/ by zero")
                        require(caught.cause == null)
                    } finally { cleanups = cleanups + 1 }
                    try { remainder(1L, 0L); error("remainder did not throw") }
                    catch (caught: RuntimeException) { require(caught is ArithmeticException) }
                    finally { cleanups = cleanups + 1 }
                    require(cleanups == 2)
                    println("arithmetic ok")
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val bytes = assertNotNull(first.artifact, first.diagnostics.toString()).toByteArray()
            assertContentEquals(bytes, assertNotNull(second.artifact).toByteArray())
            assertEquals(AbiVersion(1u, 9u), ArtifactReader.read(bytes).minimumRuntimeAbi)
            System.getProperty("compukter.vm.exceptionsArtifact")?.let { output ->
                Path.of("$output.arithmetic.cpkt").also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `array string null and cast errors are catchable with cleanup for vm execution`() =
        withAdapter { adapter ->
            val source =
                """
                class Box(val value: Int)
                fun read(values: IntArray, index: Int): Int = values[index]
                fun allocate(size: Int): IntArray = IntArray(size)
                fun cast(value: Any): Box = value as Box
                fun nonnull(value: String?): String = value as String
                fun main() {
                    val values = intArrayOf(7)
                    var cleanups = 0
                    try { read(values, 1); error("bounds did not throw") }
                    catch (caught: IndexOutOfBoundsException) {
                        require(caught.message == "Index out of bounds")
                        require(caught.cause == null)
                    } finally { cleanups = cleanups + 1 }
                    try { allocate(-1); error("size did not throw") }
                    catch (caught: NegativeArraySizeException) { require(caught.message != null) }
                    finally { cleanups = cleanups + 1 }
                    try { cast("not a box"); error("cast did not throw") }
                    catch (caught: ClassCastException) { require(caught.message == "Invalid cast") }
                    finally { cleanups = cleanups + 1 }
                    try { nonnull(null); error("null did not throw") }
                    catch (caught: NullPointerException) { require(caught.message == "Null reference") }
                    finally { cleanups = cleanups + 1 }
                    try { "text"[4]; error("string bounds did not throw") }
                    catch (caught: IndexOutOfBoundsException) { require(caught.cause == null) }
                    finally { cleanups = cleanups + 1 }
                    try { "text".substring(2, 1); error("substring bounds did not throw") }
                    catch (caught: RuntimeException) { require(caught is IndexOutOfBoundsException) }
                    finally { cleanups = cleanups + 1 }
                    require(cleanups == 6)
                    require(read(values, 0) == 7)
                    println("operations ok")
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val bytes = assertNotNull(first.artifact, first.diagnostics.toString()).toByteArray()
            assertContentEquals(bytes, assertNotNull(second.artifact).toByteArray())
            assertEquals(AbiVersion(1u, 9u), ArtifactReader.read(bytes).minimumRuntimeAbi)
            System.getProperty("compukter.vm.exceptionsArtifact")?.let { output ->
                Path.of("$output.operations.cpkt").also { it.parent.createDirectories() }.writeBytes(bytes)
            }
        }

    @Test
    fun `host failures preserve task identity call sites and cleanup for vm execution`() =
        withAdapter { adapter ->
            val sources =
                mapOf(
                    "host-tasks" to
                        """
                        import compukter.concurrent.Tasks
                        import compukter.io.IOException
                        class State(var completed: Int)
                        fun readTask(name: String, state: State) {
                            try { readln(); error("read must fail") }
                            catch (caught: IOException) {
                                require(caught.message == name)
                                require(caught.cause == null)
                                state.completed = state.completed + 1
                            } finally { state.completed = state.completed + 10 }
                        }
                        fun main() {
                            val state = State(0)
                            val first = Tasks.launch { readTask("first", state) }
                            val second = Tasks.launch { readTask("second", state) }
                            first.join()
                            second.join()
                            require(state.completed == 22)
                            println("host ok")
                        }
                        """.trimIndent(),
                    "host-state" to
                        """
                        fun write() { println("request") }
                        fun main() {
                            var cleanup = false
                            try { write(); error("write must fail") }
                            catch (caught: IllegalStateException) { require(caught.message == "unavailable") }
                            finally { cleanup = true }
                            require(cleanup)
                            println("state ok")
                        }
                        """.trimIndent(),
                    "host-filesystem" to
                        """
                        import compukter.filesystem.FileSystem
                        import compukter.io.IOException
                        fun readFile(): String = FileSystem.readText("/home/missing")
                        fun main() {
                            var cleanup = false
                            try { readFile(); error("read must fail") }
                            catch (caught: IOException) {
                                require(caught.message == "Filesystem entry was not found")
                                require(caught.cause == null)
                            } finally { cleanup = true }
                            require(cleanup)
                            println("filesystem ok")
                        }
                        """.trimIndent(),
                )
            for ((name, source) in sources) {
                val first = adapter.compile(request(source))
                val second = adapter.compile(request(source))
                val bytes = assertNotNull(first.artifact, "$name: ${first.diagnostics}").toByteArray()
                assertContentEquals(bytes, assertNotNull(second.artifact).toByteArray())
                assertEquals(AbiVersion(1u, 9u), ArtifactReader.read(bytes).minimumRuntimeAbi)
                System.getProperty("compukter.vm.exceptionsArtifact")?.let { output ->
                    Path.of("$output.$name.cpkt").also { it.parent.createDirectories() }.writeBytes(bytes)
                }
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
    fun `checked in hello example compiles with standard input and interpolation`() =
        withAdapter { adapter ->
            val greeting = repositoryFile("examples/hello/greeting.kt").readText()
            val main = repositoryFile("examples/hello/main.kt").readText()
            val first = adapter.compile(request("examples/hello/greeting.kt" to greeting, "examples/hello/main.kt" to main))
            val second = adapter.compile(request("examples/hello/greeting.kt" to greeting, "examples/hello/main.kt" to main))
            val bytes = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(bytes, assertNotNull(second.artifact).toByteArray())
            assertOrdinaryEntry(bytes)
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
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "compukter.bench.collectionsOutput", matches = ".+")
    fun `collection representation benchmark artifacts compile`() =
        withAdapter { adapter ->
            val output = Path.of(checkNotNull(System.getProperty("compukter.bench.collectionsOutput"))).createDirectories()
            val manifest = mutableListOf("id\trepresentation\tworkload\tcount\trounds\tchecksum\tartifact_bytes\ttypes\tfunctions")
            collectionBenchmarkCases().forEach { case ->
                val source = case.source()
                val result = adapter.compile(request(source))
                val bytes = collectionBenchmarkArtifact(assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray())
                val module = ArtifactReader.read(bytes).modules.single { it.kind == ModuleKind.APPLICATION }
                output.resolve("${case.id}.cpkt").writeBytes(bytes)
                output.resolve("${case.id}.kt").writeText(source)
                manifest +=
                    listOf(
                        case.id,
                        case.representation,
                        case.workload,
                        case.count,
                        case.rounds,
                        case.checksum(),
                        bytes.size,
                        module.types.size,
                        module.functions.size,
                    ).joinToString("\t")
            }
            output.resolve("manifest.tsv").writeText(manifest.joinToString("\n", postfix = "\n"))
        }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "compukter.bench.objectArraysOutput", matches = ".+")
    fun `object array heap benchmark artifacts compile`() =
        withAdapter { adapter ->
            val output = Path.of(checkNotNull(System.getProperty("compukter.bench.objectArraysOutput"))).createDirectories()
            val manifest = mutableListOf("id\trepresentation\tworkload\tcount\tfields\trounds\tchecksum\tartifact_bytes\ttypes\tfunctions")
            objectArrayBenchmarkCases().forEach { case ->
                val source = case.source()
                val result = adapter.compile(request(source))
                val bytes = collectionBenchmarkArtifact(assertNotNull(result.artifact, "${case.id}: ${result.diagnostics}").toByteArray())
                val module = ArtifactReader.read(bytes).modules.single { it.kind == ModuleKind.APPLICATION }
                output.resolve("${case.id}.cpkt").writeBytes(bytes)
                output.resolve("${case.id}.kt").writeText(source)
                manifest +=
                    listOf(
                        case.id,
                        case.representation,
                        case.workload,
                        case.count,
                        case.fields,
                        case.rounds,
                        case.checksum(),
                        bytes.size,
                        module.types.size,
                        module.functions.size,
                    ).joinToString("\t")
            }
            output.resolve("manifest.tsv").writeText(manifest.joinToString("\n", postfix = "\n"))
        }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "compukter.bench.transientOutput", matches = ".+")
    fun `transient allocation benchmark artifacts compile`() =
        withAdapter { adapter ->
            val output = Path.of(checkNotNull(System.getProperty("compukter.bench.transientOutput"))).createDirectories()
            val manifest = mutableListOf("id\trepresentation\tworkload\tcount\tfields\trounds\tchecksum\tartifact_bytes\ttypes\tfunctions")
            transientAllocationBenchmarkCases().forEach { case ->
                val source = case.source()
                val result = adapter.compile(request(source))
                val bytes = collectionBenchmarkArtifact(assertNotNull(result.artifact, "${case.id}: ${result.diagnostics}").toByteArray())
                val module = ArtifactReader.read(bytes).modules.single { it.kind == ModuleKind.APPLICATION }
                output.resolve("${case.id}.cpkt").writeBytes(bytes)
                output.resolve("${case.id}.kt").writeText(source)
                manifest +=
                    listOf(
                        case.id,
                        "scalar-list",
                        case.workload,
                        case.count,
                        1,
                        case.rounds,
                        case.checksum(),
                        bytes.size,
                        module.types.size,
                        module.functions.size,
                    ).joinToString("\t")
            }
            output.resolve("manifest.tsv").writeText(manifest.joinToString("\n", postfix = "\n"))
        }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "compukter.bench.objectCollectionsOutput", matches = ".+")
    fun `object collection heap benchmark artifacts compile`() =
        withAdapter { adapter ->
            val output = Path.of(checkNotNull(System.getProperty("compukter.bench.objectCollectionsOutput"))).createDirectories()
            val manifest = mutableListOf("id\trepresentation\tworkload\tcount\tfields\trounds\tchecksum\tartifact_bytes\ttypes\tfunctions")
            objectCollectionBenchmarkCases().forEach { case ->
                val source = case.source()
                val result = adapter.compile(request(source))
                val bytes = collectionBenchmarkArtifact(assertNotNull(result.artifact, "${case.id}: ${result.diagnostics}").toByteArray())
                val module = ArtifactReader.read(bytes).modules.single { it.kind == ModuleKind.APPLICATION }
                output.resolve("${case.id}.cpkt").writeBytes(bytes)
                output.resolve("${case.id}.kt").writeText(source)
                manifest +=
                    listOf(
                        case.id,
                        "list-${case.storage}",
                        case.workload,
                        case.count,
                        2,
                        1,
                        case.checksum(),
                        bytes.size,
                        module.types.size,
                        module.functions.size,
                    ).joinToString("\t")
            }
            output.resolve("manifest.tsv").writeText(manifest.joinToString("\n", postfix = "\n"))
        }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "compukter.bench.collectionReuseOutput", matches = ".+")
    fun `collection reuse heap benchmark artifacts compile`() =
        withAdapter { adapter ->
            val output = Path.of(checkNotNull(System.getProperty("compukter.bench.collectionReuseOutput"))).createDirectories()
            val manifest = mutableListOf("id\trepresentation\tworkload\tcount\tfields\trounds\tchecksum\tartifact_bytes\ttypes\tfunctions")
            val count = System.getProperty("compukter.bench.collectionReuseCount", "1024").toInt()
            require(count > 0 && count % 2 == 0)
            collectionReuseBenchmarkCases(count).forEach { case ->
                val source = case.source()
                val result = adapter.compile(request(source))
                val bytes = collectionBenchmarkArtifact(assertNotNull(result.artifact, "${case.id}: ${result.diagnostics}").toByteArray())
                val module = ArtifactReader.read(bytes).modules.single { it.kind == ModuleKind.APPLICATION }
                output.resolve("${case.id}.cpkt").writeBytes(bytes)
                output.resolve("${case.id}.kt").writeText(source)
                manifest +=
                    listOf(
                        case.id,
                        case.storage,
                        case.workload,
                        case.count,
                        2,
                        case.rounds,
                        case.checksum(),
                        bytes.size,
                        module.types.size,
                        module.functions.size,
                    ).joinToString("\t")
            }
            output.resolve("manifest.tsv").writeText(manifest.joinToString("\n", postfix = "\n"))
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
