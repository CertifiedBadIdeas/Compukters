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

package ru.lazyhat.compukters.ide.analysis.k2.query

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.analysis.k2.standalone.SnapshotAdmission
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.OpenSnapshotRequest
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

internal class K2QueryFixture private constructor(
    val identity: AnalysisSnapshotIdentity,
    val sources: Map<VirtualSourcePath, String>,
    val snapshot: AdmittedK2Snapshot,
    private val root: Path,
) : AutoCloseable {
    fun execute(
        query: AnalysisQuery,
        limits: AnalysisLimits = AnalysisLimits(),
    ): AnalysisResult = K2QueryDispatcher.execute(query, snapshot, limits)

    override fun close() {
        snapshot.close()
        root.toFile().deleteRecursively()
    }

    companion object {
        fun source(vararg sources: Pair<String, String>): K2QueryFixture {
            val root = createTempDirectory("compukters-k2-query-")
            val mapped = sources.associate { (path, text) -> VirtualSourcePath.kotlin(path) to text }
            val project =
                ProjectSnapshot.of(
                    mapped.map { (path, text) -> ProjectSource(path, BinaryValue.of(text.encodeToByteArray())) },
                    WorkerLimits(),
                )
            val profile = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { 1 }))
            val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(project), profile)
            val request =
                OpenSnapshotRequest(
                    RequestId.of(1uL),
                    identity,
                    project,
                    AdmittedAnalysisProfile(profile, emptyList()),
                    AnalysisLimits(),
                )
            val standardLibrary =
                Path
                    .of(
                        Unit::class.java.protectionDomain.codeSource.location
                            .toURI(),
                    ).toAbsolutePath()
                    .normalize()
            val snapshot =
                SnapshotAdmission(root.resolve("snapshots"), standardLibrary, Path.of(System.getProperty("java.home")))
                    .admit(request)
            return K2QueryFixture(identity, mapped, snapshot, root)
        }
    }
}
