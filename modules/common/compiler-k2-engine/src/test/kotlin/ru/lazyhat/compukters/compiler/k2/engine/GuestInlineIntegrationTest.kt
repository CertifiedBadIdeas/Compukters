/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.backend.common.LoweringContext
import org.jetbrains.kotlin.backend.common.ir.PreSerializationSymbols
import org.jetbrains.kotlin.backend.common.ir.SharedVariablesManager
import org.jetbrains.kotlin.backend.common.lower.UpgradeCallableReferences
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.inline.FunctionInlining
import org.jetbrains.kotlin.ir.inline.InlineFunctionResolver
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import ru.lazyhat.compukters.compiler.artifact.link.LibraryModuleLinker
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.CanonicalCallableSignature
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.CapabilityOperationHandler
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.IntrinsicBlockingMode
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.PlatformCapabilityId
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.TrustedIntrinsicKey
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.TrustedIntrinsicRegistration
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.TrustedIntrinsicRegistry
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.platform.k2.build.CompuktersFirBuildEnvironment
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises the shared Guest normalization pass and production lowering boundary. */
@OptIn(UnsafeDuringIrConstructionAPI::class, CompilerConfiguration.Internals::class)
class GuestInlineIntegrationTest {
    @Test
    fun `all primitive operators preserve narrow signed unsigned and nominal semantics`() {
        probe(
            """
            fun check(value: Boolean) { if (!value) { val failed = IntArray(-1) } }
            fun <T> identity(value: T): T = value
            fun uintValue(): UInt = 4294967295u
            fun ulongValue(): ULong = 18446744073709551615uL
            class Counter(var effects: Int)
            fun effect(counter: Counter, value: Boolean): Boolean { counter.effects++; return value }
            fun signedNarrow() {
                var byte: Byte = 127
                byte++
                check(byte == (-128).toByte())
                byte--
                check(byte == 127.toByte())
            }
            fun shortArithmetic() {
                val byte: Byte = 127
                var short: Short = 32767
                short++
                check(short == (-32768).toShort())
                check(+byte == 127 && -short == 32768)
                check((byte + short).toLong() == -32641L)
            }
            fun conversions() {
                val maxByte: UByte = 255u
                val maxShort: UShort = 65535u
                check(maxByte.toInt() == 255 && maxShort.toInt() == 65535)
                check(255.toUByte().toInt() == 255)
                check((-1).toUShort().toInt() == 65535)
                check(65536.toShort().toInt() == 0)
                check(uintValue().toLong() == 4294967295L)
                check((-1).toULong() == ulongValue())
            }
            fun unsignedArithmetic() {
                check(uintValue() / 2u == 2147483647u)
                check(ulongValue() / 2uL == 9223372036854775807uL)
            }
            fun unsignedOrdering() {
                check(uintValue() > 0u)
                check(ulongValue() > uintValue())
                check(uintValue().compareTo(ulongValue()) == -1)
                check((uintValue() shr 31) == 1u)
                check((ulongValue() shr 63) == 1uL)
            }
            fun characterAndBoolean() {
                check(0u.toUByte().inv() == 255u.toUByte())
                var narrow = 255u.toUByte()
                narrow++
                check(narrow == 0u.toUByte())
                var char = 'a'
                check(char.code == 97)
                check(char + 2 == 'c' && char - 1 == '`' && 'c' - char == 2)
                char++
                check(char == 'b')
                val counter = Counter(0)
                check((effect(counter, false) and effect(counter, true)) == false)
                check(counter.effects == 2)
                check((true or false) && (true xor false) && !(true xor true))
            }
            fun nominalAndText() {
                val boxedByte: Any = identity(127.toByte())
                val boxedUInt: Any = identity(uintValue())
                check(boxedByte is Byte && (boxedByte is Int) == false)
                check(boxedUInt is UInt && (boxedUInt is Int) == false)
                check((boxedUInt as UInt).toLong() == 4294967295L)
                check("${'$'}{uintValue()}" == "4294967295")
                check("${'$'}{ulongValue()}" == "18446744073709551615")
            }
            fun nullableFloating() {
                val float: Float? = Float.NaN
                val double: Double? = Double.NaN
                check((float == float) == false)
                check((double == double) == false)
                val boxedFloat: Any = Float.NaN
                val boxedDouble: Any = Double.NaN
                check(boxedFloat.equals(Float.NaN))
                check(boxedDouble.equals(Double.NaN))
            }
            fun booleanArrays() {
                val empty = BooleanArray(2)
                check(empty.size == 2 && empty[0] == false)
                var calls = 0
                val array = BooleanArray(3) { index -> check(index == calls); calls++; index == 1 }
                check(calls == 3 && array[1] == true)
                var visited = 0
                for (value in array) { check(value == array[visited]); visited++ }
                check(visited == 3)
                array[0] = true
                check(array[0] == true)
                val factory = booleanArrayOf(true, false)
                check(factory.size == 2 && factory[0] == true && factory[1] == false)
            }
            fun byteArrays() {
                val empty = ByteArray(2)
                check(empty.size == 2 && empty[0] == 0.toByte())
                var calls = 0
                val array = ByteArray(3) { index -> check(index == calls); calls++; (index - 2).toByte() }
                check(calls == 3 && array[1] == (-1).toByte())
                var visited = 0
                for (value in array) { check(value == array[visited]); visited++ }
                check(visited == 3)
                array[0] = (-1).toByte()
                check(array[0] == (-1).toByte())
                val factory = byteArrayOf((-1).toByte(), 0.toByte())
                check(factory.size == 2 && factory[0] == (-1).toByte() && factory[1] == 0.toByte())
            }
            fun shortArrays() {
                val empty = ShortArray(2)
                check(empty.size == 2 && empty[0] == 0.toShort())
                var calls = 0
                val array = ShortArray(3) { index -> check(index == calls); calls++; (index - 2).toShort() }
                check(calls == 3 && array[1] == (-1).toShort())
                var visited = 0
                for (value in array) { check(value == array[visited]); visited++ }
                check(visited == 3)
                array[0] = (-1).toShort()
                check(array[0] == (-1).toShort())
                val factory = shortArrayOf((-1).toShort(), 0.toShort())
                check(factory.size == 2 && factory[0] == (-1).toShort() && factory[1] == 0.toShort())
            }
            fun charArrays() {
                val empty = CharArray(2)
                check(empty.size == 2 && empty[0] == '\u0000')
                var calls = 0
                val array = CharArray(3) { index -> check(index == calls); calls++; 'a' + index }
                check(calls == 3 && array[1] == 'b')
                var visited = 0
                for (value in array) { check(value == array[visited]); visited++ }
                check(visited == 3)
                array[0] = 'b'
                check(array[0] == 'b')
                val factory = charArrayOf('b', '\u0000')
                check(factory.size == 2 && factory[0] == 'b' && factory[1] == '\u0000')
            }
            fun intArrays() {
                val empty = IntArray(2)
                check(empty.size == 2 && empty[0] == 0)
                var calls = 0
                val array = IntArray(3) { index -> check(index == calls); calls++; index - 2 }
                check(calls == 3 && array[1] == -1)
                var visited = 0
                for (value in array) { check(value == array[visited]); visited++ }
                check(visited == 3)
                array[0] = -1
                check(array[0] == -1)
                val factory = intArrayOf(-1, 0)
                check(factory.size == 2 && factory[0] == -1 && factory[1] == 0)
            }
            fun longArrays() {
                val empty = LongArray(2)
                check(empty.size == 2 && empty[0] == 0L)
                var calls = 0
                val array = LongArray(3) { index -> check(index == calls); calls++; index.toLong() - 2L }
                check(calls == 3 && array[1] == -1L)
                var visited = 0
                for (value in array) { check(value == array[visited]); visited++ }
                check(visited == 3)
                array[0] = -1L
                check(array[0] == -1L)
                val factory = longArrayOf(-1L, 0L)
                check(factory.size == 2 && factory[0] == -1L && factory[1] == 0L)
            }
            fun floatArrays() {
                val empty = FloatArray(2)
                check(empty.size == 2 && empty[0] == 0.0f)
                var calls = 0
                val array = FloatArray(3) { index -> check(index == calls); calls++; index.toFloat() - 2.0f }
                check(calls == 3 && array[1] == -1.0f)
                var visited = 0
                for (value in array) { check(value == array[visited]); visited++ }
                check(visited == 3)
                array[0] = -1.0f
                check(array[0] == -1.0f)
                val factory = floatArrayOf(-1.0f, 0.0f)
                check(factory.size == 2 && factory[0] == -1.0f && factory[1] == 0.0f)
            }
            fun doubleArrays() {
                val empty = DoubleArray(2)
                check(empty.size == 2 && empty[0] == 0.0)
                var calls = 0
                val array = DoubleArray(3) { index -> check(index == calls); calls++; index.toDouble() - 2.0 }
                check(calls == 3 && array[1] == -1.0)
                var visited = 0
                for (value in array) { check(value == array[visited]); visited++ }
                check(visited == 3)
                array[0] = -1.0
                check(array[0] == -1.0)
                val factory = doubleArrayOf(-1.0, 0.0)
                check(factory.size == 2 && factory[0] == -1.0 && factory[1] == 0.0)
            }
            fun ubyteArrays() {
                val empty = UByteArray(2)
                check(empty.size == 2 && empty[0] == 0u.toUByte())
                var calls = 0
                val array = UByteArray(3) { index -> check(index == calls); calls++; (index + 254).toUByte() }
                check(calls == 3 && array[1] == 255u.toUByte())
                var visited = 0
                for (value in array) { check(value == array[visited]); visited++ }
                check(visited == 3)
                array[0] = 255u.toUByte()
                check(array[0] == 255u.toUByte())
                val factory = ubyteArrayOf(255u.toUByte(), 0u.toUByte())
                check(factory.size == 2 && factory[0] == 255u.toUByte() && factory[1] == 0u.toUByte())
            }
            fun ushortArrays() {
                val empty = UShortArray(2)
                check(empty.size == 2 && empty[0] == 0u.toUShort())
                var calls = 0
                val array = UShortArray(3) { index -> check(index == calls); calls++; (index + 65534).toUShort() }
                check(calls == 3 && array[1] == 65535u.toUShort())
                var visited = 0
                for (value in array) { check(value == array[visited]); visited++ }
                check(visited == 3)
                array[0] = 65535u.toUShort()
                check(array[0] == 65535u.toUShort())
                val factory = ushortArrayOf(65535u.toUShort(), 0u.toUShort())
                check(factory.size == 2 && factory[0] == 65535u.toUShort() && factory[1] == 0u.toUShort())
            }
            fun uintArrays() {
                val empty = UIntArray(2)
                check(empty.size == 2 && empty[0] == 0u)
                var calls = 0
                val array = UIntArray(3) { index -> check(index == calls); calls++; index.toUInt() + 4294967294u }
                check(calls == 3 && array[1] == 4294967295u)
                var visited = 0
                for (value in array) { check(value == array[visited]); visited++ }
                check(visited == 3)
                array[0] = 4294967295u
                check(array[0] == 4294967295u)
                val factory = uintArrayOf(4294967295u, 0u)
                check(factory.size == 2 && factory[0] == 4294967295u && factory[1] == 0u)
            }
            fun ulongArrays() {
                val empty = ULongArray(2)
                check(empty.size == 2 && empty[0] == 0uL)
                var calls = 0
                val array = ULongArray(3) { index -> check(index == calls); calls++; index.toULong() + 18446744073709551614uL }
                check(calls == 3 && array[1] == 18446744073709551615uL)
                var visited = 0
                for (value in array) { check(value == array[visited]); visited++ }
                check(visited == 3)
                array[0] = 18446744073709551615uL
                check(array[0] == 18446744073709551615uL)
                val factory = ulongArrayOf(18446744073709551615uL, 0uL)
                check(factory.size == 2 && factory[0] == 18446744073709551615uL && factory[1] == 0uL)
            }
            fun main() {
                booleanArrays()
                byteArrays()
                shortArrays()
                charArrays()
                intArrays()
                longArrays()
                floatArrays()
                doubleArrays()
                ubyteArrays()
                ushortArrays()
                uintArrays()
                ulongArrays()
                nullableFloating()
                signedNarrow()
                shortArithmetic()
                conversions()
                unsignedArithmetic()
                unsignedOrdering()
                characterAndBoolean()
                nominalAndText()
            }
            """.trimIndent(),
        ) { _, _, diagnostics, artifact ->
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            val admitted = assertNotNull(artifact)
            val instructions = admitted.modules.flatMap { it.blocks }.flatMap { it.instructions }
            assertTrue(
                instructions.any {
                    it is Instruction.Divide &&
                        it.type == ru.lazyhat.compukters.compiler.artifact.model.ScalarValueType.U32
                },
            )
            assertTrue(
                instructions.any {
                    it is Instruction.Divide &&
                        it.type == ru.lazyhat.compukters.compiler.artifact.model.ScalarValueType.U64
                },
            )
            assertTrue(
                instructions.any {
                    it is Instruction.StringValueOf &&
                        it.type == ru.lazyhat.compukters.compiler.artifact.model.StringValueType.U64
                },
            )
            val linked = LibraryModuleLinker.link(admitted, emptyMap<String, Module>())
            val encoded = ArtifactWriter.write(linked)
            assertTrue(encoded is ArtifactWriteResult.Success, encoded.toString())
            System.getProperty("compukter.vm.primitivesArtifact")?.let { output ->
                val bytes = encoded.bytes
                Path.of(output).also { Files.createDirectories(it.parent) }.let { Files.write(it, bytes) }
            }
        }
    }

