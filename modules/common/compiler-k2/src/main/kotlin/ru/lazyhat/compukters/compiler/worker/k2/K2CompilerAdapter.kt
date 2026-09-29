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

@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.lazyhat.compukters.compiler.worker.k2

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.diagnostics.Severity
import ru.lazyhat.compukters.addon.api.AddonGuestApiBundle
import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.compiler.artifact.link.LibraryModuleLinker
import ru.lazyhat.compukters.compiler.artifact.model.*
import ru.lazyhat.compukters.compiler.artifact.read.ArtifactReader
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteLimits
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.k2.engine.CompilationSession
import ru.lazyhat.compukters.compiler.k2.engine.CompuktersFir2IrPipeline
import ru.lazyhat.compukters.compiler.k2.engine.PlatformCapabilityShape
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.*
import ru.lazyhat.compukters.compiler.k2.engine.library.loadPlatformLibraries
import ru.lazyhat.compukters.compiler.worker.controller.TemporaryBudget
import ru.lazyhat.compukters.compiler.worker.controller.TemporaryUsage
import ru.lazyhat.compukters.compiler.worker.protocol.*
import ru.lazyhat.compukters.platform.bundle.*
import ru.lazyhat.compukters.platform.k2.CompuktersPlatformCheckers
import ru.lazyhat.compukters.platform.k2.CompuktersPlatformDiagnosticCode
import ru.lazyhat.compukters.platform.k2.build.CompuktersFirBuildEnvironment
import ru.lazyhat.compukters.platform.k2.build.CompuktersFirModuleOutput
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

data class K2CompilerInputs(
    val temporaryRoot: Path,
    val workerJar: Path,
    val expectedIdentity: WorkerIdentity,
)

data class K2CompilationResult(
    val exitCode: ExitCode,
    val diagnostics: List<WorkerDiagnostic>,
    val reachedIr: Boolean,
    val artifact: BinaryValue?,
    private val compilerHadErrors: Boolean,
) {
    val hasErrors: Boolean get() = compilerHadErrors
}

