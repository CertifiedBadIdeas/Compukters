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
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.read.ArtifactReader
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.CanonicalTrustedIntrinsics
import ru.lazyhat.compukters.compiler.k2.engine.library.PlatformLibraryCompiler
import ru.lazyhat.compukters.compiler.k2.engine.library.PlatformLibraryFragmentCodec
import ru.lazyhat.compukters.compiler.k2.engine.library.PlatformSpecializationPlan
import ru.lazyhat.compukters.platform.bundle.PlatformDeclarationIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.platform.k2.build.CompuktersFirBuildEnvironment
import ru.lazyhat.compukters.platform.k2.build.PlatformMetadataCompiler
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

            val libraryId = PlatformModuleId("stdlib", "core")
            val dependentId = PlatformModuleId("sample", "dependent")
            val builtinsId = PlatformModuleId("kotlin", "builtins")
            val librarySources =
                listOf(
                    source(
                        "Answer.kt",
                        """
                        package sample
                        interface Result
                        class Ok(val code: Int) : Result
                        /** Independent code stays precompiled, including declarations with KDoc. */
                        fun answer(): Int = 42
                        class Box<T>(val value: T)
                        fun boxed(): Box<Int> = Box(42)
                        @Suppress("NOTHING_TO_INLINE")
                        inline fun scalarInline(): Int = 3
                        fun inlineCaller(): Int = scalarInline()
                        """.trimIndent(),
                    ),
                )
            val dependentSources =
                listOf(
                    source(
                        "Dependent.kt",
                        """
                        package dependent
                        import sample.Box
                        fun create(): Box<Int> = Box(7)
                        fun read(box: Box<Int>): Int = box.value
                        fun independent(): Int = 7
                        """.trimIndent(),
                    ),
                )
            val library = environment.compile(libraryId, librarySources, listOf(builtins))
            val dependent = environment.compile(dependentId, dependentSources, listOf(builtins, library))
            val result = CompuktersFir2IrPipeline.convert(listOf(builtins, library, dependent))
            val sourceModules =
                mapOf(
                    "Builtins.kt" to builtinsId,
                    "Collections.kt" to builtinsId,
                    "Reflection.kt" to builtinsId,
                    "Answer.kt" to libraryId,
                    "Dependent.kt" to dependentId,
                )
            val metadataCompiler = PlatformMetadataCompiler()
            val planned =
                PlatformSpecializationPlan.defer(
                    result.irModuleFragment,
                    sourceModules,
                    builtinsId,
                    mapOf(
                        libraryId to metadataCompiler.compile(libraryId, librarySources),
                        dependentId to metadataCompiler.compile(dependentId, dependentSources),
                    ),
                )
            val libraryMetadata = planned.getValue(libraryId)
            val dependentMetadata = planned.getValue(dependentId)
            val deferred = libraryMetadata.sourceDeclarations.map { it.symbol }.toSet()
            assertTrue("sample.Box" in deferred)
            assertTrue("sample.boxed" in deferred)
            assertTrue("sample.scalarInline" in deferred)
            assertTrue("sample.inlineCaller" in deferred)
            assertFalse("sample.answer" in deferred)
            assertFalse("sample.Ok" in deferred)
            assertEquals(setOf("dependent.create", "dependent.read"), dependentMetadata.sourceDeclarations.map { it.symbol }.toSet())

            fun compileFragment(
                id: PlatformModuleId,
                dependencies: List<PlatformModule> = emptyList(),
            ): ImmutableBytes {
                val metadata = planned.getValue(id)
                val sourceIdentities = metadata.sourceDeclarations.toSet()
                return assertNotNull(
                    PlatformLibraryCompiler().compile(
                        id,
                        metadata.libraryDeclarations.filter { PlatformDeclarationIdentity(it.symbol, it.signature) !in sourceIdentities },
                        result.irModuleFragment,
                        result.pluginContext,
                        sourceModules.filterValues { it == id }.keys,
                        sourceModules,
                        CanonicalTrustedIntrinsics.registry,
                        dependencies = dependencies,
                        deferredDeclarations = sourceIdentities,
                    ),
                )
            }
            val fragment = compileFragment(libraryId)
            val artifact = PlatformLibraryFragmentCodec.decode(fragment).artifact.toByteArray()
            assertEquals("CPKT", artifact.copyOfRange(0, 4).decodeToString())
            val decoded = ArtifactReader.read(artifact)
            val exports =
                decoded.modules
                    .flatMap { module ->
                        module.exports.map { module.strings[it.name.value.toInt()].toString() to it.kind }
                    }.toMap()
            assertEquals(SymbolKind.FUNCTION, exports["sample.answer#fun():Int"])
            assertEquals(SymbolKind.TYPE, exports["sample.Ok"])
            assertEquals(SymbolKind.FIELD, exports["sample.Ok.code"])
            assertFalse(exports.keys.any { it.startsWith("@specialization:type:sample.Box<") })
            val dependency =
                PlatformModule(
                    id = libraryId,
                    version = "1",
                    dependencies = emptyList(),
                    metadata = libraryMetadata.metadata,
                    libraryFragment = fragment,
                    sources = librarySources,
                    declarations = libraryMetadata.declarations,
                    completionDeclarations = libraryMetadata.completionDeclarations,
                    sourceDeclarations = libraryMetadata.sourceDeclarations,
                )
            val dependentArtifact =
                ArtifactReader.read(
                    PlatformLibraryFragmentCodec.decode(compileFragment(dependentId, listOf(dependency))).artifact.toByteArray(),
                )
            assertEquals(
                0,
                (decoded.modules + dependentArtifact.modules).sumOf { module ->
                    module.types.count { module.strings[it.name.value.toInt()].toString().startsWith("sample.Box<") }
                },
                "concrete generic owners must be emitted only in the final consumer",
            )
            assertEquals(64u, decoded.manifest.maximumCallDepth)
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
            val mfvcSources =
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
                )
            val mfvc = environment.compile(PlatformModuleId("sample", "mfvc-probe"), mfvcSources, listOf(builtins))
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
            val compiled =
                assertNotNull(
                    PlatformLibraryCompiler().compile(
                        PlatformModuleId("sample", "mfvc-probe"),
                        PlatformMetadataCompiler().compile(PlatformModuleId("sample", "mfvc-probe"), mfvcSources).libraryDeclarations,
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
                    ),
                )
            val artifact = ArtifactReader.read(PlatformLibraryFragmentCodec.decode(compiled).artifact.toByteArray())
            assertEquals(3, artifact.modules.sumOf { module -> module.types.count { it is NominalType.InlineValue } })
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
