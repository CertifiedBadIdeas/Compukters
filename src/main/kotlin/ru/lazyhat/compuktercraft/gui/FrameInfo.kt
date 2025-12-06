// Copyright Daniel Ratcliffe, 2011-2022. Do not distribute without permission.
//
// SPDX-License-Identifier: LicenseRef-CCPL
package ru.lazyhat.compuktercraft.gui

/**
 * Tracks the current client-side tick and frame.
 *
 *
 * These are updated via [ClientHooks].
 */
object FrameInfo {
	private var tick = 0
	var renderFrame: Long = 0
		private set

	val globalCursorBlink: Boolean
		get() = (tick / 8) % 2 == 0

	fun onTick() {
		tick++
	}

	fun onRenderTick() {
		renderFrame++
	}
}