class K2CompilerAdapter(
    private val inputs: K2CompilerInputs,
    private val platform: PlatformBundle = loadPackagedPlatform(),
) {
    init {
        require(Files.isRegularFile(inputs.workerJar)) { "validated worker jar is missing" }
        require(platform.identity.languageVersion == inputs.expectedIdentity.languageVersion) { "worker/platform language mismatch" }
        require(platform.identity.platformAbi == PlatformBundleCodec.SUPPORTED_PLATFORM_ABI) { "unsupported Compukters platform ABI" }
        require(
            platform.identity.contentHash
                .toByteArray()
                .contentEquals(inputs.expectedIdentity.platformAbi.toByteArray()),
        ) { "worker identity does not match the packaged Compukters platform" }
    }

    fun compile(request: CompileRequest): K2CompilationResult {
        require(request.expectedIdentity == inputs.expectedIdentity) { "compile request identity does not match pinned worker" }
        val diagnostics = mutableListOf<WorkerDiagnostic>()
        request.sources.forEach { source ->
            val text = source.content.toByteArray().decodeToString(throwOnInvalidSequence = true)
            if (DEPENDENCY_ANNOTATION.containsMatchIn(text)) {
                diagnostics += targetDiagnostic(source.path, "script dependency refinement is unsupported")
            }
            CompuktersPlatformCheckers.checkGuestSource(text).forEach { diagnostic ->
                diagnostics +=
                    WorkerDiagnostic(
                        DiagnosticSeverity.ERROR,
                        DiagnosticCategory.TARGET,
                        diagnostic.code.name,
                        when (diagnostic.code) {
                            CompuktersPlatformDiagnosticCode.FOREIGN_PLATFORM_REFERENCE -> {
                                "foreign platform references are unsupported"
                            }

                            CompuktersPlatformDiagnosticCode.JVM_INLINE -> {
                                "@JvmInline is not part of the Compukters platform"
                            }

                            CompuktersPlatformDiagnosticCode.GUEST_EXTERNAL_DECLARATION -> {
                                "Guest external declarations are unsupported"
                            }
                        },
                        source.path,
                        diagnostic.startUtf16.toUInt(),
                        diagnostic.endUtf16.toUInt(),
                    )
            }
        }
        if (diagnostics.isNotEmpty()) return failure(diagnostics, reachedIr = false, request.limits)
        val selection = selectModules(request)
        val selected = selection.modules
        val libraries = loadPlatformLibraries(selected)
        val budget = TemporaryBudget(inputs.temporaryRoot, request.limits)
        budget.requireCapacity(sourceFootprint(request))
        return budget.useRequestDirectory { requestRoot ->
            val sourceRoot =
                requestRoot
                    .resolve("source")
                    .toAbsolutePath()
                    .normalize()
                    .also(Path::createDirectories)
            request.sources.forEach { source ->
                val physical = sourceRoot.resolve(source.path.value).normalize()
                require(physical.startsWith(sourceRoot)) { "source path escapes request tree" }
                physical.parent.createDirectories()
                physical.writeBytes(source.content.toByteArray())
            }
            val sourceLibraries =
                selected.filter { module ->
                    module.id != platform.builtins.id &&
                        platform.modules.any { packaged -> packaged.id == module.id } &&
                        module.sourceDeclarations.isNotEmpty()
                }
            val librarySourceNames =
                sourceLibraries.flatMap { module ->
                    module.sources.map { source -> source.path.substringAfterLast('/') }
                }
            require(librarySourceNames.size == librarySourceNames.toSet().size) {
                "selected generic library sources have conflicting file names"
            }
            require(request.sources.none { source -> source.path.value.substringAfterLast('/') in librarySourceNames }) {
                "project source file name conflicts with a selected generic library source"
            }
            val platformSources =
                request.sources.map { source ->
                    PlatformSource(source.path.value, ImmutableBytes.of(source.content.toByteArray()))
                }
            var reachedIr = false
            var artifact: BinaryValue? = null
            val sourcePaths =
                (
                    request.sources
                        .flatMap { source ->
                            listOf(
                                Path.of(source.path.value).fileName.toString(),
                                "/${Path.of(source.path.value).fileName}",
                                source.path.value,
                                sourceRoot.resolve(source.path.value).normalize().toString(),
                            ).map { physical -> physical to source.path }
                        } +
                        sourceLibraries.flatMap { module ->
                            module.sources.flatMap { source ->
                                val virtual = VirtualSourcePath.of("platform/${module.id.namespace}/${module.id.name}/${source.path}")
                                val name = source.path.substringAfterLast('/')
                                listOf(name to virtual, "/$name" to virtual)
                            }
                        }
                ).toMap()
            CompuktersFirBuildEnvironment.create().use { environment ->
                val sourceOutputs = linkedMapOf<PlatformModuleId, CompuktersFirModuleOutput>()
                val metadataModules = selected - sourceLibraries.toSet()
                sourceLibraries.forEach { library ->
                    val dependencyIds =
                        PlatformModuleGraph(
                            platform,
                        ).resolve(
                            library.dependencies.filterNot { it == platform.builtins.id }.toSet(),
                        ).modules
                            .mapTo(mutableSetOf(), PlatformModule::id)
                    val dependencies = sourceOutputs.filterKeys { it in dependencyIds }.values.toList()
                    sourceOutputs[library.id] = environment.compileGuest(library.id, library.sources, metadataModules, dependencies)
                }
                val output =
                    environment.compileGuest(
                        PlatformModuleId("guest", "application"),
                        platformSources,
                        metadataModules,
                        sourceOutputs.values.toList(),
                    )
                val compiledOutputs = sourceOutputs.values + output
                compiledOutputs.flatMap { it.diagnostics.diagnosticsByFile.entries }.forEach { (file, fileDiagnostics) ->
                    val path = file?.name?.let(sourcePaths::get)
                    fileDiagnostics.forEach { diagnostic ->
                        diagnostics +=
                            WorkerDiagnostic(
                                when (diagnostic.severity) {
                                    Severity.ERROR -> DiagnosticSeverity.ERROR
                                    Severity.INFO -> DiagnosticSeverity.INFO
                                    else -> DiagnosticSeverity.WARNING
                                },
                                if (
                                    diagnostic.factoryName.contains("SYNTAX") ||
                                    diagnostic.renderMessage().contains("expecting", ignoreCase = true)
                                ) {
                                    DiagnosticCategory.SYNTAX
                                } else {
                                    DiagnosticCategory.TYPE
                                },
                                diagnostic.factoryName,
                                diagnostic.renderMessage(),
                                path,
                                diagnostic.firstRange.startOffset.toUInt(),
                                diagnostic.firstRange.endOffset.toUInt(),
                            )
                    }
                }
                if (compiledOutputs.none { it.diagnostics.hasErrors }) {
                    val session =
                        CompilationSession(
                            irSink = { _, _ -> reachedIr = true },
                            diagnosticSink = { diagnostic ->
                                diagnostics +=
                                    if (diagnostic.path == null && request.sources.size == 1) {
                                        diagnostic.copy(path = request.sources.single().path)
                                    } else {
                                        diagnostic
                                    }
                            },
                            sourcePaths = sourcePaths,
                            canonicalIntrinsicRegistry = intrinsicRegistry(selection.addonBundles),
                            capabilityShapes = capabilityShapes(selection.addonBundles),
                            selectedPlatformModules = selected.mapTo(mutableSetOf(), PlatformModule::id),
                            platformFunctions = libraries.functions,
                            platformTypes = libraries.types,
                            platformFields = libraries.fields,
                            platformScalarTypes = selected.flatMap(PlatformModule::scalarTypes),
                            platformScalarConstants = selected.flatMap(PlatformModule::scalarConstants),
                            limits = request.limits,
                        )
                    CompuktersFir2IrPipeline.lowerGuest(output, session, sourceOutputs.values.toList())?.let { lowered ->
                        val linked = linkLibraries(lowered, libraries.artifacts)
                        when (
                            val result =
                                ArtifactWriter.write(
                                    linked,
                                    ArtifactWriteLimits(
                                        artifactBytes = request.limits.artifactBytes,
                                        diagnostics = request.limits.diagnostics,
                                    ),
                                )
                        ) {
                            is ArtifactWriteResult.Success -> {
                                artifact = BinaryValue.of(result.bytes)
                            }

                            is ArtifactWriteResult.Failure -> {
                                result.errors.forEach { error ->
                                    diagnostics +=
                                        WorkerDiagnostic(
                                            DiagnosticSeverity.ERROR,
                                            DiagnosticCategory.INTERNAL,
                                            "ARTIFACT_WRITE_${error.code}",
                                            "artifact writer rejected lowered IR: ${error.detail}",
                                            null,
                                            null,
                                            null,
                                        )
                                }
                            }
                        }
                    }
                }
            }
            val failed = diagnostics.any { it.severity == DiagnosticSeverity.ERROR }
            K2CompilationResult(
                if (failed) ExitCode.COMPILATION_ERROR else ExitCode.OK,
                diagnostics.bounded(request.limits),
                reachedIr,
                artifact?.takeIf { !failed },
                failed,
            )
        }
    }

    private fun selectModules(request: CompileRequest): SelectedPlatform {
        val addonBundles =
            request.addonBundles.map { payload ->
                AddonGuestApiBundleCodec.decode(payload.content.toByteArray()).also { bundle ->
                    require(bundle.identity.id == payload.identity.name) { "addon bundle identity mismatch" }
                    require(
                        bundle.identity.contentHash
                            .toByteArray()
                            .contentEquals(payload.identity.hash.toByteArray()),
                    ) {
                        "addon bundle content hash mismatch"
                    }
                    require(bundle.identity.platformAbi == platform.identity.platformAbi) { "addon bundle platform ABI mismatch" }
                }
            }
        val addonById = addonBundles.associateBy { it.identity.id }
        require(addonById.size == addonBundles.size) { "duplicate addon bundle identity" }
        val addonByName = addonBundles.associateBy { it.moduleDescriptor.id.toString() }
        require(addonByName.size == addonBundles.size) { "duplicate addon bundle module identity" }
        val packagedByName = platform.modules.associateBy { it.id.toString() }
        require(addonByName.keys.intersect(packagedByName.keys).isEmpty()) { "addon bundle shadows a packaged platform module" }
        val byName = packagedByName + addonBundles.associate { it.moduleDescriptor.id.toString() to it.moduleDescriptor }
        val requested =
            request.platformModules.map { identity ->
                val module = requireNotNull(byName[identity.name]) { "unknown platform module ${identity.name}" }
                val expectedHash = addonByName[identity.name]?.identity?.contentHash ?: PlatformBundleCodec.moduleContentHash(module)
                require(expectedHash.toByteArray().contentEquals(identity.hash.toByteArray())) {
                    "platform module ${identity.name} content hash mismatch"
                }
                module
            }
        val externalRequested = requested.map(PlatformModule::id).mapTo(mutableSetOf(), PlatformModuleId::toString) - packagedByName.keys
        require(externalRequested == addonByName.keys) { "compile request addon payloads do not match selected external modules" }
        val merged =
            PlatformBundle(
                platform.identity,
                platform.builtins,
                platform.modules + addonBundles.map(AddonGuestApiBundle::moduleDescriptor),
            )
        val resolved = PlatformModuleGraph(merged).resolve(requested.mapTo(mutableSetOf(), PlatformModule::id)).modules
        require(resolved.map(PlatformModule::id).toSet() == requested.map(PlatformModule::id).toSet()) {
            "compile request platform module closure is incomplete"
        }
        return SelectedPlatform(listOf(platform.builtins) + resolved, addonBundles)
    }

    private fun intrinsicRegistry(bundles: List<AddonGuestApiBundle>): TrustedIntrinsicRegistry =
        AddonTrustedIntrinsics.extend(
            CanonicalTrustedIntrinsics.registry,
            bundles.map { bundle ->
                AddonIntrinsicContract(bundle.moduleDescriptor.id, bundle.capabilitySchemas, bundle.bindings)
            },
        )

    private fun capabilityShapes(bundles: List<AddonGuestApiBundle>): Map<PlatformCapabilityId, PlatformCapabilityShape> =
        bundles.flatMap(AddonGuestApiBundle::capabilitySchemas).associate { schema ->
            PlatformCapabilityId(schema.identity.namespace, schema.identity.name, schema.identity.abiMajor) to
                PlatformCapabilityShape(schema.identity.abiMinor, schema.operations.size.toUInt())
        }

    private fun linkLibraries(
        application: Artifact,
        libraryArtifacts: List<Artifact>,
    ): Artifact = LibraryModuleLinker.link(application, libraryArtifacts)

    private fun sourceFootprint(request: CompileRequest): TemporaryUsage {
        val directories = mutableSetOf("source")
        request.sources.forEach { source ->
            var parent = Path.of(source.path.value).parent
            while (parent != null) {
                directories += "source/$parent"
                parent = parent.parent
            }
        }
        return TemporaryUsage(request.sources.size + directories.size, request.sources.sumOf { it.content.size.toLong() })
    }

    private fun failure(
        diagnostics: List<WorkerDiagnostic>,
        reachedIr: Boolean,
        limits: WorkerLimits,
    ) = K2CompilationResult(ExitCode.COMPILATION_ERROR, diagnostics.bounded(limits), reachedIr, null, true)

    private fun targetDiagnostic(
        path: VirtualSourcePath,
        message: String,
    ) = WorkerDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.TARGET, null, message, path, 0u, 0u)

    internal companion object {
        val DEPENDENCY_ANNOTATION = Regex("(?m)^\\s*@file:(DependsOn|Repository)\\b")
        private const val PLATFORM_RESOURCE = "/compukters-platform/compukters-platform.cpb"

        fun loadPackagedPlatform(): PlatformBundle =
            checkNotNull(K2CompilerAdapter::class.java.getResourceAsStream(PLATFORM_RESOURCE)) {
                "packaged Compukters platform is missing"
            }.use { PlatformBundleCodec.decode(it.readBytes()) }
    }
}

private data class SelectedPlatform(
    val modules: List<PlatformModule>,
    val addonBundles: List<AddonGuestApiBundle>,
)
