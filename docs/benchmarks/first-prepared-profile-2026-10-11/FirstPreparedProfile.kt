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

@jdk.jfr.Name("compukters.BenchmarkPhase")
@jdk.jfr.Label("Compiler benchmark phase")
class BenchmarkPhase : jdk.jfr.Event() {
    @jdk.jfr.Label("Phase") var phase: String = ""
    @jdk.jfr.Label("Iteration") var iteration: Int = 0
}

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
    val worker = profile.classpath.single { it.fileName.toString().startsWith("compiler-k2-") && !it.fileName.toString().startsWith("compiler-k2-engine-") }
    val adapter = K2CompilerAdapter(K2CompilerInputs(output.resolve("temp"), worker, profile.manifest.identity), packaged)
    val recording = jdk.jfr.Recording()
    recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1))
    recording.enable("jdk.ClassLoad").withoutStackTrace()
    recording.enable("jdk.Compilation").withoutStackTrace()
    recording.enable("jdk.GarbageCollection")
    recording.enable("jdk.ObjectAllocationSample").withStackTrace()
    recording.enable(BenchmarkPhase::class.java)
    recording.start()
    adapter.use {
        val preparation = BenchmarkPhase().apply { phase = "preparation"; begin() }
        adapter.prepareLibraries(LibraryPreparationRequest(RequestId.of(100uL), profile.manifest.identity, WorkerLimits(), selected, bundles))
        preparation.end(); preparation.commit()

        for (sample in 1..5) {
            val request = CompileRequest(RequestId.of(sample.toULong()), sources, TargetSettings.KOTLIN_2_4_JVM_17, profile.manifest.identity, WorkerLimits(), selected, bundles)
            val marker = BenchmarkPhase().apply { phase = "compilation"; iteration = sample; begin() }
            val started = System.nanoTime()
            val result = adapter.compile(request)
            marker.end(); marker.commit()
            val elapsed = (System.nanoTime() - started) / 1e6
            check(!result.hasErrors) { result.diagnostics.toString() }
            val bytes = checkNotNull(result.artifact) { result.diagnostics.toString() }.toByteArray()
            Files.write(output.resolve("sample-$sample.cpkt"), bytes)
            println("sample=$sample millis=$elapsed bytes=${bytes.size}")
        }
    }
    recording.stop()
    recording.dump(output.resolve("profile.jfr"))
    recording.close()
}
