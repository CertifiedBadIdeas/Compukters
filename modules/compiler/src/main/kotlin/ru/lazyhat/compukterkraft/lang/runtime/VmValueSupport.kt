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

internal fun formatWorkspaceListing(entries: List<DeviceWorkspaceEntry>): String =
    entries.joinToString(" ") { entry ->
        val name = entry.path.substringAfterLast('/').ifEmpty { entry.path }
        if (entry.directory) "$name/" else name
    }

internal fun String.substringBeforeFirstSpace(): String {
    val normalized = trimStart()
    val splitIndex = normalized.indexOfFirst(Char::isWhitespace)
    return if (splitIndex == -1) normalized else normalized.substring(0, splitIndex)
}

internal fun String.substringAfterFirstSpace(): String {
    val normalized = trimStart()
    val splitIndex = normalized.indexOfFirst(Char::isWhitespace)
    if (splitIndex == -1) return ""
    return normalized.substring(splitIndex).trimStart()
}

internal fun VmValue.asBoolean(): Boolean =
    when (this) {
        is VmValue.BoolValue -> value
        else -> error("Expected Bool but got ${render()}")
    }

internal fun VmValue.asInt(): Int =
    when (this) {
        is VmValue.IntValue -> value
        is VmValue.LongValue -> value.toInt()
        else -> error("Expected Int but got ${render()}")
    }

internal fun VmValue.asLong(): Long =
    when (this) {
        is VmValue.IntValue -> value.toLong()
        is VmValue.LongValue -> value
        else -> error("Expected Long but got ${render()}")
    }

internal fun VmValue.asString(): String =
    when (this) {
        is VmValue.StringValue -> value
        VmValue.NullValue -> ""
        else -> render()
    }

internal fun VmValue.render(): String =
    when (this) {
        is VmValue.BoolValue -> value.toString()
        is VmValue.IntValue -> value.toString()
        is VmValue.LongValue -> value.toString()
        is VmValue.ObjectRef -> "object#$id"
        is VmValue.RecordValue -> "$typeName${fields.mapValues { it.value.render() }}"
        is VmValue.StringValue -> value
        VmValue.UnitValue -> "unit"
        VmValue.NullValue -> "null"
    }
