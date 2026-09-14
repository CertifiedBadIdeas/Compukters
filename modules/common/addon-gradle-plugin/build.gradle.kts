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
    alias(libs.plugins.kotlinConvention)
    `java-gradle-plugin`
    `maven-publish`
}

val addonSdkVersion = libs.versions.addon.sdk.get()

gradlePlugin {
    plugins {
        create("compuktersAddon") {
            id = "ru.lazyhat.compukters.addon"
            implementationClass = "ru.lazyhat.compukters.gradle.addon.CompuktersAddonPlugin"
            displayName = "Compukters Addon"
            description = "Builds a standalone Compukters Guest Kotlin addon bundle and generated host contract."
        }
    }
}

tasks.jar {
    manifest.attributes["Implementation-Version"] = addonSdkVersion
}

tasks.test {
    dependsOn(
        tasks.named("publishAllPublicationsToAddonSdkRepository"),
        ":compiler-k2-engine:publishAddonToolingPublicationToAddonSdkRepository",
        ":guest-platform:publishAddonPlatformPublicationToAddonSdkRepository",
        ":v1_21_1-neoforge:publishAddonApiPublicationToAddonSdkRepository",
    )
    systemProperty(
        "compukters.addon.sdk.repository",
        rootProject.layout.buildDirectory.dir("repositories/addon-sdk").get().asFile.absolutePath,
    )
    systemProperty("compukters.addon.sdk.version", addonSdkVersion)
    systemProperty("compukters.addon.kotlin.version", libs.plugins.kotlin.get().version.requiredVersion)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        groupId = project.group.toString()
        version = addonSdkVersion
        if (name == "pluginMaven") artifactId = "compukters-addon-gradle-plugin"
    }
    repositories {
        maven {
            name = "addonSdk"
            url = rootProject.layout.buildDirectory.dir("repositories/addon-sdk").get().asFile.toURI()
        }
    }
}

tasks.register("publishAddonSdkToMavenLocal") {
    description = "Publishes the public addon SDK to Maven Local for standalone builds and IDE imports."
    group = "compukters addon sdk"
    dependsOn(
        tasks.named("publishToMavenLocal"),
        ":compiler-k2-engine:publishAddonToolingPublicationToMavenLocal",
        ":guest-platform:publishAddonPlatformPublicationToMavenLocal",
        ":v1_21_1-neoforge:publishAddonApiPublicationToMavenLocal",
    )
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}
