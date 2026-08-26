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
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisBundleIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.OpenSnapshotRequest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.createDirectories

internal class AdmittedK2Snapshot(
    val identity: AnalysisSnapshotIdentity,
    private val root: Path,
    val environment: K2ProjectEnvironment,
    val files: Map<VirtualSourcePath, KtFile>,
    val sourceLengthsUtf16: Map<VirtualSourcePath, Int>,
    val bundles: List<AdmittedK2Bundle>,
    val bundleSourceFiles: Map<AnalysisBundleIdentity, Map<VirtualSourcePath, KtFile>>,
) : AutoCloseable {
    override fun close() {
        environment.close()
        root.toFile().deleteRecursively()
    }
}

internal data class AdmittedK2Bundle(
    val identity: AnalysisBundleIdentity,
    val classRoot: Path,
    val sourceRoot: Path?,
)

internal class SnapshotAdmission(
    private val temporaryRoot: Path,
    private val standardLibrary: Path,
    private val jdkHome: Path,
) {
    fun admit(request: OpenSnapshotRequest): AdmittedK2Snapshot {
        val bundles =
            request.profile.bundles.map { bundle ->
                val classRoot = validatedRegularFile(bundle.classRoot, "bundle class root")
                val actualHash = sha256(classRoot)
                require(actualHash.contentEquals(bundle.identity.hash.toByteArray())) {
                    "bundle class root hash mismatch: ${bundle.identity.name}"
                }
                val sourceRoot = bundle.sourceRoot?.let { validatedRegularFile(it, "bundle source root") }
                AdmittedK2Bundle(bundle.identity, classRoot, sourceRoot)
            }
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
            environment = K2ProjectEnvironment.create(sourceRoot, standardLibrary, bundles, jdkHome)
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
            val bundleSourceFiles =
                BundleSourceBudget().let { budget ->
                    bundles.associate { bundle ->
                        val attachedFiles =
                            bundle.sourceRoot
                                ?.let { attachedRoot ->
                                    loadBundleSourceFiles(environment, attachedRoot, request, budget)
                                }.orEmpty()
                        bundle.identity to attachedFiles
                    }
                }
            return AdmittedK2Snapshot(
                request.identity,
                root,
                environment,
                files.toMap(),
                sourceLengths.toMap(),
                bundles.toList(),
                bundleSourceFiles,
            )
        } catch (exception: Exception) {
            environment?.close()
            root.toFile().deleteRecursively()
            throw exception
        }
    }

    private fun decodeStrict(bytes: ByteArray): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun loadBundleSourceFiles(
        environment: K2ProjectEnvironment,
        sourceArchive: Path,
        request: OpenSnapshotRequest,
        budget: BundleSourceBudget,
    ): Map<VirtualSourcePath, KtFile> {
        val root =
            requireNotNull(StandardFileSystems.jar().findFileByPath("$sourceArchive!/")) {
                "bundle source archive is not visible to the standalone VFS"
            }
        val files = linkedMapOf<VirtualSourcePath, KtFile>()

        fun visit(file: VirtualFile) {
            if (file.isDirectory) {
                file.children.sortedBy { it.name }.forEach(::visit)
                return
            }
            if (file.extension != "kt") return
            require(budget.sourceCount < request.limits.sourceFiles) { "bundle source count exceeds analysis limit" }
            require(file.length <= request.limits.sourceFileBytes) { "bundle source file exceeds analysis limit" }
            budget.sourceCount += 1
            budget.totalBytes += file.length
            require(budget.totalBytes <= request.limits.sourceBytes) { "bundle source bytes exceed analysis limit" }
            val path = VirtualSourcePath.kotlin(file.path.removePrefix(root.path).removePrefix("/"))
            val psi = PsiManager.getInstance(environment.session.project).findFile(file) as? KtFile
            requireNotNull(psi) { "bundle source is not Kotlin PSI: ${path.value}" }
            val decoded = file.inputStream.use { input -> decodeStrict(input.readAllBytes()) }
            require(decoded == psi.text) { "bundle source PSI differs from strict UTF-8 content: ${path.value}" }
            require(files.put(path, psi) == null) { "duplicate bundle source path: ${path.value}" }
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

    private fun sha256(path: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private companion object {
        const val HASH_BUFFER_BYTES = 16 * 1024
    }
}

private class BundleSourceBudget(
    var sourceCount: Int = 0,
    var totalBytes: Long = 0,
)
