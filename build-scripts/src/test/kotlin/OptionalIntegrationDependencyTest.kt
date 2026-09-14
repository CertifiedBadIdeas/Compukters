/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.invariantSeparatorsPathString

class OptionalIntegrationDependencyTest {
    @Test
    fun commonCodeAndBuildConventionsDoNotDependOnCreate() {
        val repoRoot = findRepoRoot()
        val roots = listOf(repoRoot.resolve("modules/common"), repoRoot.resolve("build-scripts/src/main"))
        val forbidden = listOf("create.kinetics", "v1_21_1-create", "v1211Create", "CREATE_KINETICS")
        val offenders =
            roots.flatMap { root ->
                Files.walk(root).use { paths ->
                    paths
                        .filter(Files::isRegularFile)
                        .filter { path -> !repoRoot.relativize(path).invariantSeparatorsPathString.contains("/build/") }
                        .filter { path -> forbidden.any(Files.readString(path)::contains) }
                        .map { path -> repoRoot.relativize(path).invariantSeparatorsPathString }
                        .toList()
                }
            }.sorted()

        assertTrue(offenders.isEmpty(), "Common code depends on the optional Create integration: ${offenders.joinToString()}")
    }

    @Test
    fun createAddonIsAStandaloneMavenConsumer() {
        val repoRoot = findRepoRoot()
        val rootSettings = Files.readString(repoRoot.resolve("settings.gradle.kts"))
        val addonRoot = repoRoot.resolve("addons/create")
        val addonBuildFiles = listOf(addonRoot.resolve("settings.gradle.kts"), addonRoot.resolve("build.gradle.kts"))
        val forbidden = listOf("projects.", "project(\"", "includeBuild(", "../../", "build-scripts")

        assertTrue(Files.isRegularFile(addonRoot.resolve("settings.gradle.kts")), "Create addon must own its Gradle settings")
        assertTrue("create-addon" !in rootSettings, "The Compukters root must not include the Create addon as a subproject")
        addonBuildFiles.forEach { buildFile ->
            val content = Files.readString(buildFile)
            assertTrue(
                forbidden.none(content::contains),
                "${repoRoot.relativize(buildFile)} crosses the standalone addon boundary",
            )
        }
    }

    @Test
    fun createAddonPinsTheSdkGradleToolchain() {
        val repoRoot = findRepoRoot()
        val addonRoot = repoRoot.resolve("addons/create")
        val addonWrapper = addonRoot.resolve("gradle/wrapper/gradle-wrapper.properties")
        val wrapperFiles =
            listOf(
                addonRoot.resolve("gradlew"),
                addonRoot.resolve("gradlew.bat"),
                addonRoot.resolve("gradle/wrapper/gradle-wrapper.jar"),
                addonWrapper,
            )

        assertTrue(
            wrapperFiles.all(Files::isRegularFile),
            "The standalone Create addon must own a complete Gradle wrapper",
        )
        assertEquals(
            wrapperDistribution(repoRoot.resolve("gradle/wrapper/gradle-wrapper.properties")),
            wrapperDistribution(addonWrapper),
            "The standalone Create addon must use the Gradle distribution against which the addon SDK is built",
        )
    }

    private fun wrapperDistribution(path: Path): String =
        Properties()
            .apply { Files.newInputStream(path).use(::load) }
            .getProperty("distributionUrl")
            ?: error("Missing distributionUrl in $path")

    private fun findRepoRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (true) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts")) && Files.isDirectory(current.resolve("modules"))) {
                return current
            }
            current = current.parent ?: error("Could not locate repository root")
        }
    }
}
