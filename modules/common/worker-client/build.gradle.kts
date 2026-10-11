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
    compileOnly(libs.xz)
    implementation(libs.kotlin.stdlib)
    testImplementation(libs.xz)
    testImplementation(kotlin("test"))
}

val allowedWorkerClientRuntimeModules =
    setOf(
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains:annotations",
    )

val verifyWorkerClientRuntimeIsolation =
    tasks.register("verifyWorkerClientRuntimeIsolation") {
        group = "verification"
        description = "Fails when K2 or IntelliJ implementation dependencies leak into worker-client."
        val runtimeClasspath = configurations.named("runtimeClasspath")
        inputs.files(runtimeClasspath)
        val actionAllowedWorkerClientRuntimeModules = allowedWorkerClientRuntimeModules
        val runtimeComponents = runtimeClasspath.get().runtimeComponentNames(project.path)
        inputs.property("runtimeComponents", runtimeComponents)
        doLast {
            val resolved = runtimeComponents.get()
            val disallowed = resolved.filterNot(actionAllowedWorkerClientRuntimeModules::contains)
            check(disallowed.isEmpty()) {
                "worker-client runtimeClasspath contains forbidden platform dependencies: $disallowed"
            }
        }
    }

tasks.check {
    dependsOn(verifyWorkerClientRuntimeIsolation)
}
