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
 */

package ru.lazyhat.compukters.impl.ide

import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdeMoveDirection
import ru.lazyhat.compukters.ide.client.state.IdeProjectSummary
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalSnapshot
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.ProjectFileKind
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeEntry
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeInputAdapterTest {
    @Test
    fun `character input preserves supplementary code points only for editor focus`() {
        val fixture = fixture()

        assertTrue(fixture.adapter.charTyped(CharacterEvent(0x1f600), IdeFocusState.Editor))
        assertEquals(listOf<IdeCommand>(IdeCommand.Edit(IdeEditorInput.Type("😀"))), fixture.commands)
        assertFalse(fixture.adapter.charTyped(CharacterEvent('x'.code), IdeFocusState.Tree))
    }

    @Test
    fun `editor shortcuts and modified navigation translate once`() {
        val fixture = fixture()

        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_S, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_B, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_SPACE, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_Y, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_LEFT, GLFW.GLFW_MOD_SHIFT), IdeFocusState.Editor)

        assertEquals(
            listOf(
                IdeCommand.Save,
                IdeCommand.Build,
                IdeCommand.ManualCompletion,
                IdeCommand.Edit(IdeEditorInput.Undo),
                IdeCommand.Edit(IdeEditorInput.Redo),
                IdeCommand.Edit(IdeEditorInput.Move(IdeMoveDirection.Left, true)),
            ),
            fixture.commands,
        )
    }

    @Test
    fun `paste is bounded on UTF-16 and UTF-8 boundaries`() {
        val fixture = fixture(clipboard = "😀абвextra", limits = IdeClientLimits(clipboardCodeUnits = 5, clipboardUtf8Bytes = 8))

        assertTrue(fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_V, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor))

        assertEquals(listOf<IdeCommand>(IdeCommand.Edit(IdeEditorInput.Type("😀аб"))), fixture.commands)
    }

    @Test
    fun `unhandled keys fall through and pointer activity requests eager autosave`() {
        val fixture = fixture()

        assertFalse(fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_F8), IdeFocusState.Editor))
        fixture.adapter.pointerActivity()

        assertEquals(listOf<IdeCommand>(IdeCommand.PointerActivity), fixture.commands)
    }

    @Test
    fun `mouse maps code cells tree rows and start rows to commands`() {
        val fixture = fixture()
        val geometry = IdeRenderGeometry.compute(960, 540, 24, 180, 120, true, true, TerminalFontProfile.DINA)
        val path = ProjectPath.file("src/main.kt")
        val editor =
            IdeEditorView.Text(
                path,
                listOf("a😀\tb"),
                listOf(0),
                0,
                0,
                1,
                0,
                null,
                null,
                0,
                0,
                false,
                false,
                KotlinLexicalSnapshot(0, emptyList()),
                IdeAnalysisState.Idle,
            )
        val codeLeft = geometry.editor.left + 4 * geometry.font.cellWidth

        fixture.adapter.pointerClicked(
            codeLeft + 2.0 * geometry.font.cellWidth,
            geometry.editor.top + 1.0,
            GLFW.GLFW_MOD_SHIFT,
            IdePointerContext(geometry, editor = editor),
        )
        fixture.adapter.pointerClicked(
            geometry.tree!!.left + 2.0,
            geometry.tree.top + 5.0,
            0,
            IdePointerContext(
                geometry,
                tree = listOf(ProjectTreeEntry(path, ProjectFileKind.Text(1), null)),
            ),
        )
        fixture.adapter.pointerClicked(
            geometry.editor.left + 2.0,
            geometry.editor.top + 7.0,
            0,
            IdePointerContext(geometry, projects = listOf(IdeProjectSummary("demo", "Demo"))),
        )

        assertTrue(fixture.commands.contains(IdeCommand.Edit(IdeEditorInput.SetCaret(3, true))))
        assertTrue(fixture.commands.contains(IdeCommand.OpenFile(path)))
        assertTrue(fixture.commands.contains(IdeCommand.OpenProject("demo")))
    }

    @Test
    fun `wheel routes editor scroll through controller command`() {
        val fixture = fixture()
        val geometry = IdeRenderGeometry.compute(960, 540, 24, 180, 120, true, true, TerminalFontProfile.DINA)
        val editor =
            IdeEditorView.Text(
                ProjectPath.file("main.kt"),
                listOf("x"),
                listOf(0),
                0,
                0,
                1,
                0,
                null,
                null,
                0,
                0,
                false,
                false,
                KotlinLexicalSnapshot(0, emptyList()),
                IdeAnalysisState.Idle,
            )

        fixture.adapter.scroll(geometry.editor.left + 1.0, geometry.editor.top + 1.0, 1.0, -2.0, IdePointerContext(geometry, editor))

        assertTrue(fixture.commands.contains(IdeCommand.ScrollEditor(6, 4)))
    }

    @Test
    fun `wheel scrolls project tree without sending an editor command`() {
        val fixture = fixture()
        val geometry = IdeRenderGeometry.compute(960, 540, 24, 180, 120, true, true, TerminalFontProfile.DINA)
        val entries =
            (0 until 80).map { index ->
                ProjectTreeEntry(ProjectPath.file("src/file$index.kt"), ProjectFileKind.Text(1), null)
            }

        assertTrue(
            fixture.adapter.scroll(
                geometry.tree!!.left + 1.0,
                geometry.tree.top + 1.0,
                0.0,
                -2.0,
                IdePointerContext(geometry, tree = entries),
            ),
        )

        assertEquals(6, fixture.adapter.treeFirstRow)
        assertFalse(fixture.commands.any { it is IdeCommand.ScrollEditor })
    }

    private fun fixture(
        clipboard: String = "",
        limits: IdeClientLimits = IdeClientLimits(),
    ): Fixture {
        val commands = mutableListOf<IdeCommand>()
        return Fixture(commands, IdeInputAdapter(commands::add, IdeClipboard { clipboard }, limits))
    }

    private fun key(
        key: Int,
        modifiers: Int = 0,
    ) = KeyEvent(key, 0, modifiers)

    private data class Fixture(
        val commands: MutableList<IdeCommand>,
        val adapter: IdeInputAdapter,
    )
}
