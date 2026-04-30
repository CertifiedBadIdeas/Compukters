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
package ru.lazyhat.compukterkraft.core.workbench

/**
 * High-level local edit issued by the UI/keyboard layer. Translated by [WorkbenchStore] into
 * a CRDT [Op] before being applied locally and enqueued in the outbox.
 */
sealed interface LocalEdit {
    /** Insert [text] at the visible character offset [offset]. */
    data class Insert(
        val offset: Int,
        val text: String,
    ) : LocalEdit

    /** Delete [length] visible characters starting at [offset]. */
    data class Delete(
        val offset: Int,
        val length: Int,
    ) : LocalEdit
}
