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

package ru.lazyhat.compukters.impl.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeScrollbarTest {
    @Test
    fun `thumb drag keeps its grab point and clamps outside track on either axis`() {
        for (target in IdeScrollTarget.entries) {
            val bar =
                IdeScrollbar(
                    target,
                    if (target ==
                        IdeScrollTarget.EditorColumns
                    ) {
                        IdeRect(10, 20, 210, 28)
                    } else {
                        IdeRect(10, 20, 18, 220)
                    },
                    25,
                    100,
                    20,
                )
            val input = IdeScrollbarInteraction()
            var position = 25
            val move: (IdeScrollTarget, Int) -> Unit = { axis, value ->
                assertEquals(target, axis)
                position = value
            }
            val x = if (bar.horizontal) bar.thumbStart + 3.0 else 14.0
            val y = if (bar.horizontal) 24.0 else bar.thumbStart + 3.0
            assertTrue(input.press(listOf(bar), x, y, move))
            assertTrue(input.drag(x, y, move))
            assertTrue(kotlin.math.abs(position - 25) <= 1)
            assertTrue(input.drag(1000.0, 1000.0, move))
            assertEquals(100, position)
            assertTrue(input.drag(-1000.0, -1000.0, move))
            assertEquals(0, position)
            assertTrue(input.release())
            assertFalse(input.drag(x, y, move))
        }
    }

    @Test
    fun `track clicks page and unrelated clicks pass through`() {
        val bar = IdeScrollbar(IdeScrollTarget.Hover, IdeRect(10, 20, 18, 220), 50, 100, 20)
        val input = IdeScrollbarInteraction()
        var position = 50
        val move: (IdeScrollTarget, Int) -> Unit = { _, value -> position = value }
        assertFalse(input.press(listOf(bar), 0.0, 0.0, move))
        assertTrue(input.press(listOf(bar), 14.0, 21.0, move))
        assertEquals(30, position)
        assertFalse(input.active)
        assertTrue(input.press(listOf(bar), 14.0, 219.0, move))
        assertEquals(70, position)
        assertFalse(input.press(listOf(bar), Double.NaN, 21.0, move))
    }
}
