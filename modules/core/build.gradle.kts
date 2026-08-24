/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(projects.compilerRuntime)
    implementation(projects.nativeRuntime)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
}

val compukterFfiLibrary =
    rootProject.file(".toolchain/build/cargo/compukter-ffi/release/${System.mapLibraryName("compukter_ffi")}")
val programRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/system/shell.cpkt")
val bootRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/system/boot.cpkt")
val processTerminalChildArtifact =
    rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/process-terminal-child.cpkt")
val processInstallRomExecutableArtifact =
    rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/process-install-rom-executable.cpkt")

tasks.test {
    filter.excludeTestsMatching("ru.lazyhat.compukters.core.device.runtime.program.integration.*")
}

val programRuntimeIntegrationTest =
    tasks.register<Test>("programRuntimeIntegrationTest") {
        description = "Runs a compiler-produced Kotlin artifact through the server runtime host and native VM."
        group = "verification"
        dependsOn(
            ":compiler-k2:generateBootArtifact",
            ":compiler-k2:generateShellArtifact",
            rootProject.tasks.named("cargoBuildCompukterFfi"),
        )
        useJUnitPlatform()
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("ru.lazyhat.compukters.core.device.runtime.program.integration.*")
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
        inputs.file(compukterFfiLibrary)
        inputs.file(programRuntimeArtifact)
        inputs.file(bootRuntimeArtifact)
        inputs.file(processTerminalChildArtifact)
        inputs.file(processInstallRomExecutableArtifact)
        doFirst {
            systemProperty("compukters.ffi.library", compukterFfiLibrary.absolutePath)
            systemProperty("compukters.programRuntime.artifact", programRuntimeArtifact.get().asFile.absolutePath)
            systemProperty("compukters.bootRuntime.artifact", bootRuntimeArtifact.get().asFile.absolutePath)
            systemProperty("compukters.processTerminalChild.artifact", processTerminalChildArtifact.asFile.absolutePath)
            systemProperty(
                "compukters.processInstallRomExecutable.artifact",
                processInstallRomExecutableArtifact.asFile.absolutePath,
            )
        }
    }

tasks.check {
    dependsOn(programRuntimeIntegrationTest)
}
