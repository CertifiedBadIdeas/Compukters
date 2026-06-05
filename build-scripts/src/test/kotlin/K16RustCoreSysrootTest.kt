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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

class K16RustCoreSysrootTest {
    private val root = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun coreSmokeDocumentationRecordsPassingSysrootPath() {
        val docs = root.resolve("docs/toolchains/k16-rust-core-smoke.md").readText()

        assertTrue(docs.contains("## Current Result"))
        assertTrue(docs.contains("K16 Rust core smoke passed"))
        assertFalse(docs.contains("known backend blocker"))
    }
}
