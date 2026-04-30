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
 * along with this program.  If not, see <https://www.gnu.org/licenses/\>.
 */

package ru.lazyhat.compukterkraft.lang.runtime

data class DeviceWorkspaceEntry(
    val path: String,
    val directory: Boolean,
    val size: Int = 0,
    val version: Long = 0,
)

data class DeviceWorkspaceDocument(
    val path: String,
    val text: String,
    val version: Long,
)

interface DeviceWorkspace {
    fun list(
        computerId: Int,
        path: String = "",
    ): List<DeviceWorkspaceEntry>

    fun readDocument(
        computerId: Int,
        path: String,
    ): DeviceWorkspaceDocument?

    fun isDirectory(
        computerId: Int,
        path: String,
    ): Boolean

    fun writeDocument(
        computerId: Int,
        path: String,
        text: String,
    ): DeviceWorkspaceDocument

    fun makeDirectory(
        computerId: Int,
        path: String,
    ): Boolean

    fun deleteDocument(
        computerId: Int,
        path: String,
    ): Boolean
}
