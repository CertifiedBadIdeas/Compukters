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

package ru.lazyhat.compukters.platform.bundle

import ru.lazyhat.compukters.worker.value.ImmutableBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformModuleCodecTest {
    @Test
    fun `receiver array size defaults round trip and reject incompatible declarations`() {
        val id = PlatformModuleId("fixture", "api")
        val source = PlatformSource("fixture/api.kt", ImmutableBytes.of("external fun IntArray.copyInto()".encodeToByteArray()))
        val declaration =
            PlatformDeclaration(
                "kotlin.collections.copyInto",
                "fun(IntArray.IntArray,Int,Int,Int):IntArray",
                id,
                source.path,
                0,
                0,
                true,
                listOf(
                    null,
                    null,
                    PlatformDefaultArgument.IntValue(0),
                    PlatformDefaultArgument.IntValue(0),
                    PlatformDefaultArgument.ReceiverArraySize,
                ),
            )
        val module =
            PlatformModule(
                id,
                "1.0.0",
                emptyList(),
                ImmutableBytes.of(byteArrayOf(1)),
                null,
                listOf(source),
                listOf(declaration),
                emptyList(),
            )
        assertEquals(module, PlatformBundleCodec.decodeModule(PlatformBundleCodec.encodeModule(module)))
        val doubleModule =
            module.copy(
                sources = listOf(source.copy(content = ImmutableBytes.of("external fun DoubleArray.copyInto()".encodeToByteArray()))),
                declarations = listOf(declaration.copy(signature = "fun(DoubleArray.DoubleArray,Int,Int,Int):DoubleArray")),
            )
        assertEquals(doubleModule, PlatformBundleCodec.decodeModule(PlatformBundleCodec.encodeModule(doubleModule)))
        listOf("fun(String.IntArray,Int,Int,Int):IntArray", "fun(IntArray.IntArray,Int,Int,String):IntArray").forEach { signature ->
            assertFailsWith<IllegalArgumentException> {
                PlatformBundleCodec.encodeModule(module.copy(declarations = listOf(declaration.copy(signature = signature))))
            }
        }
    }

    @Test
    fun `standalone module round trips canonically`() {
        val id = PlatformModuleId("fixture", "api")
        val module =
            PlatformModule(
                id,
                "1.0.0",
                listOf(PlatformModuleId("stdlib", "core")),
                ImmutableBytes.of(byteArrayOf(1, 2, 3)),
                null,
                emptyList(),
                emptyList(),
                emptyList(),
            )

        val encoded = PlatformBundleCodec.encodeModule(module)

        assertContentEquals(encoded, PlatformBundleCodec.encodeModule(module))
        assertEquals(module, PlatformBundleCodec.decodeModule(encoded))
    }

    @Test
    fun `standalone module hash mutation fails closed`() {
        val module =
            PlatformModule(
                PlatformModuleId("fixture", "api"),
                "1.0.0",
                emptyList(),
                ImmutableBytes.of(byteArrayOf(1)),
                null,
                emptyList(),
                emptyList(),
                emptyList(),
            )
        val encoded = PlatformBundleCodec.encodeModule(module)
        encoded[8] = (encoded[8].toInt() xor 1).toByte()

        assertFailsWith<IllegalArgumentException> { PlatformBundleCodec.decodeModule(encoded) }
    }
}