    @Test
    fun `primitive nullable signatures preserve nominal boxes for every scalar register kind`() {
        probe(
            """
            fun boxedBoolean(value: Boolean?): Any? = value
            fun boxedLong(value: Long?): Any? = value
            fun boxedFloat(value: Float?): Any? = value
            fun boxedDouble(value: Double?): Any? = value
            fun boxedChar(value: Char?): Any? = value
            fun boxedInt(value: Int?): Any? = value
            fun main() {
                val boolean = boxedBoolean(true) as Boolean
                val long = boxedLong(1L) as Long
                val float = boxedFloat(1.0F) as Float
                val double = boxedDouble(1.0) as Double
                val char = boxedChar('x') as Char
                val int = boxedInt(1) as Int
            }
            """.trimIndent(),
        ) { _, _, diagnostics, artifact ->
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            val runtime = assertNotNull(artifact).modules.single { it.kind == ModuleKind.LIBRARY }
            val boxNames =
                runtime.fields
                    .filter {
                        !it.static &&
                            runtime.strings[it.name.value.toInt()].toString().endsWith(".<boxed-value>")
                    }.map { runtime.strings[it.name.value.toInt()].toString().substringBefore(".<boxed-value>") }
                    .toSet()
            assertEquals(GuestPrimitive.entries.map { it.qualifiedName }.toSet(), boxNames)
            for (primitive in GuestPrimitive.entries) {
                val array = runtime.types[primitive.arrayType.toInt()] as ru.lazyhat.compukters.compiler.artifact.model.NominalType.Array
                assertEquals(primitive.scalar, array.element)
                assertEquals(primitive.arrayStorage, array.storage)
                val box = runtime.types[primitive.boxType.toInt()] as ru.lazyhat.compukters.compiler.artifact.model.NominalType.Class
                assertEquals(primitive.boxField, box.fieldStart)
            }
        }
    }

