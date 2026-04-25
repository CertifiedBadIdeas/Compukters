/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package ru.lazyhat.compukterkraft.core.computer.workbench.screen

import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStore
import ru.lazyhat.compukterkraft.core.ui.editor.EditorViewModel
import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.HoverState
import ru.lazyhat.compukterkraft.core.ui.foundation.IntSize
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.foundation.UiScope
import ru.lazyhat.compukterkraft.core.ui.foundation.Value
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Position
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.UiAlignment
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.align
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.background
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.clickable
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.hoverable
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.size
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.weight
import ru.lazyhat.compukterkraft.core.ui.foundation.ui
import ru.lazyhat.compukterkraft.core.ui.foundation.value
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

/**
 * Builds the entire Workbench UI tree from a [WorkbenchStore].
 *
 * Layout (top → bottom):
 *
 *  1. **Toolbar** — Save / Pull / Push / Run / Imports actions on the left,
 *     spacer in the middle, Terminal toggle and Reboot on the right.
 *  2. **Main area** — file-browser sidebar on the left, code editor taking
 *     the rest of the width.
 *  3. **Terminal panel** — _docked at the bottom on demand_ via the toolbar
 *     toggle. Hidden by default; takes a fixed height when visible.
 *  4. **Status bar** — open document path + dirty marker + cursor position
 *     on the left, hover/diagnostic/target info on the right.
 *
 * Completion and import-picker popups float on top as overlays.
 *
 * The host screen invalidates this builder every tick (see
 * `WorkbenchEditorScreen.containerTick`), so structurally-dynamic parts
 * (the workspace listing, the conditional terminal panel) can be expressed
 * with plain Kotlin conditionals; only intra-tick mutations need the
 * `Value<…>` mechanism.
 *
 * @param viewport overall screen size (width × height) in pixels.
 * @param viewModel the editor view-model fed to [UiElement.CodeEditor].
 * @param terminalSnapshot supplier called every render; returning `null`
 *   keeps the embedded terminal panel showing an empty buffer.
 */
fun buildWorkbenchUi(
    store: WorkbenchStore,
    viewport: IntSize,
    viewModel: EditorViewModel,
    terminalSnapshot: () -> ScreenBufferSnapshot? = { null },
    onTerminalKey: (Int) -> Boolean = { false },
    onTerminalKeyReleased: (Int) -> Boolean = { false },
    onTerminalCharTyped: (Char) -> Boolean = { false },
): UiElement {
    val terminalVisible = store.state.terminalVisible
    val terminalHeight = if (terminalVisible) TERMINAL_PANEL_HEIGHT else 0
    val mainHeight =
        (viewport.height - TOOLBAR_HEIGHT - STATUS_HEIGHT - terminalHeight).coerceAtLeast(0)
    val sidebarWidth = SIDEBAR_WIDTH.coerceAtMost(viewport.width / 4)
    val inventoryWidth = WorkbenchInventoryLayout.PANEL_WIDTH.coerceAtMost(viewport.width / 3)
    val editorWidth = (viewport.width - sidebarWidth - inventoryWidth).coerceAtLeast(0)

    return ui(modifier = Modifier.size(viewport).background(BG_MAIN)) {
        column(modifier = Modifier.size(viewport)) {
            buildToolbar(store, viewport.width)

            row(modifier = Modifier.size(IntSize(viewport.width, mainHeight))) {
                buildSidebar(store, sidebarWidth, mainHeight)
                buildEditorArea(viewModel, editorWidth, mainHeight)
                buildInventoryPanel(inventoryWidth, mainHeight)
            }

            if (terminalVisible) {
                buildTerminalPanel(
                    width = viewport.width,
                    height = terminalHeight,
                    snapshot = terminalSnapshot,
                    onKey = onTerminalKey,
                    onKeyReleased = onTerminalKeyReleased,
                    onCharTyped = onTerminalCharTyped,
                )
            }

            buildStatusBar(store, viewport.width)
        }

        buildCompletionOverlay(store, viewport)
        buildImportPickerOverlay(store, viewport)
    }
}

// ---------------------------------------------------------------------------
// Zones
// ---------------------------------------------------------------------------

