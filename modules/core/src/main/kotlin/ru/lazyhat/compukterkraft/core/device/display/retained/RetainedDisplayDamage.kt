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

package ru.lazyhat.compukterkraft.core.device.display.retained

data class RetainedPatchRectangle(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class RetainedPatchRange(
    val first: Int,
    val count: Int,
)

sealed interface RetainedDisplayInstallDamage {
    data object FullReplacement : RetainedDisplayInstallDamage

    data class Delta(
        val resourceChanges: List<RetainedResourceDamage>,
        val drawListReplaced: Boolean,
    ) : RetainedDisplayInstallDamage
}

sealed interface RetainedResourceDamage {
    val resourceId: UInt
    val localIdentity: Long

    data class Created(
        override val resourceId: UInt,
        override val localIdentity: Long,
    ) : RetainedResourceDamage

    data class Dropped(
        override val resourceId: UInt,
        override val localIdentity: Long,
    ) : RetainedResourceDamage

    data class ImagePatched(
        override val resourceId: UInt,
        override val localIdentity: Long,
        val rectangles: List<RetainedPatchRectangle>,
    ) : RetainedResourceDamage

    data class MaskPatched(
        override val resourceId: UInt,
        override val localIdentity: Long,
        val rectangles: List<RetainedPatchRectangle>,
    ) : RetainedResourceDamage

    data class InstancesPatched(
        override val resourceId: UInt,
        override val localIdentity: Long,
        val ranges: List<RetainedPatchRange>,
    ) : RetainedResourceDamage
}
