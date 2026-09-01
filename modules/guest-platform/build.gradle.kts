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
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.processResources {
    from("src/platform") {
        into("compukters-platform/sources")
    }
}

tasks.test {
    dependsOn(tasks.jar)
    doFirst {
        systemProperty("compukters.platform.source-root", layout.projectDirectory.dir("src/platform").asFile.absolutePath)
        systemProperty("compukters.platform.source-archive", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    }
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
