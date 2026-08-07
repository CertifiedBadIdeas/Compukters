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

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

class K16TinyCcBuildScriptTest {
    private val root = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun tinyCcK16SourceIsPinnedAsTheDedicatedForkSubmodule() {
        val gitmodules = root.resolve(".gitmodules").readText()

        assertTrue(gitmodules.contains("[submodule \"toolchains/Compukter-Kraft-tinycc\"]"))
        assertTrue(gitmodules.contains("url = git@github.com:CertifiedBadIdeas/Compukter-Kraft-tinycc.git"))
        assertTrue(gitmodules.contains("branch = k16"))
    }
}
