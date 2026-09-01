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

import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.CanonicalTrustedIntrinsics
import ru.lazyhat.compukters.compiler.k2.engine.library.PlatformLibraryCompiler
import ru.lazyhat.compukters.compiler.k2.engine.library.PlatformLibraryFragmentCodec
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.platform.k2.build.CompuktersFirBuildEnvironment
import ru.lazyhat.compukters.platform.k2.build.PlatformLibraryDeclaration
import ru.lazyhat.compukters.platform.k2.build.PlatformLibraryDeclarationKind
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(UnsafeDuringIrConstructionAPI::class)
class CompuktersFir2IrPipelineTest {
    @Test
    fun `common pipeline converts resolved Compukters FIR with bodies`() {
        CompuktersFirBuildEnvironment.create().use { environment ->
            val builtins =
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
            val library =
                environment.compile(
                    PlatformModuleId("stdlib", "core"),
                    listOf(source("Answer.kt", "package sample\nfun answer(): Int = 42")),
                    listOf(builtins),
                )

            val result = CompuktersFir2IrPipeline.convert(listOf(builtins, library))
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
                        ),
                        result.irModuleFragment,
                        result.pluginContext,
                        setOf("Answer.kt"),
                        mapOf(
                            "Builtins.kt" to PlatformModuleId("kotlin", "builtins"),
                            "Collections.kt" to PlatformModuleId("kotlin", "builtins"),
                            "Reflection.kt" to PlatformModuleId("kotlin", "builtins"),
                            "Answer.kt" to PlatformModuleId("stdlib", "core"),
                        ),
                        CanonicalTrustedIntrinsics.registry,
                    ),
                )
            val artifact = PlatformLibraryFragmentCodec.decode(fragment).artifact.toByteArray()
            assertEquals("CPKT", artifact.copyOfRange(0, 4).decodeToString())
            kotlin.test.assertFalse(artifact.decodeToString().contains("fun answer"))
        }
    }

    private fun source(
        path: String,
        text: String,
    ): PlatformSource = PlatformSource(path, ImmutableBytes.of(text.encodeToByteArray()))
}
