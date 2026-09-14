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

import java.util.zip.ZipFile

plugins {
    id("1.21.1-convention")
    id("loom-runs-convention")
}

setLoaderKind(LoaderKind.NEOFORGE)
version = computeModArchiveVersion()

architectury {
    platformSetupLoomIde()
    neoForge()
}

dependencies {
    add("neoForge", versionLibrary("neoforge"))
}

addCompuktersNeoForgeDevelopmentRuntime()

val compuktersProject = project(":v1_21_1-neoforge")
val compuktersDevelopmentJar =
    files(compuktersProject.compuktersDevelopmentModJar()).builtBy(":v1_21_1-neoforge:developmentModJar")

mapOf(
    "runClient" to "run/client",
    "runClient2" to "run/client2",
    "runClient3" to "run/client3",
    "runServer" to "run/server",
).forEach { (runTask, runDirectory) ->
    val suffix = runTask.removePrefix("run")
    val stageDevelopmentMod =
        tasks.register<Copy>("stageCompuktersDevelopmentModFor$suffix") {
            from(compuktersDevelopmentJar)
            into(layout.projectDirectory.dir("$runDirectory/mods"))
        }
    tasks.named(runTask) {
        dependsOn(stageDevelopmentMod)
    }
}

val forgeRuntimeLibraries = configurations.named("forgeRuntimeLibrary")
val verifyAddonDevelopmentRuntime =
    tasks.register("verifyAddonDevelopmentRuntime") {
        group = "verification"
        description = "Checks that the addon dev runtime provides Kotlin without duplicating Compukters classes."
        inputs.files(forgeRuntimeLibraries)
        inputs.files(compuktersDevelopmentJar)
        doLast {
            val archives = forgeRuntimeLibraries.get().files.filter { it.extension == "jar" }
            var kotlinRuntimeFound = false
            archives.forEach { archive ->
                ZipFile(archive).use { zip ->
                    val entries = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
                    kotlinRuntimeFound = kotlinRuntimeFound || "kotlin/jvm/internal/Intrinsics.class" in entries
                    check(entries.none { it.startsWith("ru/lazyhat/compukters/") }) {
                        "Compukters classes leaked into addon runtime library ${archive.name}"
                    }
                }
            }
            check(kotlinRuntimeFound) { "Kotlin stdlib is missing from the addon NeoForge development runtime" }
            val developmentMod = compuktersDevelopmentJar.singleFile
            ZipFile(developmentMod).use { zip ->
                val entries = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
                check("META-INF/neoforge.mods.toml" in entries) { "Compukters development mod manifest is missing" }
                check("ru/lazyhat/compukters/impl/CompuktersMod.class" in entries) {
                    "Compukters development mod entry point is missing"
                }
                check(entries.none { it.startsWith("META-INF/jars/") }) {
                    "Compukters development mod must not duplicate nested runtime libraries"
                }
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyAddonDevelopmentRuntime)
}
