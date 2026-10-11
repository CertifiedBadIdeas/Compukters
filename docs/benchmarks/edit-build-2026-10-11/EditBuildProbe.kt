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

import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadLoader
import ru.lazyhat.compukters.compiler.worker.k2.*
import ru.lazyhat.compukters.compiler.worker.protocol.*
import ru.lazyhat.compukters.compiler.project.*
import ru.lazyhat.compukters.platform.bundle.*
import ru.lazyhat.compukters.addon.api.*
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val sourceRoot = Path.of(args[0]).toAbsolutePath()
    val output = Path.of(args[1]).toAbsolutePath()
    val payloadRoot = Path.of(args[2]).toAbsolutePath()
    Files.createDirectories(output)
    val profile = WorkerPayloadLoader.loadToolingProfile(payloadRoot)
    val packaged = PlatformBundleCodec.decode(checkNotNull(K2CompilerAdapter::class.java.getResourceAsStream("/compukters-platform/compukters-platform.cpb")).use { it.readAllBytes() })
    val addons = args.drop(3).map { Files.readAllBytes(Path.of(it)) }.map { it to AddonGuestApiBundleCodec.decode(it) }
    val merged = packaged.copy(modules = packaged.modules + addons.map { it.second.moduleDescriptor })
    val roots = if (addons.isEmpty()) setOf(PlatformModuleId("stdlib", "core"), PlatformModuleId("compukter", "core"))
        else setOf(PlatformModuleId("stdlib", "core"), PlatformModuleId("compukter", "core"), PlatformModuleId("sable", "api"), PlatformModuleId("propulsion", "api"))
    val selected = PlatformModuleGraph(merged).resolve(roots).modules.map { module ->
        val hash = addons.find { it.second.moduleDescriptor.id == module.id }?.second?.identity?.contentHash ?: PlatformBundleCodec.moduleContentHash(module)
        TrustedBundleIdentity.of(module.id.toString(), Hash256.of(hash.toByteArray()))
    }
    val bundles = addons.map { (bytes, addon) -> TrustedBundlePayload(TrustedBundleIdentity.of(addon.identity.id, Hash256.of(addon.identity.contentHash.toByteArray())), BinaryValue.of(bytes)) }
    val sources = Files.walk(sourceRoot.resolve("src")).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.sorted().map {
            ProjectSource(VirtualSourcePath.kotlin(sourceRoot.relativize(it).toString()), BinaryValue.of(Files.readAllBytes(it)))
        }.toList()
    }
    val mode = System.getenv("BENCH_MODE") ?: "prepared"
    val limits = WorkerLimits()
    val launch = ru.lazyhat.compukters.worker.process.WorkerLaunch(
        Path.of("/usr/lib/jvm/java-21-openjdk/bin/java"), profile.classpath, profile.manifest.mainClass,
        2048, 512, output.resolve("temp"), limits.frameBytes, limits.stderrBytes)
    Files.createDirectories(launch.temporaryDirectory)
    val launchStarted = System.nanoTime()
    val process = ru.lazyhat.compukters.worker.process.JdkWorkerProcessFactory().start(launch)
    process.use {
        fun receive(): WorkerMessage = WorkerMessageCodec.decode(WorkerCodec.decodeFrame(
            checkNotNull(process.readFrame(System.nanoTime() + 60_000_000_000L)) { String(process.stderrSnapshot()) }, limits.frameBytes))
        fun send(request: WorkerMessage): WorkerMessage {
            process.writeFrame(WorkerCodec.encodeFrame(WorkerMessageCodec.encode(request)))
            return receive()
        }
        val handshake = receive() as WorkerHandshake
        check(handshake.identity == profile.manifest.identity && handshake.limits == limits)
        println("startupMillis=" + (System.nanoTime()-launchStarted)/1e6)
        if (mode != "cold") {
            val start = System.nanoTime()
            val result = send(LibraryPreparationRequest(RequestId.of(100uL), profile.manifest.identity, limits, selected, bundles))
            check(result is LibrariesPrepared) { result.toString() }
            println("preparationMillis=" + (System.nanoTime()-start)/1e6)
        }
        if (mode == "hello") {
            val start = System.nanoTime()
            val hello = listOf(ProjectSource(VirtualSourcePath.kotlin("src/main.kt"), BinaryValue.of("fun main() { println(\"Hello, world!\") }\n".toByteArray())))
            val result = send(CompileRequest(RequestId.of(101uL), hello, TargetSettings.KOTLIN_2_4_JVM_17, profile.manifest.identity, limits, selected, bundles))
            check(result is CompileSuccess) { result.toString() }
            Files.write(output.resolve("warmup.cpkt"), result.artifact.toByteArray())
            println("helloWarmupMillis=" + (System.nanoTime()-start)/1e6)
        }
        for (sample in 1..15) {
            val editIndex = sample - 5
            val submittedSources = if (editIndex <= 0 || mode == "unchanged") sources else sources.map { source ->
                if (source.path.value != "src/main.kt") source else {
                    val original = source.content.toByteArray().decodeToString()
                    val edited = when(mode) {
                        "comment" -> original + "\n// benchmark edit " + editIndex + "\n"
                        "code" -> {
                            check(original.contains("const val HOVER_KN = 33.0"))
                            original.replace("const val HOVER_KN = 33.0", "const val HOVER_KN = 33." + (editIndex % 9 + 1))
                        }
                        else -> error("unknown edit mode")
                    }
                    ProjectSource(source.path, BinaryValue.of(edited.toByteArray()))
                }
            }
            val request = CompileRequest(RequestId.of(sample.toULong()), submittedSources, TargetSettings.KOTLIN_2_4_JVM_17, profile.manifest.identity, limits, selected, bundles)
            val started = System.nanoTime()
            val result = send(request)
            val elapsed = (System.nanoTime() - started) / 1e6
            check(result is CompileSuccess) { result.toString() }
            val bytes = result.artifact.toByteArray()
            Files.write(output.resolve("sample-$sample.cpkt"), bytes)
            println("sample=$sample millis=$elapsed bytes=${bytes.size}")
        }
    }
}
