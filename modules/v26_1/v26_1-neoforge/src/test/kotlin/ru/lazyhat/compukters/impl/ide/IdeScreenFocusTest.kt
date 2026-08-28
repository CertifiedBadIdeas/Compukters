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

import net.minecraft.client.input.CharacterEvent
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeProjectSummary
import ru.lazyhat.compukters.ide.client.state.IdeViewState
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdeScreenFocusTest {
    @Test
    fun `initial IDE focus routes character input to the editor`() {
        val commands = mutableListOf<IdeCommand>()
        val input = IdeInputAdapter(commands::add, IdeClipboard { "" }, IdeClientLimits())

        assertTrue(input.charTyped(CharacterEvent('x'.code), IdeFocusState.Initial))
        assertEquals(listOf<IdeCommand>(IdeCommand.Edit(IdeEditorInput.Type("x"))), commands)
    }

    @Test
    fun `IDE screen owns mouse focus policy`() {
        assertNotNull(IdeScreen::class.java.declaredMethods.singleOrNull { it.name == "mouseClicked" && it.parameterCount == 2 })
    }

    @Test
    fun `dialog is modal and drawn above ordinary actions`() {
        val base = IdeViewState.startPage(listOf(IdeProjectSummary("demo", "Demo")))
        val state = base.copy(dialog = IdeDialogState.Confirmation("Delete", "Permanent", 7))
        val geometry = IdeRenderGeometry.compute(960, 540, 12, 180, 120, true, true, TerminalFontProfile.DINA)

        val model = IdeRenderer.extract(state, geometry)
        val dialogTargets = model.hitTargets.filter { it.focusGroup == IdeFocusGroup.Dialog }
        val ordinaryTargets = model.hitTargets.filter { it.focusGroup == IdeFocusGroup.Page }

        assertTrue(dialogTargets.isNotEmpty())
        assertTrue(ordinaryTargets.all { !it.enabled })
        assertTrue(dialogTargets.minOf { it.zIndex } > ordinaryTargets.maxOf { it.zIndex })
        assertTrue(model.text.filter { it.kind == IdeTextKind.Dialog }.any { it.value == "Permanent" })
    }
}
