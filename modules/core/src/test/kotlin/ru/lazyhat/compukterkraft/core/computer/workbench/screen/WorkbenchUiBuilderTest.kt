/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 */
package ru.lazyhat.compukterkraft.core.computer.workbench.screen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchEditorViewModelTestSupport
import ru.lazyhat.compukterkraft.core.ui.foundation.IntSize
import ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompiler
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

            // Toolbar + sidebar parent-link/entries + (no completion/import open) → at least toolbar buttons exist.
            assertTrue(program.hitRegions.size >= 6, "expected ≥6 toolbar/sidebar hit regions, got ${program.hitRegions.size}")
            // The CodeEditor element registers a focusable node.
            assertTrue(program.focusNodes.isNotEmpty(), "expected at least one FocusNode (the CodeEditor)")
            // Sidebar ScrollArea + editor scroll → at least one ScrollRegion.
            assertTrue(program.scrollRegions.isNotEmpty(), "expected at least one ScrollRegion")
        }
}
