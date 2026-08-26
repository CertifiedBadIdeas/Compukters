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

package ru.lazyhat.compukters.ide.analysis.k2.server

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.worker.payload.WorkerPayloadLoader
import java.nio.file.Files
import java.nio.file.Path

internal data class AnalysisWorkerBootstrap(
    val identity: AnalysisWorkerIdentity,
    val standardLibrary: Path,
    val temporaryRoot: Path,
) {
    companion object {
        fun load(): AnalysisWorkerBootstrap {
            val workerJar =
                Path
                    .of(
                        AnalysisWorkerBootstrap::class.java.protectionDomain.codeSource.location
                            .toURI(),
                    ).toAbsolutePath()
                    .normalize()
            require(Files.isRegularFile(workerJar)) { "analysis worker code source is not a jar" }
            val payloadRoot = checkNotNull(workerJar.parent?.parent) { "analysis worker jar is outside a payload" }
            val payload = WorkerPayloadLoader.load(payloadRoot)
            require(payload.manifest.kind == "analysis") { "analysis worker payload kind mismatch" }
            require(payload.manifest.mainClass == MAIN_CLASS) { "analysis worker main class mismatch" }
            val compiler = payload.manifest.identityProperties.getValue("compiler")
            val language = payload.manifest.identityProperties.getValue("language")
            val standardLibrary =
                payload.classpath.singleOrNull { it.fileName.toString() == "kotlin-stdlib-$compiler.jar" }
                    ?: error("fixed Kotlin standard library is missing")
            return AnalysisWorkerBootstrap(
                AnalysisWorkerIdentity(compiler, language, Hash256.of(payload.manifest.payloadHash.toByteArray())),
                standardLibrary,
                Path
                    .of(System.getProperty("java.io.tmpdir"))
                    .resolve("snapshots")
                    .toAbsolutePath()
                    .normalize(),
            )
        }

        private const val MAIN_CLASS = "ru.lazyhat.compukters.ide.analysis.k2.server.AnalysisWorkerMainKt"
    }
}
