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
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import java.nio.file.Path
import ru.lazyhat.compukters.worker.payload.WorkerPayloadManifest as GenericWorkerPayloadManifest

data class WorkerPayloadFile(
    val path: String,
    val bytes: Long,
    val sha256: Hash256,
)

class WorkerPayloadManifest private constructor(
    val identity: WorkerIdentity,
    val mainClass: String,
    val files: List<WorkerPayloadFile>,
    val payloadHash: Hash256,
    internal val generic: GenericWorkerPayloadManifest,
) {
    override fun equals(other: Any?): Boolean =
        other is WorkerPayloadManifest &&
            identity == other.identity &&
            mainClass == other.mainClass &&
            files == other.files &&
            payloadHash == other.payloadHash

    override fun hashCode(): Int = listOf(identity, mainClass, files, payloadHash).hashCode()

    companion object {
        fun create(
            identity: WorkerIdentity,
            mainClass: String,
            files: Map<String, ByteArray>,
        ): WorkerPayloadManifest = fromGeneric(generic(identity, mainClass, files))

        internal fun fromGeneric(manifest: GenericWorkerPayloadManifest): WorkerPayloadManifest {
            require(manifest.kind == COMPILER_KIND) { "worker payload kind must be compiler" }
            require(manifest.identityProperties.keys == IDENTITY_KEYS) { "compiler payload identity properties are invalid" }
            val payloadHash = Hash256.of(manifest.payloadHash.toByteArray())
            val identity =
                WorkerIdentity(
                    compilerVersion = manifest.identityProperties.getValue(COMPILER),
                    languageVersion = manifest.identityProperties.getValue(LANGUAGE),
                    codegenAbi = manifest.identityProperties.getValue(CODEGEN_ABI).toUInt(),
                    artifactWriterVersion = manifest.identityProperties.getValue(ARTIFACT_WRITER).toUInt(),
                    payloadHash = payloadHash,
                    standardLibraryAbi = Hash256.fromHex(manifest.identityProperties.getValue(STANDARD_LIBRARY_ABI)),
                )
            return WorkerPayloadManifest(
                identity,
                manifest.mainClass,
                manifest.files.map { file -> WorkerPayloadFile(file.path, file.bytes, Hash256.of(file.sha256.toByteArray())) },
                payloadHash,
                manifest,
            )
        }

        private fun generic(
            identity: WorkerIdentity,
            mainClass: String,
            files: Map<String, ByteArray>,
        ): GenericWorkerPayloadManifest =
            GenericWorkerPayloadManifest.create(
                kind = COMPILER_KIND,
                identityProperties =
                    mapOf(
                        COMPILER to identity.compilerVersion,
                        LANGUAGE to identity.languageVersion,
                        CODEGEN_ABI to identity.codegenAbi.toString(),
                        ARTIFACT_WRITER to identity.artifactWriterVersion.toString(),
                        STANDARD_LIBRARY_ABI to identity.standardLibraryAbi.hex(),
                    ),
                mainClass = mainClass,
                files = files,
            )

        private const val COMPILER_KIND = "compiler"
        private const val COMPILER = "compiler"
        private const val LANGUAGE = "language"
        private const val CODEGEN_ABI = "codegenAbi"
        private const val ARTIFACT_WRITER = "artifactWriter"
        private const val STANDARD_LIBRARY_ABI = "standardLibraryAbi"
        private val IDENTITY_KEYS = setOf(COMPILER, LANGUAGE, CODEGEN_ABI, ARTIFACT_WRITER, STANDARD_LIBRARY_ABI)
    }
}

typealias WorkerPayloadSource = ru.lazyhat.compukters.worker.payload.WorkerPayloadSource
typealias WorkerPayloadLimits = ru.lazyhat.compukters.worker.payload.WorkerPayloadLimits
typealias WorkerPayloadException = ru.lazyhat.compukters.worker.payload.WorkerPayloadException

data class PublishedWorkerPayload(
    val root: Path,
    val manifest: WorkerPayloadManifest,
    val classpath: List<Path>,
)

object WorkerPayloadPublisher {
    fun publish(
        manifest: WorkerPayloadManifest,
        source: WorkerPayloadSource,
        cacheRoot: Path,
        limits: WorkerPayloadLimits = WorkerPayloadLimits(),
    ): PublishedWorkerPayload {
        val published =
            ru.lazyhat.compukters.worker.payload.WorkerPayloadPublisher
                .publish(manifest.generic, source, cacheRoot, limits)
        return PublishedWorkerPayload(published.root, WorkerPayloadManifest.fromGeneric(published.manifest), published.classpath)
    }
}
