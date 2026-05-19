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
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureBoundaryTest {
    private val forbiddenMinecraftImportPattern = Regex("""^\s*import\s+net\.minecraft\.""")

    @Test
    fun containsForbiddenMinecraftImportMatchesKotlinImportStatements() {
        assertTrue(containsForbiddenMinecraftImport("import    net.minecraft.world.level.Level"))
    }

    @Test
    fun containsForbiddenMinecraftImportIgnoresNonImportLines() {
        assertFalse(containsForbiddenMinecraftImport("val importText = \"import net.minecraft.world.level.Level\""))
    }

    @Test
    fun coreMainSourcesDoNotImportMinecraftPackages() {
        val coreSourcesRoot = findRepoRoot().resolve("modules/core/src/main/kotlin")
        assertTrue(
            Files.isDirectory(coreSourcesRoot),
            "Expected core source directory at $coreSourcesRoot before scanning for forbidden imports.",
        )

        val offenders =
            kotlinFilesUnder(coreSourcesRoot).filter { file ->
                Files.readAllLines(file).any(::containsForbiddenMinecraftImport)
            }

        assertTrue(
            offenders.isEmpty(),
            "Core sources must not import net.minecraft packages: ${offenders.joinToString()}",
        )
    }

    private fun findRepoRoot(): Path {
        val startingPaths = listOfNotNull(testClassLocationPathOrNull(), Path.of(System.getProperty("user.dir")).toAbsolutePath())

        for (startingPath in startingPaths.distinct()) {
            var current = startingPath.toAbsolutePath()

            while (true) {
                if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                    return current
                }

                current = current.parent ?: break
            }
        }

        error("Could not locate settings.gradle.kts starting from ${startingPaths.joinToString()}")
    }

    private fun containsForbiddenMinecraftImport(line: String): Boolean = forbiddenMinecraftImportPattern.containsMatchIn(line)

    // Some test runners can omit a usable codeSource location, so user.dir remains the fallback.
    private fun testClassLocationPathOrNull(): Path? =
        runCatching {
            Path
                .of(
                    ArchitectureBoundaryTest::class.java.protectionDomain.codeSource.location
                        .toURI(),
                ).toAbsolutePath()
        }.getOrNull()

    @Test
    fun loaderLeafModulesContainOnlyAllowedMainSourceFiles() {
        val repoRoot = findRepoRoot()
        val allowedFiles =
            mapOf(
                "modules/v1_20_1/v1_20_1-fabric/src/main/kotlin" to
                    setOf(
                        "ru/lazyhat/compukterkraft/impl/ClientRegistry.kt",
                        "ru/lazyhat/compukterkraft/impl/CompukterKraftClientMod.kt",
                        "ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt",
                        "ru/lazyhat/compukterkraft/impl/Extensions.kt",
                        "ru/lazyhat/compukterkraft/impl/FabricCommonHooks.kt",
                        "ru/lazyhat/compukterkraft/impl/ModRegistry.kt",
                        "ru/lazyhat/compukterkraft/impl/platform/NetworkHandler.kt",
                    ),
                "modules/v1_20_1/v1_20_1-forge/src/main/kotlin" to
                    setOf(
                        "ru/lazyhat/compukterkraft/impl/ClientRegistry.kt",
                        "ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt",
                        "ru/lazyhat/compukterkraft/impl/Extensions.kt",
                        "ru/lazyhat/compukterkraft/impl/ForgeClientHooks.kt",
                        "ru/lazyhat/compukterkraft/impl/ForgeClientRegistry.kt",
                        "ru/lazyhat/compukterkraft/impl/ForgeCommonHooks.kt",
                        "ru/lazyhat/compukterkraft/impl/ModRegistry.kt",
                        "ru/lazyhat/compukterkraft/impl/block/ForgeComputerBlockEntity.kt",
                        "ru/lazyhat/compukterkraft/impl/platform/NetworkHandler.kt",
                    ),
                "modules/v1_21_1/v1_21_1-fabric/src/main/kotlin" to
                    setOf(
                        "ru/lazyhat/compukterkraft/impl/ClientRegistry.kt",
                        "ru/lazyhat/compukterkraft/impl/CompukterKraftClientMod.kt",
                        "ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt",
                        "ru/lazyhat/compukterkraft/impl/Extensions.kt",
                        "ru/lazyhat/compukterkraft/impl/FabricCommonHooks.kt",
                        "ru/lazyhat/compukterkraft/impl/ModRegistry.kt",
                        "ru/lazyhat/compukterkraft/impl/platform/NetworkHandler.kt",
                    ),
                "modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin" to
                    setOf(
                        "ru/lazyhat/compukterkraft/impl/ClientRegistry.kt",
                        "ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt",
                        "ru/lazyhat/compukterkraft/impl/Extensions.kt",
                        "ru/lazyhat/compukterkraft/impl/ForgeClientHooks.kt",
                        "ru/lazyhat/compukterkraft/impl/ForgeClientRegistry.kt",
                        "ru/lazyhat/compukterkraft/impl/ForgeCommonHooks.kt",
                        "ru/lazyhat/compukterkraft/impl/ModRegistry.kt",
                        "ru/lazyhat/compukterkraft/impl/computer/block/NeoForgeComputerBlockEntity.kt",
                        "ru/lazyhat/compukterkraft/impl/notebook/block/NeoForgeNotebookBlockEntity.kt",
                        "ru/lazyhat/compukterkraft/impl/notebook/item/NeoForgeNotebookItem.kt",
                        "ru/lazyhat/compukterkraft/impl/notebook/render/NotebookBlockEntityRenderer.kt",
                        "ru/lazyhat/compukterkraft/impl/notebook/render/NotebookGeoModel.kt",
                        "ru/lazyhat/compukterkraft/impl/notebook/render/NotebookItemGeoModel.kt",
                        "ru/lazyhat/compukterkraft/impl/notebook/render/NotebookItemRenderer.kt",
                        "ru/lazyhat/compukterkraft/impl/platform/NetworkHandler.kt",
                    ),
            )

        val violations =
            allowedFiles.flatMap { (modulePath, allowed) ->
                val root = repoRoot.resolve(modulePath)
                if (!Files.isDirectory(root)) return@flatMap emptyList()
                kotlinFilesUnder(root)
                    .map { file -> root.relativize(file).invariantSeparatorsPathString }
                    .sorted()
                    .filterNot { relative -> relative in allowed }
                    .map { relative -> "$modulePath/$relative" }
            }

        assertTrue(
            violations.isEmpty(),
            "loader leaf modules must stay thin, unexpected files found: ${violations.joinToString()}",
        )
    }

    private fun kotlinFilesUnder(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
                .toList()
        }
}
