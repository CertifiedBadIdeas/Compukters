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

val nativeIntegrationTest = sourceSets.create("nativeIntegrationTest")

kotlin.target.compilations.named(nativeIntegrationTest.name) {
    associateWith(kotlin.target.compilations.getByName("main"))
}

configurations[nativeIntegrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[nativeIntegrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
configurations[nativeIntegrationTest.runtimeClasspathConfigurationName].attributes {
    attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}
nativeIntegrationTest.compileClasspath += sourceSets.main.get().output
nativeIntegrationTest.runtimeClasspath += sourceSets.main.get().output

dependencies {
    implementation(projects.addonApi)
    implementation(projects.compilerClient)
    implementation(projects.compilerRuntime)
    implementation(projects.nativeRuntimeApi)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.xz)
    testImplementation(projects.platformBundle)
    add(nativeIntegrationTest.runtimeOnlyConfigurationName, projects.nativeRuntimeFfm)
}

val compukterFfiLibrary =
    rootProject.file(".toolchain/build/cargo/compukter-ffi/release/${System.mapLibraryName("compukter_ffi")}")
val programRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/system/shell.cpkt")
val bootRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/system/boot.cpkt")
val kotlincRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/system/kotlinc.cpkt")
val filesystemRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/conformance/filesystem.cpkt")
val compilationRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/conformance/compilation.cpkt")
val lexicalPathRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/conformance/lexical-path.cpkt")
val cmatrixRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/system/cmatrix.cpkt")
val editRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/system/edit.cpkt")
val vmbenchAgentRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/system/vmbench-agent.cpkt")
val compilerWorkerPayload =
    project(":tooling-runtime").layout.buildDirectory.file("distributions/k2-tooling-workers.zip.xz")
val compilerWorkerManifest =
    project(":tooling-runtime").layout.buildDirectory.file("distributions/k2-tooling-workers.bundle")
val processTerminalChildArtifact =
    rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/process-terminal-child.cpkt")
val processCopyExecutableArtifact =
    rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/process-copy-executable.cpkt")

tasks.test {
    useJUnitPlatform()
}

val programRuntimeIntegrationTest =
    tasks.register<Test>("programRuntimeIntegrationTest") {
        description = "Runs a compiler-produced Kotlin artifact through the server runtime host and native VM."
        group = "verification"
        dependsOn(
            ":compiler-k2:generateBootArtifact",
            ":compiler-k2:generateKotlincArtifact",
            ":compiler-k2:generateShellArtifact",
            ":compiler-k2:generateEditArtifact",
            ":compiler-k2:generateCmatrixArtifact",
            ":compiler-k2:generateFilesystemArtifact",
            ":compiler-k2:generateCompilationArtifact",
            ":compiler-k2:generateVmbenchAgentArtifact",
            ":tooling-runtime:toolingRuntimeBundle",
            ":tooling-runtime:toolingRuntimeManifest",
            rootProject.tasks.named("cargoBuildCompukterFfi"),
            ":native-runtime-ffm:jar",
        )
        useJUnitPlatform()
        testClassesDirs = nativeIntegrationTest.output.classesDirs
        classpath = nativeIntegrationTest.runtimeClasspath
        filter.includeTestsMatching("ru.lazyhat.compukters.core.device.runtime.program.integration.*")
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
        javaLauncher.set(
            javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
            },
        )
        inputs.file(compukterFfiLibrary)
        inputs.file(programRuntimeArtifact)
        inputs.file(bootRuntimeArtifact)
        inputs.file(kotlincRuntimeArtifact)
        inputs.file(editRuntimeArtifact)
        inputs.file(cmatrixRuntimeArtifact)
        inputs.file(filesystemRuntimeArtifact)
        inputs.file(compilationRuntimeArtifact)
        inputs.file(lexicalPathRuntimeArtifact)
        inputs.file(vmbenchAgentRuntimeArtifact)
        inputs.file(compilerWorkerPayload)
        inputs.file(compilerWorkerManifest)
        inputs.file(processTerminalChildArtifact)
        inputs.file(processCopyExecutableArtifact)
        systemProperty("compukters.ffi.library", compukterFfiLibrary.absolutePath)
        systemProperty("compukters.programRuntime.artifact", programRuntimeArtifact.get().asFile.absolutePath)
        systemProperty("compukters.bootRuntime.artifact", bootRuntimeArtifact.get().asFile.absolutePath)
        systemProperty("compukters.kotlincRuntime.artifact", kotlincRuntimeArtifact.get().asFile.absolutePath)
        systemProperty("compukters.compilationRuntime.artifact", compilationRuntimeArtifact.get().asFile.absolutePath)
        systemProperty("compukters.filesystemRuntime.artifact", filesystemRuntimeArtifact.get().asFile.absolutePath)
        systemProperty("compukters.lexicalPathRuntime.artifact", lexicalPathRuntimeArtifact.get().asFile.absolutePath)
        systemProperty("compukters.editRuntime.artifact", editRuntimeArtifact.get().asFile.absolutePath)
        systemProperty("compukters.cmatrixRuntime.artifact", cmatrixRuntimeArtifact.get().asFile.absolutePath)
        systemProperty("compukters.vmbenchAgentRuntime.artifact", vmbenchAgentRuntimeArtifact.get().asFile.absolutePath)
        systemProperty("compukters.compilerWorker.payload", compilerWorkerPayload.get().asFile.absolutePath)
        systemProperty("compukters.compilerWorker.manifest", compilerWorkerManifest.get().asFile.absolutePath)
        systemProperty("compukters.processTerminalChild.artifact", processTerminalChildArtifact.asFile.absolutePath)
        systemProperty(
            "compukters.processCopyExecutable.artifact",
            processCopyExecutableArtifact.asFile.absolutePath,
        )
    }

