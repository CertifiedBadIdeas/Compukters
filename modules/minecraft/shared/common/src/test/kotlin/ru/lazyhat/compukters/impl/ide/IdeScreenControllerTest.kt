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

import ru.lazyhat.compukters.ide.client.analysis.IdeVisibleLatencyTrace
import ru.lazyhat.compukters.ide.client.controller.IdeClientController
import ru.lazyhat.compukters.ide.client.controller.IdeControllerClock
import ru.lazyhat.compukters.ide.client.state.BoundedIdeEventQueue
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.workspace.DefaultIdeWorkspace
import ru.lazyhat.compukters.impl.ide.target.IdeTargetTerminalClient
import ru.lazyhat.compukters.impl.ui.CompuktersUiViewport
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeScreenControllerTest {
    @Test
    fun `scaled editor scrollbar dragging changes viewport without moving caret or selection`() {
        Fixture().use { fixture ->
            fixture.controller.dispatch(IdeCommand.CreateProject("demo"))
            fixture.awaitWorkspace()
            val path =
                ru.lazyhat.compukters.ide.project.fs.ProjectPath
                    .file("src/scroll.kt")
            fixture.createFile(path, (0 until 180).joinToString("\n") { "// " + "x".repeat(240) })
            fixture.controller.dispatch(IdeCommand.OpenFile(path))
            fixture.awaitFile(path)

            fun editor() = (fixture.controller.viewState().page as IdePageState.Workspace).value.editor as IdeEditorView.Text
            val before = editor()
            val viewport = fixture.host.viewport()
            for (target in listOf(IdeScrollTarget.EditorLines, IdeScrollTarget.EditorColumns)) {
                val bar =
                    fixture.ui
                        .frame(0, 0)
                        .model.scrollbars
                        .single { it.target == target }
                val x = (bar.thumb.left + bar.thumb.right) / 2.0
                val y = (bar.thumb.top + bar.thumb.bottom) / 2.0
                assertTrue(fixture.ui.mouseClicked(IdePointerInput(viewport.toMinecraftX(x), viewport.toMinecraftY(y), 0, 0), false))
                assertTrue(
                    fixture.ui.mouseDragged(
                        IdePointerInput(
                            viewport.toMinecraftX(bar.track.right + 50.0),
                            viewport.toMinecraftY(bar.track.bottom + 50.0),
                            0,
                            0,
                        ),
                    ),
                )
                assertTrue(fixture.ui.mouseReleased(IdePointerInput(0.0, 0.0, 0, 0)))
                assertEquals(before.caretUtf16, editor().caretUtf16)
                assertEquals(before.selectionStartUtf16, editor().selectionStartUtf16)
                assertEquals(before.contentRevision, editor().contentRevision)
            }
            assertTrue(editor().firstVisibleLine > 0)
            assertTrue(editor().firstVisibleColumn > 0)
            assertTrue(editor().maximumVisualWidth >= 240)
        }
    }

    @Test
    fun `scaled project action opens a modal prompt and escape returns to the page`() {
        Fixture().use { fixture ->
            val create =
                fixture.ui
                    .frame(0, 0)
                    .model.hitTargets
                    .single { it.action == IdeHitAction.CreateProject }
            val viewport = fixture.host.viewport()
            assertTrue(
                fixture.ui.mouseClicked(
                    IdePointerInput(
                        viewport.toMinecraftX(create.bounds.left + 2.0),
                        viewport.toMinecraftY(create.bounds.top + 2.0),
                        0,
                        0,
                    ),
                    false,
                ),
            )
            assertTrue(
                fixture.ui
                    .frame(0, 0)
                    .model.panels
                    .any { it.kind == IdePanelKind.Dialog },
            )
            assertTrue(fixture.ui.charTyped(IdeCharacterInput("demo")))
            fixture.ui.mouseClicked(IdePointerInput(0.0, 0.0, 0, 0), false)
            assertTrue(
                fixture.ui
                    .frame(0, 0)
                    .model.text
                    .any { it.kind == IdeTextKind.Dialog && it.value.contains("demo") },
            )
            assertTrue(fixture.ui.keyPressed(IdeKeyInput(IdeKeyCode.ESCAPE)))
            assertFalse(
                fixture.ui
                    .frame(0, 0)
                    .model.panels
                    .any { it.kind == IdePanelKind.Dialog },
            )
            assertTrue(fixture.host.focusCleared > 0)
        }
    }

    @Test
    fun `modal field routes scaled dragging clipboard and undo without changing the page`() {
        Fixture().use { fixture ->
            val ui = fixture.ui
            val viewport = fixture.host.viewport()
            val create =
                ui
                    .frame(0, 0)
                    .model.hitTargets
                    .single { it.action == IdeHitAction.CreateProject }

            fun pointer(
                x: Double,
                y: Double,
            ) = IdePointerInput(viewport.toMinecraftX(x), viewport.toMinecraftY(y), 0, 0)
            ui.mouseClicked(pointer(create.bounds.left + 2.0, create.bounds.top + 2.0), false)
            ui.charTyped(IdeCharacterInput("abcdef"))
            val frame = ui.frame(0, 0)
            val bounds = IdePromptGeometry.compute(frame.geometry.panel).field
            val x = bounds.left + 5.0
            val y = bounds.top + 10.0
            val cell = IdeCodeFontProfile.DEFAULT.cellWidth
            ui.mouseClicked(pointer(x + cell, y), false)
            ui.mouseDragged(pointer(x + 4 * cell, y))
            ui.mouseReleased(pointer(x + 4 * cell, y))
            ui.keyPressed(IdeKeyInput(IdeKeyCode.C, IdeModifier.CONTROL))
            assertEquals("bcd", fixture.host.clipboard)
            ui.charTyped(IdeCharacterInput("X"))

            fun value() =
                ui
                    .frame(0, 0)
                    .model.text
                    .single {
                        it.kind == IdeTextKind.Dialog &&
                            it.clip == IdeRect(bounds.left + 1, bounds.top + 1, bounds.right - 1, bounds.bottom - 1)
                    }.value
            assertEquals("aXef", value())
            ui.keyPressed(IdeKeyInput(IdeKeyCode.Z, IdeModifier.CONTROL))
            assertEquals("abcdef", value())
            ui.keyPressed(IdeKeyInput(IdeKeyCode.A, IdeModifier.CONTROL))
            fixture.host.clipboard = "replacement"
            ui.keyPressed(IdeKeyInput(IdeKeyCode.V, IdeModifier.CONTROL, paste = true))
            assertEquals("replacement", value())
            assertTrue(fixture.controller.viewState().page is IdePageState.Start)
            ui.keyPressed(IdeKeyInput(IdeKeyCode.ESCAPE))
            assertFalse(
                ui
                    .frame(0, 0)
                    .model.panels
                    .any { it.kind == IdePanelKind.Dialog },
            )
        }
    }

    @Test
    fun `completed close ignores later screen ticks and releases the session only once`() {
        Fixture().use { fixture ->
            fixture.controller.dispatch(IdeCommand.CloseRequested)
            fixture.ui.tick()
            fixture.ui.tick()
            fixture.ui.removed()
            fixture.ui.tick()
            assertEquals(1, fixture.host.restored)
            assertEquals(1, fixture.host.closed)
            assertEquals(0, fixture.host.abandoned)
        }
    }

    @Test
    fun `application shutdown ignores later screen ticks and removal`() {
        Fixture().use { fixture ->
            fixture.controller.close()
            repeat(3) { fixture.ui.tick() }
            fixture.ui.removed()
            assertEquals(0, fixture.host.restored)
            assertEquals(1, fixture.host.closed)
            assertEquals(1, fixture.host.abandoned)
        }
    }

    @Test
    fun `removed screen ignores later ticks without restoring its parent`() {
        Fixture().use { fixture ->
            fixture.ui.removed()
            repeat(3) { fixture.ui.tick() }
            assertEquals(0, fixture.host.restored)
            assertEquals(1, fixture.host.closed)
            assertEquals(1, fixture.host.abandoned)
        }
    }

    @Test
    fun `resizing reveals the active tab while explicit bar scrolling stays available`() {
        Fixture().use { fixture ->
            fixture.controller.dispatch(IdeCommand.CreateProject("demo"))
            fixture.awaitWorkspace()
            val main =
                ru.lazyhat.compukters.ide.project.fs.ProjectPath
                    .file("src/main.kt")
            val other =
                ru.lazyhat.compukters.ide.project.fs.ProjectPath
                    .file("src/longFileNameForScrolling.kt")
            val middle =
                ru.lazyhat.compukters.ide.project.fs.ProjectPath
                    .file("src/anotherLongFileName.kt")
            fixture.controller.dispatch(IdeCommand.OpenFile(main))
            fixture.awaitFile(main)
            fixture.createFile(middle)
            fixture.controller.dispatch(IdeCommand.OpenFile(middle))
            fixture.awaitFile(middle)
            fixture.createFile(other)
            fixture.controller.dispatch(IdeCommand.OpenFile(other))
            fixture.awaitFile(other)
            fixture.ui.frame(0, 0)
            fixture.host.pixelWidth = 1400
            val tabs =
                fixture.ui
                    .frame(0, 0)
                    .model.hitTargets
                    .filter { it.action == IdeHitAction.FileTab }
            assertTrue(tabs.any { it.filePath == other })
            val bounds =
                fixture.ui
                    .frame(0, 0)
                    .geometry.fileTabs!!
            val viewport = fixture.host.viewport()
            repeat(2) {
                fixture.ui.mouseScrolled(viewport.toMinecraftX(bounds.left + 10.0), viewport.toMinecraftY(bounds.top + 5.0), 0.0, 1.0)
            }
            assertTrue(
                fixture.ui
                    .frame(0, 0)
                    .model.hitTargets
                    .any { it.action == IdeHitAction.FileTab && it.filePath == main },
            )
            fixture.host.pixelWidth = 1280
            assertTrue(
                fixture.ui
                    .frame(0, 0)
                    .model.hitTargets
                    .any { it.action == IdeHitAction.FileTab && it.filePath == other },
            )
        }
    }

    @Test
    fun `quick open searches collapsed files consumes modal input and activates the selected path`() {
        Fixture().use { fixture ->
            val main =
                ru.lazyhat.compukters.ide.project.fs.ProjectPath
                    .file("src/main.kt")
            val other =
                ru.lazyhat.compukters.ide.project.fs.ProjectPath
                    .file("src/other.kt")
            fixture.controller.dispatch(IdeCommand.CreateProject("demo"))
            fixture.awaitWorkspace()
            fixture.controller.dispatch(IdeCommand.CreateText(other))
            fixture.awaitTree(other)
            fixture.controller.dispatch(IdeCommand.OpenFile(main))
            fixture.awaitFile(main)
            val before = (fixture.controller.viewState().page as IdePageState.Workspace).value.editor as IdeEditorView.Text
            val ui = fixture.ui
            assertTrue(ui.keyPressed(IdeKeyInput(IdeKeyCode.N, IdeModifier.CONTROL or IdeModifier.SHIFT)))
            val modal = ui.frame(0, 0)
            assertTrue(modal.model.hitTargets.none { it.enabled })
            assertTrue(modal.model.text.any { it.kind == IdeTextKind.Dialog && it.value == other.value })
            fixture.host.pixelWidth = 0
            assertFalse(
                ui
                    .frame(0, 0)
                    .model.panels
                    .any { it.kind == IdePanelKind.Dialog },
            )
            fixture.host.pixelWidth = 1920
            ui.charTyped(IdeCharacterInput("no-match"))
            ui.keyPressed(IdeKeyInput(IdeKeyCode.ENTER))
            assertTrue(
                ui
                    .frame(0, 0)
                    .model.text
                    .any { it.value == "No matching files" },
            )
            ui.mouseClicked(IdePointerInput(0.0, 0.0, 0, 0), false)
            ui.keyPressed(IdeKeyInput(IdeKeyCode.ESCAPE))
            val after = (fixture.controller.viewState().page as IdePageState.Workspace).value.editor as IdeEditorView.Text
            assertEquals(before.path, after.path)
            assertEquals(before.contentRevision, after.contentRevision)
            assertEquals(before.visibleLines, after.visibleLines)
            assertEquals(before.caretUtf16, after.caretUtf16)
            assertFalse(
                ui
                    .frame(0, 0)
                    .model.panels
                    .any { it.kind == IdePanelKind.Dialog },
            )
            val button =
                ui
                    .frame(0, 0)
                    .model.hitTargets
                    .single { it.action == IdeHitAction.QuickOpen }
            val viewport = fixture.host.viewport()
            ui.mouseClicked(
                IdePointerInput(viewport.toMinecraftX(button.bounds.left + 2.0), viewport.toMinecraftY(button.bounds.top + 2.0), 0, 0),
                false,
            )
            ui.charTyped(IdeCharacterInput("OTHER"))
            assertTrue(
                ui
                    .frame(0, 0)
                    .model.text
                    .any { it.kind == IdeTextKind.Dialog && it.value == other.value },
            )
            ui.keyPressed(IdeKeyInput(IdeKeyCode.ENTER))
            fixture.awaitFile(other)
            assertEquals(IdeFocusArea.Editor, ui.focusArea)
            assertFalse(
                ui
                    .frame(0, 0)
                    .model.panels
                    .any { it.kind == IdePanelKind.Dialog },
            )
        }
    }

    @Test
    fun `quick open pointer selection shares scaled row geometry and query supports clipboard undo`() {
        Fixture().use { fixture ->
            val main =
                ru.lazyhat.compukters.ide.project.fs.ProjectPath
                    .file("src/main.kt")
            val other =
                ru.lazyhat.compukters.ide.project.fs.ProjectPath
                    .file("src/other.kt")
            fixture.controller.dispatch(IdeCommand.CreateProject("demo"))
            fixture.awaitWorkspace()
            fixture.controller.dispatch(IdeCommand.CreateText(other))
            fixture.awaitTree(other)
            fixture.controller.dispatch(IdeCommand.OpenFile(main))
            fixture.awaitFile(main)
            val ui = fixture.ui
            ui.keyPressed(IdeKeyInput(IdeKeyCode.N, IdeModifier.CONTROL or IdeModifier.SHIFT))
            fixture.host.clipboard = "other"
            ui.keyPressed(IdeKeyInput(IdeKeyCode.V, IdeModifier.CONTROL, paste = true))
            assertTrue(
                ui
                    .frame(0, 0)
                    .model.text
                    .any { it.kind == IdeTextKind.Dialog && it.value == other.value },
            )
            ui.keyPressed(IdeKeyInput(IdeKeyCode.Z, IdeModifier.CONTROL))
            val frame = ui.frame(0, 0)
            val row =
                frame.model.text
                    .single { it.kind == IdeTextKind.Dialog && it.value == other.value }
                    .clip!!
            val viewport = fixture.host.viewport()
            ui.mouseClicked(IdePointerInput(viewport.toMinecraftX(row.left + 10.0), viewport.toMinecraftY(row.top + 5.0), 0, 0), true)
            fixture.awaitFile(other)
            assertFalse(
                ui
                    .frame(0, 0)
                    .model.panels
                    .any { it.kind == IdePanelKind.Dialog },
            )
        }
    }

    @Test
    fun `status details consume editing input copy full messages and return to the same editor`() {
        Fixture().use { fixture ->
            fixture.controller.dispatch(IdeCommand.CreateProject("demo"))
            fixture.awaitWorkspace()
            val main =
                ru.lazyhat.compukters.ide.project.fs.ProjectPath
                    .file("src/main.kt")
            fixture.controller.dispatch(IdeCommand.OpenFile(main))
            fixture.awaitFile(main)
            val before = (fixture.controller.viewState().page as IdePageState.Workspace).value.editor as IdeEditorView.Text
            val ui = fixture.ui
            val target =
                ui
                    .frame(0, 0)
                    .model.hitTargets
                    .single { it.action == IdeHitAction.StatusDetails }
            val viewport = fixture.host.viewport()
            ui.mouseClicked(
                IdePointerInput(viewport.toMinecraftX(target.bounds.left + 2.0), viewport.toMinecraftY(target.bounds.top + 2.0), 0, 0),
                false,
            )
            assertTrue(
                ui
                    .frame(0, 0)
                    .model.text
                    .any { it.value == "Status details" },
            )
            assertTrue(ui.charTyped(IdeCharacterInput("must not enter the document")))
            assertTrue(ui.keyPressed(IdeKeyInput(IdeKeyCode.N, IdeModifier.CONTROL or IdeModifier.SHIFT)))
            assertTrue(
                ui
                    .frame(0, 0)
                    .model.text
                    .any { it.value == "Status details" },
            )
            ui.keyPressed(IdeKeyInput(IdeKeyCode.C, IdeModifier.CONTROL))
            assertTrue(fixture.host.clipboard.contains("0 errors · 0 warnings"))
            assertTrue(fixture.host.clipboard.contains("UTF-16 ${before.caretUtf16}"))
            fixture.host.pixelWidth = 0
            assertTrue(
                ui
                    .frame(0, 0)
                    .model.panels
                    .none { it.kind == IdePanelKind.Dialog },
            )
            fixture.host.pixelWidth = 1920
            ui.keyPressed(IdeKeyInput(IdeKeyCode.ESCAPE))
            assertTrue(
                ui
                    .frame(0, 0)
                    .model.panels
                    .none { it.kind == IdePanelKind.Dialog },
            )
            val after = (fixture.controller.viewState().page as IdePageState.Workspace).value.editor as IdeEditorView.Text
            assertEquals(before.path, after.path)
            assertEquals(before.contentRevision, after.contentRevision)
            assertEquals(before.visibleLines, after.visibleLines)
            assertEquals(before.caretUtf16, after.caretUtf16)
            assertEquals(IdeFocusArea.Editor, ui.focusArea)
            assertTrue(ui.charTyped(IdeCharacterInput("x")))
            val edited = (fixture.controller.viewState().page as IdePageState.Workspace).value.editor as IdeEditorView.Text
            assertTrue(edited.contentRevision > after.contentRevision)
        }
    }

    private class Host(
        private val closeApplication: () -> Unit,
    ) : IdeScreenHost {
        override var clipboard = ""
        var focusCleared = 0
        var restored = 0
        var abandoned = 0
        var closed = 0
        var pixelWidth = 1920

        override fun viewport() = CompuktersUiViewport.admitIde(pixelWidth, 1080, 3, IdeUiScale.TWO)

        override fun clearFocus() {
            focusCleared++
        }

        override fun restoreParent() {
            restored++
        }

        override fun abandonParent() {
            abandoned++
        }

        override fun closeSession() {
            closed++
            closeApplication()
        }
    }

    private class Fixture : AutoCloseable {
        private val root = createTempDirectory("ide-screen-controller-")
        private val workspace = DefaultIdeWorkspace(root.resolve("projects"))
        private val layout =
            object : IdeLayoutStore {
                override fun load() = IdeLayoutSettings.defaults()

                override fun save(settings: IdeLayoutSettings) = Unit

                override fun loadUiScale() = IdeUiScale.TWO

                override fun saveUiScale(scale: IdeUiScale) = Unit
            }
        private val preferences = IdeClientPreferences(root.resolve("preferences"), layout)
        val controller = IdeClientController(workspace, preferences, IdeControllerClock { 0L }, BoundedIdeEventQueue(128))
        private val terminal = IdeTargetTerminalClient { }
        val host = Host(controller::close)
        val ui = IdeScreenController(controller, preferences, terminal, IdeVisibleLatencyTrace.None, host)

        init {
            controller.start()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (controller.viewState().page !is IdePageState.Start && System.nanoTime() < deadline) {
                controller.tick()
                Thread.sleep(1)
            }
            assertTrue(controller.viewState().page is IdePageState.Start)
        }

        fun createFile(
            path: ru.lazyhat.compukters.ide.project.fs.ProjectPath,
            text: String = "// Test file\n",
        ) {
            root
                .resolve("projects/demo")
                .resolve(path.value)
                .toFile()
                .writeText(text)
        }

        fun awaitWorkspace() = await { controller.viewState().page is IdePageState.Workspace }

        fun awaitTree(path: ru.lazyhat.compukters.ide.project.fs.ProjectPath) =
            await {
                (controller.viewState().page as? IdePageState.Workspace)
                    ?.value
                    ?.tree
                    ?.flatten()
                    ?.any { it.path == path } == true
            }

        fun awaitFile(path: ru.lazyhat.compukters.ide.project.fs.ProjectPath) =
            await {
                (controller.viewState().page as? IdePageState.Workspace)?.value?.activeFile == path
            }

        private fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < deadline) {
                controller.tick()
                Thread.sleep(1)
            }
            assertTrue(condition())
        }

        override fun close() {
            controller.close()
            terminal.close()
        }
    }
}
