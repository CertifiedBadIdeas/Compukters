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

package ru.lazyhat.compukterkraft.lang.runtime

sealed interface VmValue {
    data object UnitValue : VmValue

    data object NullValue : VmValue

    data class BoolValue(
        val value: Boolean,
    ) : VmValue

    data class IntValue(
        val value: Int,
    ) : VmValue

    data class LongValue(
        val value: Long,
    ) : VmValue

    data class StringValue(
        val value: String,
    ) : VmValue

    data class RecordValue(
        val typeName: String,
        val fields: Map<String, VmValue>,
    ) : VmValue

    data class ObjectRef(
        val id: Int,
    ) : VmValue
}
