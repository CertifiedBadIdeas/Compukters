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

package ru.lazyhat.compukters.gradle.addon

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceTask

class CompuktersAddonPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val sdkVersion =
            checkNotNull(javaClass.`package`.implementationVersion) {
                "Compukters Addon plugin JAR has no implementation version"
            }
        val extension = project.extensions.create("compuktersAddon", CompuktersAddonExtension::class.java)
        extension.toolingCoordinate.convention("ru.lazyhat.compukters:compukters-addon-tooling:$sdkVersion")
        extension.platformCoordinate.convention("ru.lazyhat.compukters:compukters-guest-platform:$sdkVersion@cpb")

        val tooling =
            project.configurations.create("compuktersAddonTooling") { configuration ->
                configuration.isCanBeConsumed = false
                configuration.isCanBeResolved = true
            }
        val platform =
            project.configurations.create("compuktersAddonPlatform") { configuration ->
                configuration.isCanBeConsumed = false
                configuration.isCanBeResolved = true
            }
        val sourceRoot = project.layout.projectDirectory.dir("src/compuktersAddon/kotlin")
        val abiLock = project.layout.projectDirectory.file("src/compuktersAddon/addon.lock")
        val descriptor = project.layout.buildDirectory.file("compukters-addon/addon.api")
        val bundle = project.layout.buildDirectory.file("compukters-addon/${project.name}.cagb")
        val generatedHostRoot = project.layout.buildDirectory.dir("generated/sources/compuktersAddon/kotlin")
        val generatedHost = generatedHostRoot.map { it.file("GeneratedAddonHostContract.kt") }

        val generateDescriptor =
            project.tasks.register("generateCompuktersAddonDescriptor") { task ->
                task.group = "build"
                task.description = "Generates the internal Compukters addon descriptor from the public Gradle DSL."
                task.outputs.file(descriptor)
                task.doLast {
                    val addon = extension.addon.get().validatedIdentity("addon")
                    val module = extension.module.get().validatedIdentity("module")
                    require(extension.capabilities.isNotEmpty()) { "compuktersAddon must declare at least one capability" }
                    val content =
                        buildString {
                            appendLine("addon $addon")
                            appendLine("module $addon:$module")
                            appendLine("version ${extension.moduleVersion.get()}")
                            appendLine("dependencies ${extension.dependencies.get().joinToString(" ")}")
                            extension.capabilities.forEach { capability ->
                                append("capability $addon ${capability.name.validatedIdentity("capability")} ")
                                append("${capability.abiMajor.get()} ${capability.abiMinor.get()}")
                                capability.bindingOwner.orNull?.let { append(" $it") }
                                appendLine()
                            }
                        }
                    descriptor.get().asFile.apply {
                        parentFile.mkdirs()
                        writeText(content)
                    }
                }
            }

        val assemble =
            project.tasks.register("assembleCompuktersAddon", JavaExec::class.java) { task ->
                task.group = "build"
                task.description = "Builds the deterministic Compukters addon Guest Kotlin bundle."
                task.dependsOn(generateDescriptor)
                task.classpath = tooling
                task.mainClass.set("ru.lazyhat.compukters.compiler.k2.engine.build.AddonGuestApiBuilderMainKt")
                task.inputs.dir(sourceRoot)
                task.inputs.file(descriptor)
                task.inputs.file(abiLock)
                task.inputs.files(platform)
                task.outputs.file(bundle)
                task.outputs.file(generatedHost)
                task.doFirst {
                    task.args =
                        listOf(
                            "--platform-input",
                            platform.singleFile.absolutePath,
                            "--sources",
                            sourceRoot.asFile.absolutePath,
                            "--descriptor",
                            descriptor.get().asFile.absolutePath,
                            "--abi-lock",
                            abiLock.asFile.absolutePath,
                            "--output",
                            bundle.get().asFile.absolutePath,
                            "--host-output",
                            generatedHost.get().asFile.absolutePath,
                        )
                }
            }
        project.tasks.register("updateCompuktersAddonAbiLock", JavaExec::class.java) { task ->
            task.group = "build setup"
            task.description = "Updates the checked-in Compukters addon ABI lock after an intentional API change."
            task.dependsOn(generateDescriptor)
            task.classpath = tooling
            task.mainClass.set("ru.lazyhat.compukters.compiler.k2.engine.build.AddonGuestApiBuilderMainKt")
            task.inputs.dir(sourceRoot)
            task.inputs.file(descriptor)
            task.inputs.files(platform)
            task.outputs.file(abiLock)
            val validationBundle = project.layout.buildDirectory.file("compukters-addon/${project.name}-abi-lock-update.cagb")
            task.outputs.file(validationBundle)
            task.doFirst {
                task.args =
                    listOf(
                        "--platform-input",
                        platform.singleFile.absolutePath,
                        "--sources",
                        sourceRoot.asFile.absolutePath,
                        "--descriptor",
                        descriptor.get().asFile.absolutePath,
                        "--abi-lock",
                        abiLock.asFile.absolutePath,
                        "--update-abi-lock",
                        "true",
                        "--output",
                        validationBundle.get().asFile.absolutePath,
                    )
            }
        }

        val consumableBundle =
            project.configurations.create("compuktersAddonBundle") { configuration ->
                configuration.isCanBeConsumed = true
                configuration.isCanBeResolved = false
            }
        project.artifacts.add(consumableBundle.name, bundle) { artifact ->
            artifact.builtBy(assemble)
            artifact.type = "cagb"
        }

        project.afterEvaluate {
            project.dependencies.add(tooling.name, extension.toolingCoordinate.get())
            project.dependencies.add(platform.name, extension.platformCoordinate.get())
        }
        project.tasks.matching { it.name == "compileKotlin" }.configureEach { task ->
            task.dependsOn(assemble)
            (task as SourceTask).source(generatedHostRoot)
        }
        project.tasks.matching { it.name == "processResources" && it is AbstractCopyTask }.configureEach { task ->
            task as AbstractCopyTask
            task.dependsOn(assemble)
            task.duplicatesStrategy = DuplicatesStrategy.FAIL
            task.from(bundle) { spec ->
                spec.into("META-INF/compukters/addons")
                spec.rename { "${extension.addon.get()}-${extension.module.get()}.cagb" }
            }
        }
        project.tasks.matching { it.name == "check" }.configureEach { task -> task.dependsOn(assemble) }
    }
}

private fun String.validatedIdentity(kind: String): String =
    also { value ->
        require(value.matches(Regex("[a-z][a-z0-9_-]*"))) {
            "Compukters addon $kind must match [a-z][a-z0-9_-]*: $value"
        }
    }
