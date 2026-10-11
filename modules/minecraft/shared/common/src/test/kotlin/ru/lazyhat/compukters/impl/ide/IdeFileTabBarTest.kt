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

import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeFileTab
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeFileTabBarTest {
    @Test
    fun `active tab uses readable text and an underline while keeping conflict markers`() {
        val files =
            listOf(IdeFileTab(ProjectPath.file("src/main.kt"), dirty = true), IdeFileTab(ProjectPath.file("src/other.kt"), conflict = true))
        val layout = IdeFileTabBar.layout(IdeRect(0, 0, 800, 24), files)
        val draw = IdeFileTabBar.draw(layout, files.first().path, true)
        val active = draw.controls.single { it.target.action == IdeHitAction.FileTab && it.target.selected }
        assertEquals(IdeColors.EDITOR, active.panel.color)
        assertTrue(draw.text.any { it.value == "main.kt •" && it.color == IdeColors.TEXT })
        assertTrue(draw.text.any { it.value == "other.kt !" })
        val underline = draw.fills.single()
        assertEquals(IdeColors.ACCENT, underline.color)
        assertTrue(active.target.bounds.contains(underline.bounds))
        assertEquals(2, underline.bounds.height)
    }

    @Test
    fun `overflow reveals selected file and keeps controls inside the tab bar`() {
        val bounds = IdeRect(200, 46, 480, 70)
        val files = (0..9).map { IdeFileTab(ProjectPath.file("src/file$it.kt")) }
        val initial = IdeFileTabBar.layout(bounds, files)
        assertFalse(initial.hasPrevious)
        assertTrue(initial.hasNext)
        val selected = IdeFileTabBar.layout(bounds, files, reveal = files.last().path)
        assertEquals(
            files.last().path,
            selected.cells
                .single()
                .file.path,
        )
        assertTrue(selected.hasPrevious)
        assertFalse(selected.hasNext)
        val controls = IdeFileTabBar.controls(selected, files.last().path, true)
        assertTrue(
            controls.all {
                it.target.bounds.left >= bounds.left && it.target.bounds.right <= bounds.right &&
                    it.target.bounds.top >= bounds.top && it.target.bounds.bottom <= bounds.bottom
            },
        )
        assertEquals(selected.cells.single().bounds, controls.single { it.target.action == IdeHitAction.FileTab }.panel.bounds)
    }

    @Test
    fun `duplicate basenames show their paths and long labels are clipped`() {
        val files =
            listOf("src/main.kt", "test/main.kt", "src/aVeryLongFileNameThatCannotFitInsideThisTab.kt").map {
                IdeFileTab(ProjectPath.file(it))
            }
        val roomy = IdeFileTabBar.layout(IdeRect(0, 0, 800, 24), files)
        assertEquals(listOf("src/main.kt", "test/main.kt"), roomy.cells.take(2).map { it.label })
        assertTrue(
            roomy.cells
                .last()
                .label
                .endsWith("…"),
        )
        val tiny = IdeFileTabBar.layout(IdeRect(0, 0, 100, 24), files)
        assertEquals(1, tiny.cells.size)
        assertTrue(
            tiny.cells
                .single()
                .label.length <
                files
                    .first()
                    .path.value.length,
        )
        assertTrue(IdeFileTabBar.layout(IdeRect(0, 0, 40, 24), files).cells.isEmpty())
        assertTrue(IdeFileTabBar.layout(IdeRect(0, 0, 800, 24), emptyList()).cells.isEmpty())
    }

    @Test
    fun `tab and close clicks route the correct path and disabled controls dispatch nothing`() {
        val files = listOf("src/main.kt", "src/other.kt").map { IdeFileTab(ProjectPath.file(it)) }
        val bounds = IdeRect(200, 46, 800, 70)
        val layout = IdeFileTabBar.layout(bounds, files)
        val draw = IdeFileTabBar.draw(layout, files.first().path, true)
        val commands = mutableListOf<IdeCommand>()
        val input = IdeInputAdapter(IdeCommandSink { commands += it }, IdeClipboard { "" }, IdeClientLimits())
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, IdeCodeFontProfile.DEFAULT, tabsVisible = true)
        val context = IdePointerContext(geometry, hitTargets = draw.controls.map { it.target })
        val second = layout.cells[1]
        assertTrue(input.pointerClicked(second.bounds.left + 5.0, second.bounds.top + 8.0, 0, context))
        assertTrue(input.pointerClicked(second.close.left + 5.0, second.close.top + 5.0, 0, context))
        assertEquals(
            listOf(IdeCommand.SelectFileTab(files[1].path), IdeCommand.CloseFileTab(files[1].path)),
            commands.filter {
                it !=
                    IdeCommand.PointerActivity
            },
        )
        commands.clear()
        val disabled = context.copy(hitTargets = IdeFileTabBar.controls(layout, files.first().path, false).map { it.target })
        input.pointerClicked(second.close.left + 5.0, second.close.top + 5.0, 0, disabled)
        assertTrue(commands.isEmpty())
    }
}
