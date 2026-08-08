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

package ru.lazyhat.compukterkraft.impl

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class K16StorageDurabilityArchitectureTest {
    @Test
    fun guestStorageExposesDeviceNeutralFlushCommand() {
        val source = Path.of("../../../guest/kraftos/kernel/src/kfs/device.rs").readText()

        assertTrue(source.contains("pub unsafe fn flush_storage(device: KfsDevice)"))
        assertTrue(source.contains("device.register(storage::COMMAND_OFFSET)"))
        assertTrue(source.contains("device.register(storage::STATUS_OFFSET)"))
        assertTrue(source.contains("device.register(storage::ERROR_OFFSET)"))
    }

    @Test
    fun kernelFlushesRootStorageAfterSuccessfulMutations() {
        val source = Path.of("../../../guest/kraftos/kernel/src/fs.rs").readText()

        assertTrue(source.contains("unsafe fn flush_root_storage()"))
        for (functionName in MUTATING_ROOT_FS_FUNCTIONS) {
            val body = functionBody(source, functionName)
            assertTrue(
                body.contains("flush_root_storage()?"),
                "$functionName must flush storage0 after successful KFS mutation.",
            )
        }
    }

    private fun functionBody(
        source: String,
        functionName: String,
    ): String {
        val signature = "pub unsafe fn $functionName"
        val signatureStart = source.indexOf(signature)
        require(signatureStart >= 0) { "Missing function $functionName" }
        val bodyStart = source.indexOf('{', signatureStart)
        require(bodyStart >= 0) { "Missing body for $functionName" }

        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return source.substring(bodyStart, index + 1)
                    }
                }
            }
        }
        error("Unterminated body for $functionName")
    }

    private companion object {
        private val MUTATING_ROOT_FS_FUNCTIONS =
            listOf(
                "open_root_file_for_process",
                "remove_root_file_for_process",
                "rename_root_file_for_process",
                "create_root_directory",
                "remove_root_directory",
                "copy_ram_to_file_fd_range_for_process",
            )
    }
}
