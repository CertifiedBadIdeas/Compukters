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

package ck.mod.ui.dsl

import ck.lang.runtime.ScreenBufferSnapshot

/**
 * Stateless UI node tree — pure data describing "what to draw".
 *
 * Business logic produces a `List<UiNode>` each frame, and [UiRenderer] converts
 * them into draw calls. This separation makes the rendering logic testable without
 * Minecraft dependencies.
 */
sealed interface UiNode

/** Solid rectangle. */
data class Rect(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val color: Int,
) : UiNode

/** Left-aligned text at a fixed position. */
data class Text(
    val x: Int,
    val y: Int,
    val text: String,
    val color: Int,
    val shadow: Boolean = false,
) : UiNode

/** Right-aligned text — drawn so that its right edge touches [x] + [areaWidth]. */
data class RightAlignedText(
    val x: Int,
    val y: Int,
    val areaWidth: Int,
    val text: String,
    val color: Int,
    val shadow: Boolean = false,
) : UiNode

/** Terminal character grid — delegates to [FixedWidthFontRenderer]. */
data class TerminalView(
    val x: Int,
    val y: Int,
    val snapshot: ScreenBufferSnapshot,
) : UiNode

/** A group of child nodes (for logical grouping). */
data class Group(
    val children: List<UiNode>,
) : UiNode

