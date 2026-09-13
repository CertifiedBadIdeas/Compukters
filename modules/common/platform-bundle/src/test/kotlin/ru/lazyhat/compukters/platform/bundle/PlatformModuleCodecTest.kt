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