tasks.check {
    dependsOn(programRuntimeIntegrationTest)
}

tasks.register("prepareJvmMemoryProbe") {
    description = "Prepares a standalone JVM computer-memory probe; no measurement runs inside Gradle."
    group = "verification"
    dependsOn(nativeIntegrationTest.classesTaskName, ":native-runtime-ffm:jar", rootProject.tasks.named("cargoBuildCompukterFfi"))
    val launchClasspath = layout.buildDirectory.file("jvm-memory-probe/classpath.txt")
    val probeClasspath = nativeIntegrationTest.runtimeClasspath
    inputs.files(probeClasspath)
    outputs.file(launchClasspath)
    doLast {
        launchClasspath.get().asFile.apply {
            parentFile.mkdirs()
            writeText(probeClasspath.asPath)
        }
    }
}

tasks.register("prepareJvmFleetProbe") {
    description = "Prepares the opt-in production-carrier fleet probe; measurements run in standalone JVMs."
    group = "verification"
    dependsOn("prepareJvmMemoryProbe")
}

// Exercises owner-thread event delivery against the opt-in native scheduler.
tasks.register<Test>("experimentalFleetEventIntegrationTest") {
    group = "verification"
    dependsOn(":native-runtime-ffm:buildExperimentalFleetFfi", ":native-runtime-ffm:jar")
    dependsOn(":compiler-k2:generateRedstoneConformanceArtifact", ":compiler-k2:generateSoundConformanceArtifact")
    val redstone = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/redstone.cpkt")
    val sound = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/sound.cpkt")
    inputs.files(redstone, sound)
    systemProperty("compukters.fleet.redstone.fixture", redstone.get().asFile.absolutePath)
    systemProperty("compukters.fleet.sound.fixture", sound.get().asFile.absolutePath)
    dependsOn(
        ":compiler-k2:generateCompilationArtifact",
        ":tooling-runtime:toolingRuntimeBundle",
        ":tooling-runtime:toolingRuntimeManifest",
    )
    inputs.files(compilationRuntimeArtifact, compilerWorkerManifest, compilerWorkerPayload)
    systemProperty("compukters.compilationRuntime.artifact", compilationRuntimeArtifact.get().asFile.absolutePath)
    systemProperty("compukters.compilerWorker.manifest", compilerWorkerManifest.get().asFile.absolutePath)
    systemProperty("compukters.compilerWorker.payload", compilerWorkerPayload.get().asFile.absolutePath)
    useJUnitPlatform()
    testClassesDirs = nativeIntegrationTest.output.classesDirs
    classpath = nativeIntegrationTest.runtimeClasspath
    filter.includeTestsMatching("ru.lazyhat.compukters.core.device.runtime.actor.integration.NativeFleetEventPumpIntegrationTest")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    val library = rootProject.file(".toolchain/build/cargo/compukter-ffi-experimental/release/${System.mapLibraryName("compukter_ffi")}")
    val fixture = rootProject.file("host/compukter-vm/tests/fixtures/fleet-terminal.cpkt")
    val hostFixture = rootProject.file("host/compukter-vm/tests/fixtures/fleet-host-request.cpkt")
    val timerFixture = rootProject.file("host/compukter-vm/tests/fixtures/fleet-timer.cpkt")
    inputs.files(library, fixture, hostFixture, timerFixture)
    systemProperty("compukters.fleet.timer.fixture", timerFixture.absolutePath)
    systemProperty("compukters.fleet.host.fixture", hostFixture.absolutePath)
    systemProperty("compukters.ffi.library", library.absolutePath)
    systemProperty("compukters.fleet.terminal.fixture", fixture.absolutePath)
}
