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

package ru.lazyhat.compukters.compiler.worker.k2

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import ru.lazyhat.compukters.addon.api.AddonCapabilityIdentity
import ru.lazyhat.compukters.addon.api.AddonCapabilityOperation
import ru.lazyhat.compukters.addon.api.AddonCapabilitySchema
import ru.lazyhat.compukters.addon.api.AddonCapabilityValueType
import ru.lazyhat.compukters.addon.api.AddonGuestApiBinding
import ru.lazyhat.compukters.addon.api.AddonGuestApiBundle
import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.compiler.artifact.read.ArtifactReader
import ru.lazyhat.compukters.compiler.k2.engine.build.PlatformBundleBuilder
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.controller.TemporaryBudgetException
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticSeverity
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundlePayload
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class K2CompilerAdapterTest {
    @Test
    fun `source library generic function specializes in consumer`() {
        val sourceRoot =
            Path
                .of(
                    checkNotNull(System.getProperty("compukters.repository.root")),
                ).resolve("modules/common/guest-platform/src/platform")
        val root = createTempDirectory("compukters-generic-library-")
        try {
            Files.walk(sourceRoot).use { paths ->
                paths.filter(Files::isRegularFile).forEach { source ->
                    root.resolve(sourceRoot.relativize(source)).also { target ->
                        target.parent.createDirectories()
                        Files.copy(source, target)
                    }
                }
            }
            root.resolve("libraries/generic/Identity.kt").apply {
                parent.createDirectories()
                writeText(
                    "package sample\nfun <T> identity(value: T): T = value\nfun <T> bad(value: T): T { val nullable: T? = value; return nullable!! }\n",
                )
            }
            root.resolve("modules.toml").writeText(
                sourceRoot.resolve("modules.toml").readText() + "\n" +
                    """
                    [[module]]
                    id = "test:generic"
                    version = "1.0.0"
                    dependencies = ["kotlin:builtins"]
                    sources = ["libraries/generic/**/*.kt"]
                    """.trimIndent(),
            )
            val platform = PlatformBundleBuilder().build(root, root.resolve("modules.toml"))
            val library = platform.modules.single { it.id.toString() == "test:generic" }
            assertNull(library.libraryFragment)
            val workerIdentity = identity(platform)
            val selected =
                listOf(
                    platform.modules.single { it.id.toString() == "stdlib:core" },
                    platform.modules.single { it.id.toString() == "std:terminal" },
                    library,
                ).map { module ->
                    TrustedBundleIdentity.of(
                        module.id.toString(),
                        Hash256.of(PlatformBundleCodec.moduleContentHash(module).toByteArray()),
                    )
                }
            val adapter =
                K2CompilerAdapter(
                    K2CompilerInputs(
                        temporaryRoot = root.resolve("requests").also { it.createDirectories() },
                        workerJar = Path.of(checkNotNull(System.getProperty("compukters.worker.jar"))),
                        expectedIdentity = workerIdentity,
                    ),
                    platform,
                )
            val source =
                source("project/Main.kt", "import sample.identity\nfun main() { println(identity(42)); println(identity(\"hello\")) }")
            val result =
                adapter.compile(
                    CompileRequest(
                        RequestId.of(1u),
                        listOf(source),
                        TargetSettings.KOTLIN_2_4_JVM_17,
                        workerIdentity,
                        WorkerLimits(),
                        selected,
                        emptyList(),
                    ),
                )
            val artifact = ArtifactReader.read(assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray())
            val names =
                artifact.modules
                    .first()
                    .strings
                    .map { it.toString() }
            assertTrue("sample.identity<Int>" in names, names.toString())
            assertTrue("sample.identity<String>" in names, names.toString())
            System.getProperty("compukter.vm.genericLibraryArtifact")?.let { output ->
                val path = Path.of(output)
                path.parent.createDirectories()
                Files.write(path, assertNotNull(result.artifact).toByteArray())
            }
            val unsupported =
                adapter.compile(
                    CompileRequest(
                        RequestId.of(2u),
                        listOf(source("project/Bad.kt", "import sample.bad\nfun main() { bad(1) }")),
                        TargetSettings.KOTLIN_2_4_JVM_17,
                        workerIdentity,
                        WorkerLimits(),
                        selected,
                        emptyList(),
                    ),
                )
            assertNull(unsupported.artifact)
            assertTrue(
                unsupported.diagnostics.any { diagnostic ->
                    diagnostic.code == "UNSUPPORTED_IR" && diagnostic.path?.value?.startsWith("platform/") == true
                },
                unsupported.diagnostics.toString(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `worker packages one compiled platform bundle instead of trusted source inputs`() {
        assertTrue(K2CompilerAdapter.loadPackagedPlatform().modules.isNotEmpty())
        assertNull(
            K2CompilerAdapter::class.java.getResourceAsStream(
                "/compukters-platform/sources/libraries/std-terminal/compukter/terminal/Terminal.kt",
            ),
        )
    }

    @Test
    fun `valid Kotlin source reaches IR and request files are removed`() =
        withAdapter { adapter, root ->
            val result = adapter.compile(request("fun main() { val answer: Int = 42 }"))
            assertTrue(result.reachedIr)
            assertFalse(result.hasErrors)
            assertNotNull(result.artifact)
            Files.list(root).use { assertEquals(0, it.count()) }
        }

    @Test
    fun `syntax and type diagnostics use virtual paths and UTF-16 offsets`() =
        withAdapter { adapter, _ ->
            val syntax = adapter.compile(request("fun main() { if ( }"))
            val syntaxError =
                assertNotNull(
                    syntax.diagnostics.firstOrNull {
                        it.severity == DiagnosticSeverity.ERROR && it.category == DiagnosticCategory.SYNTAX
                    },
                    syntax.diagnostics.toString(),
                )
            assertEquals(DiagnosticCategory.SYNTAX, syntaxError.category, syntax.diagnostics.toString())
            assertEquals("project/virtual.kt", syntaxError.path?.value)
            assertTrue(syntaxError.startUtf16 != null)
            assertNull(syntax.artifact)

            val type = adapter.compile(request("val answer: MissingType = 42"))
            assertTrue(type.diagnostics.any { it.category == DiagnosticCategory.TYPE && it.severity == DiagnosticSeverity.ERROR })
            assertNull(type.artifact)
        }

    @Test
    fun `Guest JVM value classes are rejected before FIR`() =
        withAdapter { adapter, _ ->
            val result = adapter.compile(request("@JvmInline value class Guest(val value: Int)"))

            assertFalse(result.reachedIr)
            assertTrue(result.diagnostics.any { it.code == "JVM_INLINE" })
            assertNull(result.artifact)
        }

    @Test
    fun `dependency refinement and mod imports cannot expand compiler inputs`() =
        withAdapter { adapter, _ ->
            val dependency = adapter.compile(request("@file:DependsOn(\"evil:payload:1\")\nval answer = 42"))
            assertFalse(dependency.reachedIr)
            assertTrue(dependency.diagnostics.any { it.category == DiagnosticCategory.TARGET })

            val modImport = adapter.compile(request("import ru.lazyhat.compukters.Compukters\nval answer = Compukters"))
            assertFalse(modImport.reachedIr)
            assertTrue(modImport.diagnostics.any { it.category == DiagnosticCategory.TYPE })
        }

    @Test
    fun `cross-file reference participates in one K2 session before bounded lowering`() =
        withAdapter { adapter, _ ->
            val result =
                adapter.compile(
                    request(
                        listOf(
                            source("project/Helper.kt", "package project\nfun shared() = 41"),
                            source("project/Main.kt", "package project\nfun main() { shared() + 1 }"),
                        ),
                    ),
                )

            assertTrue(result.reachedIr)
            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertFalse(result.hasErrors, result.diagnostics.toString())
            assertFalse(result.diagnostics.any { it.message.contains("unresolved reference", ignoreCase = true) })
        }

    @Test
    fun `admitted addon metadata type checks and lowers while guest spoof is rejected`() {
        val packaged = K2CompilerAdapter.loadPackagedPlatform()
        val addon =
            AddonGuestApiBundleCodec.decode(
                Files.readAllBytes(Path.of(checkNotNull(System.getProperty("compukters.addonGuestApiFixture")))),
            )
        val fixtureModule = addon.moduleDescriptor

        withAdapter(packaged) { adapter, _ ->
            val selected =
                fixtureModule.dependencies.map { dependency ->
                    val module = packaged.modules.single { it.id == dependency }
                    TrustedBundleIdentity.of(
                        module.id.toString(),
                        Hash256.of(PlatformBundleCodec.moduleContentHash(module).toByteArray()),
                    )
                } +
                    TrustedBundleIdentity.of(
                        addon.moduleDescriptor.id.toString(),
                        Hash256.of(addon.identity.contentHash.toByteArray()),
                    )
            val payload =
                TrustedBundlePayload(
                    TrustedBundleIdentity.of(addon.identity.id, selected.last().hash),
                    BinaryValue.of(AddonGuestApiBundleCodec.encode(addon)),
                )
            val admitted =
                adapter.compile(
                    request(
                        "import fixture.kinetics.Kinetics\nfun main() { val speed = Kinetics.front.speedometer().speed() }",
                        platformModules = selected,
                        addonBundles = listOf(payload),
                    ),
                )
            val artifact = ArtifactReader.read(assertNotNull(admitted.artifact, admitted.diagnostics.joinToString()).toByteArray())
            assertTrue(
                artifact.capabilities.any { capability ->
                    val strings = artifact.modules.first().strings
                    strings[capability.namespace.value.toInt()].toString() == "fixture" &&
                        strings[capability.name.value.toInt()].toString() == "fixture"
                },
            )

            val spoof =
                adapter.compile(
                    request(
                        "package fixture.kinetics\nprivate object KineticsBindings { fun speed(handle: Int): Float = handle.toFloat() }\nfun main() { val speed = KineticsBindings.speed(1) }",
                    ),
                )
            assertNull(spoof.artifact)
            assertTrue(spoof.diagnostics.any { it.category == DiagnosticCategory.TARGET })
        }
    }

    @Test
    fun `diagnostic count text and physical paths are bounded`() {
        val physical = Path.of("private/request/source/main.kt")
        val collector =
            CompilerDiagnosticCollector(
                "val x = 1",
                physical,
                VirtualSourcePath.kotlin("project/main.kt"),
                WorkerLimits(diagnostics = 1, diagnosticTextBytes = 8),
            )
        repeat(3) {
            collector.report(
                CompilerMessageSeverity.ERROR,
                "$physical diagnostic text that is too long",
                CompilerMessageLocation.create(physical.toString(), 1, 5, "val x = 1"),
            )
        }

        assertEquals(1, collector.diagnostics.size)
        assertTrue(
            collector.diagnostics
                .single()
                .message
                .encodeToByteArray()
                .size <= 8,
        )
        assertFalse(
            collector.diagnostics
                .single()
                .message
                .contains("private/request"),
        )
        assertTrue(collector.hasErrors())
    }

    @Test
    fun `request temporary storage budget is enforced and cleaned`() =
        withAdapter { adapter, root ->
            assertFailsWith<TemporaryBudgetException> {
                adapter.compile(request("val answer: Int = 42", WorkerLimits(temporaryBytes = 0)))
            }
            Files.list(root).use { assertEquals(0, it.count()) }
        }

    @Test
    fun `source footprint is rejected before temporary materialization`() {
        val source = "val answer: Int = 42"
        listOf(
            WorkerLimits(temporaryBytes = source.encodeToByteArray().size.toLong() - 1),
            WorkerLimits(temporaryFiles = 0),
        ).forEach { limits ->
            withAdapter { adapter, root ->
                root.fileSystem.newWatchService().use { watcher ->
                    root.register(watcher, StandardWatchEventKinds.ENTRY_CREATE)

                    assertFailsWith<TemporaryBudgetException> { adapter.compile(request(source, limits)) }

                    assertNull(watcher.poll(), "temporary storage was created before rejecting $limits")
                }
            }
        }
    }

    @Test
    fun `IR compilation writes no temporary output beyond its exact source footprint`() =
        withAdapter { adapter, root ->
            val source = "fun main() { val answer: Int = 42 }"
            val result =
                adapter.compile(
                    request(
                        source,
                        WorkerLimits(
                            temporaryBytes = source.encodeToByteArray().size.toLong(),
                            // source/, source/project/, and the user source.
                            temporaryFiles = 3,
                        ),
                    ),
                )

            assertTrue(result.reachedIr)
            assertFalse(result.hasErrors)
            assertNotNull(result.artifact)
            Files.list(root).use { assertEquals(0, it.count()) }
        }

    private fun withAdapter(
        platform: PlatformBundle = K2CompilerAdapter.loadPackagedPlatform(),
        block: (K2CompilerAdapter, Path) -> Unit,
    ) {
        val root = createTempDirectory("compukters-k2-adapter-test-")
        try {
            val adapter =
                K2CompilerAdapter(
                    K2CompilerInputs(
                        temporaryRoot = root,
                        workerJar = Path.of(checkNotNull(System.getProperty("compukters.worker.jar"))),
                        expectedIdentity = identity(),
                    ),
                    platform,
                )
            block(adapter, root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun request(
        source: String,
        limits: WorkerLimits = WorkerLimits(),
        platformModules: List<TrustedBundleIdentity> = emptyList(),
        addonBundles: List<TrustedBundlePayload> = emptyList(),
    ): CompileRequest = request(listOf(source("project/virtual.kt", source)), limits, platformModules, addonBundles)

    private fun request(
        sources: List<ProjectSource>,
        limits: WorkerLimits = WorkerLimits(),
        platformModules: List<TrustedBundleIdentity> = emptyList(),
        addonBundles: List<TrustedBundlePayload> = emptyList(),
    ): CompileRequest =
        CompileRequest(
            RequestId.of(1u),
            sources,
            TargetSettings.KOTLIN_2_4_JVM_17,
            identity(),
            limits,
            platformModules,
            addonBundles,
        )

    private fun source(
        path: String,
        content: String,
    ) = ProjectSource(VirtualSourcePath.kotlin(path), BinaryValue.of(content.encodeToByteArray()))

    private fun identity(platform: PlatformBundle = K2CompilerAdapter.loadPackagedPlatform()) =
        WorkerIdentity(
            "2.4.10",
            "2.4",
            1u,
            1u,
            Hash256.zero(),
            Hash256.of(platform.identity.contentHash.toByteArray()),
        )
}
