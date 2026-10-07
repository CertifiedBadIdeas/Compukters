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

import org.jetbrains.kotlin.descriptors.MultiFieldValueClassRepresentation
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.read.ArtifactReader
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.CanonicalTrustedIntrinsics
import ru.lazyhat.compukters.compiler.k2.engine.library.PlatformLibraryCompiler
import ru.lazyhat.compukters.compiler.k2.engine.library.PlatformLibraryFragmentCodec
import ru.lazyhat.compukters.platform.bundle.PlatformDeclarationIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.platform.k2.build.CompuktersFirBuildEnvironment
import ru.lazyhat.compukters.platform.k2.build.PlatformLibraryDeclaration
import ru.lazyhat.compukters.platform.k2.build.PlatformLibraryDeclarationKind
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(UnsafeDuringIrConstructionAPI::class)
class CompuktersFir2IrPipelineTest {
    @Test
    fun `common pipeline converts resolved Compukters FIR with bodies`() {
        CompuktersFirBuildEnvironment.create().use { environment ->
            val builtins = compileBuiltins(environment)
            val valueOnly =
                environment.compile(
                    PlatformModuleId("sample", "value-types"),
                    listOf(source("ValueTypes.kt", "package sample.types\nvalue class Token(val code: Int)")),
                    listOf(builtins),
                )
            val valueIr = CompuktersFir2IrPipeline.convert(listOf(builtins, valueOnly))
            val valueFragment =
                assertNotNull(
                    PlatformLibraryCompiler().compile(
                        PlatformModuleId("sample", "value-types"),
                        emptyList(),
                        valueIr.irModuleFragment,
                        valueIr.pluginContext,
                        setOf("ValueTypes.kt"),
                        mapOf(
                            "Builtins.kt" to PlatformModuleId("kotlin", "builtins"),
                            "Collections.kt" to PlatformModuleId("kotlin", "builtins"),
                            "Reflection.kt" to PlatformModuleId("kotlin", "builtins"),
                            "ValueTypes.kt" to PlatformModuleId("sample", "value-types"),
                        ),
                        CanonicalTrustedIntrinsics.registry,
                    ),
                )
            val valueArtifact = ArtifactReader.read(PlatformLibraryFragmentCodec.decode(valueFragment).artifact.toByteArray())
            val valueOwner = valueArtifact.modules.first { it.kind == ModuleKind.LIBRARY }
            val exportNames =
                valueArtifact.modules.map { module ->
                    module.exports.map { module.strings[it.name.value.toInt()].toString() }
                }
            assertTrue(
                valueOwner.exports.any {
                    it.kind == SymbolKind.TYPE &&
                        valueOwner.strings[it.name.value.toInt()].toString() == "sample.types.Token"
                },
                exportNames.toString(),
            )
            assertTrue(
                valueOwner.exports.any {
                    it.kind == SymbolKind.FIELD &&
                        valueOwner.strings[it.name.value.toInt()].toString() == "sample.types.Token.<boxed-value>"
                },
            )

            val library =
                environment.compile(
                    PlatformModuleId("stdlib", "core"),
                    listOf(
                        source(
                            "Answer.kt",
                            """
                            package sample

                            interface Result

                            class Ok(val code: Int) : Result

                            enum class Reason {
                                MISSING,
                            }

                            fun answer(): Int = 42
                            class Box<T>(val value: T)
                            fun boxed(): Box<Int> = Box(42)
                            """.trimIndent(),
                        ),
                    ),
                    listOf(builtins),
                )

            val dependent =
                environment.compile(
                    PlatformModuleId("sample", "dependent"),
                    listOf(
                        source(
                            "Dependent.kt",
                            "package dependent\nimport sample.Box\nfun create(): Box<Int> = Box(7)\nfun read(box: Box<Int>): Int = box.value",
                        ),
                    ),
                    listOf(builtins, library),
                )
            val result = CompuktersFir2IrPipeline.convert(listOf(builtins, library, dependent))
            val answer =
                result.irModuleFragment.files
                    .flatMap { it.declarations }
                    .filterIsInstance<IrSimpleFunction>()
                    .single { it.fqNameWhenAvailable?.asString() == "sample.answer" }

            assertNotNull(answer.body)
            val fragment =
                requireNotNull(
                    PlatformLibraryCompiler().compile(
                        PlatformModuleId("stdlib", "core"),
                        listOf(
                            PlatformLibraryDeclaration(
                                "sample.answer",
                                "fun():Int",
                                "Answer.kt",
                                0,
                                39,
                                PlatformLibraryDeclarationKind.FUNCTION,
                            ),
                            PlatformLibraryDeclaration(
                                "sample.boxed",
                                "fun():Box<Int>",
                                "Answer.kt",
                                0,
                                1,
                                PlatformLibraryDeclarationKind.FUNCTION,
                            ),
                            PlatformLibraryDeclaration(
                                "sample.Result",
                                "interface()",
                                "Answer.kt",
                                0,
                                1,
                                PlatformLibraryDeclarationKind.TYPE,
                            ),
                            PlatformLibraryDeclaration(
                                "sample.Ok",
                                "class(Int)",
                                "Answer.kt",
                                0,
                                1,
                                PlatformLibraryDeclarationKind.TYPE,
                            ),
                            PlatformLibraryDeclaration(
                                "sample.Ok.code",
                                "val():Int",
                                "Answer.kt",
                                0,
                                1,
                                PlatformLibraryDeclarationKind.FIELD,
                            ),
                            PlatformLibraryDeclaration(
                                "sample.Reason",
                                "enum()",
                                "Answer.kt",
                                0,
                                1,
                                PlatformLibraryDeclarationKind.TYPE,
                            ),
                            PlatformLibraryDeclaration(
                                "sample.Reason.MISSING",
                                "enum-entry",
                                "Answer.kt",
                                0,
                                1,
                                PlatformLibraryDeclarationKind.FIELD,
                            ),
                        ),
                        result.irModuleFragment,
                        result.pluginContext,
                        setOf("Answer.kt"),
                        mapOf(
                            "Builtins.kt" to PlatformModuleId("kotlin", "builtins"),
                            "Collections.kt" to PlatformModuleId("kotlin", "builtins"),
                            "Reflection.kt" to PlatformModuleId("kotlin", "builtins"),
                            "Answer.kt" to PlatformModuleId("stdlib", "core"),
                            "Dependent.kt" to PlatformModuleId("sample", "dependent"),
                        ),
                        CanonicalTrustedIntrinsics.registry,
                    ),
                )
            val artifact = PlatformLibraryFragmentCodec.decode(fragment).artifact.toByteArray()
            assertEquals("CPKT", artifact.copyOfRange(0, 4).decodeToString())
            kotlin.test.assertFalse(artifact.decodeToString().contains("fun answer"))
            val decoded = ArtifactReader.read(artifact)
            val libraryModule =
                decoded.modules.single { module ->
                    module.kind == ModuleKind.LIBRARY &&
                        module.exports.any { export ->
                            export.kind == SymbolKind.FUNCTION &&
                                module.strings[export.name.value.toInt()].toString() == "sample.answer#fun():Int"
                        }
                }
            val exports = libraryModule.exports.associateBy { libraryModule.strings[it.name.value.toInt()].toString() }
            val reasonType =
                libraryModule.types.filterIsInstance<NominalType.Class>().single { type ->
                    libraryModule.strings[type.name.value.toInt()].toString() == "sample.Reason"
                }
            val initializer = libraryModule.functions[assertNotNull(reasonType.initializer).value.toInt()]
            val initializerInstructions =
                libraryModule.blocks
                    .subList(
                        initializer.firstBlock.value.toInt(),
                        (initializer.firstBlock.value + initializer.blockCount).toInt(),
                    ).flatMap { it.instructions }
            assertEquals(SymbolKind.TYPE, exports.getValue("sample.Ok").kind)
            assertEquals(SymbolKind.FIELD, exports.getValue("sample.Ok.code").kind)
            assertEquals(SymbolKind.FIELD, exports.getValue("sample.Reason.MISSING").kind)
            assertEquals(1, initializerInstructions.count { it is Instruction.StaticSet })
            assertFalse(exports.containsKey("code"))
            assertTrue(exports.keys.any { it == "@specialization:type:sample.Box<Int>" })
            val dependentIr = result
            val dependency =
                PlatformModule(
                    PlatformModuleId("stdlib", "core"),
                    "1",
                    emptyList(),
                    ImmutableBytes.of(byteArrayOf()),
                    fragment,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    sourceDeclarations = listOf(PlatformDeclarationIdentity("sample.Box", "class()")),
                )
            val dependentFragment =
                assertNotNull(
                    PlatformLibraryCompiler().compile(
                        PlatformModuleId("sample", "dependent"),
                        listOf(
                            PlatformLibraryDeclaration(
                                "dependent.create",
                                "fun():Box<Int>",
                                "Dependent.kt",
                                0,
                                1,
                                PlatformLibraryDeclarationKind.FUNCTION,
                            ),
                            PlatformLibraryDeclaration(
                                "dependent.read",
                                "fun(Box<Int>):Int",
                                "Dependent.kt",
                                0,
                                1,
                                PlatformLibraryDeclarationKind.FUNCTION,
                            ),
                        ),
                        dependentIr.irModuleFragment,
                        dependentIr.pluginContext,
                        setOf("Dependent.kt"),
                        mapOf(
                            "Builtins.kt" to PlatformModuleId("kotlin", "builtins"),
                            "Collections.kt" to PlatformModuleId("kotlin", "builtins"),
                            "Reflection.kt" to PlatformModuleId("kotlin", "builtins"),
                            "Answer.kt" to PlatformModuleId("stdlib", "core"),
                            "Dependent.kt" to PlatformModuleId("sample", "dependent"),
                        ),
                        CanonicalTrustedIntrinsics.registry,
                        dependencies = listOf(dependency),
                    ),
                )
            val dependentArtifact = ArtifactReader.read(PlatformLibraryFragmentCodec.decode(dependentFragment).artifact.toByteArray())
            assertEquals(
                1,
                dependentArtifact.modules.sumOf { module ->
                    module.types.count { module.strings[it.name.value.toInt()].toString() == "sample.Box<Int>" }
                },
                "dependent ordinary factories and parameters must reuse the original specialization",
            )
            val largestFrameBytes =
                decoded.modules
                    .flatMap { it.functions }
                    .maxOfOrNull(::compactFrameBytes) ?: 0uL
            assertEquals(
                largestFrameBytes * decoded.manifest.maximumCallDepth.toULong(),
                decoded.manifest.requiredStackBytes.toULong(),
            )
        }
    }

