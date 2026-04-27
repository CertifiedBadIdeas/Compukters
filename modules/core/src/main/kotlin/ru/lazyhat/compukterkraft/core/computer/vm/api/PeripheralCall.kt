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

package ru.lazyhat.compukterkraft.core.computer.vm.api

/**
 * Result of a peripheral method call.
 *
 * Values returned to the VM are intentionally restricted to a small set of types that the VM
 * scripting language can reason about: numbers, strings, booleans, lists, and maps with string keys.
 * Peripheral implementations must never expose Minecraft- or mod-specific objects across this
 * boundary — that is the entire point of having an SPI in the first place.
 */
sealed interface PeripheralCallResult {
    data class Success(
        val values: List<Any?>,
    ) : PeripheralCallResult

    data class Failure(
        val message: String,
    ) : PeripheralCallResult

    companion object {
        fun success(vararg values: Any?): Success = Success(values.toList())

        fun failure(message: String): Failure = Failure(message)
    }
}

/**
 * Method-call surface contributed by a peripheral.
 *
 * A peripheral is any device the in-game computer can talk to (a Create funnel, an AE2 grid node,
 * an internal monitor, ...). Addon modules implement this interface to expose new device methods
 * without leaking their dependencies into core or the common layer.
 */
fun interface PeripheralMethods {
    fun call(
        method: String,
        args: List<Any?>,
    ): PeripheralCallResult

    companion object {
        val NONE: PeripheralMethods =
            PeripheralMethods { method, _ ->
                PeripheralCallResult.failure("Peripheral has no methods (called '$method')")
            }
    }
}
