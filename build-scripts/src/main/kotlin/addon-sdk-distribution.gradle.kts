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

import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

val sdkDirectory = layout.buildDirectory.dir("addon-sdk")
val sdkRepository = sdkDirectory.map { it.dir("repository") }
val sdkProjects = listOf(
    ":addon-gradle-plugin",
    ":addon-api",
    ":compiler-k2-engine",
    ":guest-platform",
    ":v1_21_1-addon-neoforge-api",
    ":v26_1-addon-neoforge-api",
    ":v1_21_1-neoforge",
    ":v26_1-neoforge",
)
val cleanRepository = tasks.register<Delete>("cleanAddonSdkRepository") {
    delete(sdkDirectory)
}
sdkProjects.forEach { path ->
    project(path).pluginManager.withPlugin("maven-publish") {
        project(path).extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "AddonSdk"
                url = uri(sdkRepository)
            }
        }
        project(path).tasks.withType<PublishToMavenRepository>().configureEach {
            if (name.endsWith("ToAddonSdkRepository")) dependsOn(cleanRepository)
        }
    }
}
val stageRepository = tasks.register("stageAddonDevelopmentDependencies") {
    group = "addon sdk"
    description = "Stages the complete addon SDK and named development mod JARs in an isolated file repository."
    sdkProjects.forEach { dependsOn("$it:publishAllPublicationsToAddonSdkRepository") }
    val sdkVersion = project.extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion("addon-sdk").get().requiredVersion
    val productVersion = project.version.toString()
    val productBuildVersion = effectiveBuildVersion()
    inputs.property("sdkVersion", sdkVersion)
    inputs.property("modVersion", productVersion)
    inputs.property("modBuildVersion", productBuildVersion)
    outputs.file(sdkDirectory.map { it.file("sdk.properties") })
    doLast {
        sdkDirectory.get().file("sdk.properties").asFile.writeText(
            "format=1\nsdkVersion=$sdkVersion\nmodVersion=$productVersion\nmodBuildVersion=$productBuildVersion\n",
        )
    }
}
tasks.register<Zip>("packageAddonDevelopmentDependencies") {
    group = "distribution"
    description = "Packages downloadable addon development dependencies for a tagged GitHub Release."
    dependsOn(stageRepository)
    from(sdkDirectory)
    from(layout.projectDirectory.file("LICENSE.md")) { rename { "LICENSE" } }
    from(layout.projectDirectory.dir("licenses")) { into("licenses") }
    from(layout.projectDirectory.dir("licenses/kotlin")) {
        include("*/NOTICE.txt")
        eachFile { path = "NOTICE" }
        includeEmptyDirs = false
    }
    archiveFileName.set("compukters-addon-development-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distribution"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
}