    @Test
    fun `multi field value classes resolve on Guest platform and retain their IR representation`() {
        CompuktersFirBuildEnvironment.create().use { environment ->
            val builtins = compileBuiltins(environment)
            val mfvc =
                environment.compile(
                    PlatformModuleId("sample", "mfvc-probe"),
                    listOf(
                        source(
                            "Mfvc.kt",
                            """
                            package sample.mfvc
                            interface Position { fun coordinate(): Double }
                            value class Vec3(val x: Double, val y: Double, val z: Double) : Position {
                                override fun coordinate(): Double = x
                            }
                            value class Mixed(val count: Int, val wide: Long, val flag: Boolean)
                            value class Nested(val vector: Vec3, val label: Int)
                            fun make(): Vec3 = Vec3(1.0, 2.0, 3.0)
                            fun read(value: Vec3): Double = value.y
                            fun boxed(value: Vec3): Any = value
                            fun nullable(value: Vec3): Vec3? = value
                            fun iface(value: Vec3): Position = value
                            """.trimIndent(),
                        ),
                    ),
                    listOf(builtins),
                )
            assertFalse(mfvc.diagnostics.hasErrors, mfvc.diagnostics.diagnostics.joinToString { it.factoryName })
            val ir = CompuktersFir2IrPipeline.convert(listOf(builtins, mfvc))
            val classes =
                ir.irModuleFragment.files
                    .flatMap { it.declarations }
                    .filterIsInstance<IrClass>()
                    .filter { it.fqNameWhenAvailable?.asString()?.startsWith("sample.mfvc.") == true && it.isValue }
            assertEquals(3, classes.size)
            classes.forEach { klass ->
                val representation = klass.valueClassRepresentation
                assertTrue(representation is MultiFieldValueClassRepresentation<*>)
                val fields = representation.underlyingPropertyNamesToTypes.map { it.first.asString() }
                val fieldTypes =
                    representation.underlyingPropertyNamesToTypes.map { (_, type) ->
                        (type.classifier as IrClassSymbol).owner.fqNameWhenAvailable?.asString()
                    }
                assertEquals(
                    when (klass.name.asString()) {
                        "Vec3" -> listOf("kotlin.Double", "kotlin.Double", "kotlin.Double")
                        "Mixed" -> listOf("kotlin.Int", "kotlin.Long", "kotlin.Boolean")
                        "Nested" -> listOf("sample.mfvc.Vec3", "kotlin.Int")
                        else -> error("unexpected MFVC")
                    },
                    fieldTypes,
                )
                assertEquals(
                    when (klass.name.asString()) {
                        "Vec3" -> listOf("x", "y", "z")
                        "Mixed" -> listOf("count", "wide", "flag")
                        "Nested" -> listOf("vector", "label")
                        else -> error("unexpected MFVC")
                    },
                    fields,
                )
            }
            val rejected =
                assertFailsWith<IllegalArgumentException> {
                    PlatformLibraryCompiler().compile(
                        PlatformModuleId("sample", "mfvc-probe"),
                        emptyList(),
                        ir.irModuleFragment,
                        ir.pluginContext,
                        setOf("Mfvc.kt"),
                        mapOf(
                            "Builtins.kt" to PlatformModuleId("kotlin", "builtins"),
                            "Collections.kt" to PlatformModuleId("kotlin", "builtins"),
                            "Reflection.kt" to PlatformModuleId("kotlin", "builtins"),
                            "Mfvc.kt" to PlatformModuleId("sample", "mfvc-probe"),
                        ),
                        CanonicalTrustedIntrinsics.registry,
                    )
                }
            assertTrue(rejected.message.orEmpty().contains("value class must have one underlying property"))
        }
    }