private fun UiScope.buildToolbar(
    store: WorkbenchStore,
    width: Int,
) {
    row(modifier = Modifier.size(IntSize(width, TOOLBAR_HEIGHT)).background(BG_HEADER)) {
        toolbarButton(label = value { "Save" }, onClick = { store.saveDocument() })
        toolbarButton(label = value { "Pull" }, onClick = { store.pullFromTarget() })
        toolbarButton(label = value { "Push" }, onClick = { store.pushToTarget() })
        toolbarButton(label = value { "Run" }, onClick = { store.runTargetProgram() })
        toolbarButton(label = value { "Imports" }, onClick = { store.openImportPicker() })
        box(modifier = Modifier.weight(2f).size(IntSize(0, TOOLBAR_HEIGHT)))
        toolbarButton(
            label = value { if (store.state.terminalVisible) "Hide T" else "Term" },
            highlighted = store.state.terminalVisible,
            onClick = { store.toggleTerminalVisibility() },
        )
        toolbarButton(label = value { "Reboot" }, onClick = { store.rebootComputer() })
    }
}

private fun UiScope.buildSidebar(
    store: WorkbenchStore,
    width: Int,
    height: Int,
) {
    column(modifier = Modifier.size(IntSize(width, height)).background(BG_SIDEBAR)) {
        // Header — current browser path.
        box(modifier = Modifier.size(IntSize(width, SIDEBAR_HEADER_HEIGHT)).background(BG_HEADER)) {
            text(
                modifier = Modifier.align(UiAlignment.Center),
                color = TEXT_DIM,
                text = value { "/" + store.state.browserPath },
            )
        }
        scrollArea(
            modifier = Modifier.size(IntSize(width, height - SIDEBAR_HEADER_HEIGHT)),
            scrollY = value { 0 },
        ) {
            val rowCount =
                store.state.entries.size + if (store.state.browserPath.isNotEmpty()) 1 else 0
            val contentHeight = (rowCount * SIDEBAR_ROW_HEIGHT).coerceAtLeast(height - SIDEBAR_HEADER_HEIGHT)
            // Wrap entries in an explicit column — ScrollArea content uses
            // box (overlap) layout by default, so without this all rows would
            // stack on top of each other.
            column(modifier = Modifier.size(IntSize(width, contentHeight))) {
                if (store.state.browserPath.isNotEmpty()) {
                    sidebarRow(
                        width = width,
                        label = value { ".." },
                        selected = false,
                        onClick = { store.navigateUp() },
                    )
                }
                store.state.entries.forEach { entry ->
                    val displayLabel = if (entry.directory) "${entry.path}/" else entry.path
                    val isOpenDocument =
                        !entry.directory && store.state.openDocument?.path == entry.path
                    sidebarRow(
                        width = width,
                        label = value { displayLabel },
                        selected = isOpenDocument,
                        onClick = {
                            if (entry.directory) {
                                store.requestListing(entry.path)
                            } else {
                                store.requestDocument(entry.path)
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun UiScope.buildEditorArea(
    viewModel: EditorViewModel,
    width: Int,
    height: Int,
) {
    box(modifier = Modifier.size(IntSize(width, height)).background(BG_EDITOR)) {
        codeEditor(
            viewModel = value { viewModel },
            modifier = Modifier.size(IntSize(width, height)),
            fontWidth = 6,
            fontHeight = 9,
        )
    }
}

private fun UiScope.buildTerminalPanel(
    width: Int,
    height: Int,
    snapshot: () -> ScreenBufferSnapshot?,
    onKey: (Int) -> Boolean,
    onKeyReleased: (Int) -> Boolean,
    onCharTyped: (Char) -> Boolean,
) {
    column(modifier = Modifier.size(IntSize(width, height)).background(BG_TERMINAL)) {
        box(modifier = Modifier.size(IntSize(width, TERMINAL_HEADER_HEIGHT)).background(BG_HEADER)) {
            text(
                modifier = Modifier.align(UiAlignment.Center),
                color = TEXT_DIM,
                text = value { "Terminal" },
            )
        }
        terminalSurface(
            modifier = Modifier.size(IntSize(width, height - TERMINAL_HEADER_HEIGHT)),
            snapshot = value { snapshot() ?: EMPTY_TERMINAL_SNAPSHOT },
            onKey = onKey,
            onKeyReleased = onKeyReleased,
            onCharTyped = onCharTyped,
        )
    }
}

private fun UiScope.buildStatusBar(
    store: WorkbenchStore,
    width: Int,
) {
    row(modifier = Modifier.size(IntSize(width, STATUS_HEIGHT)).background(BG_HEADER)) {
        text(
            modifier = Modifier.size(IntSize(width / 2, STATUS_HEIGHT)).align(UiAlignment.Center),
            color = TEXT_LIGHT,
            text =
                value {
                    val ed = store.state.editor
                    val path = store.state.openDocument?.path ?: "No file opened"
                    val prefix = if (ed.dirty) "* " else ""
                    "$prefix$path  L${ed.cursorLine + 1}:C${ed.cursorColumn + 1}"
                },
        )
        box(modifier = Modifier.weight(1f).size(IntSize(0, STATUS_HEIGHT)))
        text(
            modifier = Modifier.size(IntSize(width / 2 - 8, STATUS_HEIGHT)).align(UiAlignment.Center),
            color = TEXT_ACCENT,
            text =
                value {
                    val ed = store.state.editor
                    (
                        ed.hoverInfo?.contents
                            ?: ed.ideSnapshot
                                ?.diagnostics
                                ?.firstOrNull()
                                ?.message
                            ?: store.state.target.displayName
                                .orEmpty()
                    ).take(96)
                },
        )
    }
}

// ---------------------------------------------------------------------------
// Overlays
// ---------------------------------------------------------------------------

private fun UiScope.buildCompletionOverlay(
    store: WorkbenchStore,
    viewport: IntSize,
) {
    val items =
        store.state.editor.completionItems
            .take(MAX_COMPLETION_ROWS)
    if (items.isEmpty()) return
    val popupWidth = COMPLETION_POPUP_WIDTH
    val popupHeight = items.size * COMPLETION_ROW_HEIGHT + COMPLETION_POPUP_PADDING * 2

    overlay(
        modifier = Modifier.size(IntSize(popupWidth, popupHeight)),
        anchor = value { completionAnchor(viewport, popupWidth, popupHeight) },
    ) {
        column(modifier = Modifier.size(IntSize(popupWidth, popupHeight)).background(BG_POPUP)) {
            box(modifier = Modifier.size(IntSize(popupWidth, COMPLETION_POPUP_PADDING)))
            items.forEachIndexed { idx, item ->
                completionRow(
                    width = popupWidth,
                    label = item.label,
                    selected = idx == store.state.editor.selectedCompletion,
                    onClick = { store.applyCompletion(idx) },
                )
            }
        }
    }
}

private fun UiScope.buildImportPickerOverlay(
    store: WorkbenchStore,
    viewport: IntSize,
) {
    if (!store.state.editor.importPickerVisible) return
    val items =
        store.state.editor.importPickerItems
            .take(MAX_IMPORT_ROWS)
    val popupWidth = IMPORT_POPUP_WIDTH
    val popupHeight = items.size * COMPLETION_ROW_HEIGHT + IMPORT_HEADER_HEIGHT

    overlay(
        modifier = Modifier.size(IntSize(popupWidth, popupHeight)),
        anchor =
            value {
                Position(
                    x = (viewport.width - popupWidth) / 2,
                    y = (viewport.height - popupHeight) / 3,
                )
            },
    ) {
        column(modifier = Modifier.size(IntSize(popupWidth, popupHeight)).background(BG_IMPORT_POPUP)) {
            box(modifier = Modifier.size(IntSize(popupWidth, IMPORT_HEADER_HEIGHT)).background(BG_BUTTON)) {
                text(
                    modifier = Modifier.align(UiAlignment.Center),
                    color = TEXT_LIGHT,
                    text = value { "Available imports" },
                )
            }
            items.forEachIndexed { idx, item ->
                completionRow(
                    width = popupWidth,
                    label = item.label,
                    selected = idx == store.state.editor.selectedImportPickerIndex,
                    onClick = {
                        store.applyImportPickerSelection(idx, visibleEditorLines = DEFAULT_VISIBLE_EDITOR_LINES)
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun UiScope.toolbarButton(
    label: Value<String>,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    // BackgroundModifier bakes a static color into the compiled FillRect; to
    // get a hover-aware fill we wire a HoverState through `Modifier.hoverable`
    // and paint the background ourselves from a canvas, whose `onDraw` runs
    // every frame and therefore sees the current `isHovered` flag.
    val hover = HoverState()
    button(
        modifier =
            Modifier
                .weight(1f)
                .size(IntSize(0, TOOLBAR_HEIGHT))
                .hoverable(hover),
        onClick = onClick,
    ) {
        canvas {
            val color =
                when {
                    highlighted -> BG_BUTTON_ACTIVE
                    hover.isHovered -> BG_BUTTON_HOVER
                    else -> BG_BUTTON
                }
            fillRect(0, 0, width, height, color)
        }
        // align(Center) vertically centres the intrinsic-height (9px) text
        // line inside the 22px-tall button; without it the glyph hugs the
        // top edge.
        text(
            modifier = Modifier.align(UiAlignment.Center),
            color = TEXT_LIGHT,
            text = label,
        )
    }
}

private fun UiScope.sidebarRow(
    width: Int,
    label: Value<String>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    box(
        modifier =
            Modifier
                .size(IntSize(width, SIDEBAR_ROW_HEIGHT))
                .background(if (selected) BG_ROW_SELECTED else BG_TRANSPARENT)
                .clickable(onClick),
    ) {
        text(
            modifier = Modifier.align(UiAlignment.Center),
            color = if (selected) TEXT_ACCENT else TEXT_LIGHT,
            text = label,
        )
    }
}

// ---------------------------------------------------------------------------
// Inventory panel
// ---------------------------------------------------------------------------

/**
 * Right-hand chrome that hosts the workbench's target slot, the player's
 * 3×9 main inventory and the 9-slot hotbar. Slot _contents_ are rendered by
 * `AbstractContainerScreen` — this builder only paints headers, separators
 * and dark 18×18 squares per slot. The screen relocates the underlying
 * Minecraft `Slot` instances to the matching pixel positions via
 * [WorkbenchInventoryLayout], guaranteeing both layers stay in sync.
 */
private fun UiScope.buildInventoryPanel(
    width: Int,
    height: Int,
) {
    column(modifier = Modifier.size(IntSize(width, height)).background(BG_INVENTORY)) {
        box(modifier = Modifier.size(IntSize(width, SIDEBAR_HEADER_HEIGHT)).background(BG_HEADER)) {
            text(
                modifier = Modifier.align(UiAlignment.Center),
                color = TEXT_DIM,
                text = value { "Target" },
            )
        }
        box(modifier = Modifier.size(IntSize(width, INV_TARGET_SECTION_HEIGHT))) {
            canvas {
                val slotX = (width - WorkbenchInventoryLayout.SLOT_SIZE) / 2
                val slotY = (INV_TARGET_SECTION_HEIGHT - WorkbenchInventoryLayout.SLOT_SIZE) / 2
                fillRect(
                    slotX,
                    slotY,
                    WorkbenchInventoryLayout.SLOT_SIZE,
                    WorkbenchInventoryLayout.SLOT_SIZE,
                    BG_SLOT,
                )
            }
        }
        box(modifier = Modifier.size(IntSize(width, SIDEBAR_HEADER_HEIGHT)).background(BG_HEADER)) {
            text(
                modifier = Modifier.align(UiAlignment.Center),
                color = TEXT_DIM,
                text = value { "Inventory" },
            )
        }
        box(
            modifier =
                Modifier.size(
                    IntSize(
                        width,
                        WorkbenchInventoryLayout.SLOT_SIZE * 3 +
                            WorkbenchInventoryLayout.INV_HOTBAR_GAP +
                            WorkbenchInventoryLayout.SLOT_SIZE,
                    ),
                ),
        ) {
            canvas {
                val gridStartX = (width - WorkbenchInventoryLayout.SLOT_SIZE * 9) / 2
                // 3×9 main inventory grid.
                for (rowIdx in 0 until 3) {
                    for (colIdx in 0 until 9) {
                        fillRect(
                            gridStartX + colIdx * WorkbenchInventoryLayout.SLOT_SIZE,
                            rowIdx * WorkbenchInventoryLayout.SLOT_SIZE,
                            WorkbenchInventoryLayout.SLOT_SIZE,
                            WorkbenchInventoryLayout.SLOT_SIZE,
                            BG_SLOT,
                        )
                    }
                }
                val hotbarY =
                    WorkbenchInventoryLayout.SLOT_SIZE * 3 + WorkbenchInventoryLayout.INV_HOTBAR_GAP
                for (colIdx in 0 until 9) {
                    fillRect(
                        gridStartX + colIdx * WorkbenchInventoryLayout.SLOT_SIZE,
                        hotbarY,
                        WorkbenchInventoryLayout.SLOT_SIZE,
                        WorkbenchInventoryLayout.SLOT_SIZE,
                        BG_SLOT,
                    )
                }
            }
        }
    }
}

private fun UiScope.completionRow(
    width: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    box(
        modifier =
            Modifier
                .size(IntSize(width, COMPLETION_ROW_HEIGHT))
                .background(if (selected) BG_ROW_SELECTED else BG_TRANSPARENT)
                .clickable(onClick),
    ) {
        text(
            modifier = Modifier.align(UiAlignment.Center),
            color = TEXT_LIGHT,
            text = value { label },
        )
    }
}

private fun completionAnchor(
    viewport: IntSize,
    popupWidth: Int,
    popupHeight: Int,
): Position {
    // Cheap, deterministic placement — under the toolbar, indented past the
    // sidebar so it visually attaches to the editor area.
    val x = (SIDEBAR_WIDTH + 16).coerceAtMost((viewport.width - popupWidth).coerceAtLeast(0))
    val y = (TOOLBAR_HEIGHT + 24).coerceAtMost((viewport.height - popupHeight).coerceAtLeast(0))
    return Position(x, y)
}

// ---------------------------------------------------------------------------
// Sizing constants
// ---------------------------------------------------------------------------

private const val TOOLBAR_HEIGHT = 22
private const val STATUS_HEIGHT = 14
private const val SIDEBAR_WIDTH = 140
private const val SIDEBAR_HEADER_HEIGHT = 14
private const val SIDEBAR_ROW_HEIGHT = 12
private const val TERMINAL_PANEL_HEIGHT = 160
private const val TERMINAL_HEADER_HEIGHT = 12
private const val COMPLETION_POPUP_WIDTH = 200
private const val COMPLETION_ROW_HEIGHT = 12
private const val COMPLETION_POPUP_PADDING = 2
private const val MAX_COMPLETION_ROWS = 8
private const val IMPORT_POPUP_WIDTH = 240
private const val IMPORT_HEADER_HEIGHT = 14
private const val MAX_IMPORT_ROWS = 10
private const val DEFAULT_VISIBLE_EDITOR_LINES = 32
private const val INV_TARGET_SECTION_HEIGHT = 22

// ---------------------------------------------------------------------------
// Palette
// ---------------------------------------------------------------------------

private val BG_MAIN = Color.hex(0xFF0B0E14.toInt())
private val BG_HEADER = Color.hex(0xFF161B25.toInt())
private val BG_SIDEBAR = Color.hex(0xFF1D2330.toInt())
private val BG_EDITOR = Color.hex(0xFF0D1016.toInt())
private val BG_TERMINAL = Color.hex(0xFF101823.toInt())
private val BG_BUTTON = Color.hex(0xFF222938.toInt())
private val BG_BUTTON_HOVER = Color.hex(0xFF2C3445.toInt())
private val BG_BUTTON_ACTIVE = Color.hex(0xFF35516B.toInt())
private val BG_INVENTORY = Color.hex(0xFF1A2030.toInt())
private val BG_SLOT = Color.hex(0xFF080A10.toInt())
private val BG_POPUP = Color.hex(0xEE11151E.toInt())
private val BG_IMPORT_POPUP = Color.hex(0xF0121721.toInt())
private val BG_ROW_SELECTED = Color.hex(0x664883C7)
private val BG_TRANSPARENT = Color.hex(0x00000000)

private val TEXT_LIGHT = Color.hex(0xFFE6ECF5.toInt())
private val TEXT_DIM = Color.hex(0xFFBFD5E8.toInt())
private val TEXT_ACCENT = Color.hex(0xFFE0A96D.toInt())

private val EMPTY_TERMINAL_SNAPSHOT =
    ScreenBufferSnapshot(
        width = 0,
        height = 0,
        colour = false,
        cursorX = 0,
        cursorY = 0,
        cursorBlink = false,
        currentFg = 0,
        currentBg = 0,
        chars = CharArray(0),
        fgColours = ByteArray(0),
        bgColours = ByteArray(0),
    )
