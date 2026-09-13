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

loom {
    mods {
        maybeCreate("compukters").apply {
            listOf(
                ":addon-guest-api",
                ":compiler-client",
                ":compiler-runtime",
                ":core",
                ":ide-analysis-client",
                ":ide-client",
                ":ide-core",
                ":native-runtime-api",
                ":native-runtime-jni",
                ":platform-bundle",
                ":v1_21_1-common",
                ":v1_21_1-neoforge",
                ":worker-client",
            ).forEach { projectPath -> sourceSet("main", project(projectPath)) }
        }
    }
}

dependencies {
    add("neoForge", versionLibrary("neoforge"))
}

addCompuktersNeoForgeDevelopmentRuntime()

val forgeRuntimeLibraries = configurations.named("forgeRuntimeLibrary")
val verifyAddonDevelopmentRuntime =
    tasks.register("verifyAddonDevelopmentRuntime") {
        group = "verification"
        description = "Checks that the addon dev runtime provides Kotlin without duplicating Compukters classes."
        inputs.files(forgeRuntimeLibraries)
        doLast {
            val archives = forgeRuntimeLibraries.get().files.filter { it.extension == "jar" }
            var kotlinRuntimeFound = false
            archives.forEach { archive ->
                ZipFile(archive).use { zip ->
                    val entries = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
                    kotlinRuntimeFound = kotlinRuntimeFound || "kotlin/jvm/internal/Intrinsics.class" in entries
                    check(entries.none { it.startsWith("ru/lazyhat/compukters/") }) {
                        "Compukters classes must come from the base mod, not addon runtime library ${archive.name}"
                    }
                }
            }
            check(kotlinRuntimeFound) { "Kotlin stdlib is missing from the addon NeoForge development runtime" }
        }
    }

tasks.named("check") {
    dependsOn(verifyAddonDevelopmentRuntime)
}