    @Test
    fun `source maps distinguish user callers across project files`() {
        probe(
            source = "fun main() { outer() }\nfun outer() { Worker().allocate() }",
            additionalSource = "class Worker {\n    fun allocate() { val values = IntArray(100000) }\n}",
        ) { _, _, diagnostics, artifact ->
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            val entries = assertNotNull(artifact).modules.flatMap { it.debug }
            assertTrue(entries.any { it.sourcePath.toString() == "project/Main.kt" && it.sourceLine == 1u })
            assertTrue(entries.any { it.sourcePath.toString() == "project/Main.kt" && it.sourceLine == 2u })
            assertTrue(entries.any { it.sourcePath.toString() == "project/Other.kt" && it.sourceLine == 2u })
        }
    }

    @Test
    fun `debug boundaries preserve nested call sites and source library coordinates`() {
        probe(
            source = "import probe.helper\nfun main() { helper(1 + 2) }",
            librarySource = "package probe\nfun helper(value: Int): Int { return value + 1 }",
        ) { _, _, diagnostics, artifact ->
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            val mapped =
                assertNotNull(artifact).modules.flatMap { module ->
                    module.debug.map { entry -> module to entry }
                }
            val call =
                mapped
                    .single { (module, entry) ->
                        entry.sourcePath.toString() == "project/Main.kt" &&
                            module.blocks[entry.block.value.toInt()].instructions[entry.instruction.toInt()] is Instruction.Call
                    }.second
            assertEquals(2u, call.sourceLine)
            assertEquals(14u, call.sourceColumn)
            assertTrue(
                mapped.any { (_, entry) ->
                    entry.sourcePath.toString() == "platform/probe/library/Library.kt" &&
                        entry.sourceLine == 2u
                },
            )
        }
    }

