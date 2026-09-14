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
    alias(libs.plugins.kotlinConvention)
    id("com.gradleup.shadow")
    `maven-publish`
}

val addonSdkVersion = libs.versions.addon.sdk.get()
val addonApiContents = configurations.create("addonApiContents")

dependencies {
    api(projects.addonGuestApi)
    api(projects.nativeRuntimeApi)
    implementation(libs.kotlin.stdlib)
    testImplementation(kotlin("test"))

    listOf(projects.addonGuestApi, projects.nativeRuntimeApi, projects.platformBundle, projects.workerClient).forEach { dependency ->
        add(addonApiContents.name, dependency) { isTransitive = false }
    }
}

val addonApiJar = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("addon-api")
    configurations = listOf(addonApiContents)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

publishing {
    publications {
        create<MavenPublication>("addonApi") {
            groupId = project.group.toString()
            artifactId = "compukters-addon-api"
            version = addonSdkVersion
            artifact(addonApiJar) {
                classifier = null
            }
        }
    }
}

val verifyAddonApiJar =
    tasks.register("verifyAddonApiJar") {
        group = "verification"
        description = "Checks that the public addon API JAR remains independent of Minecraft and product implementation."
        dependsOn(addonApiJar)
        inputs.file(addonApiJar.flatMap { it.archiveFile })
        doLast {
            val archive = addonApiJar.get().archiveFile.get().asFile
            val entries = ZipFile(archive).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
            val forbiddenPrefixes =
                listOf(
                    "net/minecraft/",
                    "net/neoforged/",
                    "ru/lazyhat/compukters/core/",
                    "ru/lazyhat/compukters/compiler/",
                    "ru/lazyhat/compukters/ide/",
                    "ru/lazyhat/compukters/minecraft/",
                )
            check(entries.none { entry -> forbiddenPrefixes.any(entry::startsWith) }) {
                "Minecraft or product implementation classes leaked into ${archive.name}"
            }
            listOf(
                "ru/lazyhat/compukters/api/addon/AddonCallResult.class",
                "ru/lazyhat/compukters/api/addon/ProgramAddonHost.class",
                "ru/lazyhat/compukters/addon/api/AddonGuestApiBundle.class",
                "ru/lazyhat/compukters/lang/runtime/capability/HostCapabilitySchema.class",
            ).forEach { required -> check(required in entries) { "$required is missing from ${archive.name}" } }
        }
    }

tasks.check {
    dependsOn(verifyAddonApiJar)
}
