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

import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchMode
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStore
import ru.lazyhat.compukterkraft.core.ui.editor.EditorViewModel
import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.IntSize
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.foundation.Value
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.background
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.clickable
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.size
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.weight
import ru.lazyhat.compukterkraft.core.ui.foundation.ui
import ru.lazyhat.compukterkraft.core.ui.foundation.value
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

/**
 * Builds the entire Workbench UI tree from a [WorkbenchStore].
 *
 * The tree is *re-evaluated lazily* through [Value] expressions for every
 * dynamic part (button labels, file path text, dirty-marker, etc.), so the
 * caller does not need to recompile on every mutation. Structurally-dynamic
 * parts (the workspace entry list and overlay popups) are snapshotted at
 * call time; the host screen is responsible for invalidating + re-calling
 * this builder when those snapshots change.
 *
 * @param viewport overall screen size (width × height) in pixels.
 * @param viewModel the editor view-model fed to [UiElement.CodeEditor].
 * @param terminalSnapshot supplier called every render tick; returning
 *   `null` keeps the embedded terminal panel showing the last known state.
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
    val toolbarHeight = 24
    val statusHeight = 14
    val sidebarWidth = 120
    val terminalWidth = 200
    val mainHeight = viewport.height - toolbarHeight - statusHeight

    return ui(modifier = Modifier.size(viewport).background(BG_MAIN)) {
        column(modifier = Modifier.size(viewport)) {
            // ===== Toolbar =====
            row(modifier = Modifier.size(IntSize(viewport.width, toolbarHeight)).background(BG_HEADER)) {
                toolbarButton(
                    label =
                        value {
                            if (store.state.mode == WorkbenchMode.TERMINAL) "IDE" else "Console"
                        },
                    onClick = { store.toggleMode() },
                )
                toolbarButton(label = value { "Save" }, onClick = { store.saveDocument() })
                toolbarButton(label = value { "Pull" }, onClick = { store.pullFromTarget() })
                toolbarButton(label = value { "Push" }, onClick = { store.pushToTarget() })
                toolbarButton(label = value { "Run" }, onClick = { store.runTargetProgram() })
                toolbarButton(label = value { "Imports" }, onClick = { store.openImportPicker() })
                box(modifier = Modifier.weight(1f))
                toolbarButton(
                    label = value { if (store.state.terminalVisible) "Hide" else "Terminal" },
                    onClick = { store.toggleTerminalVisibility() },
                )
                toolbarButton(label = value { "Reboot" }, onClick = { store.rebootComputer() })
            }

            // ===== Main area: sidebar | editor | (optional) terminal =====
            row(modifier = Modifier.size(IntSize(viewport.width, mainHeight))) {
                // -- Sidebar (file browser) --
                column(
                    modifier = Modifier.size(IntSize(sidebarWidth, mainHeight)).background(BG_SIDEBAR),
                ) {
                    text(
                        modifier = Modifier.size(IntSize(sidebarWidth - 8, 12)),
                        color = Color.hex(0xFFBFD5E8.toInt()),
                        text = value { "/" + store.state.browserPath },
                    )
                    scrollArea(
                        modifier = Modifier.size(IntSize(sidebarWidth, mainHeight - 14)),
                        scrollY = value { 0 },
                    ) {
                        // Parent-directory link if not at root.
                        if (store.state.browserPath.isNotEmpty()) {
                            sidebarRow(
                                width = sidebarWidth,
                                label = value { ".." },
                                onClick = { store.navigateUp() },
                            )
                        }
                        store.state.entries.forEach { entry ->
                            val displayLabel = if (entry.directory) "${entry.path}/" else entry.path
                            sidebarRow(
                                width = sidebarWidth,
                                label = value { displayLabel },
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

                // -- Code editor (weight = 1) --
                box(modifier = Modifier.weight(1f).background(BG_EDITOR)) {
                    codeEditor(
                        viewModel = value { viewModel },
                        modifier =
                            Modifier.size(
                                IntSize(viewport.width - sidebarWidth - terminalAreaWidth(store, terminalWidth), mainHeight),
                            ),
                        fontWidth = 6,
                        fontHeight = 9,
                    )
                }

                // -- Terminal panel (only when visible) --
                If(condition = value { store.state.terminalVisible }) {
                    column(
                        modifier = Modifier.size(IntSize(terminalWidth, mainHeight)).background(BG_TERMINAL),
                    ) {
                        terminalSurface(
                            modifier = Modifier.size(IntSize(terminalWidth, mainHeight)),
                            snapshot =
                                value {
                                    terminalSnapshot() ?: EMPTY_TERMINAL_SNAPSHOT
                                },
                            onKey = onTerminalKey,
                            onKeyReleased = onTerminalKeyReleased,
                            onCharTyped = onTerminalCharTyped,
                        )
                    }
                }
            }

            // ===== Status bar =====
            row(modifier = Modifier.size(IntSize(viewport.width, statusHeight)).background(BG_HEADER)) {
                text(
                    modifier = Modifier.size(IntSize(viewport.width / 2, statusHeight)),
                    color = Color.hex(0xFFE6ECF5.toInt()),
                    text =
                        value {
                            val ed = store.state.editor
                            val path = store.state.openDocument?.path ?: "No file opened"
                            val prefix = if (ed.dirty) "* " else ""
                            "$prefix$path  L${ed.cursorLine + 1}:C${ed.cursorColumn + 1}"
                        },
                )
                box(modifier = Modifier.weight(1f))
                text(
                    modifier = Modifier.size(IntSize(viewport.width / 2 - 8, statusHeight)),
                    color = Color.hex(0xFFE0A96D.toInt()),
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

        // ===== Completion overlay =====
        overlay(
            modifier = Modifier.size(IntSize(180, 12 * 8 + 4)),
            visible =
                value {
                    store.state.editor.completionItems
                        .isNotEmpty()
                },
        ) {
            box(modifier = Modifier.size(IntSize(180, 12 * 8 + 4)).background(Color.hex(0xEE11151E.toInt()))) {
                column(modifier = Modifier.size(IntSize(180, 12 * 8 + 4))) {
                    store.state.editor.completionItems.take(8).forEachIndexed { idx, item ->
                        completionRow(
                            label = item.label,
                            onClick = { store.applyCompletion(idx) },
                        )
                    }
                }
            }
        }

        // ===== Import-picker overlay =====
        overlay(
            modifier = Modifier.size(IntSize(220, 14 * 10 + 18)),
            visible = value { store.state.editor.importPickerVisible },
        ) {
            box(modifier = Modifier.size(IntSize(220, 14 * 10 + 18)).background(Color.hex(0xF0121721.toInt()))) {
                column(modifier = Modifier.size(IntSize(220, 14 * 10 + 18))) {
                    box(modifier = Modifier.size(IntSize(220, 14)).background(Color.hex(0xFF1F2937.toInt()))) {
                        text(
                            color = Color.hex(0xFFF5F7FA.toInt()),
                            text = value { "Available imports" },
                        )
                    }
                    store.state.editor.importPickerItems.take(10).forEachIndexed { idx, item ->
                        completionRow(
                            label = item.label,
                            onClick = { store.applyImportPickerSelection(idx, visibleEditorLines = 32) },
                        )
                    }
                }
            }
        }
    }
}

private fun ru.lazyhat.compukterkraft.core.ui.foundation.UiScope.toolbarButton(
    label: Value<String>,
    onClick: () -> Unit,
) {
    button(
        modifier = Modifier.size(IntSize(64, 20)).background(BG_BUTTON),
        onClick = onClick,
    ) {
        text(color = Color.hex(0xFFE6ECF5.toInt()), text = label)
    }
}

private fun ru.lazyhat.compukterkraft.core.ui.foundation.UiScope.sidebarRow(
    width: Int,
    label: Value<String>,
    onClick: () -> Unit,
) {
    box(
        modifier =
            Modifier
                .size(IntSize(width - 4, 11))
                .clickable(onClick),
    ) {
        text(color = Color.hex(0xFFE6ECF5.toInt()), text = label)
    }
}

private fun ru.lazyhat.compukterkraft.core.ui.foundation.UiScope.completionRow(
    label: String,
    onClick: () -> Unit,
) {
    box(modifier = Modifier.size(IntSize(220, 12)).clickable(onClick)) {
        text(color = Color.hex(0xFFF5F7FA.toInt()), text = value { label })
    }
}

private fun terminalAreaWidth(
    store: WorkbenchStore,
    terminalWidth: Int,
): Int = if (store.state.terminalVisible) terminalWidth else 0

// Backgrounds shared between zones.
private val BG_MAIN = Color.hex(0xFF0B0E14.toInt())
private val BG_HEADER = Color.hex(0xFF161B25.toInt())
private val BG_SIDEBAR = Color.hex(0xFF1D2330.toInt())
private val BG_EDITOR = Color.hex(0xFF0D1016.toInt())
private val BG_TERMINAL = Color.hex(0xFF101823.toInt())
private val BG_BUTTON = Color.hex(0xFF222938.toInt())

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
