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
    implementation(libs.kotlin.stdlib)
    testImplementation(kotlin("test"))
}

val executableConformanceArtifact = layout.buildDirectory.file("generated/conformance/executable-instructions.cpkt")

tasks.test {
    val rustFixtures = rootProject.layout.projectDirectory.dir("host/compukter-vm/tests/fixtures")
    inputs.dir(rustFixtures)
    outputs.file(executableConformanceArtifact)
    outputs.file(layout.buildDirectory.file("generated/conformance/executable-instructions.cpkt.inline.cpkt"))
    systemProperty("compukter.vm.fixtures", rustFixtures.asFile.absolutePath)
    systemProperty("compukter.vm.executableArtifact", executableConformanceArtifact.get().asFile.absolutePath)
}

val assertCompilerArtifactIsolation = tasks.register("assertCompilerArtifactIsolation") {
    group = "verification"
    description = "Fails when compiler implementation artifacts enter compiler-artifact production classpaths."

    val classpathComponents = listOf("compileClasspath", "runtimeClasspath").map {
        configurations.getByName(it).runtimeComponentNames(project.path)
    }
    val productionComponents = providers.provider { classpathComponents.flatMap { it.get() }.distinct().sorted() }
    inputs.property("productionComponents", productionComponents)
    doLast {
        val forbidden = productionComponents.get().filter {
            it.startsWith("org.jetbrains.kotlin:kotlin-compiler") || it.startsWith("org.jetbrains.kotlin:kotlin-scripting-compiler")
        }
        check(forbidden.isEmpty()) { "compiler-artifact contains compiler implementation dependencies: $forbidden" }
    }
}

tasks.check {
    dependsOn(assertCompilerArtifactIsolation)
}
