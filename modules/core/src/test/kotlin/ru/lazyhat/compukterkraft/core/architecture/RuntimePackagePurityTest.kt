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

package ru.lazyhat.compukterkraft.core.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The `ru.lazyhat.compukterkraft.core.computer.runtime` package is the home of
 * [ru.lazyhat.compukterkraft.core.computer.runtime.RuntimeDevice] and its
 * canonical implementation. It must remain platform-neutral: no Minecraft
 * imports and no cross-module dependencies on `:v1_21_1-common` /
 * loader-leaf modules.
 *
 * Module boundaries already enforce most of this at Gradle level, but this
 * test documents the intent and provides a fast, deterministic signal in
 * case a future refactor accidentally adds an offending import.
 */
class RuntimePackagePurityTest {
    private val forbiddenImportPatterns =
        listOf(
            Regex("""^\s*import\s+net\.minecraft\."""),
            Regex("""^\s*import\s+ru\.lazyhat\.compukterkraft\.common\."""),
            Regex("""^\s*import\s+ru\.lazyhat\.compukterkraft\.impl\."""),
        )

    @Test
    fun runtimePackageContainsNoPlatformOrCarrierImports() {
        val runtimeRoot =
            findRepoRoot()
                .resolve("modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime")
        assertTrue(
            Files.isDirectory(runtimeRoot),
            "Expected runtime package directory at $runtimeRoot.",
        )

        val offenders = mutableListOf<Pair<Path, String>>()
        Files.walk(runtimeRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .forEach { file ->
                    Files.readAllLines(file).forEach { line ->
                        if (forbiddenImportPatterns.any { it.containsMatchIn(line) }) {
                            offenders += file to line.trim()
                        }
                    }
                }
        }

        if (offenders.isNotEmpty()) {
            val rendered = offenders.joinToString("\n") { (file, line) -> "  $file: $line" }
            fail(
                "The :core/.../runtime package must remain platform-neutral. Offending imports:\n$rendered",
            )
        }
    }

    private fun findRepoRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (true) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: error("Could not locate settings.gradle.kts from ${System.getProperty("user.dir")}")
        }
    }
}
