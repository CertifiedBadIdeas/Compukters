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

package ru.lazyhat.compukters.compiler.worker.server

import ru.lazyhat.compukters.compiler.worker.k2.K2CompilerAdapter
import ru.lazyhat.compukters.compiler.worker.k2.K2CompilerInputs
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.TimeZone
import kotlin.system.exitProcess

fun main() {
    Locale.setDefault(Locale.ROOT)
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    try {
        val bootstrap = WorkerBootstrap.load()
        val adapter =
            K2CompilerAdapter(
                K2CompilerInputs(
                    temporaryRoot = bootstrap.temporaryRoot,
                    workerJar = bootstrap.workerJar,
                    standardLibrary = bootstrap.standardLibrary,
                    jdkHome = Path.of(System.getProperty("java.home")),
                    expectedIdentity = bootstrap.identity,
                ),
            )
        val exit =
            CompilerWorkerServer(
                bootstrap.identity,
                WorkerLimits(),
                BufferedInputStream(System.`in`),
                BufferedOutputStream(System.out),
                adapter::compile,
            ).run()
        if (exit == WorkerServerExit.PROTOCOL_ERROR) exitProcess(3)
    } catch (exception: Exception) {
        System.err.println("compiler worker initialization failed: ${exception.message ?: exception::class.java.simpleName}")
        exitProcess(2)
    }
}

internal data class WorkerBootstrap(
    val identity: WorkerIdentity,
    val workerJar: Path,
    val standardLibrary: Path,
    val temporaryRoot: Path,
) {
    companion object {
        fun load(): WorkerBootstrap {
            val codeLocation = CompilerWorkerServer::class.java.protectionDomain.codeSource.location
            val rawWorkerJar = Path.of(codeLocation.toURI())
            val workerJar = rawWorkerJar.toAbsolutePath().normalize()
            require(Files.isRegularFile(workerJar)) { "worker code source is not a jar" }
            val payloadRoot = checkNotNull(workerJar.parent?.parent) { "worker jar is outside a payload" }
            val properties = readManifest(payloadRoot.resolve(MANIFEST_FILE))
            require(properties.getValue("format") == "1") { "unsupported worker payload format" }
            require(properties.getValue("kind") == "compiler") { "worker payload kind mismatch" }
            require(properties.getValue("identity.compiler") == COMPILER_VERSION) { "worker compiler version mismatch" }
            require(properties.getValue("identity.language") == LANGUAGE_VERSION) { "worker language version mismatch" }
            require(properties.getValue("identity.codegenAbi") == CODEGEN_ABI.toString()) { "worker codegen ABI mismatch" }
            require(properties.getValue("identity.artifactWriter") == ARTIFACT_WRITER_VERSION.toString()) {
                "worker artifact writer mismatch"
            }
            require(properties.getValue("mainClass") == MAIN_CLASS) { "worker main class mismatch" }
            val payloadHash = Hash256.of(properties.getValue("payloadSha256").decodeHex())
            val standardLibrary = workerJar.parent.resolve("kotlin-stdlib-$COMPILER_VERSION.jar").normalize()
            require(Files.isRegularFile(standardLibrary)) { "fixed Kotlin standard library is missing" }
            val temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).resolve("requests").normalize()
            return WorkerBootstrap(
                WorkerIdentity(
                    COMPILER_VERSION,
                    LANGUAGE_VERSION,
                    CODEGEN_ABI,
                    ARTIFACT_WRITER_VERSION,
                    payloadHash,
                    Hash256.of(properties.getValue("identity.standardLibraryAbi").decodeHex()),
                ),
                workerJar,
                standardLibrary,
                temporaryRoot,
            )
        }

        private fun readManifest(path: Path): Map<String, String> {
            require(Files.isRegularFile(path)) { "validated worker payload manifest is missing" }
            val properties = linkedMapOf<String, String>()
            Files.readAllLines(path).forEach { line ->
                if (line.startsWith("file=")) return@forEach
                val separator = line.indexOf('=')
                require(separator > 0) { "worker payload manifest line is malformed" }
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1)
                require(properties.put(key, value) == null) { "duplicate worker payload manifest property" }
            }
            REQUIRED_PROPERTIES.forEach { key -> require(properties.containsKey(key)) { "worker payload manifest lacks $key" } }
            require(properties.keys == REQUIRED_PROPERTIES) { "worker payload manifest has unknown properties" }
            return properties
        }

        private const val MANIFEST_FILE = "worker.payload"
        private const val COMPILER_VERSION = "2.4.10"
        private const val LANGUAGE_VERSION = "2.4"
        private const val CODEGEN_ABI = 1u
        private const val ARTIFACT_WRITER_VERSION = 1u
        private const val MAIN_CLASS = "ru.lazyhat.compukters.compiler.worker.server.CompilerWorkerMainKt"
        private val REQUIRED_PROPERTIES =
            linkedSetOf(
                "format",
                "kind",
                "identity.artifactWriter",
                "identity.codegenAbi",
                "identity.compiler",
                "identity.language",
                "identity.standardLibraryAbi",
                "mainClass",
                "payloadSha256",
            )
    }
}

private fun String.decodeHex(): ByteArray {
    require(length == 64) { "payload SHA-256 must contain 64 hex digits" }
    return ByteArray(length / 2) { index ->
        val high = Character.digit(this[index * 2], 16)
        val low = Character.digit(this[index * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "payload SHA-256 is not hexadecimal" }
        ((high shl 4) or low).toByte()
    }
}
