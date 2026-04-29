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

import ru.lazyhat.compukterkraft.core.computer.workbench.EditorPresence
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.SiteId

/**
 * Compute the on-screen pixel X-coordinate of a caret sitting at [column] of [textLine],
 * using [measure] to translate text-prefix lengths to pixel widths.
 *
 * Local rendering (in [GuiGraphicsRenderBackend.drawCodeEditor]) places the caret at
 * `gutter + font.width(prefix)`. The remote-caret overlay must use the *same* prefix-width
 * formula or peers will appear drifted by the cumulative deviation between fixed-width
 * `column * fontWidth` and Minecraft's variable-width font metrics.
 *
 * Pure helper (no UI deps) so it can be unit-tested with synthetic measurers.
 */
fun remoteCaretPixelX(
    textLine: String,
    column: Int,
    leftPad: Int,
    gutter: Int,
    measure: (String) -> Int,
): Int {
    val safeColumn = column.coerceIn(0, textLine.length)
    val prefix = textLine.substring(0, safeColumn)
    return leftPad + gutter + measure(prefix)
}

/**
 * Drop the recipient's own [EditorPresence] from a server-broadcast presence list. The
 * recipient already knows their own cursor/path and rendering it as a "remote" caret causes
 * (a) the file-tree counter to read 1 for the local file even when nobody else is editing,
 * (b) a duplicate caret bar painted on top of the local one.
 *
 * Filtering server-side avoids depending on perfect [SiteId] symmetry between client and
 * server (e.g. UUID derivation discrepancies in development environments).
 */
fun presencesForRecipient(
    presences: List<EditorPresence>,
    recipientSite: SiteId,
): List<EditorPresence> = presences.filter { it.siteId != recipientSite }
