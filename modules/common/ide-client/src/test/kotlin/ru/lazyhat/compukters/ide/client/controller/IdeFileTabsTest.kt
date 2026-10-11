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

package ru.lazyhat.compukters.ide.client.controller

import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.preferences.IdePreferences
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeConflictAction
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeFileTabsTest {
    @Test
    fun `tab switching retains unsaved text selection scroll and undo without rereading files`() {
        val f = fixture()
        try {
            openOther(f)
            f.controller.dispatch(IdeCommand.SelectFileTab(MAIN))
            f.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("first\nsecond\nthird\n")))
            f.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SetCaret(1, false)))
            f.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SetCaret(4, true)))
            f.controller.dispatch(IdeCommand.ScrollEditor(1, 3))
            val before = f.textEditor()
            val reads = f.workspace.openRequests.size
            f.controller.dispatch(IdeCommand.SelectFileTab(OTHER))
            f.controller.dispatch(IdeCommand.SelectFileTab(MAIN))
            val after = f.textEditor()
            assertEquals(before.visibleLines, after.visibleLines)
            assertEquals(before.caretUtf16, after.caretUtf16)
            assertEquals(before.selectionStartUtf16, after.selectionStartUtf16)
            assertEquals(before.selectionEndUtf16, after.selectionEndUtf16)
            assertEquals(before.firstVisibleLine, after.firstVisibleLine)
            assertEquals(before.firstVisibleColumn, after.firstVisibleColumn)
            assertTrue(after.dirty)
            assertTrue(
                f
                    .workspaceView()
                    .openFiles
                    .single { it.path == MAIN }
                    .dirty,
            )
            assertEquals(reads, f.workspace.openRequests.size)
            assertTrue(f.workspace.saveRequests.isEmpty())
            f.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Undo))
            f.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
            val original =
                (
                    f.workspace.openResults.getValue(
                        MAIN,
                    ) as ru.lazyhat.compukters.ide.client.workspace.ProjectFileOpenResult.Text
                ).snapshot.text
            assertEquals(original, f.controller.selectedText())
        } finally {
            f.controller.close()
        }
    }

    @Test
    fun `dirty tab closes only after its save completes and selects an adjacent file`() {
        val f = fixture()
        try {
            openOther(f)
            f.controller.dispatch(IdeCommand.SelectFileTab(MAIN))
            f.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("change")))
            f.controller.dispatch(IdeCommand.CloseFileTab(MAIN))
            assertEquals(listOf(MAIN, OTHER), f.workspaceView().openFiles.map { it.path })
            assertEquals(
                MAIN,
                f.workspace.saveRequests
                    .single()
                    .path,
            )
            f.workspace.completeSave()
            f.controller.tick()
            assertEquals(listOf(OTHER), f.workspaceView().openFiles.map { it.path })
            assertEquals(OTHER, f.workspaceView().activeFile)
            assertFalse(f.controller.isCloseReady())
            f.controller.dispatch(IdeCommand.CloseFileTab(OTHER))
            assertTrue(f.workspaceView().openFiles.isEmpty())
            assertIs<IdeEditorView.Empty>(f.workspaceView().editor)
        } finally {
            f.controller.close()
        }
    }

    @Test
    fun `save conflict keeps a closing tab until explicit cancel or discard without closing IDE`() {
        val f = fixture()
        try {
            openOther(f)
            f.controller.dispatch(IdeCommand.SelectFileTab(MAIN))
            f.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("unsaved")))
            f.controller.dispatch(IdeCommand.CloseFileTab(MAIN))
            f.workspace.completeSaveConflict()
            f.controller.tick()
            assertTrue(assertIs<IdeDialogState.FileConflict>(f.controller.viewState().dialog).closing)
            assertEquals(2, f.workspaceView().openFiles.size)
            f.controller.dispatch(IdeCommand.ResolveConflict(IdeConflictAction.Cancel))
            f.controller.tick()
            assertNull(f.controller.viewState().dialog)
            assertTrue(f.textEditor().dirty)
            f.controller.dispatch(IdeCommand.CloseFileTab(MAIN))
            assertIs<IdeDialogState.FileConflict>(f.controller.viewState().dialog)
            f.controller.dispatch(IdeCommand.ResolveConflict(IdeConflictAction.DiscardAndClose))
            assertEquals(listOf(OTHER), f.workspaceView().openFiles.map { it.path })
            assertEquals(OTHER, f.workspaceView().activeFile)
            assertFalse(f.controller.isCloseReady())
        } finally {
            f.controller.close()
        }
    }

    @Test
    fun `rename and delete update tabs and project switching clears the previous project`() {
        val f = fixture(additionalProject = true)
        try {
            openOther(f)
            val renamed = ProjectPath.file("src/renamed.kt")
            f.controller.dispatch(IdeCommand.Rename(OTHER, renamed))
            repeat(3) { f.controller.tick() }
            assertEquals(listOf(MAIN, renamed), f.workspaceView().openFiles.map { it.path })
            f.controller.dispatch(IdeCommand.RequestDelete(renamed))
            repeat(2) { f.controller.tick() }
            val confirmation = assertIs<IdeDialogState.Confirmation>(f.controller.viewState().dialog)
            f.controller.dispatch(IdeCommand.ConfirmDialog(confirmation.actionId))
            repeat(3) { f.controller.tick() }
            assertEquals(listOf(MAIN), f.workspaceView().openFiles.map { it.path })
            f.controller.dispatch(IdeCommand.OpenProject("second"))
            repeat(4) { f.controller.tick() }
            assertEquals("second", f.workspaceView().project.directoryName)
            assertTrue(f.workspaceView().openFiles.all { it.path != renamed })
        } finally {
            f.controller.close()
        }
    }

    @Test
    fun `bounded tab admission does not evict visible files and closing releases a slot`() {
        val f = fixture(limits = IdeClientLimits(projectDocuments = 1))
        try {
            f.controller.dispatch(IdeCommand.OpenFile(OTHER))
            repeat(2) { f.controller.tick() }
            assertEquals(listOf(MAIN), f.workspaceView().openFiles.map { it.path })
            assertEquals(MAIN, f.workspaceView().activeFile)
            f.controller.dispatch(IdeCommand.CloseFileTab(MAIN))
            openOther(f)
            assertEquals(listOf(OTHER), f.workspaceView().openFiles.map { it.path })
        } finally {
            f.controller.close()
        }
    }

    @Test
    fun `failed close save leaves file open and does not retry every tick`() {
        val f = fixture()
        try {
            f.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("unsaved")))
            f.controller.dispatch(IdeCommand.CloseFileTab(MAIN))
            f.workspace.completeSaveFailure()
            repeat(5) { f.controller.tick() }
            assertEquals(listOf(MAIN), f.workspaceView().openFiles.map { it.path })
            assertTrue(f.textEditor().dirty)
            assertEquals(1, f.workspace.saveRequests.size)
            f.controller.dispatch(IdeCommand.CloseFileTab(MAIN))
            assertEquals(2, f.workspace.saveRequests.size)
            f.workspace.completeSave()
            f.controller.tick()
            assertTrue(f.workspaceView().openFiles.isEmpty())
        } finally {
            f.controller.close()
        }
    }

    @Test
    fun `inactive dirty tab conflict selects that file and Save As closes only after writing`() {
        val f = fixture()
        try {
            openOther(f)
            f.controller.dispatch(IdeCommand.SelectFileTab(MAIN))
            f.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("unsaved")))
            f.controller.dispatch(IdeCommand.SelectFileTab(OTHER))
            f.controller.dispatch(IdeCommand.CloseFileTab(MAIN))
            f.workspace.completeSaveConflict()
            f.controller.tick()
            assertEquals(MAIN, f.workspaceView().activeFile)
            assertTrue(assertIs<IdeDialogState.FileConflict>(f.controller.viewState().dialog).closing)
            val copy = ProjectPath.file("src/copy.kt")
            f.controller.dispatch(IdeCommand.ResolveConflict(IdeConflictAction.SaveAs(copy)))
            assertEquals(listOf(copy, OTHER), f.workspaceView().openFiles.map { it.path })
            assertEquals(
                copy,
                f.workspace.saveRequests
                    .last()
                    .path,
            )
            f.workspace.completeSave()
            f.controller.tick()
            assertEquals(listOf(OTHER), f.workspaceView().openFiles.map { it.path })
            assertFalse(f.controller.isCloseReady())
        } finally {
            f.controller.close()
        }
    }

    @Test
    fun `binary tabs switch from cache and close without saving`() {
        val f = fixture()
        try {
            f.workspace.openResults[OTHER] =
                ru.lazyhat.compukters.ide.client.workspace.ProjectFileOpenResult
                    .Binary(OTHER, 17)
            openOther(f)
            f.controller.dispatch(IdeCommand.SelectFileTab(MAIN))
            val reads = f.workspace.openRequests.size
            f.controller.dispatch(IdeCommand.SelectFileTab(OTHER))
            assertEquals(IdeEditorView.Binary(OTHER, 17), f.workspaceView().editor)
            assertEquals(reads, f.workspace.openRequests.size)
            f.controller.dispatch(IdeCommand.CloseFileTab(OTHER))
            assertEquals(listOf(MAIN), f.workspaceView().openFiles.map { it.path })
            assertTrue(f.workspace.saveRequests.isEmpty())
        } finally {
            f.controller.close()
        }
    }

    private fun openOther(f: ControllerFixture) {
        f.controller.dispatch(IdeCommand.OpenFile(OTHER))
        repeat(2) { f.controller.tick() }
    }

    private fun fixture(
        additionalProject: Boolean = false,
        limits: IdeClientLimits = IdeClientLimits(),
    ): ControllerFixture =
        ControllerFixture(IdePreferences.admit("demo", MAIN.value, 0, 0, 0, 180, 120, true), additionalProject, limits = limits).also {
            it.startAndTick()
        }

    private companion object {
        val MAIN = ProjectPath.file("src/main.kt")
        val OTHER = ProjectPath.file("src/other.kt")
    }
}
