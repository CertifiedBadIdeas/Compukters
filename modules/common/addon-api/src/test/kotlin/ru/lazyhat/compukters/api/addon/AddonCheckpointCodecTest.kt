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

package ru.lazyhat.compukters.api.addon

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class AddonCheckpointCodecTest {
    @Test fun `framing preserves empty and binary resource payloads and rejects incomplete bytes`() {
        val parts = listOf(byteArrayOf(), byteArrayOf(0, -1, 99))
        val encoded = AddonCheckpointCodec.encode(parts)
        val restored = AddonCheckpointCodec.decode(encoded, 2)
        parts.zip(restored).forEach { (before, after) -> assertContentEquals(before, after) }
        for (size in encoded.indices) {
            assertFailsWith<IllegalArgumentException> { AddonCheckpointCodec.decode(encoded.copyOf(size), 2) }
        }
        assertFailsWith<IllegalArgumentException> { AddonCheckpointCodec.decode(encoded + byteArrayOf(0), 2) }
        assertFailsWith<IllegalArgumentException> { AddonCheckpointCodec.decode(encoded, 1) }
        assertFailsWith<IllegalArgumentException> { AddonCheckpointCodec.encode(listOf(ByteArray(1024 * 1024))) }
    }
}
