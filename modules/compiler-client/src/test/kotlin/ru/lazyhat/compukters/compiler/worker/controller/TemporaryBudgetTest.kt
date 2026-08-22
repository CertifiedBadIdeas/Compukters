/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.compiler.worker.controller

import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporaryBudgetTest {
    @Test
    fun `request result is copied before exact request directory cleanup`() {
        val root = createTempDirectory("compukters-temporary-budget-")
        val outside = root.resolve("keep.txt").also { it.writeText("keep") }
        val budget = TemporaryBudget(root, WorkerLimits(temporaryFiles = 2, temporaryBytes = 8))

        val copied =
            budget.useRequestDirectory { request ->
                request.resolve("one.bin").writeBytes(byteArrayOf(1, 2, 3))
                request.resolve("two.bin").writeBytes(byteArrayOf(4, 5))
                Files.readAllBytes(request.resolve("one.bin"))
            }

        assertEquals(listOf<Byte>(1, 2, 3), copied.toList())
        assertTrue(Files.exists(outside))
        assertEquals(listOf(outside), Files.list(root).use { it.toList() })
        root.toFile().deleteRecursively()
    }

    @Test
    fun `file count byte overflow and symlinks are rejected and cleaned`() {
        val root = createTempDirectory("compukters-temporary-overflow-")
        val budget = TemporaryBudget(root, WorkerLimits(temporaryFiles = 1, temporaryBytes = 3))

        assertFailsWith<TemporaryBudgetException> {
            budget.useRequestDirectory { request -> request.resolve("large.bin").writeBytes(ByteArray(4)) }
        }
        assertTrue(Files.list(root).use { it.findAny().isEmpty })

        assertFailsWith<TemporaryBudgetException> {
            budget.useRequestDirectory { request ->
                request.resolve("first").writeText("a")
                request.resolve("second").writeText("b")
            }
        }
        assertTrue(Files.list(root).use { it.findAny().isEmpty })

        val outside = root.resolve("outside").also { it.writeText("safe") }
        assertFailsWith<TemporaryBudgetException> {
            budget.useRequestDirectory { request -> Files.createSymbolicLink(request.resolve("link"), outside) }
        }
        assertTrue(Files.exists(outside))
        assertFalse(Files.isSymbolicLink(outside))
        root.toFile().deleteRecursively()
    }

    @Test
    fun `temporary file count includes directories below the request root`() {
        val root = createTempDirectory("compukters-temporary-directories-")
        val budget = TemporaryBudget(root, WorkerLimits(temporaryFiles = 0))

        assertFailsWith<TemporaryBudgetException> {
            budget.useRequestDirectory { request -> request.resolve("source").createDirectories() }
        }

        assertTrue(Files.list(root).use { it.findAny().isEmpty })
        root.toFile().deleteRecursively()
    }
}
