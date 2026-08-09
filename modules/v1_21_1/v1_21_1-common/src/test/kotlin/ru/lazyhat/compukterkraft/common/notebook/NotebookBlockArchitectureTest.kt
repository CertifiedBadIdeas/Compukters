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

import com.google.gson.JsonPrimitive
import com.mojang.serialization.JsonOps
import ru.lazyhat.compukterkraft.common.notebook.block.NOTEBOOK_DEVICE_FAMILY_CODEC
import ru.lazyhat.compukterkraft.common.notebook.block.requireNotebookDeviceFamily
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotebookBlockArchitectureTest {
    @Test
    fun notebookFamilyFlowsFromProductBlockThroughEntityAndItemData() {
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
        val neoForgeItemSource =
            Path
                .of(
                    "../v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/notebook/item/" +
                        "NeoForgeNotebookItem.kt",
                ).readText()

        assertTrue(blockEntitySource.contains("ComputerBlockEntity("))
        assertTrue(blockEntitySource.contains("familyOf(state)"))
        assertTrue(blockEntitySource.contains("(state.block as? NotebookBlock)?.deviceFamily"))
        assertTrue(blockEntitySource.contains("NotebookBlockEntity requires NotebookBlock state"))
        assertFalse(blockEntitySource.contains("DeviceFamily.NORMAL"))
        assertTrue(blockSource.contains("AbstractComputerBlock<NotebookBlockEntity>"))
        assertTrue(blockSource.contains("ModObjects.notebookBlockEntityType"))
        assertTrue(blockSource.contains("val deviceFamily: DeviceFamily"))
        assertTrue(blockSource.contains("check(tile.family == deviceFamily)"))
        assertTrue(itemSource.contains("block: NotebookBlock"))
        assertTrue(itemSource.contains("val deviceFamily: DeviceFamily"))
        assertTrue(itemSource.contains("deviceFamilyId = deviceFamily.name.lowercase()"))
        assertTrue(itemSource.contains("item.compukterkraft.advanced_notebook.tooltip"))
        assertFalse(itemSource.contains("DeviceFamily.NORMAL"))
        assertTrue(neoForgeItemSource.contains("block: NotebookBlock"))
        assertFalse(itemSource.contains("useOn"))
        assertFalse(itemSource.contains("openMenu"))
    }

    @Test
    fun notebookCodecPersistsOnlyNormalOrAdvancedFamilyWithoutFallback() {
        val blockSource =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/block/NotebookBlock.kt")
                .readText()

        assertTrue(blockSource.contains("RecordCodecBuilder.mapCodec"))
        assertTrue(blockSource.contains("propertiesCodec()"))
        assertTrue(blockSource.contains("fieldOf(\"device_family\")"))
        assertTrue(blockSource.contains("\"normal\" -> DataResult.success(DeviceFamily.NORMAL)"))
        assertTrue(blockSource.contains("\"advanced\" -> DataResult.success(DeviceFamily.ADVANCED)"))
        assertTrue(blockSource.contains("unsupported Notebook device family"))
        assertFalse(blockSource.contains("simpleCodec(::NotebookBlock)"))
    }

    @Test
    fun notebookFamilyCodecRejectsCommandAndUnknownValues() {
        assertEquals(
            DeviceFamily.NORMAL,
            NOTEBOOK_DEVICE_FAMILY_CODEC.parse(JsonOps.INSTANCE, JsonPrimitive("normal")).result().orElseThrow(),
        )
        assertEquals(
            DeviceFamily.ADVANCED,
            NOTEBOOK_DEVICE_FAMILY_CODEC.parse(JsonOps.INSTANCE, JsonPrimitive("advanced")).result().orElseThrow(),
        )
        assertTrue(NOTEBOOK_DEVICE_FAMILY_CODEC.parse(JsonOps.INSTANCE, JsonPrimitive("command")).error().isPresent)
        assertTrue(NOTEBOOK_DEVICE_FAMILY_CODEC.parse(JsonOps.INSTANCE, JsonPrimitive("future")).error().isPresent)
        assertTrue(NOTEBOOK_DEVICE_FAMILY_CODEC.encodeStart(JsonOps.INSTANCE, DeviceFamily.COMMAND).error().isPresent)
        assertEquals(DeviceFamily.NORMAL, requireNotebookDeviceFamily(DeviceFamily.NORMAL))
        assertEquals(DeviceFamily.ADVANCED, requireNotebookDeviceFamily(DeviceFamily.ADVANCED))
        assertFailsWith<IllegalArgumentException> {
            requireNotebookDeviceFamily(DeviceFamily.COMMAND)
        }
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
                screenSource.contains("ComputerDisplayScreen<NotebookComputerMenu>"),
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
        assertTrue(screenSource.contains("imageHeight = TERMINAL_PANEL_HEIGHT + INVENTORY_PANEL_HEIGHT"))
        assertTrue(screenSource.contains("imageHeight = TERMINAL_PANEL_HEIGHT"))
        assertTrue(screenSource.contains("menu.moduleStack.sdkArtifactIdentity"))
        assertTrue(screenSource.contains("inventoryPanel()"))
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

    @Test
    fun notebookOwnsPersistentSdkModuleBayWithPoweredOffMutationBoundary() {
        val blockEntitySource =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/block/NotebookBlockEntity.kt")
                .readText()
        val abstractComputerSource =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/AbstractComputerBlockEntity.kt")
                .readText()

        assertTrue(blockEntitySource.contains("NonNullList.withSize(1, ItemStack.EMPTY)"))
        assertTrue(blockEntitySource.contains("ContainerHelper.saveAllItems"))
        assertTrue(blockEntitySource.contains("ContainerHelper.loadAllItems"))
        assertTrue(blockEntitySource.contains("commitMutation = ::commitPoweredOffHardwareChange"))
        assertTrue(abstractComputerSource.contains("if (isRuntimeDeviceOn()) return false"))
        assertTrue(abstractComputerSource.contains("deleteComputerSnapshot(computerId)"))
        assertTrue(abstractComputerSource.contains("pendingRuntimeSnapshot = null"))
    }
}
