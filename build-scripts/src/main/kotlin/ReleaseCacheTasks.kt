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

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class DownloadRuntimeBundles : DefaultTask() {
    @get:Internal
    abstract val contract: Property<RuntimeBundleContract>

    @get:OutputDirectory
    abstract val bundleDirectory: DirectoryProperty

    @TaskAction
    fun download() {
        val selected = contract.get()
        val directory = bundleDirectory.get().asFile
        val result = RuntimeBundleDownloadSupport.download(directory.toPath(), selected)
        logger.lifecycle("Runtime ${selected.runtimeVersion}: ${result.name.lowercase()} in $directory")
    }
}

abstract class PrepareReleaseRuntime : DefaultTask() {
    @get:Internal
    abstract val contract: Property<RuntimeBundleContract>

    @get:InputDirectory
    abstract val bundleDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val nativeResources: DirectoryProperty

    @get:Input
    abstract val transport: Property<RuntimeTransport>

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun stage() {
        val output = nativeResources.get().asFile
        fileSystem.delete { delete(output) }
        RuntimeBundleSupport.validateAndStage(
            bundleDirectory.get().asFile.toPath(), output.toPath(), contract.get(), transport.get(),
        )
    }
}

abstract class StageAddonSdk : DefaultTask() {
    @get:InputFiles
    abstract val releaseTools: ConfigurableFileCollection

    @get:Internal
    abstract val sdkDirectory: DirectoryProperty

    @get:OutputDirectory
    val toolsDirectory: Provider<Directory>
        get() = sdkDirectory.dir("tools")

    @get:OutputFile
    val descriptor: Provider<RegularFile>
        get() = sdkDirectory.file("sdk.properties")

    @get:Input
    abstract val sdkVersion: Property<String>

    @get:Input
    abstract val modVersion: Property<String>

    @get:Input
    abstract val modBuildVersion: Property<String>

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun stage() {
        val directory = sdkDirectory.get()
        fileSystem.copy {
            from(releaseTools)
            into(directory.dir("tools"))
        }
        directory.file("sdk.properties").asFile.writeText(
            "format=1\nsdkVersion=${sdkVersion.get()}\nmodVersion=${modVersion.get()}\nmodBuildVersion=${modBuildVersion.get()}\n",
        )
    }
}

abstract class VerifyUniversalReleaseState : DefaultTask() {
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:Input
    abstract val productVersion: Property<String>

    @get:Input
    abstract val runtimeBundlesConfigured: Property<Boolean>

    private fun captureGit(vararg arguments: String): String {
        val process = ProcessBuilder("git", *arguments)
            .directory(repositoryRoot.get().asFile)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trimEnd()
        check(process.waitFor() == 0) { "git ${arguments.toList()} failed: $output" }
        return output
    }

    @TaskAction
    fun verify() {
        validateUniversalReleaseState(
            UniversalReleaseState(
                version = productVersion.get(),
                runtimeBundlesConfigured = runtimeBundlesConfigured.get(),
                headTags = captureGit("tag", "--points-at", "HEAD").lineSequence().filter(String::isNotBlank).toSet(),
                worktreeStatus = captureGit("status", "--porcelain"),
                submoduleStatus = captureGit("submodule", "status", "--recursive"),
            ),
        )
    }
}
