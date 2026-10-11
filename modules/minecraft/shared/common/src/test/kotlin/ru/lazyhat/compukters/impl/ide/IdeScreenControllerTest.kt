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
    fun `completed close restores parent and removal releases the session only once`() {
        Fixture().use { fixture ->
            fixture.controller.dispatch(IdeCommand.CloseRequested)
            fixture.ui.tick()
            fixture.ui.removed()
            assertEquals(1, fixture.host.restored)
            assertEquals(1, fixture.host.closed)
            assertEquals(0, fixture.host.abandoned)
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

    private class Host : IdeScreenHost {
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
        val host = Host()
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

        fun createFile(path: ru.lazyhat.compukters.ide.project.fs.ProjectPath) {
            root
                .resolve("projects/demo")
                .resolve(path.value)
                .toFile()
                .writeText("// Test file\n")
        }

        fun awaitWorkspace() = await { controller.viewState().page is IdePageState.Workspace }

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
