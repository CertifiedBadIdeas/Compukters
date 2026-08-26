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

import ru.lazyhat.compukters.ide.analysis.k2.query.K2AnalysisQueryHandler
import ru.lazyhat.compukters.ide.analysis.k2.standalone.SnapshotAdmission
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Path
import java.util.Locale
import java.util.TimeZone
import kotlin.system.exitProcess

fun main() {
    Locale.setDefault(Locale.ROOT)
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    try {
        val bootstrap = AnalysisWorkerBootstrap.load()
        val limits = AnalysisLimits()
        val admission = SnapshotAdmission(bootstrap.temporaryRoot, bootstrap.standardLibrary, Path.of(System.getProperty("java.home")))
        AnalysisWorkerServer(
            bootstrap.identity,
            limits,
            BufferedInputStream(System.`in`),
            BufferedOutputStream(System.out),
            admission,
            K2AnalysisQueryHandler(limits),
        ).use { server ->
            if (server.run() == AnalysisServerExit.ProtocolError) exitProcess(3)
        }
    } catch (exception: Exception) {
        System.err.println("analysis worker initialization failed: ${exception.message ?: exception::class.java.simpleName}")
        exitProcess(2)
    }
}
