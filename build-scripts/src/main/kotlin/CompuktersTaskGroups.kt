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

import org.gradle.api.Project

private val developmentTasks =
    setOf("runClient", "runClient2", "runClient3", "runServer", "runGameTestServer", "runCreateAddonClient")
private val distributionTasks = setOf("buildCreateAddon", "buildProductionUniversalJar", "buildReleaseUniversalJar")
private val verificationTasks =
    setOf(
        "verifyLocalFast",
        "verifyLocalFull",
        "verifyAllModuleChecks",
        "verifyKotlinVmConformance",
        "verifyCreateAddon",
        "verifyNativeRuntime",
        "programRuntimeIntegrationTest",
        "endToEndTest",
        "visibleIdeLatencyPerformanceTest",
        "incrementalAnalysisPerformanceTest",
    )
private val addonSdkTasks =
    setOf(
        "stageAddonSdkMavenRepository",
        "assembleAddonGuestApiBundle",
        "assemblePlatformBundle",
        "updateAddonGuestApiAbiLock",
    )
private val developmentSupportTasks =
    setOf("developmentModJar", "devRuntimeLibrariesJar", "prepareClientDev", "prepareServerDev")
private val systemProgramTasks =
    setOf(
        "generateBootArtifact",
        "generateEditArtifact",
        "generateKotlincArtifact",
        "generateShellArtifact",
        "generateVmbenchArtifact",
        "generateVmbenchAgentArtifact",
    )
private val toolingTasks =
    setOf(
        "prepareToolingRuntimeBundle",
        "canonicalToolingRuntimeBundle",
        "toolingRuntimeManifest",
        "toolingRuntimeBundle",
        "relocatedFormatterJar",
        "prepareCompilerWorkerPayload",
        "compilerWorkerPayload",
        "prepareAnalysisWorkerPayload",
    )
private val maintenanceTasks =
    setOf(
        "cleanWorkspace",
        "downloadCompukterRuntimeBundles",
        "generateCozetteTerminalFont",
        "generateDinaTerminalFont",
        "generateProggyTinyTerminalFont",
        "verifyCozetteTerminalFont",
        "verifyDinaTerminalFont",
        "verifyProggyTinyTerminalFont",
    )
private val standardVerificationTasks = setOf("check", "test")

fun Project.organizeCompuktersTasks() {
    gradle.projectsEvaluated {
        rootProject.allprojects {
            tasks.configureEach {
                group =
                    when {
                        name in developmentTasks -> "compukters development"
                        name in distributionTasks -> "compukters distribution"
                        name in verificationTasks -> "compukters verification"
                        name in addonSdkTasks -> "compukters addon sdk"
                        name in developmentSupportTasks -> "compukters development support"
                        name in systemProgramTasks -> "compukters system programs"
                        name in toolingTasks -> "compukters tooling"
                        name in maintenanceTasks -> "compukters maintenance"
                        name.startsWith("cargoBuildCompukter") ||
                            name.startsWith("testCompukter") ||
                            name.startsWith("fmtCompukter") ||
                            name.startsWith("clippyCompukter") -> "compukters native"
                        group == "release" -> "compukters release"
                        group == "verification" && name !in standardVerificationTasks -> "compukters verification details"
                        else -> group
                    }
            }
        }
    }
}
