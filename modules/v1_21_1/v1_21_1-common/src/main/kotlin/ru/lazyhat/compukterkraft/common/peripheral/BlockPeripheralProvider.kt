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

package ru.lazyhat.compukterkraft.common.peripheral

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import ru.lazyhat.compukterkraft.core.computer.vm.api.PeripheralMethods
import ru.lazyhat.compukterkraft.core.computer.vm.api.VmPeripheralDevice

/**
 * Result of resolving a Minecraft block adjacent to a computer into a peripheral device.
 *
 * The descriptor pairs the neutral [VmPeripheralDevice] (already known to core) with the optional
 * [methods] surface contributed by the addon. Keeping these two pieces together at the boundary
 * lets [BlockPeripheralRegistry] hand them to the runtime as one unit, while addons stay free to
 * implement [methods] using whatever mod APIs they need.
 */
data class BlockPeripheralDescriptor(
    val device: VmPeripheralDevice,
    val methods: PeripheralMethods = PeripheralMethods.NONE,
)

/**
 * Lookup context handed to [BlockPeripheralProvider]s by the registry.
 *
 * The level/pos/side are exposed through an interface (rather than as direct parameters) so that
 *  - addons receive a single self-describing object that can be extended with extra fields later
 *    without breaking the SPI;
 *  - tests can supply a stub whose [level] is never dereferenced.
 */
interface BlockPeripheralContext {
    val level: Level
    val pos: BlockPos
    val side: Direction
}

/**
 * Single-method SPI an addon implements to expose Minecraft blocks as peripherals.
 *
 * Implementations must:
 *  - return null when the block at (level, pos, side) is not relevant to this addon;
 *  - never throw on absent target mods — guarding happens in the addon's bootstrap, not here;
 *  - never leak mod-specific types through the returned [BlockPeripheralDescriptor].
 */
fun interface BlockPeripheralProvider {
    fun provide(context: BlockPeripheralContext): BlockPeripheralDescriptor?
}
