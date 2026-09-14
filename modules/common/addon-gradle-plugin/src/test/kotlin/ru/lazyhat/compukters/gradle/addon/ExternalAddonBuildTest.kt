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

package ru.lazyhat.compukters.gradle.addon

import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalAddonBuildTest {
    @Test
    fun `standalone build resolves SDK coordinates and packages its bundle`() {
        val project = Files.createTempDirectory("compukters-external-addon-")
        try {
            val sdkVersion = requireNotNull(System.getProperty("compukters.addon.sdk.version"))
            val kotlinVersion = requireNotNull(System.getProperty("compukters.addon.kotlin.version"))
            val repository = project.resolve("repository")
            stageModule(
                repository,
                "compukters-addon-api",
                sdkVersion,
                Path.of(requireNotNull(System.getProperty("compukters.addon.test.api"))),
            )
            stageModule(
                repository,
                "compukters-addon-tooling",
                sdkVersion,
                Path.of(requireNotNull(System.getProperty("compukters.addon.test.tooling"))),
            )
            stageModule(
                repository,
                "compukters-guest-platform",
                sdkVersion,
                Path.of(requireNotNull(System.getProperty("compukters.addon.test.platform"))),
                "cpb",
            )
            stageModule(
                repository,
                "compukters-addon-neoforge-1.21.1",
                sdkVersion,
                singleJar(Path.of(requireNotNull(System.getProperty("compukters.addon.test.adapter-dir")))),
            )
            project.resolve("settings.gradle.kts").writeText(
                """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositories {
                        maven { url = uri(${repository.toUri().toString().quoted()}) }
                        mavenCentral()
                    }
                }
                rootProject.name = "external-addon"
                """.trimIndent(),
            )
            project.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    java
                    id("org.jetbrains.kotlin.jvm") version "$kotlinVersion"
                    id("ru.lazyhat.compukters.addon")
                }

                version = "2.3.4"

                compuktersAddon {
                    register("fixture")
                    toolingCoordinate.set("ru.lazyhat.compukters:compukters-addon-tooling:$sdkVersion")
                    platformCoordinate.set("ru.lazyhat.compukters:compukters-guest-platform:$sdkVersion@cpb")
                    commonApiCoordinate.set("ru.lazyhat.compukters:compukters-addon-api:$sdkVersion")
                    adapterApiCoordinate.set("ru.lazyhat.compukters:compukters-addon-neoforge-1.21.1:$sdkVersion")
                }

                tasks.register("verifyCompuktersApiClasspath") {
                    doLast {
                        val modules =
                            configurations.compileOnly.get().dependencies
                                .map { it.name }
                                .toSet()
                        check("compukters-addon-api" in modules) { "common API is missing from ${'$'}modules" }
                        check("compukters-addon-neoforge-1.21.1" in modules) { "adapter API is missing from ${'$'}modules" }
                        check("compukters-addon-api-neoforge-1.21.1" !in modules) { "legacy combined API remains in ${'$'}modules" }
                    }
                }
                """.trimIndent(),
            )
            project.resolve("src/compuktersAddon/kotlin/fixture/kinetics/Kinetics.kt").also { source ->
                source.parent.createDirectories()
                source.writeText(
                    """
                    package fixture.kinetics

                    private object Bindings {
                        external fun speed(handle: Int): Float
                    }
                    """.trimIndent(),
                )
            }

            runner(project, "updateCompuktersAddonAbiLock").build()
            runner(project, "verifyCompuktersApiClasspath").build()
            runner(project, "jar").build()

            assertTrue(project.resolve("src/compuktersAddon/addon.lock").toFile().isFile)
            assertTrue(
                project
                    .resolve("build/compukters-addon/addon.api")
                    .toFile()
                    .readText()
                    .contains("version 2.3.4"),
            )
            assertTrue(project.resolve("build/generated/sources/compuktersAddon/kotlin/GeneratedAddonHostContract.kt").toFile().isFile)
            val jar = project.resolve("build/libs/external-addon-2.3.4.jar").toFile()
            ZipFile(jar).use { zip ->
                assertEquals(1, zip.entries().asSequence().count { it.name == "META-INF/compukters/addons/fixture.cagb" })
                assertTrue(zip.entries().asSequence().none { it.name.startsWith("ru/lazyhat/compukters/") })
            }
        } finally {
            Files.walk(project).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun runner(
        project: Path,
        task: String,
    ): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(project.toFile())
            .withArguments("--stacktrace", task)
            .withPluginClasspath()
            .forwardOutput()
}

private fun singleJar(directory: Path): Path =
    Files.list(directory).use { files ->
        files
            .filter { file -> file.fileName.toString().endsWith(".jar") }
            .filter { file -> "transformProductionNeoForge" !in file.fileName.toString() }
            .toList()
            .single()
    }

private fun stageModule(
    repository: Path,
    artifact: String,
    version: String,
    source: Path,
    extension: String = "jar",
) {
    val module = repository.resolve("ru/lazyhat/compukters/$artifact/$version")
    module.createDirectories()
    Files.copy(source, module.resolve("$artifact-$version.$extension"), StandardCopyOption.REPLACE_EXISTING)
    module.resolve("$artifact-$version.pom").writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>ru.lazyhat.compukters</groupId>
          <artifactId>$artifact</artifactId>
          <version>$version</version>
        </project>
        """.trimIndent(),
    )
}

private fun String.quoted(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
