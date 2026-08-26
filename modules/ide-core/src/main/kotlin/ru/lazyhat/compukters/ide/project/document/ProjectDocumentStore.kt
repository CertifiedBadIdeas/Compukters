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

package ru.lazyhat.compukters.ide.project.document

import ru.lazyhat.compukters.compiler.project.ProjectSnapshotLoader
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.TomlSupport
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.fs.SecureProjectFiles
import ru.lazyhat.compukters.ide.project.fs.SecureWriteResult
import java.security.MessageDigest

class ProjectDocumentException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class ProjectDocumentStore internal constructor(
    private val handle: ProjectHandle,
    private val limits: ProjectLimits,
    private val writeHook: (ProjectWriteStep) -> Unit,
) {
    constructor(
        handle: ProjectHandle,
        limits: ProjectLimits = ProjectLimits(),
    ) : this(handle, limits, {})

    fun open(path: ProjectPath): DocumentSnapshot =
        try {
            val content =
                SecureProjectFiles.readSource(handle.identity, path, limits.sourceFileBytes)
                    ?: throw ProjectDocumentException("source file does not exist: ${path.value}")
            val text = TomlSupport.decodeStrictUtf8(content)
            DocumentSnapshot(path, text, FileRevision.Present(hash(content)))
        } catch (exception: ProjectDocumentException) {
            throw exception
        } catch (exception: Exception) {
            throw ProjectDocumentException("failed to open source: ${path.value}", exception)
        }

    fun save(
        path: ProjectPath,
        expected: FileRevision,
        text: String,
    ): DocumentSaveResult {
        if (!handle.isValid()) return DocumentSaveResult.ProjectInvalidated
        return try {
            val content =
                try {
                    TomlSupport.strictUtf8(text)
                } catch (exception: Exception) {
                    throw ProjectDocumentException("source text must be strict UTF-8", exception)
                }
            if (content.size > limits.sourceFileBytes) throw ProjectDocumentException("source exceeds per-file byte limit")

            val currentBytes = SecureProjectFiles.readSource(handle.identity, path, limits.sourceFileBytes)
            val currentRevision = currentBytes?.let { FileRevision.Present(hash(it)) } ?: FileRevision.Absent
            if (currentRevision != expected) return DocumentSaveResult.Conflict(expected, currentRevision)
            validateProjectBounds(path, content.size, currentBytes?.size)

            when (
                val result =
                    SecureProjectFiles.writeSource(
                        handle.identity,
                        path,
                        expected,
                        content,
                        limits.sourceFileBytes,
                        writeHook,
                    )
            ) {
                is SecureWriteResult.Saved -> {
                    DocumentSaveResult.Saved(DocumentSnapshot(path, text, result.revision))
                }

                is SecureWriteResult.Conflict -> {
                    DocumentSaveResult.Conflict(expected, result.actual)
                }
            }
        } catch (exception: ProjectDocumentException) {
            throw exception
        } catch (exception: Exception) {
            if (!handle.isValid()) {
                DocumentSaveResult.ProjectInvalidated
            } else {
                throw ProjectDocumentException("failed to save source: ${path.value}", exception)
            }
        }
    }

    private fun validateProjectBounds(
        path: ProjectPath,
        newBytes: Int,
        oldBytes: Int?,
    ) {
        val snapshot =
            ProjectSnapshotLoader.loadSourceSet(
                handle.canonicalPath,
                WorkerLimits(
                    sourceFiles = limits.sourceFiles,
                    sourceFileBytes = limits.sourceFileBytes,
                    sourceBytes = limits.sourceBytes,
                ),
            )
        val existing = snapshot.sources.singleOrNull { it.path.value == path.value }
        check((oldBytes == null) == (existing == null)) { "source set changed during save admission" }
        val newCount = snapshot.sources.size + if (existing == null) 1 else 0
        if (newCount > limits.sourceFiles) throw ProjectDocumentException("project source count exceeds limit")
        val total = Math.addExact(snapshot.totalSourceBytes - (oldBytes ?: 0), newBytes.toLong())
        if (total > limits.sourceBytes.toLong()) throw ProjectDocumentException("project source bytes exceed limit")
    }

    private fun hash(content: ByteArray): Hash256 = Hash256.of(MessageDigest.getInstance("SHA-256").digest(content))
}
