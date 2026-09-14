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

package ru.lazyhat.compukters.ide.analysis.k2.standalone

import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import ru.lazyhat.compukters.addon.api.AddonGuestApiBundle
import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.k2.query.GlobalCompletionIndex
import ru.lazyhat.compukters.ide.analysis.protocol.OpenSnapshotRequest
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformModuleGraph
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.k2.CompuktersAnalysisPlatformContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories

internal class AdmittedK2Snapshot(
    val identity: AnalysisSnapshotIdentity,
    val environment: K2ProjectEnvironment,
    val files: Map<VirtualSourcePath, KtFile>,
    val sourceLengthsUtf16: Map<VirtualSourcePath, Int>,
    val moduleIdentities: Map<PlatformModuleId, AnalysisModuleIdentity>,
    val platformSourceFiles: Map<VirtualSourcePath, KtFile>,
    val platform: CompuktersAnalysisPlatformContext,
    val projectCompletionIndex: GlobalCompletionIndex,
    val platformCompletionIndex: GlobalCompletionIndex,
)

internal class SnapshotAdmission(
    private val temporaryRoot: Path,
    private val platformBundle: PlatformBundle,
    private val sourceUpdater: K2SourceUpdater = DocumentK2SourceUpdater,
) {
    fun admit(request: OpenSnapshotRequest): IncrementalK2Workspace {
        require(
            request.profile.platform.abi
                .toByteArray()
                .contentEquals(platformBundle.identity.contentHash.toByteArray()),
        ) {
            "analysis platform ABI does not match worker platform"
        }
        val addonBundles =
            request.profile.platform.addonBundles.map { payload ->
                AddonGuestApiBundleCodec.decode(payload.content.toByteArray()).also { bundle ->
                    require(bundle.identity.id == payload.identity.name) { "analysis addon bundle identity mismatch" }
                    require(
                        bundle.identity.contentHash
                            .toByteArray()
                            .contentEquals(payload.identity.hash.toByteArray()),
                    ) {
                        "analysis addon bundle content hash mismatch"
                    }
                    require(bundle.identity.platformAbi == platformBundle.identity.platformAbi) {
                        "analysis addon bundle platform ABI mismatch"
                    }
                }
            }
        val addonById = addonBundles.associateBy { it.identity.id }
        require(addonById.size == addonBundles.size) { "duplicate analysis addon bundle identity" }
        val addonByName = addonBundles.associateBy { it.moduleDescriptor.id.toString() }
        require(addonByName.size == addonBundles.size) { "duplicate analysis addon bundle module identity" }
        val packagedByName = platformBundle.modules.associateBy { it.id.toString() }
        require(addonByName.keys.intersect(packagedByName.keys).isEmpty()) { "analysis addon bundle shadows packaged module" }
        val mergedBundle =
            PlatformBundle(
                platformBundle.identity,
                platformBundle.builtins,
                platformBundle.modules + addonBundles.map(AddonGuestApiBundle::moduleDescriptor),
            )
        val requestedModules =
            request.profile.platform.modules.associate { admitted ->
                val id = platformModuleId(admitted.identity.name)
                val module =
                    if (id == platformBundle.builtins.id) {
                        platformBundle.builtins
                    } else {
                        mergedBundle.modules.singleOrNull { it.id == id }
                    }
                        ?: error("analysis platform module is unavailable: $id")
                val expectedHash =
                    addonByName[admitted.identity.name]?.identity?.contentHash ?: PlatformBundleCodec.moduleContentHash(module)
                require(
                    admitted.identity.hash
                        .toByteArray()
                        .contentEquals(expectedHash.toByteArray()),
                ) {
                    "analysis platform module hash mismatch: $id"
                }
                id to admitted.identity
            }
        val selectedModules = requestedModules.keys - platformBundle.builtins.id
        val selectedExternal = selectedModules.mapTo(mutableSetOf(), PlatformModuleId::toString) - packagedByName.keys
        require(selectedExternal.all(addonByName::containsKey)) { "selected external analysis modules are missing addon payloads" }
        val graph = PlatformModuleGraph(mergedBundle)
        addonBundles.forEach { bundle -> graph.resolve(setOf(bundle.moduleDescriptor.id)) }
        val resolvedModules = graph.resolve(selectedModules).modules
        require(resolvedModules.mapTo(mutableSetOf()) { it.id } == selectedModules) {
            "analysis platform module selection is not dependency-closed"
        }
        val attachedSourceRoot =
            request.profile.platform.sourceRoot
                ?.let { validatedRegularFile(it, "platform source root") }
        val root = temporaryRoot.resolve("snapshot-${UUID.randomUUID()}").toAbsolutePath().normalize()
        val sourceRoot = root.resolve("source")
        sourceRoot.createDirectories()
        var environment: K2ProjectEnvironment? = null
        try {
            val sourceLengths = mutableMapOf<VirtualSourcePath, Int>()
            request.sources.sources.forEach { source ->
                val target = sourceRoot.resolve(source.path.value).normalize()
                require(target.startsWith(sourceRoot)) { "source path escapes snapshot root" }
                val text = decodeStrict(source.content.toByteArray())
                sourceLengths[source.path] = text.length
                target.parent.createDirectories()
                Files.writeString(target, text, StandardCharsets.UTF_8)
            }
            environment = K2ProjectEnvironment.create(sourceRoot, mergedBundle, selectedModules)
            val platform =
                CompuktersAnalysisPlatformContext(
                    listOf(platformBundle.builtins) + resolvedModules,
                )
            val files =
                environment.session.modulesWithFiles.values
                    .flatten()
                    .filterIsInstance<KtFile>()
                    .filter { file -> file.virtualFilePath.startsWith(sourceRoot.toString()) && "!/" !in file.virtualFilePath }
                    .associateBy { file ->
                        val physical = Path.of(file.virtualFilePath).toAbsolutePath().normalize()
                        VirtualSourcePath.kotlin(sourceRoot.relativize(physical).toString().replace('\\', '/'))
                    }
            require(files.keys == sourceLengths.keys) { "standalone K2 source mapping differs from admitted snapshot" }
            val projectCompletionIndex = GlobalCompletionIndex.project(files)
            val addonIdentities =
                addonBundles.associate { bundle ->
                    bundle.moduleDescriptor.id to
                        AnalysisModuleIdentity(
                            bundle.moduleDescriptor.id.toString(),
                            Hash256.of(bundle.identity.contentHash.toByteArray()),
                        )
                }
            val platformSourceFiles =
                attachedSourceRoot?.let { loadPlatformSourceFiles(environment, it, request) }.orEmpty() +
                    loadAddonSourceFiles(environment, addonBundles, request)
            return IncrementalK2Workspace(
                request.identity,
                root,
                environment,
                files.toMap(),
                request.sources,
                sourceLengths.toMap(),
                requestedModules,
                platformSourceFiles,
                platform,
                sourceUpdater,
                projectCompletionIndex,
                GlobalCompletionIndex.platform(mergedBundle, addonIdentities),
            )
        } catch (exception: Exception) {
            environment?.close()
            root.toFile().deleteRecursively()
            throw exception
        }
    }

    private fun loadAddonSourceFiles(
        environment: K2ProjectEnvironment,
        bundles: List<AddonGuestApiBundle>,
        request: OpenSnapshotRequest,
    ): Map<VirtualSourcePath, KtFile> {
        val factory = KtPsiFactory(environment.session.project, markGenerated = false)
        val sources = bundles.flatMap { bundle -> bundle.moduleDescriptor.sources }
        require(sources.size <= request.limits.sourceFiles) { "addon platform source count exceeds analysis limit" }
        require(sources.all { source -> source.content.size <= request.limits.sourceFileBytes }) {
            "addon platform source file exceeds analysis limit"
        }
        require(sources.sumOf { source -> source.content.size.toLong() } <= request.limits.sourceBytes.toLong()) {
            "addon platform source bytes exceed analysis limit"
        }
        return sources
            .associate { source ->
                val path = VirtualSourcePath.kotlin(source.path)
                path to factory.createFile(path.value.substringAfterLast('/'), decodeStrict(source.content.toByteArray()))
            }
    }

    private fun decodeStrict(bytes: ByteArray): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun loadPlatformSourceFiles(
        environment: K2ProjectEnvironment,
        sourceArchive: Path,
        request: OpenSnapshotRequest,
    ): Map<VirtualSourcePath, KtFile> {
        val root =
            requireNotNull(StandardFileSystems.jar().findFileByPath("$sourceArchive!/")) {
                "platform source archive is not visible to the standalone VFS"
            }
        val files = linkedMapOf<VirtualSourcePath, KtFile>()
        var sourceCount = 0
        var totalBytes = 0L

        fun visit(file: VirtualFile) {
            if (file.isDirectory) {
                file.children.sortedBy { it.name }.forEach(::visit)
                return
            }
            if (file.extension != "kt") return
            require(sourceCount < request.limits.sourceFiles) { "platform source count exceeds analysis limit" }
            require(file.length <= request.limits.sourceFileBytes) { "platform source file exceeds analysis limit" }
            sourceCount += 1
            totalBytes += file.length
            require(totalBytes <= request.limits.sourceBytes) { "platform source bytes exceed analysis limit" }
            val path = VirtualSourcePath.kotlin(file.path.removePrefix(root.path).removePrefix("/"))
            val psi = PsiManager.getInstance(environment.session.project).findFile(file) as? KtFile
            requireNotNull(psi) { "platform source is not Kotlin PSI: ${path.value}" }
            val decoded = file.inputStream.use { input -> decodeStrict(input.readAllBytes()) }
            require(decoded == psi.text) { "platform source PSI differs from strict UTF-8 content: ${path.value}" }
            require(files.put(path, psi) == null) { "duplicate platform source path: ${path.value}" }
        }
        visit(root)
        return files.toMap()
    }

    private fun validatedRegularFile(
        value: String,
        label: String,
    ): Path {
        val path = Path.of(value)
        require(path.isAbsolute && path.normalize() == path) { "$label must be an absolute normalized path" }
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "$label is missing or is not a regular file"
        }
        return path
    }

    private fun platformModuleId(value: String): PlatformModuleId {
        val components = value.split(':')
        require(components.size == 2) { "invalid analysis platform module ID: $value" }
        return PlatformModuleId(components[0], components[1])
    }
}
