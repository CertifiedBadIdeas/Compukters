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

package ru.lazyhat.compukters.ide.client.workspace

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.ide.git.GitCancellation
import ru.lazyhat.compukters.ide.git.GitCredentials
import ru.lazyhat.compukters.ide.git.GitOperation
import ru.lazyhat.compukters.ide.git.GitResult
import ru.lazyhat.compukters.ide.project.ProjectDescriptor
import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.document.DocumentSaveResult
import ru.lazyhat.compukters.ide.project.document.DocumentSnapshot
import ru.lazyhat.compukters.ide.project.document.FileRevision
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.AdmittedProjectDelete
import ru.lazyhat.compukters.ide.project.tree.ProjectImport
import ru.lazyhat.compukters.ide.project.tree.ProjectMutationResult
import ru.lazyhat.compukters.ide.project.tree.ProjectTree
import java.util.concurrent.CompletableFuture

interface IdeWorkspace : AutoCloseable {
    fun projects(): CompletableFuture<List<ProjectDescriptor>>

    fun renameProject(
        project: ProjectDescriptor,
        name: String,
    ): CompletableFuture<List<ProjectDescriptor>> =
        CompletableFuture.failedFuture(UnsupportedOperationException("project folder rename is unavailable"))

    fun removeProject(project: ProjectDescriptor): CompletableFuture<List<ProjectDescriptor>> =
        CompletableFuture.failedFuture(UnsupportedOperationException("project removal is unavailable"))

    fun createProject(name: String): CompletableFuture<ProjectDescriptor>

    fun importProject(root: String): CompletableFuture<ProjectDescriptor> =
        CompletableFuture.failedFuture(UnsupportedOperationException("opening existing projects is unavailable"))

    fun cloneProject(
        name: String,
        remote: String,
        credentials: GitCredentials?,
        cancellation: GitCancellation,
    ): CompletableFuture<ProjectDescriptor> = CompletableFuture.failedFuture(UnsupportedOperationException("Git clone is unavailable"))

    fun git(
        project: ProjectHandle,
        operation: GitOperation,
        credentials: GitCredentials?,
        cancellation: GitCancellation,
    ): CompletableFuture<GitResult> = CompletableFuture.failedFuture(UnsupportedOperationException("Git is unavailable"))

    /** Optional, read-only editor decoration refresh; never saves or changes working files. */
    fun inspectGit(
        project: ProjectHandle,
        source: GitOperation.SourceChanges?,
        cancellation: GitCancellation,
    ): CompletableFuture<GitResult?> = CompletableFuture.completedFuture(null)

    fun tree(project: ProjectHandle): CompletableFuture<ProjectTree>

    fun open(
        project: ProjectHandle,
        path: ProjectPath,
    ): CompletableFuture<ProjectFileOpenResult>

    fun save(request: IdeSaveRequest): CompletableFuture<IdeSaveResult>

    fun admitDelete(
        project: ProjectHandle,
        path: ProjectPath,
    ): CompletableFuture<AdmittedProjectDelete>

    fun mutate(request: IdeMutationRequest): CompletableFuture<ProjectMutationResult>

    fun importTree(
        project: ProjectHandle,
        import: ProjectImport,
    ): CompletableFuture<ProjectMutationResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("project imports are unavailable"))

    fun projectConfiguration(project: ProjectHandle): CompletableFuture<IdeProjectConfiguration>

    fun buildInput(project: ProjectHandle): CompletableFuture<IdeBuildInput>
}

sealed interface ProjectFileOpenResult {
    data class Text(
        val snapshot: DocumentSnapshot,
    ) : ProjectFileOpenResult

    data class Binary(
        val path: ProjectPath,
        val bytes: Long,
    ) : ProjectFileOpenResult
}

data class IdeSaveRequest(
    val project: ProjectHandle,
    val path: ProjectPath,
    val expected: FileRevision,
    val text: String,
)

typealias IdeSaveResult = DocumentSaveResult

sealed interface IdeMutationRequest {
    val project: ProjectHandle

    data class CreateText(
        override val project: ProjectHandle,
        val path: ProjectPath,
    ) : IdeMutationRequest

    data class CreateDirectory(
        override val project: ProjectHandle,
        val path: ProjectPath,
    ) : IdeMutationRequest

    data class Rename(
        override val project: ProjectHandle,
        val source: ProjectPath,
        val target: ProjectPath,
    ) : IdeMutationRequest

    data class Delete(
        override val project: ProjectHandle,
        val admitted: AdmittedProjectDelete,
    ) : IdeMutationRequest
}

class IdeProjectConfiguration(
    val project: ProjectHandle,
    manifestBytes: ByteArray,
    lockBytes: ByteArray?,
) {
    private val storedManifestBytes = manifestBytes.copyOf()
    private val storedLockBytes = lockBytes?.copyOf()

    val manifestBytes: ByteArray get() = storedManifestBytes.copyOf()
    val lockBytes: ByteArray? get() = storedLockBytes?.copyOf()
}

class IdeBuildInput(
    val project: ProjectHandle,
    manifestBytes: ByteArray,
    lockBytes: ByteArray?,
    val sources: ProjectSnapshot,
) {
    val configuration = IdeProjectConfiguration(project, manifestBytes, lockBytes)
    val manifestBytes: ByteArray get() = configuration.manifestBytes
    val lockBytes: ByteArray? get() = configuration.lockBytes
}

sealed class IdeWorkspaceFailure(
    message: String,
) : IllegalStateException(message) {
    class Busy : IdeWorkspaceFailure("IDE workspace is busy")

    class Closed : IdeWorkspaceFailure("IDE workspace is closed")
}