    @Test
    fun `common inliner expands direct and generic callbacks using Guest builtins`() {
        probe(
            """
            inline fun apply(value: Int, block: (Int) -> Int): Int = block(value)
            inline fun <T, R> transform(value: T, block: (T) -> R): R = block(value)
            fun main() {
                val first = apply(3) { it + 2 }
                val second = transform(first) { it * 2 }
                val third = transform(5000000000L) { it + 2L }
                val fourth = transform(true) { !it }
            }
            """.trimIndent(),
        ) { _, facts, diagnostics, artifact ->
            assertEquals(0, facts.lambdas)
            assertEquals(0, facts.richReferences)
            assertEquals(0, facts.inlineCalls)
            assertTrue(facts.blocks.isNotEmpty())
            assertTrue(!facts.erasedGenericValue, "specialized inline inputs must retain concrete types")
            assertEquals(
                0,
                assertNotNull(artifact)
                    .modules
                    .filter { it.kind == ModuleKind.APPLICATION }
                    .flatMap { it.blocks }
                    .flatMap { it.instructions }
                    .count { it is Instruction.NewObject },
                "scalar generic callbacks must not allocate boxes or closures",
            )
            assertTrue(facts.returns.any { it.returnTargetSymbol.owner is IrReturnableBlock })
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            assertNotNull(artifact)
        }
    }

    @Test
    fun `common inliner preserves local and non-local return targets and mutable captures`() {
        probe(
            """
            inline fun apply(value: Int, block: (Int) -> Int): Int = block(value)
            fun early(): Int {
                return apply(3) { return 7 }
            }
            fun main() {
                var bias = 1
                val first = apply(3) { value -> bias = bias + value; bias }
                val second = apply(first) label@{ value -> if (value > 0) return@label 9; 0 }
                val third = early()
            }
            """.trimIndent(),
        ) { _, facts, diagnostics, artifact ->
            assertEquals(0, facts.lambdas)
            assertEquals(0, facts.richReferences)
            assertEquals(0, facts.inlineCalls)
            assertTrue(facts.returns.any { it.returnTargetSymbol.owner is IrReturnableBlock })
            assertTrue(facts.biasWrites > 0, "inlining must preserve captured local writes")
            assertTrue(facts.nonLocalReturn, "return 7 must still target early(), not an inline block")
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            assertNotNull(artifact)
        }
    }

    @Test
    fun `common inliner resolves source-library bodies and preserves array loop origins`() {
        probe(
            source =
                """
                import probe.foldAll
                fun main() {
                    val result = foldAll(intArrayOf(1, 2), 0) { sum, value -> sum + value }
                }
                """.trimIndent(),
            librarySource =
                """
                package probe
                inline fun <T> identity(value: T, block: (T) -> T): T = block(value)
                inline fun <T> foldAll(values: IntArray, initial: T, operation: (T, Int) -> T): T {
                    var result = identity(initial) { it }
                    for (value in values) result = operation(result, value)
                    return result
                }
                """.trimIndent(),
        ) { _, facts, diagnostics, artifact ->
            assertEquals(0, facts.lambdas)
            assertEquals(0, facts.richReferences)
            assertEquals(0, facts.inlineCalls)
            assertEquals(1, facts.forLoops)
            assertTrue(facts.blocks.isNotEmpty())
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            assertNotNull(artifact)
        }
    }

