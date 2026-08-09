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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class K16FirmwareVolumeBuildScriptTest {
    private val root = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun k16FirmwareOrchestrationLivesInSharedConvention() {
        val neoforgeBuildScript = root.resolve("modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts").readText()
        val producerScript =
            root.resolve("build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts").readText()
        val consumerScript = root.resolve("build-scripts/src/main/kotlin/k16-firmware-convention.gradle.kts").readText()

        assertTrue(neoforgeBuildScript.contains("alias(libs.plugins.k16FirmwareConvention)"))
        assertTrue(producerScript.contains("val compileK16SystemKernel ="))
        assertTrue(producerScript.contains("""val k16KernelArtifact = generatedK16FirmwareArtifacts.map { it.file("kernel.kx") }"""))
        assertFalse(producerScript.contains("""it.file("display-ok.kx")"""))
        assertTrue(producerScript.contains("val createK16SystemStorage0 ="))
        assertTrue(consumerScript.contains("tasks.register<Test>(\"profileK16RuntimeTextIo\")"))
        assertFalse(neoforgeBuildScript.contains("val compileK16SystemKernel ="))
        assertFalse(neoforgeBuildScript.contains("val createK16SystemStorage0 ="))
        assertFalse(neoforgeBuildScript.contains("tasks.register<Test>(\"profileK16RuntimeTextIo\")"))
    }

    @Test
    fun systemStorage0TaskCreatesPartitionedVolumeBeforePutBoot() {
        val buildScript =
            root.resolve("build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts").readText()
        val taskBody =
            buildScript.substringAfter("val createK16SystemStorage0 =")
                .substringBefore("val putK16SystemStorage0Boot =")

        assertTrue(taskBody.contains("\"volume\""), "storage0 task should invoke k16 volume tooling")
        assertTrue(taskBody.contains("\"init\""), "storage0 task must create a K16PT partitioned volume")
        assertFalse(taskBody.contains("\"create\""), "plain k16 volume create is not accepted by put-boot")
        assertFalse(buildScript.contains("createRuxSystemStorage0"))
        assertFalse(buildScript.contains("putRuxSystemStorage0Boot"))
    }

    @Test
    fun systemStorage0StagesUseDistinctGradleOutputs() {
        val buildScript =
            root.resolve("build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts").readText()
        val createTask =
            buildScript.substringAfter("val createK16SystemStorage0 =")
                .substringBefore("val putK16SystemStorage0Boot =")
        val putBootTask =
            buildScript.substringAfter("val putK16SystemStorage0Boot =")
                .substringBefore("val compileK16SystemStorage0 =")
        val putKernelTask =
            buildScript.substringAfter("val compileK16SystemStorage0 =")
                .substringBefore("val putK16SystemStorage0Init =")
        val putInitTask =
            buildScript.substringAfter("val putK16SystemStorage0Init =")
                .substringBefore("val generateKraftOsArtifactManifest =")

        assertTrue(buildScript.contains("val k16EmptyStorage0Artifact"))
        assertTrue(buildScript.contains("val k16BootStorage0Artifact"))
        assertTrue(buildScript.contains("val k16KernelStorage0Artifact"))
        assertTrue(createTask.contains("outputs.file(k16EmptyStorage0Artifact)"))
        assertTrue(putBootTask.contains("inputs.file(k16EmptyStorage0Artifact)"))
        assertTrue(putBootTask.contains("outputs.file(k16BootStorage0Artifact)"))
        assertTrue(putKernelTask.contains("inputs.file(k16BootStorage0Artifact)"))
        assertTrue(putKernelTask.contains("outputs.file(k16KernelStorage0Artifact)"))
        assertTrue(putInitTask.contains("inputs.file(k16KernelStorage0Artifact)"))
        assertTrue(putInitTask.contains("outputs.file(k16SystemStorage0Resource)"))
        assertFalse(createTask.contains("outputs.file(k16SystemStorage0Resource)"))
        assertFalse(putKernelTask.contains("outputs.file(k16SystemStorage0Resource)"))
    }

    @Test
    fun sdkFixtureIsTestOnlyAndHasDeterministicManifestSelection() {
        val producerScript =
            root.resolve("build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts").readText()
        val consumerScript = root.resolve("build-scripts/src/main/kotlin/k16-firmware-convention.gradle.kts").readText()
        val productionManifest =
            producerScript.substringAfter("val generateKraftOsArtifactManifest =")
                .substringBefore("val assembleKraftOsProductionBundle =")
        val testManifest =
            producerScript.substringAfter("val generateKraftOsTestArtifactManifest =")
                .substringBefore("val reportK16UserlandSize =")

        assertTrue(
            producerScript.contains(
                "generatedK16FirmwareTestResources.map { it.file(\"firmware/sdk-fixture-v1.kv\") }",
            ),
        )
        assertTrue(producerScript.contains("val putK16SdkFixture ="))
        assertTrue(producerScript.contains("/fixture.txt"))
        assertTrue(producerScript.contains("val generateKraftOsTestArtifactManifest ="))
        assertFalse(productionManifest.contains("artifact.sdk."))
        assertTrue(testManifest.contains("artifact.sdk.sdk_fixture_v1.resource=firmware/sdk-fixture-v1.kv"))
        assertTrue(testManifest.contains("artifact.sdk.sdk_fixture_v1.format=kfs-kv"))
        assertFalse(
            producerScript.substringAfter("val assembleKraftOsProductionBundle =")
                .substringBefore("val putK16DevelopmentStorage0TestPrograms =")
                .contains("k16SdkFixtureResource"),
        )

        assertTrue(consumerScript.contains("sourceSets.configureEach"))
        assertTrue(consumerScript.contains("name == \"gameTest\""))
        assertTrue(consumerScript.contains("runtimeClasspath = output + originalRuntimeClasspath"))
        assertTrue(consumerScript.contains("name == \"processGameTestResources\""))
        assertTrue(consumerScript.contains("dependsOn(\"putK16SdkFixture\")"))
        assertTrue(consumerScript.contains("dependsOn(\"generateKraftOsTestArtifactManifest\")"))
    }

    @Test
    fun cSdkCandidateIsFreshDeterministicFourMiBMediaAndRemainsUnregistered() {
        val producerScript =
            root.resolve("build-scripts/src/main/kotlin/k16-firmware-producer-convention.gradle.kts").readText()
        val candidateTask =
            producerScript.substringAfter("val assembleK16CSdkCandidate =")
                .substringBefore("val generateKraftOsTestArtifactManifest =")
        val productionManifest =
            producerScript.substringAfter("val generateKraftOsArtifactManifest =")
                .substringBefore("val assembleKraftOsProductionBundle =")
        val testManifest =
            producerScript.substringAfter("val generateKraftOsTestArtifactManifest =")
                .substringBefore("val reportK16UserlandSize =")

        assertTrue(
            producerScript.contains(
                "generatedK16NativeTinyCcTarget.map { it.file(\"c-sdk-candidate.kv\") }",
            ),
        )
        assertTrue(producerScript.contains("val k16CSdkCandidatePayloadSizeBytes = 4 * 1024 * 1024"))
        assertTrue(producerScript.contains("val k16CSdkCandidateEntries ="))
        assertTrue(
            candidateTask.contains("guestPath.removePrefix(\"/sdk\")"),
            "storage1's KFS root must omit the virtual /sdk mount prefix",
        )
        assertTrue(candidateTask.contains("\"volume\""))
        assertTrue(candidateTask.contains("\"init\""), "the candidate must start as a fresh partitioned volume")
        assertTrue(candidateTask.contains("k16CSdkCandidatePayloadSizeBytes.toString()"))
        assertFalse(candidateTask.contains("k16SystemStorage0Resource"), "the SDK must not copy the system disk")
        assertTrue(candidateTask.contains("firstAssembly"))
        assertTrue(candidateTask.contains("secondAssembly"))
        assertTrue(candidateTask.contains("contentEquals"), "two clean candidate assemblies must match byte-for-byte")
        assertTrue(
            candidateTask.contains(
                "check(output.length() == k16CSdkCandidatePayloadSizeBytes.toLong() + k16VolumeHeaderSizeBytes)",
            ),
        )
        assertFalse(productionManifest.contains("artifact.sdk.c_sdk_candidate"))
        assertFalse(testManifest.contains("artifact.sdk.c_sdk_candidate"))
    }

    @Test
    fun nativeTinyCcProofMountsTheCandidateReadOnlyAndValidatesGuestOutput() {
        val proofSource =
            root.resolve(
                "modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/" +
                    "ru/lazyhat/compukterkraft/impl/K16NativeTinyCcCompileTest.kt",
            )
        assertTrue(proofSource.exists(), "the native compiler proof test must exist")
        val source = proofSource.readText()

        assertTrue(source.contains("K16StaticStorageAttachment(candidatePath)"))
        assertTrue(source.contains("/sdk/bin/tcc.kx -c /work/hello.c -o /work/hello.o"))
        assertTrue(source.contains("/work/hello.o did not exist before launch"))
        assertTrue(source.contains("ERR ROFS"))
        assertTrue(source.contains("native tinycc ok\\n"))
        assertTrue(source.contains("shutdown"))
    }

    @Test
    fun localVerificationEntrypointsAreCentralized() {
        val rootBuildScript = root.resolve("build.gradle.kts").readText()
        val conventionScript = root.resolve("build-scripts/src/main/kotlin/k16-firmware-convention.gradle.kts").readText()
        val agentDocs = root.resolve("AGENTS.md").readText()

        assertTrue(rootBuildScript.contains("""tasks.register("verifyLocalFast")"""))
        assertTrue(rootBuildScript.contains("""tasks.register("verifyLocalFull")"""))
        assertTrue(rootBuildScript.contains("""tasks.register("verifyK16Runtime")"""))
        assertTrue(rootBuildScript.contains("""tasks.register("verifyK16Firmware")"""))
        assertTrue(rootBuildScript.contains("""gradle.includedBuild("build-scripts").task(":test")"""))
        assertTrue(rootBuildScript.contains(""":core:test"""))
        assertTrue(rootBuildScript.contains(""":native-runtime:test"""))
        assertTrue(rootBuildScript.contains(""":v1_21_1-common:test"""))
        assertTrue(rootBuildScript.contains(""":v1_21_1-neoforge:test"""))
        assertTrue(rootBuildScript.contains("""tasks.register<Exec>("testK16HostVmRust")"""))
        assertTrue(rootBuildScript.contains("""tasks.register<Exec>("testK16HostToolsRust")"""))
        assertTrue(rootBuildScript.contains("""dependsOn(testK16HostVmRust)"""))
        assertTrue(rootBuildScript.contains("""dependsOn(testK16HostToolsRust)"""))
        assertTrue(rootBuildScript.contains("""dependsOn(":v1_21_1-neoforge:verifyK16Runtime")"""))
        assertTrue(rootBuildScript.contains("""dependsOn(":v1_21_1-neoforge:verifyK16FirmwareArchitecture")"""))

        assertTrue(conventionScript.contains("""tasks.register<Test>("verifyK16Runtime")"""))
        assertTrue(conventionScript.contains("""tasks.register<Test>("verifyK16FirmwareArchitecture")"""))
        assertTrue(conventionScript.contains("K16ShellRuntimeSmokeTest"))
        assertTrue(conventionScript.contains("K16DynamicLoaderArchitectureTest"))
        assertTrue(conventionScript.contains("K16StorageDurabilityArchitectureTest"))
        assertTrue(conventionScript.contains("systemProperty(\"k16.vm.native.library\""))

        assertTrue(agentDocs.contains("./gradlew-sandbox-dev-parallel verifyLocalFast"))
        assertTrue(agentDocs.contains("./gradlew-sandbox-dev-parallel verifyK16Runtime"))
        assertTrue(agentDocs.contains("./gradlew-sandbox-dev-parallel verifyLocalFull"))
    }
}
