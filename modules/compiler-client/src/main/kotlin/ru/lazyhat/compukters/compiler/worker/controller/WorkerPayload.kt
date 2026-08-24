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

package ru.lazyhat.compukters.compiler.worker.controller

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

data class WorkerPayloadFile(
    val path: String,
    val bytes: Long,
    val sha256: Hash256,
)

data class WorkerPayloadManifest(
    val identity: WorkerIdentity,
    val mainClass: String,
    val files: List<WorkerPayloadFile>,
    val payloadHash: Hash256,
) {
    companion object {
        fun create(
            identity: WorkerIdentity,
            mainClass: String,
            files: Map<String, ByteArray>,
        ): WorkerPayloadManifest {
            require(mainClass.isNotBlank()) { "worker main class must not be blank" }
            val records =
                files.entries
                    .map { (path, bytes) ->
                        validatePayloadPath(path)
                        WorkerPayloadFile(path, bytes.size.toLong(), sha256(bytes))
                    }.sortedBy(WorkerPayloadFile::path)
            require(records.map(WorkerPayloadFile::path).distinct().size == records.size) { "duplicate payload path" }
            val payloadHash = hashManifest(identity, mainClass, records)
            return WorkerPayloadManifest(identity.copy(payloadHash = payloadHash), mainClass, records, payloadHash)
        }
    }
}

fun interface WorkerPayloadSource {
    fun open(path: String): InputStream
}

data class PublishedWorkerPayload(
    val root: Path,
    val manifest: WorkerPayloadManifest,
    val classpath: List<Path>,
)

data class WorkerPayloadLimits(
    val files: Int = 128,
    val bytes: Long = 512L * 1024 * 1024,
)

class WorkerPayloadException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

object WorkerPayloadPublisher {
    fun publish(
        manifest: WorkerPayloadManifest,
        source: WorkerPayloadSource,
        cacheRoot: Path,
        limits: WorkerPayloadLimits = WorkerPayloadLimits(),
    ): PublishedWorkerPayload {
        validateManifest(manifest)
        require(limits.files >= 0) { "payload file limit must not be negative" }
        require(limits.bytes >= 0) { "payload byte limit must not be negative" }
        require(manifest.files.size <= limits.files) { "payload file count exceeds limit" }
        val totalBytes = manifest.files.fold(0L) { total, file -> Math.addExact(total, file.bytes) }
        require(totalBytes <= limits.bytes) { "payload bytes exceed limit" }
        cacheRoot.createDirectories()
        val destination = cacheRoot.resolve(manifest.payloadHash.hex())
        if (destination.exists()) return validatePublished(destination, manifest)

        val staging = cacheRoot.resolve(".staging-${manifest.payloadHash.hex()}-${UUID.randomUUID()}")
        staging.createDirectories()
        try {
            manifest.files.forEach { file -> publishFile(staging, file, source) }
            writeManifest(staging, manifest)
            forceDirectory(staging)
            movePublished(staging, destination)
            return validatePublished(destination, manifest)
        } catch (exception: Exception) {
            deleteTree(staging)
            throw if (exception is WorkerPayloadException) exception else WorkerPayloadException("payload publication failed", exception)
        }
    }