    @Test
    fun `inline blocks emit executable nested local non-local Unit and wide results`() {
        probe(
            """
            class Box(var value: Int)
            fun next(box: Box): Int { box.value += 1; return box.value }
            fun verify(value: Boolean) {
                if (!value) { val zero = 0; val failure = 1 / zero }
            }
            inline fun apply(value: Int, block: (Int) -> Int): Int = block(value)
            inline fun <T, R> transform(value: T, block: (T) -> R): R = block(value)
            inline fun <T, R> forward(value: T, block: (T) -> R): R = transform(value, block)
            inline fun keep(noinline block: () -> Int): () -> Int = block
            inline fun <T> genericWrap(crossinline block: () -> T): () -> T = { block() }
            inline fun wrap(crossinline block: () -> Int): () -> Int = { block() + 1 }
            fun independent(initial: Int): () -> Int { var count = initial; return { count += 1; count } }
            fun addOne(value: Int): Int = value + 1
            inline fun decide(block: () -> Boolean): Boolean = block()
            fun statementCondition(): Int { if (decide { return 7 }) return 1; return 0 }
            fun valueCondition(): Int { val value = if (decide { return 8 }) 1 else 0; return value }
            fun laterCondition(first: Boolean): Int = when {
                first -> 3
                decide { return 7 } -> 2
                else -> 0
            }
            fun loopCondition(): Int { while (decide { return 9 }) {} ; return 0 }
            inline fun Int.scale(block: (Int) -> Int): Int = block(this)
            inline fun defaultCall(value: Int = 3, block: (Int) -> Int = { it + 1 }): Int = block(value)
            inline fun callable(value: Int): Int = apply(value) { if (it > 0) return 7; 0 }
            class Initialized(val seed: Int) { val value = transform(seed) { it + 1 } }
            inline fun effect(block: () -> Unit) { block() }
            inline fun terminal(block: () -> Nothing): Nothing = block()
            inline fun wide(value: Long, block: (Long) -> Long): Long = block(value)
            inline fun reference(value: Box, block: (Box) -> Box): Box = block(value)
            inline fun nullable(block: () -> Int?): Int? = block()
            inline fun fold(values: IntArray, initial: Int, block: (Int, Int) -> Int): Int {
                var result = initial
                for (value in values) result = block(result, value)
                return result
            }
            fun early(): Int {
                val value = forward(2) { if (it > 0) return 9; 3 }
                return value + 100
            }
            fun both(flag: Boolean): Int = apply(2) { if (flag) return 11 else return 12 }
            fun fromNothing(): Int { terminal { return 17 } }
            fun genericNothing(): Int { forward(2) { return 23 } }
            fun nestedBoth(flag: Boolean): Int =
                if (flag) apply(2) { return 21 } else apply(3) { return 22 }
            fun nested(): Int = apply(1) outer@{ first ->
                val second = apply(2) { if (it > 0) return@outer 7; 5 }
                first + second
            }
            fun main() {
                val counter = Box(0)
                verify(apply(next(counter)) { it + it } == 2)
                verify(counter.value == 1)
                verify(transform(3) { it + 2 } == 5)
                verify(forward(5000000000L) { it + 2L } == 5000000002L)
                verify(forward(true) { !it } == false)
                verify(forward<Int?, Int?>(null) { it } == null)
                verify(forward<Int?, Int?>(4) { it } == 4)
                verify(forward(counter) { it } === counter)
                verify(forward(3) label@{ if (it > 0) return@label 8; 0 } == 8)
                var bias = 1
                verify(apply(3) { bias += it; bias } == 4)
                verify(bias == 4)
                verify(apply(3) label@{ if (it == 3) return@label 7; 9 } == 7)
                verify(early() == 9)
                verify(both(true) == 11)
                verify(both(false) == 12)
                verify(nested() == 7)
                verify(nestedBoth(true) == 21)
                verify(nestedBoth(false) == 22)
                verify(fromNothing() == 17)
                verify(genericNothing() == 23)
                effect { bias += 1; return@effect }
                verify(bias == 5)
                effect { bias += 1 }
                effect { if (bias > 0) return@effect; bias = 99 }
                verify(bias == 6)
                forward(2) { bias += it }
                verify(bias == 8)
                verify(wide(5000000000L) { it + 2L } == 5000000002L)
                val box = Box(1)
                val returned = reference(box) { it.value = 8; it }
                verify(returned === box && box.value == 8)
                verify(nullable { null } == null)
                verify(nullable { 4 } == 4)
                verify(fold(intArrayOf(1, 2, 3), 0) { sum, value -> sum + value } == 6)
                verify(fold(intArrayOf(), 17) { sum, value -> sum + value } == 17)
                var state = 1
                val stored = { state += 1; state }
                val alias = stored
                verify(alias === stored)
                verify(apply(0) { stored() } == 2)
                val retained = keep { state += 2; state }
                val escaped = wrap { state += 3; state }
                verify(retained() == 4)
                verify(escaped() == 8)
                verify(state == 7)
                val genericEscaped = genericWrap { state += 1; state }
                verify(genericEscaped() == 8)
                verify(stored() == 9)
                val firstCounter = independent(10)
                val secondCounter = independent(20)
                verify(firstCounter() == 11)
                verify(secondCounter() == 21)
                verify(firstCounter() == 12)
                val nested = keep {
                    val local = 2
                    val inner = { argument: Int -> local + argument + state }
                    inner(3)
                }
                verify(nested() == 14)
                val function = ::addOne
                verify(transform(4, function) == 5)
                verify(next(counter).scale { it + it } == 4)
                verify(counter.value == 2)
                verify(statementCondition() == 7 && valueCondition() == 8 && loopCondition() == 9)
                verify(laterCondition(true) == 3 && laterCondition(false) == 7)
                verify(defaultCall() == 4)
                verify(defaultCall(block = { it + 2 }) == 5)
                verify(Initialized(3).value == 4)
                val callableReference = ::callable
                verify(callableReference(3) == 7)
                // Rust expects this distinct trap only after every preceding assertion has executed.
                val completion = IntArray(-1)
            }
            """.trimIndent(),
            throughSharedEntry = true,
        ) { _, facts, diagnostics, artifact ->
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            assertTrue(facts.richReferences >= 7, "stored and escaping callbacks must remain managed")
            assertEquals(0, facts.inlineCalls)
            val linked = LibraryModuleLinker.link(assertNotNull(artifact), emptyMap<String, Module>())
            val encoded = ArtifactWriter.write(linked)
            assertTrue(encoded is ArtifactWriteResult.Success, encoded.toString())
            System.getProperty("compukter.vm.inlineBlocksArtifact")?.let { output ->
                val path = Path.of(output)
                Files.createDirectories(path.parent)
                Files.write(path, encoded.bytes)
            }
        }
    }

    @Test
    fun `unknown inline return targets produce located diagnostics`() {
        probe(
            source =
                """
                inline fun apply(block: () -> Int): Int = block()
                fun main() { val result = apply { 7 } }
                """.trimIndent(),
            invalidReturnTarget = true,
        ) { _, _, diagnostics, artifact ->
            assertEquals(null, artifact)
            assertTrue(diagnostics.single().message.contains("return target is outside"), diagnostics.toString())
        }
    }

    @Test
    fun `generic specialization reuses a concrete variant within the limit`() {
        probe(
            """
            inline fun <T> identity(value: T): T { var result = value; result = value; return result }
            fun main() { val a = identity(1); val b = identity(2) }
            """.trimIndent(),
            maximumVariants = 1,
        ) { _, facts, diagnostics, artifact ->
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            assertTrue(!facts.erasedGenericValue)
            assertEquals(0, facts.inlineCalls)
            assertNotNull(artifact)
        }
    }

