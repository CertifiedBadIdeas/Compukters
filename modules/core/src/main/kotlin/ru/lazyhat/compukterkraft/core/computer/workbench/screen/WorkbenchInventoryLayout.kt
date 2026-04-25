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
package ru.lazyhat.compukterkraft.core.computer.workbench.screen

import ru.lazyhat.compukterkraft.core.computer.workbench.screen.WorkbenchInventoryLayout.Companion.SLOT_SIZE
import ru.lazyhat.compukterkraft.core.ui.foundation.IntSize

/**
 * Single source of truth for the placement of the Workbench's right-hand
 * inventory panel (target slot + 3×9 main inventory + 1×9 hotbar).
 *
 * Both the DSL UI builder (which paints the slot backgrounds and panel
 * chrome) and the host Minecraft screen (which calls
 * `WorkbenchPositionableSlot.relocate(...)` so `AbstractContainerScreen`
 * draws the slot contents in the same place) consume the absolute
 * coordinates produced here.
 *
 * @property panelLeft x of the panel's leftmost pixel.
 * @property panelTop y of the panel's topmost pixel.
 * @property panelWidth panel width (constant — see [WorkbenchInventoryLayout.PANEL_WIDTH]).
 * @property panelHeight panel height — depends on [SLOT_SIZE] and section heights.
 * @property targetSlotX absolute x of the single target slot.
 * @property targetSlotY absolute y of the single target slot.
 * @property mainGridX absolute x of the top-left main-inventory slot.
 * @property mainGridY absolute y of the top-left main-inventory slot.
 * @property hotbarX absolute x of the leftmost hotbar slot.
 * @property hotbarY absolute y of the leftmost hotbar slot.
 */
data class WorkbenchInventoryLayout(
    val panelLeft: Int,
    val panelTop: Int,
    val panelWidth: Int,
    val panelHeight: Int,
    val targetSlotX: Int,
    val targetSlotY: Int,
    val mainGridX: Int,
    val mainGridY: Int,
    val hotbarX: Int,
    val hotbarY: Int,
) {
    companion object {
        const val SLOT_SIZE: Int = 18
        private const val PANEL_PADDING: Int = 8
        const val PANEL_WIDTH: Int = SLOT_SIZE * 9 + PANEL_PADDING * 2
        const val SECTION_HEADER_HEIGHT: Int = 14
        const val TARGET_SECTION_HEIGHT: Int = SLOT_SIZE + 4
        const val INV_HOTBAR_GAP: Int = 4
        const val BORDER_WIDTH: Int = 1 // Slots x/y need to be padded with border width.

        /**
         * Total panel height = header + target + header + 3*slot + gap + slot.
         */
        const val PANEL_HEIGHT: Int =
            SECTION_HEADER_HEIGHT + TARGET_SECTION_HEIGHT +
                SECTION_HEADER_HEIGHT + SLOT_SIZE * 3 +
                INV_HOTBAR_GAP + SLOT_SIZE

        /**
         * Compute the layout for the right-side inventory panel.
         *
         * @param viewport overall screen size in pixels.
         * @param panelTop y where the panel begins (typically below the toolbar).
         */
        fun compute(
            viewport: IntSize,
            panelTop: Int,
        ): WorkbenchInventoryLayout {
            val panelLeft = (viewport.width - PANEL_WIDTH).coerceAtLeast(0)
            val gridX = panelLeft + PANEL_PADDING + BORDER_WIDTH
            val targetX = panelLeft + (PANEL_WIDTH - SLOT_SIZE) / 2 + BORDER_WIDTH
            val targetY = panelTop + SECTION_HEADER_HEIGHT + 2 + BORDER_WIDTH
            val mainGridY =
                panelTop + SECTION_HEADER_HEIGHT + TARGET_SECTION_HEIGHT + SECTION_HEADER_HEIGHT + BORDER_WIDTH
            val hotbarY = mainGridY + SLOT_SIZE * 3 + INV_HOTBAR_GAP
            return WorkbenchInventoryLayout(
                panelLeft = panelLeft,
                panelTop = panelTop,
                panelWidth = PANEL_WIDTH,
                panelHeight = PANEL_HEIGHT,
                targetSlotX = targetX,
                targetSlotY = targetY,
                mainGridX = gridX,
                mainGridY = mainGridY,
                hotbarX = gridX,
                hotbarY = hotbarY,
            )
        }
    }
}
