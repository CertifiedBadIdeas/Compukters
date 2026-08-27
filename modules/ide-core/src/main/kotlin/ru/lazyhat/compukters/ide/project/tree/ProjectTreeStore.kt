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

package ru.lazyhat.compukters.ide.project.tree

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.TomlSupport
import ru.lazyhat.compukters.ide.project.document.FileRevision
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.fs.SecureProjectFileException
import ru.lazyhat.compukters.ide.project.fs.SecureProjectFiles
import java.nio.charset.CharacterCodingException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.security.MessageDigest

class ProjectTreeStore(
    private val handle: ProjectHandle,
    private val limits: ProjectLimits = ProjectLimits(),
) {
    fun scan(): ProjectTree =
        SecureProjectFiles.withValidProject(handle.identity) { root ->
            val state = ScanState()
            scanDirectory(root, emptyList(), state)
            if (!SecureProjectFiles.isValid(handle.identity)) {
                throw SecureProjectFileException("project root was invalidated while scanning")
            }
            ProjectTree.of(state.entries)
        }

    private fun scanDirectory(
        directory: SecureDirectoryStream<Path>,
        parent: List<String>,
        state: ScanState,
    ) {
        val names =
            buildList {
                directory.forEach { entry ->
                    val name = entry.fileName
                    SecureProjectFiles.validateFilename(name)
                    add(name.toString())
                }
            }.sortedWith(TomlSupport.utf8Comparator)

        names.forEach { name ->
            val components = parent + name
            if (components.size > limits.treeDepth) throw SecureProjectFileException("project tree exceeds depth limit")
            val path = ProjectPath.file(components.joinToString("/"))
            val pathBytes = TomlSupport.strictUtf8(path.value).size
            if (pathBytes > limits.pathUtf8Bytes) throw SecureProjectFileException("project path exceeds byte limit")
            state.admitMetadata(pathBytes)

            val target = Path.of(name)
            val attributes = SecureProjectFiles.attributes(directory, target)
            if (attributes.isSymbolicLink) throw SecureProjectFileException("project tree contains a symbolic link: $path")
            when {
                attributes.isDirectory -> {
                    state.entries += ProjectTreeEntry(path, ProjectFileKind.Directory, revision = null)
                    directory.newDirectoryStream(target, LinkOption.NOFOLLOW_LINKS).use { child ->
                        scanDirectory(child, components, state)
                    }
                }

                attributes.isRegularFile -> {
                    if (attributes.size() > limits.projectFileBytes.toLong()) {
                        throw SecureProjectFileException("project file exceeds byte limit: $path")
                    }
                    state.requireFileBytes(attributes.size())
                    val bytes = SecureProjectFiles.readBytes(directory, target, limits.projectFileBytes)
                    state.admitFileBytes(bytes.size)
                    val kind =
                        try {
                            TomlSupport.decodeStrictUtf8(bytes)
                            ProjectFileKind.Text(bytes.size)
                        } catch (_: CharacterCodingException) {
                            ProjectFileKind.Binary(bytes.size.toLong())
                        }
                    state.entries +=
                        ProjectTreeEntry(
                            path = path,
                            kind = kind,
                            revision = FileRevision.Present(hash(bytes)),
                        )
                }

                else -> {
                    throw SecureProjectFileException("project tree contains a special file: $path")
                }
            }
        }
    }

    private inner class ScanState {
        val entries = mutableListOf<ProjectTreeEntry>()
        private var metadataBytes = 0L
        private var fileBytes = 0L

        fun admitMetadata(pathBytes: Int) {
            if (entries.size >= limits.treeEntries) throw SecureProjectFileException("project tree exceeds entry limit")
            metadataBytes = Math.addExact(metadataBytes, pathBytes.toLong() + ENTRY_METADATA_BYTES)
            if (metadataBytes > limits.treeMetadataBytes.toLong()) {
                throw SecureProjectFileException("project tree exceeds metadata byte limit")
            }
        }

        fun admitFileBytes(bytes: Int) {
            fileBytes = Math.addExact(fileBytes, bytes.toLong())
            if (fileBytes > limits.projectBytes) throw SecureProjectFileException("project exceeds total byte limit")
        }

        fun requireFileBytes(bytes: Long) {
            if (bytes > limits.projectBytes - fileBytes) {
                throw SecureProjectFileException("project exceeds total byte limit")
            }
        }
    }

    private companion object {
        const val ENTRY_METADATA_BYTES = 48L

        fun hash(content: ByteArray): Hash256 = Hash256.of(MessageDigest.getInstance("SHA-256").digest(content))
    }
}
