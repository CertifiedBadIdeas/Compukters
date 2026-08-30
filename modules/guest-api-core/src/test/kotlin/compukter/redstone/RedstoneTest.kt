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

package compukter.redstone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RedstoneTest {
    @Test
    fun `constants and output accessors retain level and direct flag`() {
        assertEquals(0, RedstoneSignal.MIN.level)
        assertEquals(15, RedstoneSignal.MAX.level)
        assertEquals(RedstoneSignal.MIN, RedstoneOutput.MIN.signal)
        assertEquals(false, RedstoneOutput.MIN.direct)
        assertEquals(RedstoneSignal.MAX, RedstoneOutput.MAX.signal)
        assertEquals(true, RedstoneOutput.MAX.direct)
    }

    @Test
    fun `with replaces only the addressed side`() {
        val outputs =
            RedstoneOutputs.ALL_MIN
                .with(RedstoneSide.LEFT, Redstone.output(RedstoneSignal(7)))
                .with(RedstoneSide.TOP, RedstoneOutput.MAX)

        assertEquals(7, outputs[RedstoneSide.LEFT].signal.level)
        assertEquals(false, outputs[RedstoneSide.LEFT].direct)
        assertEquals(RedstoneOutput.MAX, outputs[RedstoneSide.TOP])
        assertEquals(RedstoneOutput.MIN, outputs[RedstoneSide.FRONT])
    }

    @Test
    fun `signal constructor rejects values outside vanilla range`() {
        assertFailsWith<IllegalArgumentException> { RedstoneSignal(-1) }
        assertFailsWith<IllegalArgumentException> { RedstoneSignal(16) }
    }
}
