/*
 * The Compukter Kraft Developers
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

@file:Suppress("PropertyName")

import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType

plugins {
    id("k16-firmware-producer-convention")
}

val sourceSets = extensions.getByType<SourceSetContainer>()
val k16VmNativePlatform = currentK16VmNativePlatform()
val k16VmNativeLibrary =
    rootProject.layout.projectDirectory.file(".toolchain/build/cargo/k16-vm/debug/${k16VmNativePlatform.libraryName}")
val generatedK16FirmwareResources = layout.buildDirectory.dir("generated/k16-firmware-resources")
val generatedK16FirmwareTestResources = layout.buildDirectory.dir("generated/k16-firmware-test-resources")
val generatedKraftOsProductionBundle = layout.buildDirectory.dir("generated/kraftos-bundles/production")
val k16TinyCcUnameProof = layout.buildDirectory.file("generated/k16-tinycc-proof/uname.kx")
val k16NativeTinyCcCandidate = layout.buildDirectory.file("generated/k16-native-tinycc/c-sdk-candidate.kv")
val k16SharedKraftArtifact = layout.buildDirectory.file("generated/k16-firmware-artifacts/libkraft.kso")
val k16NativeTinyCcTool = rootProject.layout.projectDirectory.file(".toolchain/build/cargo/k16-tools/release/k16")
val k16NativeTinyCcLlvmReadObj =
    rootProject.layout.projectDirectory.file(
        providers.gradleProperty("k16LlvmBuildDir").orElse(".toolchain/build/llvm/k16-min").get() +
            "/bin/llvm-readobj",
    )
val k16BiosFlashResource = generatedK16FirmwareResources.map { it.file("firmware/k16-bios.kflash") }
val k16DevelopmentStorage0Resource =
    generatedK16FirmwareTestResources.map { it.file("firmware/k16-system-storage0-dev.kv") }
val k16SdkFixtureResource =
    generatedK16FirmwareTestResources.map { it.file("firmware/sdk-fixture-v1.kv") }
val k16TestArtifactManifestResource =
    generatedK16FirmwareTestResources.map { it.file("firmware/kraftos-artifacts.properties") }

sourceSets.getByName("main") {
    resources.srcDir(generatedKraftOsProductionBundle)
}

sourceSets.configureEach {
    if (name == "test" || name == "gameTest") {
        resources.srcDir(generatedK16FirmwareTestResources)
        val originalRuntimeClasspath = runtimeClasspath
        runtimeClasspath = output + originalRuntimeClasspath
    }
}

tasks.named("processResources") {
    dependsOn("assembleKraftOsProductionBundle")
}

fun org.gradle.api.Task.dependsOnK16SdkTestResources() {
    dependsOn("putK16DevelopmentStorage0TestPrograms")
    dependsOn("putK16SdkFixture")
    dependsOn("generateKraftOsTestArtifactManifest")
}

tasks.named("processTestResources") {
    dependsOnK16SdkTestResources()
}

tasks.configureEach {
    if (name == "processGameTestResources") {
        dependsOnK16SdkTestResources()
    }
}

fun Test.inputsK16RuntimeFirmwareResources() {
    dependsOn("linkK16BiosFlash")
    dependsOn("putK16DevelopmentStorage0TestPrograms")
    dependsOn("putK16SdkFixture")
    dependsOn("generateKraftOsTestArtifactManifest")
    inputs.file(k16BiosFlashResource)
    inputs.file(k16DevelopmentStorage0Resource)
    inputs.file(k16SdkFixtureResource)
    inputs.file(k16TestArtifactManifestResource)
}

fun Test.useK16NeoforgeTestRuntime() {
    dependsOn(tasks.named("testClasses"))
    val testSourceSet = sourceSets.getByName("test")
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
}

tasks.register<Test>("verifyK16FirmwareArchitecture") {
    description = "Runs focused K16 firmware build-surface and image architecture tests."
    group = "verification"
    inputsK16RuntimeFirmwareResources()
    useK16NeoforgeTestRuntime()
    filter {
        includeTestsMatching("ru.lazyhat.compukterkraft.impl.K16DynamicLoaderArchitectureTest")
        includeTestsMatching("ru.lazyhat.compukterkraft.impl.K16RuntimeProfilingArchitectureTest")
        includeTestsMatching("ru.lazyhat.compukterkraft.impl.K16StorageDurabilityArchitectureTest")
    }
}

tasks.register<Test>("verifyK16Runtime") {
    description = "Runs the K16 native runtime shell smoke test against bundled firmware resources."
    group = "verification"
    dependsOn(tasks.named("buildK16VmNativeLibrary"))
    inputsK16RuntimeFirmwareResources()
    useK16NeoforgeTestRuntime()
    filter {
        includeTestsMatching("ru.lazyhat.compukterkraft.impl.K16ShellRuntimeSmokeTest")
    }
    systemProperty("k16.vm.native.library", k16VmNativeLibrary.asFile.absolutePath)
}

tasks.named<Test>("test") {
    filter {
        excludeTestsMatching("ru.lazyhat.compukterkraft.impl.K16SdkMountRuntimeSmokeTest")
        excludeTestsMatching("ru.lazyhat.compukterkraft.impl.K16TinyCcRuntimeSmokeTest")
        excludeTestsMatching("ru.lazyhat.compukterkraft.impl.K16NativeTinyCcCompileTest")
    }
}

tasks.register<Test>("verifyK16SdkMount") {
    description = "Runs the immutable K16 SDK module through the real bundled KraftOS runtime."
    group = "verification"
    dependsOn(tasks.named("buildK16VmNativeLibrary"))
    inputsK16RuntimeFirmwareResources()
    useK16NeoforgeTestRuntime()
    filter {
        includeTestsMatching("ru.lazyhat.compukterkraft.impl.K16SdkMountRuntimeSmokeTest")
    }
    systemProperty("k16.vm.native.library", k16VmNativeLibrary.asFile.absolutePath)
}

tasks.register<Test>("verifyK16TinyCcRuntime") {
    description = "Runs the TinyCC-built uname inside the real bundled KraftOS runtime."
    group = "verification"
    dependsOn(tasks.named("buildK16VmNativeLibrary"))
    dependsOn(tasks.named("compileK16TinyCcUnameProof"))
    dependsOn(tasks.named("assembleKraftOsProductionBundle"))
    inputsK16RuntimeFirmwareResources()
    inputs.file(k16TinyCcUnameProof)
    inputs.dir(generatedKraftOsProductionBundle)
    useK16NeoforgeTestRuntime()
    filter {
        includeTestsMatching("ru.lazyhat.compukterkraft.impl.K16TinyCcRuntimeSmokeTest")
    }
    systemProperty("k16.vm.native.library", k16VmNativeLibrary.asFile.absolutePath)
    systemProperty("k16.tinycc.uname.path", k16TinyCcUnameProof.get().asFile.absolutePath)
}

tasks.register<Test>("verifyK16NativeTinyCc") {
    description = "Compiles C inside a 4 MiB KraftOS VM with the unregistered native TinyCC SDK candidate."
    group = "verification"
    dependsOn(tasks.named("buildK16VmNativeLibrary"))
    dependsOn(tasks.named("assembleK16CSdkCandidate"))
    dependsOn(tasks.named("assembleKraftOsProductionBundle"))
    inputsK16RuntimeFirmwareResources()
    inputs.file(k16NativeTinyCcCandidate)
    inputs.file(k16SharedKraftArtifact)
    inputs.file(k16NativeTinyCcTool)
    inputs.file(k16NativeTinyCcLlvmReadObj)
    inputs.dir(generatedKraftOsProductionBundle)
    useK16NeoforgeTestRuntime()
    filter {
        includeTestsMatching("ru.lazyhat.compukterkraft.impl.K16NativeTinyCcCompileTest")
    }
    systemProperty("k16.vm.native.library", k16VmNativeLibrary.asFile.absolutePath)
    systemProperty("k16.native.tinycc.candidate.path", k16NativeTinyCcCandidate.get().asFile.absolutePath)
    systemProperty("k16.native.tinycc.libkraft.path", k16SharedKraftArtifact.get().asFile.absolutePath)
    systemProperty("k16.native.tinycc.tool.path", k16NativeTinyCcTool.asFile.absolutePath)
    systemProperty("k16.native.tinycc.llvm.readobj.path", k16NativeTinyCcLlvmReadObj.asFile.absolutePath)
}

tasks.register<Test>("profileK16RuntimeWait") {
    description = "Runs the bundled K16 runtime wait profiling workload and prints runtime metrics."
    group = "verification"
    dependsOn(tasks.named("buildK16VmNativeLibrary"))
    inputsK16RuntimeFirmwareResources()
    useK16NeoforgeTestRuntime()
    filter {
        includeTestsMatching("ru.lazyhat.compukterkraft.impl.K16RuntimeWaitProfilingTest")
    }
    systemProperty("k16.vm.native.library", k16VmNativeLibrary.asFile.absolutePath)
    testLogging {
        showStandardStreams = true
    }
}

tasks.register<Test>("profileK16RuntimeTextIo") {
    description = "Runs the bundled K16 runtime terminal text IO profiling workload and prints runtime metrics."
    group = "verification"
    dependsOn(tasks.named("buildK16VmNativeLibrary"))
    inputsK16RuntimeFirmwareResources()
    useK16NeoforgeTestRuntime()
    filter {
        includeTestsMatching("ru.lazyhat.compukterkraft.impl.K16RuntimeTextIoProfilingTest")
    }
    systemProperty("k16.vm.native.library", k16VmNativeLibrary.asFile.absolutePath)
    testLogging {
        showStandardStreams = true
    }
}

tasks.register<Test>("profileK16ManyVmServerBudget") {
    description = "Runs the bundled K16 many-VM server budget profiling workload and prints runtime metrics."
    group = "verification"
    dependsOn(tasks.named("buildK16VmNativeLibrary"))
    inputsK16RuntimeFirmwareResources()
    useK16NeoforgeTestRuntime()
    filter {
        includeTestsMatching("ru.lazyhat.compukterkraft.impl.K16ManyVmServerBudgetProfilingTest")
    }
    systemProperty("k16.vm.native.library", k16VmNativeLibrary.asFile.absolutePath)
    systemProperty("k16.profile.manyVmCount", providers.gradleProperty("k16ManyVmCount").orElse("16").get())
    systemProperty("k16.profile.manyVmIdleTicks", providers.gradleProperty("k16ManyVmIdleTicks").orElse("80").get())
    systemProperty("k16.profile.manyVmBootTickLimit", providers.gradleProperty("k16ManyVmBootTickLimit").orElse("120").get())
    testLogging {
        showStandardStreams = true
    }
}