    private fun publishFile(
        staging: Path,
        file: WorkerPayloadFile,
        source: WorkerPayloadSource,
    ) {
        val target = staging.resolve(file.path).normalize()
        if (!target.startsWith(staging)) throw WorkerPayloadException("payload path escapes staging directory")
        target.parent.createDirectories()
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        source.open(file.path).use { input ->
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    written = Math.addExact(written, count.toLong())
                    if (written > file.bytes) throw WorkerPayloadException("payload file exceeds declared size: ${file.path}")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        if (written != file.bytes || Hash256.of(digest.digest()) != file.sha256) {
            throw WorkerPayloadException("payload file hash mismatch: ${file.path}")
        }
        forceFile(target)
    }

    private fun validatePublished(
        root: Path,
        manifest: WorkerPayloadManifest,
    ): PublishedWorkerPayload {
        val expectedManifest = manifest.canonicalText().encodeToByteArray()
        val manifestPath = root.resolve(MANIFEST_FILE)
        if (!manifestPath.isRegularFile() || !Files.readAllBytes(manifestPath).contentEquals(expectedManifest)) {
            throw WorkerPayloadException("published payload manifest is missing or invalid")
        }
        val classpath =
            manifest.files.map { file ->
                val path = root.resolve(file.path).normalize()
                if (!path.startsWith(root) || !path.isRegularFile() || path.fileSize() != file.bytes) {
                    throw WorkerPayloadException("published payload file is missing or invalid: ${file.path}")
                }
                val digest = Files.newInputStream(path).use(::sha256)
                if (digest != file.sha256) throw WorkerPayloadException("published payload hash mismatch: ${file.path}")
                path
            }
        return PublishedWorkerPayload(root, manifest, classpath)
    }

    private const val MANIFEST_FILE = "worker.payload"
}

private fun validateManifest(manifest: WorkerPayloadManifest) {
    if (manifest.mainClass.isBlank() || '\u0000' in manifest.mainClass || '\n' in manifest.mainClass || '\r' in manifest.mainClass) {
        throw WorkerPayloadException("worker main class is invalid")
    }
    val paths = manifest.files.map(WorkerPayloadFile::path)
    if (paths != paths.sorted() || paths.distinct().size != paths.size) {
        throw WorkerPayloadException("payload paths must be unique and canonically ordered")
    }
    if (manifest.identity.payloadHash != manifest.payloadHash) {
        throw WorkerPayloadException("worker identity payload hash mismatch")
    }
    manifest.files.forEach { file ->
        try {
            validatePayloadPath(file.path)
            require(file.bytes >= 0) { "payload file size must not be negative" }
        } catch (exception: IllegalArgumentException) {
            throw WorkerPayloadException("payload manifest is invalid", exception)
        }
    }
    if (manifest.payloadHash != hashManifest(manifest.identity, manifest.mainClass, manifest.files)) {
        throw WorkerPayloadException("payload manifest hash mismatch")
    }
}

private fun WorkerPayloadManifest.canonicalText(): String =
    buildString {
        appendLine("format=1")
        appendLine("compiler=${identity.compilerVersion}")
        appendLine("language=${identity.languageVersion}")
        appendLine("codegenAbi=${identity.codegenAbi}")
        appendLine("artifactWriter=${identity.artifactWriterVersion}")
        appendLine("mainClass=$mainClass")
        appendLine("payloadSha256=${payloadHash.hex()}")
        files.forEach { file -> appendLine("file=${file.path}\t${file.bytes}\t${file.sha256.hex()}") }
    }

private fun writeManifest(
    staging: Path,
    manifest: WorkerPayloadManifest,
) {
    val target = staging.resolve("worker.payload")
    Files.writeString(target, manifest.canonicalText(), StandardCharsets.UTF_8)
    forceFile(target)
}

private fun movePublished(
    staging: Path,
    destination: Path,
) {
    try {
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        moveWithoutReplacement(staging, destination)
    } catch (exception: IOException) {
        if (destination.exists()) {
            deleteTree(staging)
        } else {
            throw exception
        }
    }
}

private fun moveWithoutReplacement(
    staging: Path,
    destination: Path,
) {
    try {
        Files.move(staging, destination)
    } catch (_: FileAlreadyExistsException) {
        deleteTree(staging)
    } catch (exception: IOException) {
        if (destination.exists()) {
            deleteTree(staging)
        } else {
            throw exception
        }
    }
}

private fun forceFile(path: Path) {
    java.nio.channels.FileChannel
        .open(path, java.nio.file.StandardOpenOption.WRITE)
        .use { channel -> channel.force(true) }
}

private fun forceDirectory(path: Path) {
    try {
        java.nio.channels.FileChannel
            .open(path, java.nio.file.StandardOpenOption.READ)
            .use { channel -> channel.force(true) }
    } catch (_: UnsupportedOperationException) {
        // Some file systems do not support opening directories as channels.
    } catch (_: IOException) {
        // File contents were already forced; directory fsync is best-effort across platforms.
    }
}

internal fun validatePayloadPath(path: String) {
    VirtualSourcePath.of(path)
    require(path.startsWith("lib/") && path.endsWith(".jar")) { "payload entries must be jars below lib/" }
}

internal fun hashManifest(
    identity: WorkerIdentity,
    mainClass: String,
    files: List<WorkerPayloadFile>,
): Hash256 {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("Compukter compiler worker payload v1\u0000".encodeToByteArray())
    listOf(identity.compilerVersion, identity.languageVersion, mainClass).forEach { value ->
        digest.update(value.encodeToByteArray())
        digest.update(0)
    }
    updateUInt(digest, identity.codegenAbi)
    updateUInt(digest, identity.artifactWriterVersion)
    digest.update(identity.standardLibraryAbi.toByteArray())
    files.forEach { file ->
        digest.update(file.path.encodeToByteArray())
        digest.update(0)
        updateLong(digest, file.bytes)
        digest.update(file.sha256.toByteArray())
    }
    return Hash256.of(digest.digest())
}

private fun sha256(bytes: ByteArray): Hash256 = Hash256.of(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun sha256(input: InputStream): Hash256 {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return Hash256.of(digest.digest())
        digest.update(buffer, 0, count)
    }
}

private fun updateUInt(
    digest: MessageDigest,
    value: UInt,
) {
    repeat(4) { digest.update((value shr (it * 8)).toByte()) }
}

private fun updateLong(
    digest: MessageDigest,
    value: Long,
) {
    require(value >= 0)
    repeat(8) { digest.update((value ushr (it * 8)).toByte()) }
}

private fun deleteTree(root: Path) {
    if (!root.exists()) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