    @Test
    fun `generic specialization rejects variant and nesting limits explicitly`() {
        val variants =
            assertFailsWith<IllegalArgumentException> {
                probe(
                    """
                    inline fun <T> identity(value: T): T = value
                    fun main() { val a = identity(1); val b = identity(2L) }
                    """.trimIndent(),
                    maximumVariants = 1,
                ) { _, _, _, _ -> error("limit must fail before emission") }
            }
        assertTrue(variants.message.orEmpty().contains("variant limit"))
        val depth =
            assertFailsWith<IllegalArgumentException> {
                probe(
                    """
                    inline fun <T> identity(value: T): T = value
                    inline fun <T> forward(value: T): T = identity(value)
                    fun main() { val a = forward(1) }
                    """.trimIndent(),
                    maximumDepth = 1,
                ) { _, _, _, _ -> error("limit must fail before emission") }
            }
        assertTrue(depth.message.orEmpty().contains("depth limit"))
    }

    @Test
    fun `inline preflight rejects generic recursive expansion explicitly`() {
        val recursion =
            assertFailsWith<IllegalArgumentException> {
                probe(
                    """
                    inline fun <T> recursive(value: T): T = recursive(value)
                    fun main() { val a = recursive(1) }
                    """.trimIndent(),
                ) { _, _, _, _ -> error("recursion must fail before emission") }
            }
        assertTrue(recursion.message.orEmpty().contains("recursive inline expansion"))
    }

    @Test
    fun `generic specialization rejects reified and member templates explicitly`() {
        val reified =
            assertFailsWith<IllegalArgumentException> {
                probe(
                    """
                    inline fun <reified T> identity(value: T): T = value
                    fun main() { val a = identity(1) }
                    """.trimIndent(),
                ) { _, _, _, _ -> error("unsupported shape must fail before emission") }
            }
        assertTrue(reified.message.orEmpty().contains("reified inline"))
        val member =
            assertFailsWith<IllegalArgumentException> {
                probe(
                    """
                    class Owner { inline fun <T> identity(value: T): T = value }
                    fun main() { val a = Owner().identity(1) }
                    """.trimIndent(),
                ) { _, _, _, _ -> error("unsupported shape must fail before emission") }
            }
        assertTrue(member.message.orEmpty().contains("top-level declaration"))
    }

    @Test
    fun `common inliner preserves stored noinline and escaping crossinline callbacks`() {
        probe(
            """
            inline fun apply(block: () -> Int): Int = block()
            inline fun keep(noinline block: () -> Int): () -> Int = block
            inline fun wrap(crossinline block: () -> Int): () -> Int = { block() + 1 }
            fun main() {
                var state = 1
                val stored = { state += 1; state }
                val first = apply(stored)
                val retained = keep { state += 2; state }
                val escaped = wrap { state += 3; state }
                val second = retained()
                val third = escaped()
            }
            """.trimIndent(),
        ) { _, facts, diagnostics, artifact ->
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            val instructions = assertNotNull(artifact).modules.flatMap { it.blocks }.flatMap { it.instructions }
            assertTrue(instructions.count { it is Instruction.NewObject } >= 3)
            assertTrue(facts.richReferences >= 3, "retained callbacks must remain managed function values")
        }
    }

    @Test
    fun `inline preflight rejects cycles exponential copies and cumulative call sites`() {
        val cycle =
            assertFailsWith<IllegalArgumentException> {
                probe(
                    """
                    inline fun first(): Int = second()
                    inline fun second(): Int = first()
                    fun main() { val value = first() }
                    """.trimIndent(),
                ) { _, _, _, _ -> error("cycle must fail before emission") }
            }
        assertTrue(cycle.message.orEmpty().contains("recursive inline expansion"))
        val expanding =
            buildString {
                append("inline fun f0(): Int = 1\n")
                for (i in 1..20) append("inline fun f$i(): Int = f${i - 1}() + f${i - 1}()\n")
                append("fun main() { val value = f20() }")
            }
        val work =
            assertFailsWith<IllegalArgumentException> {
                probe(expanding, maximumExpansionWork = 10_000) { _, _, _, _ -> error("work limit must fail before emission") }
            }
        assertTrue(work.message.orEmpty().contains("work limit"))
        val depth =
            assertFailsWith<IllegalArgumentException> {
                probe(
                    """
                    inline fun first(): Int = 1
                    inline fun second(): Int = first()
                    fun main() { val value = second() }
                    """.trimIndent(),
                    maximumExpansionDepth = 1,
                ) { _, _, _, _ -> error("depth must fail before emission") }
            }
        assertTrue(depth.message.orEmpty().contains("depth limit"))
        val repeated =
            "inline fun leaf(): Int = 1\nfun main() { " +
                (1..20).joinToString("; ") { "val a$it = leaf()" } + " }"
        val cumulative =
            assertFailsWith<IllegalArgumentException> {
                probe(repeated, maximumExpansionWork = 100) { _, _, _, _ -> error("cumulative limit must fail before emission") }
            }
        assertTrue(cumulative.message.orEmpty().contains("work limit"))
    }

    @Test
    fun `common inliner preserves generic escaping callbacks`() {
        probe(
            """
            inline fun <T> wrap(crossinline block: () -> T): () -> T = { block() }
            fun main() { var value = 1; val escaped = wrap { value += 1; value }; val result = escaped() }
            """.trimIndent(),
        ) { _, _, diagnostics, artifact ->
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            assertNotNull(artifact)
        }
    }

