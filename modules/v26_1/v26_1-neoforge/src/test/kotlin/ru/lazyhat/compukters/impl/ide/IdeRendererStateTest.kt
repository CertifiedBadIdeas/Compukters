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

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.analysis.SemanticCategory
import ru.lazyhat.compukters.ide.analysis.SemanticToken
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotId
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisPresentation
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.build.IdeBuildState
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.state.IdeProjectSummary
import ru.lazyhat.compukters.ide.client.state.IdeViewState
import ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.highlight.IncrementalKotlinHighlighter
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalKind
import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeStore
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeRendererStateTest {
    @Test
    fun `start page exposes bounded project rows and actions`() {
        val state =
            IdeViewState.startPage(
                listOf(
                    IdeProjectSummary("alpha", "Alpha"),
                    IdeProjectSummary("beta", "Beta"),
                ),
            )

        val model = IdeRenderer.extract(state, geometry())

        assertEquals(listOf("Alpha", "Beta"), model.text.filter { it.kind == IdeTextKind.StartProject }.map { it.value })
        assertTrue(model.hitTargets.any { it.action == IdeHitAction.CreateProject && it.enabled })
        assertTrue(model.hitTargets.any { it.action == IdeHitAction.OpenProject && it.enabled })
        assertTrue(model.zOrdered())
    }

    @Test
    fun `workspace clips rows and gives semantic spans precedence over lexical spans`() {
        val source = "fun main()\r\nval value = 1\r\nprintln(value)"
        val secondStart = source.indexOf("val value")
        val valueStart = source.indexOf("value")
        val document = EditorDocument(source)
        document.setCaret(valueStart + "value".length)
        val lexical = IncrementalKotlinHighlighter(document).use { it.snapshot() }
        val identity = AnalysisSnapshotIdentity(SourceSnapshotId(Hash256.zero()), AnalysisProfileIdentity(Hash256.zero()))
        val path = ProjectPath.file("src/main.kt")
        val virtualPath = VirtualSourcePath.kotlin(path.value)
        val presentation =
            IdeAnalysisPresentation.of(
                diagnostics =
                    listOf(
                        EditorDiagnostic(
                            EditorDiagnosticSeverity.Warning,
                            "Example warning",
                            virtualPath,
                            EditorRange(valueStart, valueStart + 5),
                        ),
                    ),
                semanticTokens =
                    listOf(SemanticToken(virtualPath, EditorRange(valueStart, valueStart + 5), SemanticCategory.LocalVariable)),
            )
        val editor =
            IdeEditorView.Text(
                path = path,
                visibleLines = listOf("val value = 1", "println(value)"),
                visibleLineStartsUtf16 = listOf(secondStart, source.indexOf("println")),
                firstVisibleLine = 1,
                totalLines = 3,
                caretUtf16 = document.caretOffset,
                selectionStartUtf16 = valueStart,
                selectionEndUtf16 = valueStart + 5,
                contentRevision = 1,
                persistedContentRevision = 0,
                dirty = true,
                conflict = false,
                lexical = lexical,
                analysis = IdeAnalysisState.Active(identity, virtualPath, 1, presentation, null),
            )
        val model = IdeRenderer.extract(workspaceState(editor, IdeBuildState.Idle), geometry())

        assertEquals(listOf("2", "3"), model.text.filter { it.kind == IdeTextKind.LineNumber }.map { it.value.trim() })
        val value = model.text.single { it.sourceRange == EditorRange(valueStart, valueStart + 5) }
        assertEquals(IdeTextStyle.Semantic(SemanticCategory.LocalVariable), value.style)
        val keyword = model.text.single { it.sourceRange == EditorRange(secondStart, secondStart + 3) }
        assertEquals(IdeTextStyle.Lexical(KotlinLexicalKind.Keyword), keyword.style)
        assertTrue(model.fills.any { it.kind == IdeFillKind.Selection })
        assertTrue(model.fills.any { it.kind == IdeFillKind.Caret })
        assertEquals(listOf("Example warning"), model.text.filter { it.kind == IdeTextKind.Diagnostic }.map { it.value })
        assertTrue(model.scissors.any { it.kind == IdeScissorKind.Editor })
        assertTrue(model.text.filter { it.kind == IdeTextKind.TreeRow }.any { "main.kt" in it.value })
    }

    @Test
    fun `editor rows are clipped to derived font geometry`() {
        val source = (1..40).joinToString("\n") { "line$it" }
        val document = EditorDocument(source)
        val lexical = IncrementalKotlinHighlighter(document).use { it.snapshot() }
        val path = ProjectPath.file("src/main.kt")
        val editor =
            IdeEditorView.Text(
                path = path,
                visibleLines = (0 until document.lineCount).map(document::materializeLine),
                visibleLineStartsUtf16 = (0 until document.lineCount).map(document::lineStartOffset),
                firstVisibleLine = 0,
                totalLines = document.lineCount,
                caretUtf16 = 0,
                selectionStartUtf16 = null,
                selectionEndUtf16 = null,
                contentRevision = 0,
                persistedContentRevision = 0,
                dirty = false,
                conflict = false,
                lexical = lexical,
                analysis = IdeAnalysisState.Idle,
            )
        val geometry = geometry()

        val model = IdeRenderer.extract(workspaceState(editor, IdeBuildState.Idle), geometry)

        val lineNumbers = model.text.filter { it.kind == IdeTextKind.LineNumber }
        assertEquals(geometry.codeRows, lineNumbers.size)
        assertEquals(geometry.codeRows.toString(), lineNumbers.last().value.trim())
    }

    @Test
    fun `toolbar reports artifact and leaves target actions visibly disabled`() {
        val editor = IdeEditorView.Binary(ProjectPath.file("image.bin"), 4_096)
        val build = IdeBuildState.Succeeded(Hash256.zero(), Hash256.zero(), 321, true, 42)

        val model = IdeRenderer.extract(workspaceState(editor, build), geometry(TerminalFontProfile.COZETTE))

        assertTrue(model.text.any { it.kind == IdeTextKind.Binary && "4096" in it.value })
        assertTrue(model.text.any { it.kind == IdeTextKind.Status && "321 B" in it.value && "cache" in it.value })
        listOf(IdeHitAction.Verify, IdeHitAction.Deploy, IdeHitAction.Run).forEach { action ->
            val target = model.hitTargets.single { it.action == action }
            assertFalse(target.enabled)
            assertEquals("No target attached", target.tooltip)
        }
    }

    private fun workspaceState(
        editor: IdeEditorView,
        build: IdeBuildState,
    ): IdeViewState {
        val root = createTempDirectory("compukters-renderer-tree-")
        val descriptor = ProjectCatalog.open(root).create("demo")
        return try {
            IdeViewState(
                generation = 1,
                page =
                    IdePageState.Workspace(
                        IdeWorkspaceView(
                            project = IdeProjectSummary("demo", "Demo"),
                            tree = ProjectTreeStore(descriptor.handle).scan(),
                            activeFile =
                                when (editor) {
                                    is IdeEditorView.Text -> editor.path
                                    is IdeEditorView.Binary -> editor.path
                                    IdeEditorView.Empty -> null
                                },
                            editor = editor,
                            status = null,
                            build = build,
                        ),
                    ),
                dialog = null,
                busy = emptySet(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun geometry(font: TerminalFontProfile = TerminalFontProfile.DINA) =
        IdeRenderGeometry.compute(960, 540, 24, 180, 120, true, true, font)
}

private fun IdeDrawModel.zOrdered(): Boolean {
    val values =
        panels.map { it.zIndex } +
            fills.map { it.zIndex } +
            text.map { it.zIndex } +
            scissors.map { it.zIndex } +
            hitTargets.map { it.zIndex }
    return values.all { it >= 0 }
}
