/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package ru.lazyhat.compukterkraft.core.computer.workbench.screen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchEditorViewModelTestSupport.makeStoreWithDocument
import ru.lazyhat.compukterkraft.core.input.KeyCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchEditorViewModelTest {
    @Test
    fun viewModelExposesEditorStateFromStore() =
        runTest(UnconfinedTestDispatcher()) {
            val store = makeStoreWithDocument(this, "alpha\nbeta")
            val vm = WorkbenchEditorViewModel(store)

            assertEquals("alpha\nbeta", vm.text)
            assertEquals(0, vm.cursorLine)
            assertEquals(0, vm.cursorColumn)
            assertEquals(0, vm.scrollLine)
            assertTrue(vm.highlights.isEmpty())
            assertTrue(vm.diagnostics.isEmpty())
        }

    @Test
    fun keyAndCharEventsRouteThroughTheStore() =
        runTest(UnconfinedTestDispatcher()) {
            val store = makeStoreWithDocument(this, "")
            val vm = WorkbenchEditorViewModel(store)

            assertTrue(vm.onCharTyped('h', visibleLines = 10))
            assertTrue(vm.onCharTyped('i', visibleLines = 10))

            assertEquals("hi", store.state.editor.text)
            assertEquals(2, store.state.editor.cursorColumn)

            // F4 toggles the terminal panel even from the editor adapter.
            assertTrue(vm.onKeyPressed(KeyCodes.KEY_F4, modifiers = 0, visibleLines = 10))
            assertTrue(store.state.terminalVisible)
        }

    @Test
    fun mouseClickAndScrollDelegateToStoreMutators() =
        runTest(UnconfinedTestDispatcher()) {
            val store = makeStoreWithDocument(this, "one\ntwo\nthree\nfour")
            val vm = WorkbenchEditorViewModel(store)

            vm.onMouseClickAt(line = 2, column = 3)
            assertEquals(2, store.state.editor.cursorLine)
            assertEquals(3, store.state.editor.cursorColumn)

            vm.onScroll(deltaLines = 1)
            assertEquals(1, store.state.editor.scrollLine)
        }
}
