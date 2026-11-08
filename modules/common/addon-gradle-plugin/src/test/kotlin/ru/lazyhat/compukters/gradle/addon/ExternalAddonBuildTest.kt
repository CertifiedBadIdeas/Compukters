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
import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
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
                Path.of(requireNotNull(System.getProperty("compukters.addon.test.adapter"))),
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
                include("shared")
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

                dependencies {
                    add("compuktersGuestDependencies", project(":shared"))
                    testImplementation(kotlin("test"))
                    testImplementation("ru.lazyhat.compukters:compukters-addon-api:$sdkVersion")
                }
                tasks.test { useJUnitPlatform() }

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
            project.resolve("shared/build.gradle.kts").apply {
                parent.createDirectories()
                writeText(
                    """
                    plugins { id("ru.lazyhat.compukters.addon") }
                    version = "1.0.0"
                    compuktersAddon {
                        register("shared")
                        toolingCoordinate.set("ru.lazyhat.compukters:compukters-addon-tooling:$sdkVersion")
                        platformCoordinate.set("ru.lazyhat.compukters:compukters-guest-platform:$sdkVersion@cpb")
                    }
                    """.trimIndent(),
                )
            }
            project.resolve("shared/src/contract/kotlin/shared/time/Epoch.kt").apply {
                parent.createDirectories()
                writeText(
                    """
                    package shared.time
                    import ru.lazyhat.compukters.api.contracts.*
                    import kotlin.math.Vector3d
                    value class Direction(val vector: Vector3d) {
                        fun normalized(): Vector3d = vector.normalized()
                    }
                    @GuestService interface Epoch {
                        @GuestCall(binding = "now") fun now(): Long
                    }
                    """.trimIndent(),
                )
            }
            project.resolve("shared/src/contract/addon.lock").writeText(
                "lock 1\noperation shared shared 1 0 active shared.time.EpochBindings.now fun():Long\n",
            )
            project.resolve("src/test/kotlin/fixture/GeneratedContractTest.kt").also { target ->
                target.parent.createDirectories()
                requireNotNull(javaClass.getResourceAsStream("/declarative-addon/GeneratedContractTest.kt")).use { input ->
                    Files.copy(input, target)
                }
            }
            val contractSource = project.resolve("src/contract/kotlin/fixture/kinetics/Kinetics.kt")
            contractSource.also { source ->
                source.parent.createDirectories()
                source.writeText(
                    """
                    package fixture.kinetics

                    import ru.lazyhat.compukters.api.contracts.GuestDevice
                    import ru.lazyhat.compukters.api.contracts.GuestResource
                    import ru.lazyhat.compukters.api.contracts.GuestCall
                    import ru.lazyhat.compukters.api.contracts.GuestWait
                    import ru.lazyhat.compukters.api.contracts.Finite
                    import ru.lazyhat.compukters.api.contracts.DoubleRange
                    import kotlin.math.Rotation3d
                    import kotlin.math.Vector3d

                    data class State(val position: Vector3d, val orientation: Rotation3d) {
                        fun distance(): Double = position.length
                    }
                    value class Velocity(val x: Double, val y: Double, val z: Double) {
                        fun direction(): Vector3d = Vector3d(x, y, z).normalized()
                    }

                    @GuestResource interface Snapshot {
                        @GuestCall fun size(): Int
                        @GuestCall fun close(): Unit
                    }

                    @GuestDevice("fixture:engine") interface Engine {
                        @GuestCall fun speed(): Float
                        @GuestCall fun state(): State
                        @GuestCall fun direction(): shared.time.Direction
                        @GuestCall fun orientation(): Rotation3d
                        @GuestCall fun snapshot(): Snapshot
                        @GuestCall fun aim(direction: Vector3d): Unit
                        @GuestCall fun target(state: State): Unit
                        @GuestCall fun setThrottle(@Finite @DoubleRange(min = 0.0, max = 1.0) throttle: Double): Unit
                        @GuestCall fun close(): Unit
                        @GuestWait fun awaitState(): State
                        fun currentTime(): Long = shared.time.Epoch.now()
                    }
                    """.trimIndent(),
                )
            }

            runner(project, "updateCompuktersAddonAbiLock", "--configuration-cache").build()
            runner(project, "verifyCompuktersApiClasspath").build()
            runner(project, "jar", "--configuration-cache").build()
            runner(project, "check", "compuktersGuestSourcesJar", "--configuration-cache").build()
            assertTrue(Files.notExists(project.resolve("build/classes/kotlin/contract")))
            val hostTests = Files.readString(project.resolve("build/test-results/test/TEST-fixture.GeneratedContractTest.xml"))
            assertEquals(3, Regex("<testcase\\s").findAll(hostTests).count(), hostTests)
            Files.delete(project.resolve("build/compukters-addon/external-addon.cagb"))
            val replay = runner(project, "jar", "--configuration-cache").build()
            assertTrue(replay.output.contains("Reusing configuration cache"), replay.output)
            assertEquals(org.gradle.testkit.runner.TaskOutcome.SUCCESS, replay.task(":assembleCompuktersAddon")?.outcome)

            assertTrue(project.resolve("src/contract/addon.lock").toFile().isFile)
            assertTrue(
                project
                    .resolve("build/compukters-addon/addon.api")
                    .toFile()
                    .readText()
                    .contains("version 2.3.4"),
            )
            assertTrue(project.resolve("build/generated/sources/compuktersAddon/kotlin/GeneratedAddonHostContract.kt").toFile().isFile)
            val guestRoot = project.resolve("build/generated/sources/compuktersGuest/kotlin")
            val generatedFiles =
                Files.walk(guestRoot).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.toList().associate {
                        guestRoot.relativize(it).toString() to
                            Files.readString(it)
                    }
                }
            val guest = generatedFiles.getValue("fixture/kinetics/fixture.kinetics.__CompuktersHandleEngine.kt")
            assertTrue("setThrottleCommand" in guest && "aimCommand" in guest)
            assertTrue("closeCommand" in guest && "speedCommand" in guest && "awaitStateCommand" !in guest)
            assertTrue("kotlin.math.Rotation3d.fromQuaternion" in guest)
            val host = Files.readString(project.resolve("build/generated/sources/compuktersAddon/kotlin/GeneratedAddonHostContract.kt"))
            assertTrue("batchCommands" !in host)
            assertTrue("argument1 >= 0.0 && argument1 <= 1.0" in host)
            val bundle = AddonGuestApiBundleCodec.decode(Files.readAllBytes(project.resolve("build/compukters-addon/external-addon.cagb")))
            assertTrue(bundle.moduleDescriptor.dependencies.any { it.toString() == "shared:api" })
            assertTrue(requireNotNull(bundle.moduleDescriptor.libraryFragment).toByteArray().isNotEmpty())
            assertEquals(generatedFiles, sourceEntries(requireNotNull(bundle.sourcesJar).toByteArray()))
            assertEquals(
                generatedFiles,
                sourceEntries(Files.readAllBytes(project.resolve("build/libs/external-addon-2.3.4-guest-sources.jar"))),
            )
            val jar = project.resolve("build/libs/external-addon-2.3.4.jar").toFile()
            ZipFile(jar).use { zip ->
                assertEquals(1, zip.entries().asSequence().count { it.name == "META-INF/compukters/addons/fixture.cagb" })
                assertTrue(zip.entries().asSequence().none { it.name.startsWith("ru/lazyhat/compukters/") })
                assertTrue(zip.getEntry("fixture/kinetics/Engine.class") == null)
            }
            contractSource.toFile().appendText("\n@GuestDevice(\"fixture:invalid\") interface Invalid { @GuestCall fun read(): Int = 1 }\n")
            val invalid = runner(project, "generateCompuktersGuestSources").buildAndFail()
            assertTrue("must have no body" in invalid.output, invalid.output)
        } finally {
            Files.walk(project).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun runner(
        project: Path,
        vararg arguments: String,
    ): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(project.toFile())
            .withArguments("--stacktrace", *arguments)
            .withPluginClasspath()
            .forwardOutput()
}

private fun sourceEntries(bytes: ByteArray): Map<String, String> =
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        buildMap {
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".kt")) put(entry.name, zip.readBytes().decodeToString())
            }
        }
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
