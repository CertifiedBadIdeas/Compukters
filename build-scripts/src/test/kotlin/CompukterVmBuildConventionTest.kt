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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class CompukterVmBuildConventionTest {
    @Test
    fun neutralVmHasHostRustVerificationWithoutNativePackaging() {
        val rootBuildScript = rootBuildSource().readText()

        assertTrue(rootBuildScript.contains("testCompukterVmRust"))
        assertTrue(rootBuildScript.contains("host/compukter-vm"))
        assertTrue(rootBuildScript.contains("Compukter-VM submodule"))
        assertTrue(rootBuildScript.contains("host/compukter-vm/benchmarks"))
        assertTrue(rootBuildScript.contains("host/compukter-vm/scripts"))
        assertTrue(rootBuildScript.contains("host/compukter-vm/fixtures"))
        assertTrue(rootBuildScript.contains("dependsOn(testCompukterVmRust)"))
        assertFalse(rootBuildScript.contains("libcompukter_vm"))
        assertFalse(rootBuildScript.contains("buildCompukterVmNativeLibrary"))
    }

    private fun rootBuildSource(): Path {
        val candidates =
            listOf(
                Path.of(System.getProperty("user.dir"), "..", "build.gradle.kts").normalize(),
                Path.of(System.getProperty("user.dir"), "build.gradle.kts"),
            )
        return candidates.firstOrNull(Files::exists)
            ?: error("Could not locate root build.gradle.kts from ${System.getProperty("user.dir")}")
    }
}
