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
    id("org.jetbrains.dokka") version "2.2.0"
}

repositories {
    mavenCentral()
}

val modVersion = providers.fileContents(layout.projectDirectory.file("../../gradle.properties")).asText.map { properties ->
    Regex("(?m)^version\\s*=\\s*(\\S+)").find(properties)?.groupValues?.get(1)
        ?: error("Missing version in gradle.properties")
}

dokka {
    dokkaGeneratorIsolation.set(
        ProcessIsolation {
            systemProperties.put("org.jetbrains.dokka.analysis.allowKotlinPackage", "true")
        },
    )
    pluginsConfiguration.html {
        customAssets.from(layout.projectDirectory.file("../assets/images/compukters-icon.svg"))
        customStyleSheets.from(layout.projectDirectory.file("logo-styles.css"))
    }
    dokkaPublications.html {
        moduleName.set("Compukters Guest API")
        moduleVersion.set(modVersion)
    }
}

dependencies {
    dokka(project(":guest"))
    dokka(project(":create"))
}