    @Test
    fun `inline preflight checks default expression dependencies before copying`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                probe(
                    """
                    inline fun first(): Int = second()
                    inline fun second(value: Int = first()): Int = value
                    fun main() { val value = first() }
                    """.trimIndent(),
                ) { _, _, _, _ -> error("default cycle must fail before emission") }
            }
        assertTrue(failure.message.orEmpty().contains("recursive inline expansion"))
    }

    @Test
    fun `runtime references to generic inline templates retain signature rejection`() {
        probe(
            """
            inline fun <T> identity(value: T): T = value
            fun main() { val reference: (Int) -> Int = ::identity; val value = reference(3) }
            """.trimIndent(),
        ) { _, _, diagnostics, artifact ->
            assertEquals(null, artifact)
            assertTrue(diagnostics.any { it.message.contains("function reference signature") }, diagnostics.toString())
        }
    }

    @Test
    fun `production boundary rejects unavailable and unadmitted inline bodies with located diagnostics`() {
        probe(
            "inline fun apply(value: Int): Int = value\nfun main() { val result = apply(3) }",
            throughSharedEntry = true,
            bodyUnavailable = true,
        ) { _, _, diagnostics, artifact ->
            assertEquals(null, artifact)
            assertTrue(diagnostics.single().message.contains("unavailable"), diagnostics.toString())
            assertEquals(VirtualSourcePath.of("project/Main.kt"), diagnostics.single().path)
            assertNotNull(diagnostics.single().startUtf16)
        }
        probe(
            "import probe.identity\nfun main() { val result = identity(3) }",
            librarySource = "package probe\ninline fun identity(value: Int): Int = value + 99",
            throughSharedEntry = true,
            libraryAdmission = false,
        ) { _, _, diagnostics, artifact ->
            assertEquals(null, artifact)
            assertTrue(diagnostics.single().message.contains("admitted Guest source"), diagnostics.toString())
            assertEquals(VirtualSourcePath.of("project/Main.kt"), diagnostics.single().path)
        }
    }

    @Test
    fun `expanded library diagnostics retain original source ownership`() {
        probe(
            "import probe.identity\nfun main() { val result = identity(3) }",
            librarySource = "package probe\ninline fun identity(value: Int): Int { val unsupported: (() -> Int)? = null; return value }",
            throughSharedEntry = true,
        ) { _, _, diagnostics, artifact ->
            assertEquals(null, artifact)
            assertEquals(VirtualSourcePath.of("platform/probe/library/Library.kt"), diagnostics.single().path)
            assertNotNull(diagnostics.single().startUtf16)
        }
    }

    @Test
    fun `source inline wrappers preserve canonical intrinsic symbols without blessing same named player functions`() {
        probe(
            "import probe.identity\ninline fun apply(value: Int): Int = identity(value)\nfun main() { val result = apply(3) }",
            librarySource = "package probe\ninline fun identity(value: Int): Int = value + 99",
            throughSharedEntry = true,
            trustedLibrary = true,
            canonicalIdentity = true,
        ) { _, _, diagnostics, artifact ->
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            assertTrue(
                assertNotNull(
                    artifact,
                ).modules.flatMap { it.blocks }.flatMap { it.instructions }.any { it is Instruction.CapabilityCallSync },
            )
        }
        probe(
            "package probe\ninline fun identity(value: Int): Int = value + 99\nfun main() { val result = identity(3) }",
            throughSharedEntry = true,
            canonicalIdentity = true,
        ) { _, _, diagnostics, artifact ->
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            assertTrue(
                assertNotNull(artifact).modules.flatMap { it.blocks }.flatMap { it.instructions }.none {
                    it is Instruction.CapabilityCallSync ||
                        it is Instruction.CapabilityCallAsync
                },
            )
        }
    }

    private fun probe(
        source: String,
        librarySource: String? = null,
        additionalSource: String? = null,
        invalidReturnTarget: Boolean = false,
        throughSharedEntry: Boolean = false,
        libraryAdmission: Boolean = true,
        trustedLibrary: Boolean = false,
        canonicalIdentity: Boolean = false,
        bodyUnavailable: Boolean = false,
        maximumVariants: Int = 256,
        maximumDepth: Int = 64,
        maximumExpansionWork: Long = 1_000_000,
        maximumExpansionDepth: Int = 64,
        check: (IrSimpleFunction, InlineFacts, List<WorkerDiagnostic>, Artifact?) -> Unit,
    ) {
        val builtinsRoot = Path.of(checkNotNull(System.getProperty("compukters.guest.builtins")))
        val builtinsSources =
            Files.walk(builtinsRoot).use { paths ->
                paths
                    .filter { it.toString().endsWith(".kt") }
                    .sorted()
                    .map { path ->
                        PlatformSource(builtinsRoot.relativize(path).toString(), ImmutableBytes.of(Files.readAllBytes(path)))
                    }.toList()
            }
        CompuktersFirBuildEnvironment.create().use { environment ->
            val builtins = environment.compile(PlatformModuleId("kotlin", "builtins"), builtinsSources, emptyList())
            val library =
                librarySource?.let {
                    environment.compile(
                        PlatformModuleId("probe", "library"),
                        listOf(PlatformSource("Library.kt", ImmutableBytes.of(it.encodeToByteArray()))),
                        listOf(builtins),
                    )
                }
            val dependencies = listOfNotNull(builtins, library)
            val project =
                environment.compile(
                    PlatformModuleId("probe", "inline"),
                    listOfNotNull(
                        PlatformSource("Main.kt", ImmutableBytes.of(source.encodeToByteArray())),
                        additionalSource?.let { PlatformSource("Other.kt", ImmutableBytes.of(it.encodeToByteArray())) },
                    ),
                    dependencies,
                )
            val converted = CompuktersFir2IrPipeline.convert(dependencies + project)
            val file = converted.irModuleFragment.files.single { it.fileEntry.name.endsWith("Main.kt") }
            val before = InlineFacts(converted.pluginContext.irBuiltIns.anyNType).also { file.accept(it, null) }
            val foreign = file.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull { it.name.asString() == "apply" }
            val diagnostics = mutableListOf<WorkerDiagnostic>()
            val identityModule = PlatformModuleId("probe", "library")
            val capability = PlatformCapabilityId("test", "inline", 1)
            val registry =
                if (canonicalIdentity) {
                    TrustedIntrinsicRegistry.create(
                        listOf(
                            TrustedIntrinsicRegistration(
                                TrustedIntrinsicKey(
                                    identityModule,
                                    CallableId(FqName("probe"), Name.identifier("identity")),
                                    CanonicalCallableSignature("fun(Int):Int"),
                                ),
                                CapabilityOperationHandler(capability, 0u, IntrinsicBlockingMode.NONE),
                            ),
                        ),
                    )
                } else {
                    null
                }
            val session =
                CompilationSession(
                    irSink = { _, _ -> },
                    diagnosticSink = { diagnostics += it },
                    sourcePaths =
                        converted.irModuleFragment.files
                            .filter {
                                it.fileEntry.name.endsWith("Main.kt") || it.fileEntry.name.endsWith("Other.kt") ||
                                    (libraryAdmission && it.fileEntry.name.endsWith("Library.kt"))
                            }.associate {
                                it.fileEntry.name to
                                    VirtualSourcePath.of(
                                        when {
                                            it === file -> "project/Main.kt"
                                            it.fileEntry.name.endsWith("Other.kt") -> "project/Other.kt"
                                            else -> "platform/probe/library/Library.kt"
                                        },
                                    )
                            },
                    trustedPlatformSourceModules =
                        if (trustedLibrary) {
                            converted.irModuleFragment.files.filter { it.fileEntry.name.endsWith("Library.kt") }.associate {
                                it.fileEntry.name to
                                    identityModule
                            }
                        } else {
                            emptyMap()
                        },
                    canonicalIntrinsicRegistry = registry,
                    selectedPlatformModules = if (canonicalIdentity) setOf(identityModule) else emptySet(),
                    capabilityShapes = if (canonicalIdentity) mapOf(capability to PlatformCapabilityShape(0, 1u)) else emptyMap(),
                )
            converted.irModuleFragment.files.removeAll {
                !it.fileEntry.name.endsWith(
                    "Main.kt",
                ) && !it.fileEntry.name.endsWith("Library.kt") && !it.fileEntry.name.endsWith("Other.kt")
            }
            if (bodyUnavailable) {
                file.declarations
                    .filterIsInstance<IrSimpleFunction>()
                    .first { it.isInline }
                    .body = null
            }
            if (!throughSharedEntry) {
                GuestInlineNormalization.lower(
                    converted.irModuleFragment,
                    converted.pluginContext,
                    session,
                    maximumVariants,
                    maximumDepth,
                    maximumExpansionWork,
                    maximumExpansionDepth,
                )
            }
            val main = file.declarations.filterIsInstance<IrSimpleFunction>().single { it.name.asString() == "main" }

            fun collectFacts() =
                InlineFacts(converted.pluginContext.irBuiltIns.anyNType).also { facts ->
                    file.declarations
                        .filterIsInstance<IrSimpleFunction>()
                        .filterNot { it.isInline }
                        .forEach { it.accept(facts, null) }
                }
            if (invalidReturnTarget) {
                collectFacts().returns.first { it.returnTargetSymbol.owner is IrReturnableBlock }.returnTargetSymbol =
                    requireNotNull(foreign).symbol
            }
            val artifact = MinimalScriptLowering.lower(converted.irModuleFragment, converted.pluginContext, session)
            val facts = collectFacts()
            assertTrue(diagnostics.all { it.code == "UNSUPPORTED_IR" && it.path != null }, diagnostics.toString())
            check(main, facts, diagnostics, artifact)
            println(
                "inline probe: original nodes=${before.nodes}, executable expanded nodes=${facts.nodes}, " +
                    "blocks=${facts.blocks.size}, richReferences=${facts.richReferences}, diagnostics=$diagnostics",
            )
        }
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private class InlineFacts(
    private val anyNullableType: IrType,
) : IrVisitorVoid() {
    val blocks = mutableListOf<IrReturnableBlock>()
    val returns = mutableListOf<IrReturn>()
    var nodes = 0
    var richReferences = 0
    var lambdas = 0
    var inlineCalls = 0
    var nonLocalReturn = false
    var erasedGenericValue = false
    var biasWrites = 0
    var forLoops = 0

    override fun visitElement(element: IrElement) {
        nodes += 1
        element.acceptChildren(this, null)
    }

    override fun visitVariable(declaration: IrVariable) {
        if (declaration.type == anyNullableType) erasedGenericValue = true
        super.visitVariable(declaration)
    }

    override fun visitSetValue(expression: IrSetValue) {
        if (expression.symbol.owner.name
                .asString() == "bias"
        ) {
            biasWrites += 1
        }
        super.visitSetValue(expression)
    }

    override fun visitBlock(expression: IrBlock) {
        if (expression.origin?.toString() == "FOR_LOOP") forLoops += 1
        super.visitBlock(expression)
    }

    override fun visitReturnableBlock(expression: IrReturnableBlock) {
        blocks += expression
        super.visitReturnableBlock(expression)
    }

    override fun visitReturn(expression: IrReturn) {
        returns += expression
        if ((expression.returnTargetSymbol.owner as? IrSimpleFunction)?.name?.asString() == "early" &&
            (expression.value as? IrConst)?.value == 7
        ) {
            nonLocalReturn = true
        }
        super.visitReturn(expression)
    }

    override fun visitRichFunctionReference(expression: IrRichFunctionReference) {
        richReferences += 1
        super.visitRichFunctionReference(expression)
    }

    override fun visitFunctionExpression(expression: IrFunctionExpression) {
        lambdas += 1
        super.visitFunctionExpression(expression)
    }

    override fun visitCall(expression: IrCall) {
        if (expression.symbol.owner.isInline) inlineCalls += 1
        super.visitCall(expression)
    }
}