    private fun compileBuiltins(environment: CompuktersFirBuildEnvironment) =
        environment.compile(
            PlatformModuleId("kotlin", "builtins"),
            listOf(
                source(
                    "Builtins.kt",
                    """
                    package kotlin
                    open class Any
                    open class Number
                    class Nothing private constructor()
                    object Unit
                    class Boolean private constructor() { external operator fun not(): Boolean }
                    class Char private constructor()
                    class Byte private constructor() : Number()
                    class Short private constructor() : Number()
                    class Int private constructor() : Number() {
                        external operator fun plus(other: Int): Int
                        external operator fun times(other: Int): Int
                        external infix fun xor(other: Int): Int
                        external infix fun and(other: Int): Int
                    }
                    class Long private constructor() : Number()
                    class UByte private constructor()
                    class UShort private constructor()
                    class UInt private constructor()
                    class ULong private constructor()
                    class Float private constructor() : Number()
                    class Double private constructor() : Number()
                    interface CharSequence
                    class String : CharSequence
                    open class Throwable
                    class Array<T>
                    class BooleanArray
                    class CharArray
                    class ByteArray
                    class ShortArray
                    class IntArray
                    class LongArray
                    class FloatArray
                    class DoubleArray
                    class UByteArray
                    class UShortArray
                    class UIntArray
                    class ULongArray
                    interface Comparable<in T>
                    abstract class Enum<E : Enum<E>>
                    interface Annotation
                    interface Function<out R>
                    interface Function0<out R> : Function<R>
                    annotation class ExtensionFunctionType
                    annotation class NoInfer
                    annotation class Deprecated(val message: String)
                    enum class DeprecationLevel { WARNING, ERROR, HIDDEN }
                    external fun <T> arrayOf(vararg elements: T): Array<T>
                    external fun <T> arrayOfNulls(size: Int): Array<T?>
                    """.trimIndent(),
                ),
                source(
                    "Collections.kt",
                    """
                    package kotlin.collections
                    import kotlin.*
                    interface Iterable<out T>
                    interface Iterator<out T>
                    interface Collection<out T> : Iterable<T>
                    interface List<out T> : Collection<T>
                    interface Set<out T> : Collection<T>
                    interface Map<K, out V> { interface Entry<out K, out V> }
                    interface ListIterator<out T> : Iterator<T>
                    interface MutableIterable<out T> : Iterable<T>
                    interface MutableIterator<out T> : Iterator<T>
                    interface MutableCollection<T> : Collection<T>, MutableIterable<T>
                    interface MutableList<T> : List<T>, MutableCollection<T>
                    interface MutableSet<T> : Set<T>, MutableCollection<T>
                    interface MutableMap<K, V> : Map<K, V> { interface MutableEntry<K, V> : Map.Entry<K, V> }
                    interface MutableListIterator<T> : ListIterator<T>, MutableIterator<T>
                    abstract class BooleanIterator : Iterator<Boolean>
                    abstract class ByteIterator : Iterator<Byte>
                    abstract class CharIterator : Iterator<Char>
                    abstract class ShortIterator : Iterator<Short>
                    abstract class IntIterator : Iterator<Int>
                    abstract class LongIterator : Iterator<Long>
                    abstract class FloatIterator : Iterator<Float>
                    abstract class DoubleIterator : Iterator<Double>
                    """.trimIndent(),
                ),
                source(
                    "Reflection.kt",
                    """
                    package kotlin.reflect
                    import kotlin.*
                    interface KCallable<out R>
                    interface KProperty<out R> : KCallable<R>
                    interface KProperty0<out R> : KProperty<R>
                    interface KProperty1<in T, out R> : KProperty<R>
                    interface KProperty2<in D, in E, out R> : KProperty<R>
                    interface KMutableProperty0<R> : KProperty0<R>
                    interface KMutableProperty1<T, R> : KProperty1<T, R>
                    interface KMutableProperty2<D, E, R> : KProperty2<D, E, R>
                    interface KClass<out T : Any> : KCallable<T>
                    interface KType
                    interface KFunction<out R> : KCallable<R>, Function<R>
                    """.trimIndent(),
                ),
            ),
            emptyList(),
        )

    private fun compactFrameBytes(function: ru.lazyhat.compukters.compiler.artifact.model.Function): ULong {
        var offset = 0uL
        function.values.forEach { value ->
            value.physicalShape.components.forEach { component ->
                val alignment = component.alignment.toULong()
                offset = (offset + alignment - 1uL) and (alignment - 1uL).inv()
                offset += component.byteSize
            }
        }
        return (offset + 7uL) and 7uL.inv()
    }

    private fun source(
        path: String,
        text: String,
    ): PlatformSource = PlatformSource(path, ImmutableBytes.of(text.encodeToByteArray()))
}
