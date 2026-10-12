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

class IdeHoverViewportTest {
    @Test
    fun `wheel inside documentation clamps at either end and outside remains available to editor`() {
        val viewport = IdeHoverViewport()
        val model =
            IdeDrawModel(
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                hoverBounds = IdeRect(10, 20, 210, 180),
                hoverScrollMaximum = 5,
            )
        assertFalse(viewport.scroll(model, 5.0, 30.0, -1.0))
        assertEquals(0, viewport.scroll)
        assertTrue(viewport.scroll(model, 30.0, 30.0, -1.0))
        assertEquals(3, viewport.scroll)
        assertTrue(viewport.scroll(model, 30.0, 30.0, -1.0))
        assertEquals(5, viewport.scroll)
        repeat(3) { assertTrue(viewport.scroll(model, 30.0, 30.0, 1.0)) }
        assertEquals(0, viewport.scroll)
        assertFalse(viewport.scroll(model.copy(hoverBounds = null), 30.0, 30.0, -1.0))
    }
}
