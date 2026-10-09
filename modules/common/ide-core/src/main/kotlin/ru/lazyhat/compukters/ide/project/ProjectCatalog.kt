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

package ru.lazyhat.compukters.ide.project

import ru.lazyhat.compukters.ide.project.fs.ProjectRootIdentity
import ru.lazyhat.compukters.ide.project.fs.SecureProjectFileException
import ru.lazyhat.compukters.ide.project.fs.SecureProjectFiles
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.util.UUID

class ProjectCatalogException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

enum class ProjectCreationStep {
    STAGING_CREATED,
    MANIFEST_WRITTEN,
    SOURCE_WRITTEN,
    BEFORE_PUBLISH,
}

class ProjectCatalog private constructor(
    private val rootIdentity: ProjectRootIdentity,
    private val limits: ProjectLimits,
    private val creationHook: (ProjectCreationStep) -> Unit,
) {
    fun projects(): List<ProjectDescriptor> =
        catalogOperation("list projects") { root ->
            buildList {
                root.forEach { entry ->
                    val name = entry.fileName
                    SecureProjectFiles.validateFilename(name)
                    val directoryName = name.toString()
                    if (directoryName.startsWith(STAGING_PREFIX)) return@forEach
                    if (directoryName.startsWith(REGISTRATION_PREFIX)) {
                        val registeredPath = SecureProjectFiles.readText(root, directoryName, limits.pathUtf8Bytes)
                        try {
                            add(describe(directoryName, Path.of(registeredPath)))
                        } catch (_: NoSuchFileException) {
                            // A temporarily unavailable external root does not hide the other projects.
                        }
                        return@forEach
                    }
                    validateDirectoryName(directoryName)
                    add(describeOwned(root, directoryName))
                }
            }.sortedWith { left, right -> TomlSupport.utf8Comparator.compare(left.directoryName, right.directoryName) }
        }

    private fun describeOwned(
        root: SecureDirectoryStream<Path>,
        directoryName: String,
    ): ProjectDescriptor {
        val name = Path.of(directoryName)
        val attributes = SecureProjectFiles.attributes(root, name)
        if (attributes.isSymbolicLink || !attributes.isDirectory) {
            throw ProjectCatalogException("project catalog contains an unsafe entry: $directoryName")
        }
        return root.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use {
            val fileKey =
                attributes.fileKey() ?: throw ProjectCatalogException("filesystem does not expose stable project identity")
            val identity =
                ProjectRootIdentity(
                    rootIdentity.canonicalPath.resolve(directoryName).toRealPath(LinkOption.NOFOLLOW_LINKS),
                    fileKey,
                )
            ProjectDescriptor(directoryName, ProjectHandle(directoryName, identity))
        }
    }

    /** Renames an owned folder; the project manifest and source contents stay intact. */
    fun rename(
        project: ProjectDescriptor,
        name: String,
    ): ProjectDescriptor {
        require(!project.external) { "external project folders cannot be renamed here" }
        validateDirectoryName(name)
        return catalogOperation("rename project") { root ->
            validateOwnedIdentity(root, project)
            if (name == project.directoryName) return@catalogOperation describeOwned(root, name)
            if (SecureProjectFiles.attributesOrNull(root, Path.of(name)) != null) {
                throw ProjectCatalogException("project folder already exists: $name")
            }
            root.move(Path.of(project.directoryName), root, Path.of(name))
            describeOwned(root, name)
        }
    }

    /** Owned projects delete their folder; external projects only remove their registration. */
    fun remove(project: ProjectDescriptor) {
        catalogOperation("remove project") { root ->
            if (project.external) {
                SecureProjectFiles.validateFilename(Path.of(project.directoryName))
                val registered = SecureProjectFiles.readText(root, project.directoryName, limits.pathUtf8Bytes)
                if (Path.of(registered) != project.handle.canonicalPath) {
                    throw ProjectCatalogException("external project registration changed")
                }
                root.deleteFile(Path.of(project.directoryName))
            } else {
                validateOwnedIdentity(root, project)

                // Walk relative to opened directory handles and never follow symbolic links.
                fun removeEntry(
                    parent: SecureDirectoryStream<Path>,
                    name: Path,
                ) {
                    val attributes = SecureProjectFiles.attributes(parent, name)
                    if (attributes.isDirectory && !attributes.isSymbolicLink) {
                        parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { child ->
                            child.map { it.fileName }.forEach { removeEntry(child, it) }
                        }
                        if (SecureProjectFiles.attributes(parent, name).fileKey() != attributes.fileKey()) {
                            throw ProjectCatalogException("project entry changed during deletion")
                        }
                        parent.deleteDirectory(name)
                    } else {
                        parent.deleteFile(name)
                    }
                }
                removeEntry(root, Path.of(project.directoryName))
            }
        }
    }

    private fun validateOwnedIdentity(
        root: SecureDirectoryStream<Path>,
        project: ProjectDescriptor,
    ) {
        validateDirectoryName(project.directoryName)
        val attributes = SecureProjectFiles.attributesOrNull(root, Path.of(project.directoryName))
        if (project.handle.canonicalPath != rootIdentity.canonicalPath.resolve(project.directoryName) ||
            attributes?.fileKey() != project.handle.identity.fileKey || attributes.isSymbolicLink || !attributes.isDirectory
        ) {
            throw ProjectCatalogException("project folder identity changed: ${project.directoryName}")
        }
    }

    /** Registers an existing root in place. Reopening the catalog preserves this project identity. */
    fun register(projectRoot: Path): ProjectDescriptor {
        val identity = SecureProjectFiles.identity(projectRoot)
        val id = "$REGISTRATION_PREFIX${UUID.randomUUID()}"
        val descriptor = ProjectDescriptor(id, ProjectHandle(id, identity))
        readManifest(descriptor.handle)
        projects().firstOrNull { it.handle.identity.canonicalPath == identity.canonicalPath }?.let {
            check(it.handle.identity == identity) { "project changed during registration" }
            return it
        }
        val content = TomlSupport.strictUtf8(identity.canonicalPath.toString())
        require(content.size <= limits.pathUtf8Bytes) { "registered project path exceeds byte limit" }
        catalogOperation("register project") { root ->
            check(descriptor.handle.isValid()) { "project changed during registration" }
            writeNew(root, id, content)
        }
        return descriptor
    }

    /** Materializes a project into an owned staging directory and publishes only admitted content. */
    fun importProject(
        name: String,
        beforePublish: () -> Unit = {},
        materialize: (Path) -> Unit,
    ): ProjectDescriptor {
        validateDirectoryName(name)
        val stagingName = "$STAGING_PREFIX${UUID.randomUUID()}"
        val stagingPath = rootIdentity.canonicalPath.resolve(stagingName)
        catalogOperation("admit import") { root ->
            if (SecureProjectFiles.attributesOrNull(root, Path.of(name)) != null) throw FileAlreadyExistsException(name)
            Files.createDirectory(stagingPath)
        }
        try {
            materialize(stagingPath)
            val staged = describe(stagingName, stagingPath)
            readManifest(staged.handle)
            ru.lazyhat.compukters.ide.project.tree
                .ProjectTreeStore(staged.handle, limits)
                .scan()
            catalogOperation("publish import") { root ->
                beforePublish()
                check(staged.handle.isValid()) { "staged project changed before publication" }
                if (SecureProjectFiles.attributesOrNull(root, Path.of(name)) != null) throw FileAlreadyExistsException(name)
                root.move(Path.of(stagingName), root, Path.of(name))
            }
        } finally {
            cleanupImportedStaging(stagingName)
        }
        return catalogOperation("open published project") { root ->
            describeOwned(root, name)
        }
    }

    private fun cleanupImportedStaging(name: String) {
        if (!SecureProjectFiles.isValid(rootIdentity)) return
        runCatching {
            catalogOperation("cleanup import") { root ->
                fun remove(
                    directory: SecureDirectoryStream<Path>,
                    entry: Path,
                ) {
                    val attributes = SecureProjectFiles.attributesOrNull(directory, entry) ?: return
                    if (attributes.isDirectory && !attributes.isSymbolicLink) {
                        directory.newDirectoryStream(entry, LinkOption.NOFOLLOW_LINKS).use { child ->
                            child.map { it.fileName }.forEach { remove(child, it) }
                        }
                        directory.deleteDirectory(entry)
                    } else {
                        directory.deleteFile(entry)
                    }
                }
                remove(root, Path.of(name))
            }
        }
    }

    private fun describe(
        id: String,
        projectRoot: Path,
    ): ProjectDescriptor {
        val identity = SecureProjectFiles.identity(projectRoot)
        return ProjectDescriptor(id, ProjectHandle(id, identity))
    }

    /** Validates the selected project's current manifest without caching it in catalog rows. */
    fun readManifest(project: ProjectHandle): ProjectManifest =
        SecureProjectFiles.withValidProject(project.identity) { root ->
            try {
                ProjectManifestCodec.decode(SecureProjectFiles.readText(root, MANIFEST_FILENAME, limits.manifestBytes), limits)
            } catch (exception: ManifestException) {
                throw ProjectCatalogException(
                    "invalid project manifest: ${project.canonicalPath.fileName}: ${exception.message}",
                    exception,
                )
            }
        }

    fun create(name: String): ProjectDescriptor {
        validateDirectoryName(name)
        val manifest = ProjectManifest.of(name, emptySet(), limits)
        val stagingName = "$STAGING_PREFIX${UUID.randomUUID()}"
        val stagingPath = rootIdentity.canonicalPath.resolve(stagingName)
        try {
            catalogOperation("create project") { root ->
                if (SecureProjectFiles.attributesOrNull(root, Path.of(name)) != null) throw FileAlreadyExistsException(name)
                Files.createDirectory(stagingPath)
                creationHook(ProjectCreationStep.STAGING_CREATED)
                root.newDirectoryStream(Path.of(stagingName), LinkOption.NOFOLLOW_LINKS).use { staging ->
                    writeNew(staging, MANIFEST_FILENAME, ProjectManifestCodec.encode(manifest).encodeToByteArray())
                    creationHook(ProjectCreationStep.MANIFEST_WRITTEN)
                    Files.createDirectory(stagingPath.resolve(SOURCE_DIRECTORY))
                    staging.newDirectoryStream(Path.of(SOURCE_DIRECTORY), LinkOption.NOFOLLOW_LINKS).use { source ->
                        writeNew(source, MAIN_FILENAME, DEFAULT_MAIN.encodeToByteArray())
                    }
                    creationHook(ProjectCreationStep.SOURCE_WRITTEN)
                }
                creationHook(ProjectCreationStep.BEFORE_PUBLISH)
                if (!SecureProjectFiles.isValid(rootIdentity)) throw ProjectCatalogException("project catalog root was invalidated")
                root.move(Path.of(stagingName), root, Path.of(name))
            }
        } catch (exception: Exception) {
            cleanupStaging(stagingName)
            if (exception is IllegalArgumentException) throw exception
            throw ProjectCatalogException("failed to create project: $name", exception)
        }
        return catalogOperation("open published project") { root ->
            describeOwned(root, name)
        }
    }

    private fun validateDirectoryName(name: String) {
        ProjectManifest.validateName(name, limits)
        require(!name.startsWith(STAGING_PREFIX) && !name.startsWith(REGISTRATION_PREFIX)) { "project name is reserved" }
    }

    private fun cleanupStaging(stagingName: String) {
        if (!SecureProjectFiles.isValid(rootIdentity)) return
        runCatching {
            SecureProjectFiles.withDirectory(rootIdentity.canonicalPath) { root, _ ->
                val staging = SecureProjectFiles.attributesOrNull(root, Path.of(stagingName)) ?: return@withDirectory
                if (staging.isSymbolicLink || !staging.isDirectory) return@withDirectory
                root.newDirectoryStream(Path.of(stagingName), LinkOption.NOFOLLOW_LINKS).use { directory ->
                    runCatching {
                        directory.newDirectoryStream(Path.of(SOURCE_DIRECTORY), LinkOption.NOFOLLOW_LINKS).use { source ->
                            runCatching { source.deleteFile(Path.of(MAIN_FILENAME)) }
                        }
                    }
                    runCatching { directory.deleteDirectory(Path.of(SOURCE_DIRECTORY)) }
                    runCatching { directory.deleteFile(Path.of(MANIFEST_FILENAME)) }
                }
                runCatching { root.deleteDirectory(Path.of(stagingName)) }
            }
        }
    }

    private fun <T> catalogOperation(
        description: String,
        action: (SecureDirectoryStream<Path>) -> T,
    ): T =
        try {
            SecureProjectFiles.withDirectory(rootIdentity.canonicalPath) { root, attributes ->
                if (attributes.fileKey() != rootIdentity.fileKey) throw ProjectCatalogException("project catalog root was invalidated")
                action(root)
            }
        } catch (exception: ProjectCatalogException) {
            throw exception
        } catch (exception: Exception) {
            throw ProjectCatalogException("failed to $description", exception)
        }

    private fun writeNew(
        directory: SecureDirectoryStream<Path>,
        name: String,
        content: ByteArray,
    ) {
        directory.newByteChannel(Path.of(name), WRITE_OPTIONS).use { channel ->
            val buffer = ByteBuffer.wrap(content)
            while (buffer.hasRemaining()) channel.write(buffer)
            (channel as? FileChannel)?.force(true)
        }
    }

    companion object {
        fun open(
            projectsRoot: Path,
            limits: ProjectLimits = ProjectLimits(),
        ): ProjectCatalog = ProjectCatalog(SecureProjectFiles.identity(projectsRoot), limits) {}

        internal fun open(
            projectsRoot: Path,
            limits: ProjectLimits = ProjectLimits(),
            creationHook: (ProjectCreationStep) -> Unit,
        ): ProjectCatalog = ProjectCatalog(SecureProjectFiles.identity(projectsRoot), limits, creationHook)

        private const val MANIFEST_FILENAME = "compukter.toml"
        private const val SOURCE_DIRECTORY = "src"
        private const val MAIN_FILENAME = "main.kt"
        private const val DEFAULT_MAIN = "fun main() {\n}\n"
        private const val STAGING_PREFIX = ".creating-"
        private const val REGISTRATION_PREFIX = REGISTERED_PROJECT_PREFIX
        private val WRITE_OPTIONS: Set<OpenOption> =
            setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
    }
}
