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

plugins {
    java
}

val pinnedKotlinVersion = libsCatalog().findVersion("kotlin").get().requiredVersion
val pinnedXzVersion = libsCatalog().findVersion("xz").get().requiredVersion
val pinnedJGitVersion = libsCatalog().findVersion("jgit").get().requiredVersion
val pinnedJavaEwahVersion = libsCatalog().findVersion("javaewah").get().requiredVersion
val pinnedCodecVersion = libsCatalog().findVersion("commons-codec").get().requiredVersion

val systemPrograms =
    mapOf(
        "boot" to "generateBootArtifact",
        "shell" to "generateShellArtifact",
        "kotlinc" to "generateKotlincArtifact",
        "edit" to "generateEditArtifact",
        "vmbench" to "generateVmbenchArtifact",
        "vmbench-agent" to "generateVmbenchAgentArtifact",
    )
val toolingRuntimeBundle = project(":tooling-runtime").layout.buildDirectory.file("distributions/k2-tooling-workers.zip.xz")
val toolingRuntimeManifest = project(":tooling-runtime").layout.buildDirectory.file("distributions/k2-tooling-workers.bundle")

tasks.processResources {
    systemPrograms.forEach { (name, generator) ->
        val artifact = project(":compiler-k2").layout.buildDirectory.file("generated/system/$name.cpkt")
        dependsOn(":compiler-k2:$generator")
        from(artifact) {
            into("system/programs")
            rename { name }
        }
    }
    dependsOn(":tooling-runtime:toolingRuntimeBundle", ":tooling-runtime:toolingRuntimeManifest")
    from(toolingRuntimeBundle) {
        into("tooling/workers")
        rename { "k2-tooling-workers.zip.xz" }
    }
    from(toolingRuntimeManifest) {
        into("tooling/workers")
        rename { "k2-tooling-workers.bundle" }
    }
    from(rootProject.layout.projectDirectory.file("licenses/project/Apache-2.0.txt")) {
        into("META-INF/licenses")
        rename { "Compukters-Apache-2.0.txt" }
    }
    from(rootProject.layout.projectDirectory.file("NOTICE")) {
        into("META-INF")
        rename { "NOTICE.txt" }
    }
    from(rootProject.layout.projectDirectory.file("THIRD-PARTY-NOTICES.md")) {
        into("META-INF")
    }
    from(rootProject.layout.projectDirectory.dir("licenses/kotlin/v$pinnedKotlinVersion")) {
        into("META-INF/licenses/kotlin/v$pinnedKotlinVersion")
    }
    from(rootProject.layout.projectDirectory.file("licenses/rust/generic-array-0.14.7-LICENSE.txt")) {
        into("META-INF/licenses/rust")
    }
    listOf(
        "antlr4-runtime-4.13.2-BSD-3-Clause.txt",
        "xz-java-$pinnedXzVersion-0BSD.txt",
        "jgit-$pinnedJGitVersion-EDL-1.0.html",
        "javaewah-$pinnedJavaEwahVersion-Apache-2.0.txt",
        "javaewah-$pinnedJavaEwahVersion-PROVENANCE.txt",
        "commons-codec-$pinnedCodecVersion-Apache-2.0.txt",
        "commons-codec-$pinnedCodecVersion-NOTICE.txt",
    ).forEach { filename ->
        from(rootProject.layout.projectDirectory.file("licenses/jvm/$filename")) {
            into("META-INF/licenses/jvm")
        }
    }
    from(rootProject.layout.projectDirectory.file("licenses/distribution-components.tsv")) {
        into("META-INF/licenses")
    }
}
