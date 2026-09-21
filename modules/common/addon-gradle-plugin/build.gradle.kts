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
version = addonSdkVersion

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

val addonApiProject = project(":addon-api")
val addonApiArtifact =
    addonApiProject.layout.buildDirectory.file("libs/${addonApiProject.name}-${addonApiProject.version}-addon-api.jar")
val addonToolingProject = project(":compiler-k2-engine")
val addonToolingArtifact =
    addonToolingProject.layout.buildDirectory.file(
        "libs/${addonToolingProject.name}-${addonToolingProject.version}-addon-tooling.jar",
    )
val guestPlatformArtifact = project(":guest-platform").layout.buildDirectory.file("platform/compukters-platform.cpb")
val addonAdapterProject = project(":v1_21_1-addon-neoforge-api")
val addonAdapterArtifact =
    providers.provider {
        addonAdapterProject.tasks.named<Jar>("jar").get().archiveFile.get()
    }

tasks.test {
    dependsOn(
        ":addon-api:shadowJar",
        ":compiler-k2-engine:shadowJar",
        ":guest-platform:assemblePlatformBundle",
        ":v1_21_1-addon-neoforge-api:jar",
    )
    inputs.files(addonApiArtifact, addonToolingArtifact, guestPlatformArtifact, addonAdapterArtifact)
    systemProperty("compukters.addon.sdk.version", addonSdkVersion)
    systemProperty("compukters.addon.kotlin.version", libs.plugins.kotlin.get().version.requiredVersion)
    doFirst {
        systemProperty("compukters.addon.test.api", addonApiArtifact.get().asFile.absolutePath)
        systemProperty("compukters.addon.test.tooling", addonToolingArtifact.get().asFile.absolutePath)
        systemProperty("compukters.addon.test.platform", guestPlatformArtifact.get().asFile.absolutePath)
        systemProperty("compukters.addon.test.adapter", addonAdapterArtifact.get().asFile.absolutePath)
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        groupId = project.group.toString()
        version = addonSdkVersion
        if (name == "pluginMaven") artifactId = "compukters-addon-gradle-plugin"
    }
}

tasks.register("publishAddonSdkToMavenLocal") {
    description = "Publishes the public addon SDK to Maven Local for standalone builds and IDE imports."
    group = "compukters addon sdk"
    dependsOn(
        tasks.named("publishToMavenLocal"),
        ":addon-api:publishAddonApiPublicationToMavenLocal",
        ":compiler-k2-engine:publishAddonToolingPublicationToMavenLocal",
        ":guest-platform:publishAddonPlatformPublicationToMavenLocal",
        ":v1_21_1-addon-neoforge-api:publishAddonNeoForgeApiPublicationToMavenLocal",
        ":v26_1-addon-neoforge-api:publishAddonNeoForgeApiPublicationToMavenLocal",
    )
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}
