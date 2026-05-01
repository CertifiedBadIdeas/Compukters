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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceWorkspaceSourceLoaderTest {
    @Test
    fun resolvesAndReadsWorkspaceDocuments() {
        val workspace =
            FakeWorkspace(
                mapOf(
                    "main.ck" to "fun main() {}",
                    "lib/math.ck" to "fun add() {}",
                    "lib/io/print.ck" to "fun p() {}",
                ),
            )
        val loader = DeviceWorkspaceSourceLoader(workspace, deviceId = 7)

        assertEquals("lib/math.ck", loader.resolve("main.ck", "lib/math.ck"))
        assertEquals("lib/math.ck", loader.resolve("lib/io/print.ck", "../math.ck"))
        assertEquals("fun add() {}", loader.read("lib/math.ck"))
        assertNull(loader.resolve("main.ck", "missing.ck"))
        assertNull(loader.resolve("main.ck", "../escape.ck"))
    }
}

private class FakeWorkspace(
    private val documents: Map<String, String>,
) : DeviceWorkspace {
    override fun list(
        deviceId: Int,
        path: String,
    ): List<DeviceWorkspaceEntry> = emptyList()

    override fun readDocument(
        deviceId: Int,
        path: String,
    ): DeviceWorkspaceDocument? = documents[path]?.let { DeviceWorkspaceDocument(path, it, version = 1) }

    override fun isDirectory(
        deviceId: Int,
        path: String,
    ): Boolean = false

    override fun writeDocument(
        deviceId: Int,
        path: String,
        text: String,
    ): DeviceWorkspaceDocument = error("not needed")

    override fun makeDirectory(
        deviceId: Int,
        path: String,
    ): Boolean = false

    override fun deleteDocument(
        deviceId: Int,
        path: String,
    ): Boolean = false
}
