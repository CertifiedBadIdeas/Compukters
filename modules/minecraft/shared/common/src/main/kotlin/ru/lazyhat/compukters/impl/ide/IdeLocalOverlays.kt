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

import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.state.IdeViewState
import ru.lazyhat.compukters.ide.project.fs.ProjectPath

/** Owns local modal precedence and input isolation independently of Minecraft screen adapters. */
internal class IdeLocalOverlays(
    private val openFile: (ProjectPath) -> Unit,
    private val confirmPrompt: () -> Boolean,
    private val promptClick: (IdePointerInput, Boolean, IdeRenderGeometry) -> Unit,
    private val readClipboard: () -> String,
    private val writeClipboard: (String) -> Unit,
) {
    val prompt = IdePromptController()
    val quickOpen = IdeQuickOpen()
    val details = IdeStatusDetails()
    val active: Boolean get() = details.visible || quickOpen.visible || prompt.state != null

    fun synchronize(state: IdeViewState) {
        if (state.dialog != null || prompt.state != null) {
            quickOpen.close()
            details.close()
        }
        val workspace = (state.page as? IdePageState.Workspace)?.value
        quickOpen.synchronize(workspace)
        details.synchronize(workspace?.project?.directoryName)
    }

    fun press(
        event: IdePointerInput,
        doubleClick: Boolean,
        geometry: IdeRenderGeometry,
    ): Boolean {
        if (!active) return false
        if (event.button() != 0) return true
        val shift = event.modifiers() and IdeModifier.SHIFT != 0
        when {
            details.visible -> details.press(event.x(), event.y(), geometry.panel, writeClipboard)
            quickOpen.visible -> quickOpen.press(event.x(), event.y(), geometry.panel, shift, doubleClick)?.let(openFile)
            prompt.press(event.x(), event.y(), geometry.panel, shift, doubleClick) -> Unit
            else -> promptClick(event, doubleClick, geometry)
        }
        return true
    }

    fun drag(
        event: IdePointerInput,
        panel: IdeRect,
    ): Boolean {
        if (!active) return false
        if (quickOpen.visible) {
            quickOpen.drag(event.x(), panel)
        } else if (!details.visible) {
            prompt.drag(event.x(), panel)
        }
        return true
    }

    fun release(): Boolean {
        if (!active) return false
        quickOpen.release()
        prompt.release()
        return true
    }

    fun scroll(
        x: Double,
        y: Double,
        scrollX: Double,
        scrollY: Double,
        panel: IdeRect,
    ): Boolean {
        if (!active) return false
        val direction = if (scrollX != 0.0) scrollX else -scrollY
        if (direction == 0.0) return true
        val step = if (direction > 0) 1 else -1
        when {
            details.visible -> details.scroll(step * 3, panel)
            quickOpen.visible -> quickOpen.scroll(x, y, step, panel)
            else -> prompt.scroll(x, y, panel, step * 3)
        }
        return true
    }

    fun key(
        event: IdeKeyInput,
        panel: IdeRect,
    ): Boolean {
        when {
            details.visible -> {
                details.key(event, panel, writeClipboard)
            }

            quickOpen.visible -> {
                when (event.key) {
                    IdeKeyCode.ESCAPE -> quickOpen.close()
                    IdeKeyCode.ENTER -> quickOpen.takeSelected()?.let(openFile)
                    else -> quickOpen.key(event, readClipboard, writeClipboard, panel)
                }
            }

            prompt.state != null -> {
                when (event.key) {
                    IdeKeyCode.ESCAPE -> prompt.cancel()
                    IdeKeyCode.ENTER -> confirmPrompt()
                    else -> prompt.keyPressed(event, readClipboard, writeClipboard)
                }
            }

            else -> {
                return false
            }
        }
        return true
    }

    fun type(text: String): Boolean {
        when {
            details.visible -> Unit
            quickOpen.visible -> quickOpen.type(text)
            prompt.state != null -> prompt.type(text)
            else -> return false
        }
        return true
    }

    fun close() {
        details.close()
        quickOpen.close()
        prompt.cancel()
    }
}
