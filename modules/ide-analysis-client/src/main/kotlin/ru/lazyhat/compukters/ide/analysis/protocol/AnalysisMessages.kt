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

package ru.lazyhat.compukters.ide.analysis.protocol

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.ide.analysis.AnalysisBundleIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections

const val ANALYSIS_PROTOCOL_VERSION: UInt = 1u

data class AnalysisWorkerIdentity(
    val compilerVersion: String,
    val languageVersion: String,
    val payloadHash: Hash256,
) {
    init {
        validateWireText("compiler version", compilerVersion)
        validateWireText("language version", languageVersion)
    }
}

enum class AnalysisFeature {
    Presentation,
    Completion,
    ExpressionInfo,
    Declaration,
    References,
}

data class AdmittedAnalysisBundle(
    val identity: AnalysisBundleIdentity,
    val classRoot: String,
    val sourceRoot: String? = null,
) {
    init {
        validateRoot("bundle class root", classRoot)
        sourceRoot?.let { validateRoot("bundle source root", it) }
    }
}

class AdmittedAnalysisProfile(
    val identity: AnalysisProfileIdentity,
    bundles: List<AdmittedAnalysisBundle>,
) {
    val bundles: List<AdmittedAnalysisBundle> = immutableCopy(bundles)

    init {
        require(bundles.size <= ProtocolLimits.MAX_BUNDLES) { "analysis bundle count exceeds protocol limit" }
        requireCanonicalBundles(this.bundles)
    }

    override fun equals(other: Any?): Boolean = other is AdmittedAnalysisProfile && identity == other.identity && bundles == other.bundles

    override fun hashCode(): Int = 31 * identity.hashCode() + bundles.hashCode()
}

sealed interface AnalysisMessage

class AnalysisHandshake(
    val protocol: UInt,
    val workerIdentity: AnalysisWorkerIdentity,
    features: Set<AnalysisFeature>,
    val limits: AnalysisLimits,
) : AnalysisMessage {
    val features: Set<AnalysisFeature> = Collections.unmodifiableSet(features.toSet())

    init {
        require(protocol == ANALYSIS_PROTOCOL_VERSION) { "unsupported analysis protocol version" }
    }

    override fun equals(other: Any?): Boolean =
        other is AnalysisHandshake &&
            protocol == other.protocol &&
            workerIdentity == other.workerIdentity &&
            features == other.features &&
            limits == other.limits

    override fun hashCode(): Int = listOf(protocol, workerIdentity, features, limits).hashCode()
}

data class OpenSnapshotRequest(
    val requestId: RequestId,
    val identity: AnalysisSnapshotIdentity,
    val sources: ProjectSnapshot,
    val profile: AdmittedAnalysisProfile,
    val limits: AnalysisLimits,
) : AnalysisMessage {
    init {
        require(SourceSnapshotIdentity.of(sources) == identity.source) { "source snapshot identity does not match sources" }
        require(profile.identity == identity.profile) { "analysis profile identity does not match snapshot" }
        require(sources.sources.size <= limits.sourceFiles) { "source count exceeds analysis limit" }
        require(sources.totalSourceBytes <= limits.sourceBytes.toLong()) { "source bytes exceed analysis limit" }
        require(sources.sources.all { it.content.size <= limits.sourceFileBytes }) { "source file exceeds analysis limit" }
        sources.sources.forEach { source -> validateProtocolSourcePath(source.path.value) }
        require(profile.bundles.size <= limits.bundles) { "bundle count exceeds analysis limit" }
    }
}

data class AnalysisQueryRequest(
    val requestId: RequestId,
    val query: AnalysisQuery,
) : AnalysisMessage

data class CancelAnalysisRequest(
    val requestId: RequestId,
) : AnalysisMessage

data class CloseSnapshotRequest(
    val requestId: RequestId,
    val identity: AnalysisSnapshotIdentity,
) : AnalysisMessage

data class SnapshotReady(
    val requestId: RequestId,
    val identity: AnalysisSnapshotIdentity,
) : AnalysisMessage

data class SnapshotClosed(
    val requestId: RequestId,
    val identity: AnalysisSnapshotIdentity,
) : AnalysisMessage

data class AnalysisQuerySuccess(
    val requestId: RequestId,
    val result: AnalysisResult,
) : AnalysisMessage

data class AnalysisCancelled(
    val requestId: RequestId,
    val identity: AnalysisSnapshotIdentity?,
) : AnalysisMessage

enum class AnalysisFailureKind {
    Startup,
    Protocol,
    InvalidSnapshot,
    UnsupportedFeature,
    Timeout,
    MemoryLimit,
    Cancelled,
    OutputLimit,
    InternalAnalysis,
    WorkerExit,
}

data class AnalysisFailure(
    val requestId: RequestId,
    val identity: AnalysisSnapshotIdentity?,
    val failure: AnalysisFailureKind,
    val detail: String,
) : AnalysisMessage {
    init {
        require(detail.isNotEmpty()) { "analysis failure detail must not be empty" }
        require(strictUtf8Size(detail) <= ProtocolLimits.MAX_TEXT_BYTES) { "analysis failure detail exceeds protocol limit" }
    }
}

private fun validateRoot(
    label: String,
    value: String,
) {
    require(value.isNotEmpty()) { "$label must not be empty" }
    require('\u0000' !in value) { "$label contains NUL" }
    require(strictUtf8Size(value) <= ProtocolLimits.MAX_PATH_BYTES) { "$label exceeds protocol limit" }
}

private fun validateWireText(
    label: String,
    value: String,
) {
    require(value.isNotEmpty()) { "$label must not be empty" }
    require(strictUtf8Size(value) <= ProtocolLimits.MAX_TEXT_BYTES) { "$label exceeds protocol limit" }
}

internal fun strictUtf8Size(value: String): Int =
    try {
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
            .remaining()
    } catch (exception: CharacterCodingException) {
        throw IllegalArgumentException("protocol text must be strict UTF-8", exception)
    }

internal fun validateProtocolSourcePath(value: String) {
    require(strictUtf8Size(value) <= ProtocolLimits.MAX_PATH_BYTES) { "virtual source path exceeds protocol limit" }
}

private fun requireCanonicalBundles(bundles: List<AdmittedAnalysisBundle>) {
    bundles.zipWithNext().forEach { (left, right) ->
        require(compareUnsigned(left.identity.name.encodeToByteArray(), right.identity.name.encodeToByteArray()) < 0) {
            "analysis bundles must be uniquely ordered by UTF-8 name"
        }
    }
}

private fun compareUnsigned(
    left: ByteArray,
    right: ByteArray,
): Int {
    repeat(minOf(left.size, right.size)) { index ->
        val result = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
        if (result != 0) return result
    }
    return left.size.compareTo(right.size)
}

private fun <T> immutableCopy(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
