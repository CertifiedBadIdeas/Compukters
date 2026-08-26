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
) : AutoCloseable {
    override fun close() {
        environment.close()
        root.toFile().deleteRecursively()
    }
}

internal class SnapshotAdmission(
    private val temporaryRoot: Path,
    private val standardLibrary: Path,
    private val jdkHome: Path,
) {
    fun admit(request: OpenSnapshotRequest): AdmittedK2Snapshot {
        val binaryRoots =
            request.profile.bundles.map { bundle ->
                val classRoot = validatedRegularFile(bundle.classRoot, "bundle class root")
                val actualHash = sha256(classRoot)
                require(actualHash.contentEquals(bundle.identity.hash.toByteArray())) {
                    "bundle class root hash mismatch: ${bundle.identity.name}"
                }
                bundle.sourceRoot?.let { validatedRegularFile(it, "bundle source root") }
                classRoot
            }
        val root = temporaryRoot.resolve("snapshot-${UUID.randomUUID()}").toAbsolutePath().normalize()
        val sourceRoot = root.resolve("source")
        sourceRoot.createDirectories()
        try {
            request.sources.sources.forEach { source ->
                val target = sourceRoot.resolve(source.path.value).normalize()
                require(target.startsWith(sourceRoot)) { "source path escapes snapshot root" }
                val text = decodeStrict(source.content.toByteArray())
                target.parent.createDirectories()
                Files.writeString(target, text, StandardCharsets.UTF_8)
            }
            val environment = K2ProjectEnvironment.create(sourceRoot, standardLibrary, binaryRoots, jdkHome)
            return AdmittedK2Snapshot(request.identity, root, environment)
        } catch (exception: Exception) {
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
