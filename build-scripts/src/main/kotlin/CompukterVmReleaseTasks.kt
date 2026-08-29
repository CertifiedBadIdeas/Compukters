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
import org.gradle.api.tasks.Exec
import java.io.File

private data class CompukterVmReleaseTaskSpec(
    val name: String,
    val description: String,
    val xtaskArguments: List<String>,
)

private val compukterVmReleaseTaskSpecs =
    listOf(
        CompukterVmReleaseTaskSpec(
            name = "checkCompukterVmRelease",
            description = "Checks the canonical Compukter VM release state.",
            xtaskArguments = listOf("check"),
        ),
        CompukterVmReleaseTaskSpec(
            name = "bumpCompukterVmRevision",
            description = "Bumps the compatible Compukter VM Runtime revision.",
            xtaskArguments = listOf("bump", "revision"),
        ),
        CompukterVmReleaseTaskSpec(
            name = "bumpCompukterVmAbi",
            description = "Bumps the Compukter VM Runtime ABI version.",
            xtaskArguments = listOf("bump", "abi"),
        ),
        CompukterVmReleaseTaskSpec(
            name = "releaseCompukterVm",
            description = "Verifies and creates the local Compukter VM Runtime release tag.",
            xtaskArguments = listOf("release"),
        ),
    )

fun Project.registerCompukterVmReleaseTasks(compukterVmRoot: File) {
    compukterVmReleaseTaskSpecs.forEach { spec ->
        tasks.register(spec.name, Exec::class.java) {
            description = spec.description
            group = "release"
            workingDir(compukterVmRoot)
            doFirst {
                check(compukterVmRoot.resolve("Cargo.toml").isFile) {
                    "Compukter-VM submodule is not initialized; run: git submodule update --init --recursive"
                }
            }
            commandLine("cargo", "xtask", *spec.xtaskArguments.toTypedArray())
            standardInput = System.`in`
            outputs.upToDateWhen { false }
        }
    }
}
