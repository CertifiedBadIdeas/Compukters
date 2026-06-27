/*
 * This file is part of Compukter Kraft.
 *
 * Compukter Kraft is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Compukter Kraft is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Compukter Kraft. If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.impl

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse

class K16DynamicLoaderArchitectureTest {
    @Test
    fun dynamicLoaderDoesNotReadK16eStringsByteByByteFromStorage() {
        val source = Path.of("../../../guest/kraftos/kernel/src/process.rs").readText()

        assertFalse(
            source.contains("RELOCATION_RECORD_ADDR,\n                1,"),
            "K16 dynamic loader should parse import/export string sections from RAM instead of issuing one storage read per byte.",
        )
    }
}
