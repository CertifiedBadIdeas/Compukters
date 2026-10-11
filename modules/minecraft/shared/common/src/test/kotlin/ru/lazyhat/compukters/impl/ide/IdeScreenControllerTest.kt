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

    private class Host : IdeScreenHost {
        override var clipboard = ""
        var focusCleared = 0
        var restored = 0
        var abandoned = 0
        var closed = 0

        override fun viewport() = CompuktersUiViewport.admitIde(1920, 1080, 3, IdeUiScale.TWO)

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

        override fun close() {
            controller.close()
            terminal.close()
        }
    }
}
