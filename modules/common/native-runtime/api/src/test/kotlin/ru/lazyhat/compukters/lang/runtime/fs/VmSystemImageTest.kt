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

package ru.lazyhat.compukters.lang.runtime.fs

import kotlin.test.Test
import kotlin.test.assertContentEquals

class VmSystemImageTest {
    @Test
    fun `shared system image owns immutable bytes across callers`() {
        val input = byteArrayOf(1, 2, 3)
        val image = VmSystemImage.of(input)
        input.fill(9)
        val transportCopy = image.copyBytes()
        transportCopy.fill(7)
        assertContentEquals(byteArrayOf(1, 2, 3), image.copyBytes())
    }
}
