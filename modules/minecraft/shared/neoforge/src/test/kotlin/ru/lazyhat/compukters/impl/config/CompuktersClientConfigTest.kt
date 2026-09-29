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

package ru.lazyhat.compukters.impl.config

import com.electronwill.nightconfig.core.CommentedConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class CompuktersClientConfigTest {
    @Test
    fun `old font preferences are discarded without losing IDE layout`() {
        listOf("cozette", "dina", "proggy_tiny", "jetbrains_mono").forEach { oldFont ->
            val config = CommentedConfig.inMemory()
            config.set<String>("terminal.font", oldFont)
            config.set<Int>("ide.tree_width", 210)
            config.set<Int>("ide.diagnostics_height", 90)
            config.set<Boolean>("ide.diagnostics_expanded", false)

            CompuktersClientConfig.SPEC.correct(config)

            assertFalse(config.contains("terminal.font"))
            assertEquals(210, config.get<Int>("ide.tree_width"))
            assertEquals(90, config.get<Int>("ide.diagnostics_height"))
            assertFalse(config.get<Boolean>("ide.diagnostics_expanded"))
        }
    }

    @Test
    fun `IDE layout config has strict ranges and invalid values recover through admission`() {
        val recovered = CompuktersClientConfig.admitIdeLayout(Int.MAX_VALUE, Int.MIN_VALUE, true)
        assertEquals(4_096, recovered.treeWidth)
        assertEquals(32, recovered.diagnosticsHeight)
        assertTrue(recovered.diagnosticsExpanded)
    }
}
