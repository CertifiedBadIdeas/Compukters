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

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.impl.config.CompuktersClientConfig

internal class IdeScreen(
    private val application: IdeClientApplication,
) : Screen(Component.literal("Compukters IDE")) {
    private val prompt = IdePromptController()
    private val input =
        IdeInputAdapter(
            application.controller::dispatch,
            IdeClipboard { minecraft.keyboardHandler.clipboard },
            IdeClientLimits(),
            IdeUiActionSink(::activateUiAction),
        )
    private val splitters = IdeSplitterInteraction(application.preferences.layout(), application.preferences::saveLayout)
    private var focusArea = IdeFocusArea.None
    private var selectedTreePath: ProjectPath? = null

    override fun setInitialFocus() = Unit

    override fun mouseClicked(
        event: MouseButtonEvent,
        doubleClick: Boolean,
    ): Boolean {
        val geometry = geometry()
        val state = application.controller.viewState()
        if (prompt.state != null || state.dialog != null) {
            input.pointerClicked(event.x(), event.y(), event.modifiers(), pointerContext(geometry))
            clearFocus()
            return true
        }
        selectTreeRow(event.x(), event.y(), geometry)
        if (splitters.press(event.x().toInt(), event.y().toInt(), geometry)) {
            input.pointerActivity()
            return true
        }
        if (input.pointerClicked(event.x(), event.y(), event.modifiers(), pointerContext(geometry))) {
            focusArea =
                if (geometry.editor.contains(event.x(), event.y())) {
                    IdeFocusArea.Editor
                } else {
                    IdeFocusArea.Tree
                }
            clearFocus()
            return true
        }
        val handled = super.mouseClicked(event, doubleClick)
        if (handled) clearFocus()
        focusArea =
            when {
                geometry.editor.contains(event.x(), event.y()) -> IdeFocusArea.Editor
                geometry.tree?.contains(event.x(), event.y()) == true -> IdeFocusArea.Tree
                geometry.panel.contains(event.x(), event.y()) -> IdeFocusArea.Panel
                else -> IdeFocusArea.None
            }
        input.pointerActivity()
        return handled || focusArea != IdeFocusArea.None
    }

    override fun mouseDragged(
        event: MouseButtonEvent,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        val geometry = geometry()
        if (splitters.drag(event.x().toInt(), event.y().toInt(), geometry)) return true
        if (focusArea == IdeFocusArea.Editor) {
            return input.pointerClicked(
                event.x(),
                event.y(),
                event.modifiers() or GLFW.GLFW_MOD_SHIFT,
                pointerContext(geometry),
            )
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean = splitters.release() || super.mouseReleased(event)

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean =
        input.scroll(mouseX, mouseY, scrollX, scrollY, pointerContext(geometry())) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)

    override fun keyPressed(event: KeyEvent): Boolean {
        if (prompt.state != null) {
            return when {
                event.key() == GLFW.GLFW_KEY_ESCAPE -> prompt.cancel()
                event.key() == GLFW.GLFW_KEY_ENTER -> confirmPrompt()
                event.key() == GLFW.GLFW_KEY_BACKSPACE -> prompt.backspace()
                event.isPaste -> prompt.type(minecraft.keyboardHandler.clipboard)
                else -> true
            }
        }
        if (application.controller.viewState().dialog != null) return input.keyPressed(event, focusState())
        if (splitters.captured) return true
        return input.keyPressed(event, focusState()) || super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (prompt.state != null) return prompt.type(event.codepointAsString())
        return input.charTyped(event, focusState()) || super.charTyped(event)
    }

    override fun removed() {
        splitters.focusLost()
        application.controller.dispatch(ru.lazyhat.compukters.ide.client.state.IdeCommand.EditorFocusLost)
        super.removed()
    }

    override fun tick() {
        application.controller.tick()
        super.tick()
    }

    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, IdeColors.DIM)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val profile = CompuktersClientConfig.selectedFont()
        val geometry = geometry(profile)
        val treeFirstRow = admittedTreeFirstRow(geometry)
        val model =
            IdeRenderer.extract(
                application.controller.viewState(),
                geometry,
                prompt = prompt.state,
                treeFirstRow = treeFirstRow,
                selectedTreePath = selectedTreePath,
            )
        val operations = mutableListOf<RenderOperation>()
        model.panels.forEach { draw -> operations += RenderOperation(draw.zIndex) { graphics.fill(draw.bounds, draw.color) } }
        model.fills.forEach { draw -> operations += RenderOperation(draw.zIndex) { graphics.fill(draw.bounds, draw.color) } }
        model.text.forEach { draw ->
            operations +=
                RenderOperation(draw.zIndex) {
                    draw.clip?.let { graphics.enableScissor(it.left, it.top, it.right, it.bottom) }
                    val value =
                        if (draw.codeFont == null) {
                            Component.literal(draw.value)
                        } else {
                            Component.literal(draw.value).withStyle { style -> style.withFont(draw.codeFont.fontDescription) }
                        }
                    graphics.text(font, value, draw.x, draw.y, draw.color, false)
                    if (draw.clip != null) graphics.disableScissor()
                }
        }
        operations.sortedBy(RenderOperation::zIndex).forEach { it.draw() }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun isPauseScreen(): Boolean = false

    private fun geometry(
        profile: ru.lazyhat.compukters.impl.terminal.TerminalFontProfile = CompuktersClientConfig.selectedFont(),
    ): IdeRenderGeometry {
        val layout = splitters.layout
        return IdeRenderGeometry.compute(
            width,
            height,
            layout.padding,
            layout.treeWidth,
            layout.diagnosticsHeight,
            layout.diagnosticsExpanded,
            treeVisible = true,
            profile,
        )
    }

    private fun focusState(): IdeFocusState {
        val state = application.controller.viewState()
        val editor = ((state.page as? IdePageState.Workspace)?.value?.editor as? IdeEditorView.Text)
        val completion = (editor?.analysis as? IdeAnalysisState.Active)?.completion != null
        return IdeFocusState(focusArea, completion, state.dialog)
    }

    private fun pointerContext(geometry: IdeRenderGeometry): IdePointerContext {
        val state = application.controller.viewState()
        val treeFirstRow = admittedTreeFirstRow(geometry)
        val model =
            IdeRenderer.extract(
                state,
                geometry,
                prompt = prompt.state,
                treeFirstRow = treeFirstRow,
                selectedTreePath = selectedTreePath,
            )
        return when (val page = state.page) {
            is IdePageState.Start -> {
                IdePointerContext(
                    geometry,
                    projects = page.projects,
                    hitTargets = model.hitTargets,
                    dialog = state.dialog,
                )
            }

            is IdePageState.Workspace -> {
                IdePointerContext(
                    geometry,
                    editor = page.value.editor as? IdeEditorView.Text,
                    tree = page.value.tree.flatten(),
                    treeFirstRow = treeFirstRow,
                    hitTargets = model.hitTargets,
                    dialog = state.dialog,
                )
            }
        }
    }

    private fun activateUiAction(action: IdeHitAction): Boolean {
        when (action) {
            IdeHitAction.CreateProject -> {
                prompt.open(IdePromptKind.CreateProject)
            }

            IdeHitAction.OpenProject -> {
                focusArea = IdeFocusArea.Editor
            }

            IdeHitAction.CreateText -> {
                prompt.open(IdePromptKind.CreateText, "src/")
            }

            IdeHitAction.CreateDirectory -> {
                prompt.open(IdePromptKind.CreateDirectory, "src/")
            }

            IdeHitAction.Rename -> {
                val path = selectedTreePath ?: activeFile() ?: return false
                prompt.open(IdePromptKind.Rename(path), path.value)
            }

            IdeHitAction.Delete -> {
                val path = selectedTreePath ?: activeFile() ?: return false
                application.controller.dispatch(IdeCommand.RequestDelete(path))
            }

            IdeHitAction.Confirm -> {
                return confirmPrompt()
            }

            IdeHitAction.Dismiss -> {
                return prompt.cancel()
            }

            else -> {
                return false
            }
        }
        clearFocus()
        return true
    }

    private fun confirmPrompt(): Boolean {
        val command = prompt.confirm() ?: return true
        application.controller.dispatch(command)
        return true
    }

    private fun activeFile(): ProjectPath? = ((application.controller.viewState().page as? IdePageState.Workspace)?.value?.activeFile)

    private fun admittedTreeFirstRow(geometry: IdeRenderGeometry): Int {
        val entries =
            (application.controller.viewState().page as? IdePageState.Workspace)
                ?.value
                ?.tree
                ?.flatten()
                ?.size ?: 0
        return input.clampTree(entries, geometry.tree?.height ?: 0)
    }

    private fun selectTreeRow(
        x: Double,
        y: Double,
        geometry: IdeRenderGeometry,
    ) {
        val treeBounds = geometry.tree ?: return
        if (!treeBounds.contains(x, y)) return
        val workspace = (application.controller.viewState().page as? IdePageState.Workspace)?.value ?: return
        val row = input.treeFirstRow + ((y - treeBounds.top - TREE_ROWS_TOP).toInt() / UI_LINE_HEIGHT)
        selectedTreePath =
            workspace.tree
                .flatten()
                .getOrNull(row)
                ?.path
    }

    private fun IdeRect.contains(
        x: Double,
        y: Double,
    ): Boolean = x >= left && x < right && y >= top && y < bottom

    private fun GuiGraphicsExtractor.fill(
        bounds: IdeRect,
        color: Int,
    ) {
        fill(bounds.left, bounds.top, bounds.right, bounds.bottom, color)
    }

    private class RenderOperation(
        val zIndex: Int,
        val draw: () -> Unit,
    )

    private companion object {
        const val UI_LINE_HEIGHT = 12
        const val TREE_ROWS_TOP = 4
    }
}
