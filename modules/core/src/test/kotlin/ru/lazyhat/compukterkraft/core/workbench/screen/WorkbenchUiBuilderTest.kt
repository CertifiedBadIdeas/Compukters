/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ru.lazyhat.compukterkraft.core.workbench.screen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import ru.lazyhat.compukterkraft.core.ui.foundation.IntSize
import ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompiler
import ru.lazyhat.compukterkraft.core.workbench.WorkbenchEditorViewModelTestSupport
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchUiBuilderTest {
    @Test
    fun `buildWorkbenchUi compiles into a non-empty ScreenProgram`() =
        runTest {
            val store =
                WorkbenchEditorViewModelTestSupport.makeStoreWithDocument(
                    scope = TestScope(testScheduler),
                    text = "print 1\nprint 2\n",
                )
            val viewModel = WorkbenchEditorViewModel(store)

            val tree =
                buildWorkbenchUi(
                    store = store,
                    viewport = IntSize(440, 240),
                    viewModel = viewModel,
                )
            val program = ScreenProgramCompiler().compile(tree, rootWidth = 440, rootHeight = 240)

            // Toolbar + sidebar entries → at least Run/Format/Clean/Term/Reboot plus sidebar hit regions exist.
            assertTrue(
                program.hitRegions.size >= 6,
                "expected ≥6 toolbar/sidebar hit regions after adding Format/Clean, got ${program.hitRegions.size}",
            )
            // The CodeEditor element registers a focusable node.
            assertTrue(program.focusNodes.isNotEmpty(), "expected at least one FocusNode (the CodeEditor)")
            // Sidebar ScrollArea + editor scroll → at least one ScrollRegion.
            assertTrue(program.scrollRegions.isNotEmpty(), "expected at least one ScrollRegion")
        }
}
