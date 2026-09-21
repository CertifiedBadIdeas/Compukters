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
    alias(libs.plugins.v261)
    alias(libs.plugins.minecraftSharedSourcesConvention)
    `maven-publish`
}

val addonSdkVersion = libs.versions.addon.sdk.get()

dependencies {
    compileOnly(projects.addonApi)
    compileOnly(projects.v261Common)
}

publishing {
    publications {
        create<MavenPublication>("addonNeoForgeApi") {
            groupId = project.group.toString()
            artifactId = "compukters-addon-neoforge-26.1.2"
            version = addonSdkVersion
            artifact(tasks.jar) {
                classifier = null
            }
        }
    }
}

val verifyAddonNeoForgeApiJar =
    tasks.register("verifyAddonNeoForgeApiJar") {
        group = "verification"
        description = "Checks that the NeoForge 26.1.2 addon adapter JAR remains thin."
        dependsOn(tasks.jar)
        inputs.file(tasks.jar.flatMap { it.archiveFile })
        doLast {
            val archive = tasks.jar.get().archiveFile.get().asFile
            val productClasses =
                ZipFile(archive).use { zip ->
                    zip.entries().asSequence()
                        .map { it.name }
                        .filter { it.startsWith("ru/lazyhat/compukters/") && it.endsWith(".class") }
                        .toSet()
                }
            val expected =
                setOf(
                    "ru/lazyhat/compukters/api/addon/minecraft/CompuktersAddonHostFactory.class",
                    "ru/lazyhat/compukters/api/addon/minecraft/CompuktersAddonRegistry.class",
                    "ru/lazyhat/compukters/api/addon/minecraft/CompuktersComputerContext.class",
                    "ru/lazyhat/compukters/api/addon/minecraft/CompuktersPeripheralContact.class",
                    "ru/lazyhat/compukters/api/addon/minecraft/CompuktersPeripheralDevice.class",
                    "ru/lazyhat/compukters/api/addon/minecraft/CompuktersPeripheralProvider.class",
                )
            check(productClasses == expected) { "unexpected classes in ${archive.name}: ${productClasses - expected}" }
        }
    }

tasks.check {
    dependsOn(verifyAddonNeoForgeApiJar)
}
