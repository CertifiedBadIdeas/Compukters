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
    id("com.gradleup.shadow")
    `maven-publish`
}

val addonSdkVersion = libs.versions.addon.sdk.get()

val addonToolingJar = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("addon-tooling")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

// Stable SDK tooling for independent addon builds inside this workspace.
tasks.register<Copy>("developmentAddonToolingJar") {
    from(addonToolingJar)
    into(layout.buildDirectory.dir("devlibs"))
    rename { "compukters-addon-tooling-development.jar" }
}

publishing {
    publications {
        create<MavenPublication>("addonTooling") {
            groupId = project.group.toString()
            artifactId = "compukters-addon-tooling"
            version = addonSdkVersion
            artifact(addonToolingJar) {
                classifier = null
            }
        }
    }
}

dependencies {
    api(projects.addonGuestApi)
    api(projects.platformK2)
    implementation(projects.compilerArtifact)
    implementation(projects.compilerClient)
    implementation(libs.kotlin.compiler)
    testImplementation(kotlin("test"))
}

val assertCompilerEngineBoundary = tasks.register("assertCompilerEngineBoundary") {
    doLast {
        val allowedProjects =
            setOf(":addon-guest-api", ":compiler-artifact", ":compiler-client", ":platform-bundle", ":platform-k2", ":worker-client")
        val projects =
            configurations.runtimeClasspath
                .get()
                .incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier }
                .map { it.projectPath }
                .toSet() - project.path
        check(projects.all { it in allowedProjects }) { "compiler engine has forbidden project dependencies: ${projects - allowedProjects}" }
    }
}

tasks.check {
    dependsOn(assertCompilerEngineBoundary)
}

tasks.test {
    val guestBuiltins = rootProject.file("modules/common/guest-platform/src/platform/builtins")
    inputs.dir(guestBuiltins)
    dependsOn(tasks.jar)
    doFirst {
        systemProperty("compukters.guest.builtins", guestBuiltins.absolutePath)
        systemProperty("compukters.engine.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    }
}

val inlineBlocksArtifact = layout.buildDirectory.file("generated/conformance/kotlin-inline-blocks.cpkt")
tasks.register<Test>("generateInlineBlocksConformanceArtifact") {
    description = "Compiles test-normalized inline blocks for pinned VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*inline blocks emit executable nested local non-local Unit and wide results*")
    val guestBuiltins = rootProject.file("modules/common/guest-platform/src/platform/builtins")
    inputs.dir(guestBuiltins)
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    outputs.file(inlineBlocksArtifact)
    doFirst {
        systemProperty("compukters.guest.builtins", guestBuiltins.absolutePath)
        systemProperty("compukter.vm.inlineBlocksArtifact", inlineBlocksArtifact.get().asFile.absolutePath)
    }
}

val primitivesArtifact = layout.buildDirectory.file("generated/conformance/kotlin-primitives.cpkt")
tasks.register<Test>("generatePrimitivesConformanceArtifact") {
    description = "Compiles all Guest primitive families and operator boundary cases for VM conformance."
    group = "verification"
    dependsOn(tasks.jar)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*all primitive operators preserve narrow signed unsigned and nominal semantics*")
    val guestBuiltins = rootProject.file("modules/common/guest-platform/src/platform/builtins")
    inputs.dir(guestBuiltins)
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    outputs.file(primitivesArtifact)
    doFirst {
        systemProperty("compukters.guest.builtins", guestBuiltins.absolutePath)
        systemProperty("compukter.vm.primitivesArtifact", primitivesArtifact.get().asFile.absolutePath)
    }
}
