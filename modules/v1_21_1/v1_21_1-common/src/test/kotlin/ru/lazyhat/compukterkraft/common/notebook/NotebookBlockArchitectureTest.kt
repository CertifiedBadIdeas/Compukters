/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.common.notebook

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotebookBlockArchitectureTest {
    @Test
    fun notebookIsAPlacedK16DeviceNotAnInventoryComputer() {
        val blockEntityPath =
            Path.of("src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/block/NotebookBlockEntity.kt")
        val blockPath =
            Path.of("src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/block/NotebookBlock.kt")
        val itemPath =
            Path.of("src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/item/NotebookItem.kt")

        assertTrue(blockEntityPath.exists())
        assertTrue(blockPath.exists())
        assertTrue(itemPath.exists())

        val blockEntitySource = blockEntityPath.readText()
        val blockSource = blockPath.readText()
        val itemSource = itemPath.readText()

        assertTrue(blockEntitySource.contains("ComputerBlockEntity("))
        assertTrue(blockEntitySource.contains("DeviceFamily.NORMAL"))
        assertTrue(blockSource.contains("AbstractComputerBlock<NotebookBlockEntity>"))
        assertTrue(blockSource.contains("ModObjects.notebookBlockEntityType"))
        assertTrue(itemSource.contains("deviceFamilyId = DeviceFamily.NORMAL.name.lowercase()"))
        assertFalse(itemSource.contains("useOn"))
        assertFalse(itemSource.contains("openMenu"))
    }

    @Test
    fun notebookScreenUsesK16Branding() {
        val screenSource =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/screen/NotebookScreen.kt")
                .readText()

        assertTrue(screenSource.contains("\"K16 LAPTOP\""))
        assertFalse(screenSource.contains("\"RUX LAPTOP\""))
    }

    @Test
    fun notebookOpensLaptopTerminalScreenInsteadOfDesktopControlMenu() {
        val blockPath =
            Path.of("src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/block/NotebookBlock.kt")
        val screenPath =
            Path.of("src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/screen/NotebookScreen.kt")
        val displayScreenPath =
            Path.of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerDisplayScreen.kt")

        val blockSource = blockPath.readText()
        val screenSource = screenPath.readText()
        val displayScreenSource = displayScreenPath.readText()

        assertTrue(
            blockSource.contains("override fun useWithoutItem") &&
                blockSource.contains("ModObjects.openComputerMenu") &&
                !blockSource.contains("openComputerControlMenu"),
            "Notebook RMB should open the laptop terminal menu directly instead of the desktop control menu.",
        )
        assertTrue(
            screenSource.contains("class NotebookScreen") &&
                screenSource.contains("ComputerDisplayScreen<ComputerMenuWithoutInventory>"),
            "Notebook should have its own screen class so laptop-only UI can grow independently.",
        )
        assertTrue(
                screenSource.contains("override fun content(): UiElement") &&
                screenSource.contains("override fun currentLayout()") &&
                screenSource.contains("ComputerControlAction.REBOOT") &&
                screenSource.contains("ComputerControlAction.SHUTDOWN") &&
                !screenSource.contains("SerialTerminalScreen"),
            "NotebookScreen should define its own laptop layout while reusing the terminal display/input backend.",
        )
        assertTrue(
            displayScreenSource.contains("abstract class ComputerDisplayScreen"),
            "NotebookScreen should be able to reuse the existing display/input implementation.",
        )
        assertTrue(
            displayScreenSource.contains("protected abstract fun currentLayout()") &&
                displayScreenSource.contains("protected val inputHandler") &&
                displayScreenSource.contains("protected val terminalInput"),
            "ComputerDisplayScreen should expose narrow hooks for laptop-specific screen layouts.",
        )
    }

    @Test
    fun notebookLidStateFollowsServerMenuLifecycle() {
        val blockEntityPath =
            Path.of("src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/block/NotebookBlockEntity.kt")
        val computerMenuPath =
            Path.of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt")
        val computerModPath =
            Path.of("../v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt")

        val blockEntitySource = blockEntityPath.readText()
        val computerMenuSource = computerMenuPath.readText()
        val computerModSource = computerModPath.readText()

        assertTrue(
            blockEntitySource.contains("notebookMenuOpened") &&
                blockEntitySource.contains("notebookMenuClosed") &&
                blockEntitySource.contains("setNotebookLidOpen(open = true)") &&
                blockEntitySource.contains("setNotebookLidOpen(open = false)"),
            "Notebook block entity should drive lid state from menu viewer count changes.",
        )
        assertTrue(
            computerMenuSource.contains("onRemoved") &&
                computerMenuSource.contains("onRemoved?.invoke()"),
            "Computer menus should expose a close callback so notebook viewers are released on removed().",
        )
        assertTrue(
            computerModSource.contains("notebookMenuOpened") &&
                computerModSource.contains("notebookMenuClosed"),
            "Notebook control menus should open and close the notebook lid around the menu lifecycle.",
        )
    }
}
