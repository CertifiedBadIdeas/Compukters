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

package ru.lazyhat.compukters.core.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertTrue

class LegacyImplementationRemovalTest {
    @Test
    fun obsoleteProductContoursAreAbsent() {
        val repoRoot = findRepoRoot()
        val forbiddenPaths =
            listOf(
                "models",
                "fixtures/ui-dsl-consumer",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/ClientHooks.kt",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/Config.kt",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/block",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/DeviceEvents.kt",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/DeviceProperties.kt",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/display",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/input",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/runtime/RuntimeDevice.kt",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/runtime/RetainedDisplaySessionTracker.kt",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/runtime/ServerThreadPublicationPump.kt",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/runtime/ports",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/vm",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/gui",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/input",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/platform",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/ui",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/utils",
                "modules/core/src/main/kotlin/ru/lazyhat/compukters/core/workbench",
                "modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/DeviceRuntime.kt",
                "modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/DeviceVmModels.kt",
                "modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/IdeStubs.kt",
                "modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/ScreenBuffer.kt",
                "modules/v1_21_1/v1_21_1-common/src/main/kotlin",
                "modules/v1_21_1/v1_21_1-common/src/test/kotlin",
                "modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/platform",
                "modules/v1_21_1/v1_21_1-neoforge/src/main/resources/META-INF/licenses/Spleen-LICENSE.txt",
                "modules/v1_21_1/v1_21_1-neoforge/src/main/resources/META-INF/services",
                "modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukters",
                "modules/v1_21_1/v1_21_1-neoforge/src/main/resources/" + "compukter" + "craft.mixins.json",
                "vendor/ui-dsl",
            )

        val present = forbiddenPaths.filter { hasMaterialContent(repoRoot.resolve(it)) }

        assertTrue(present.isEmpty(), "Obsolete product paths remain: ${present.joinToString()}")
    }

    @Test
    fun activeOwnedFilesUseTheCompuktersName() {
        val repoRoot = findRepoRoot()
        val oldNames =
            listOf(
                "compukter" + "kraft",
                "compukter" + "craft",
                "Compukter" + " Kraft",
            )
        val searchableExtensions = setOf("kt", "kts", "rs", "md", "toml", "json")

        val offenders =
            Files.walk(repoRoot).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) }
                    .filter { path -> path.fileName.toString().substringAfterLast('.', "") in searchableExtensions }
                    .filter { path ->
                        val relative = repoRoot.relativize(path).invariantSeparatorsPathString
                        !(
                            relative.startsWith(".git/") ||
                            relative.startsWith(".gradle/") ||
                            relative.startsWith(".agents/") ||
                            relative.startsWith("build/") ||
                            relative.contains("/build/") ||
                                relative.contains("/.gradle/")
                        )
                    }.filter { path ->
                        val text = Files.readString(path)
                        oldNames.any(text::contains)
                    }.map { repoRoot.relativize(it).invariantSeparatorsPathString }
                    .sorted()
                    .toList()
            }

        assertTrue(offenders.isEmpty(), "Files with obsolete project names remain: ${offenders.joinToString()}")
    }

    private fun findRepoRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (true) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: error("Could not locate repository root")
        }
    }

    private fun hasMaterialContent(path: Path): Boolean {
        if (Files.isRegularFile(path)) return true
        if (!Files.isDirectory(path)) return false
        return Files.walk(path).use { paths ->
            paths.anyMatch { candidate ->
                if (!Files.isRegularFile(candidate)) return@anyMatch false
                val relative = path.relativize(candidate).invariantSeparatorsPathString
                !(
                    relative.startsWith("build/") ||
                        relative.startsWith(".gradle/") ||
                        relative.startsWith(".gradle-sandbox/") ||
                        relative.contains("/build/") ||
                        relative.contains("/.gradle/") ||
                        relative.contains("/.gradle-sandbox/")
                )
            }
        }
    }
}
