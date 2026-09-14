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

package ru.lazyhat.compukters.ide.client.analysis

import ru.lazyhat.compukters.addon.api.AddonCapabilityIdentity
import ru.lazyhat.compukters.addon.api.AddonCapabilityOperation
import ru.lazyhat.compukters.addon.api.AddonCapabilitySchema
import ru.lazyhat.compukters.addon.api.AddonCapabilityValueType
import ru.lazyhat.compukters.addon.api.AddonGuestApiBinding
import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundlePayload
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.CompletionSymbol
import ru.lazyhat.compukters.ide.analysis.CompletionTextEdit
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.compiler.profile.PlatformCatalog
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.project.AddonId
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ProjectManifest
import ru.lazyhat.compukters.ide.project.ResolvedModule
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdeCompletionPlannerTest {
    @Test
    fun `built-in completion requires only its import`() {
        val catalog = catalog()
        val entry = catalog.entries.single()
        val proposal =
            CompletionItem(
                "Redstone",
                "Redstone",
                CompletionKind.Object,
                origin = DeclarationOrigin.Platform(AnalysisModuleIdentity(entry.identity.id.value, entry.identity.contentHash)),
                symbol = CompletionSymbol("compukter.redstone.Redstone", "compukter.redstone.Redstone"),
                additionalEdits = listOf(CompletionTextEdit(EditorRange(0, 0), "import compukter.redstone.Redstone\n\n")),
            )
        val manifest = ProjectManifest.of("sample", emptySet())

        val detached = IdeCompletionPlanner(catalog).plan(listOf(proposal), manifest, null).single()
        assertEquals("import compukter.redstone.Redstone", detached.actionText)
        assertEquals(null, detached.addonRequirement)

        val unsupportedTarget = TargetCompileProfile(toolchain(catalog), emptyList(), WorkerLimits())
        assertEquals(1, IdeCompletionPlanner(catalog).plan(listOf(proposal), manifest, unsupportedTarget).size)
        val supportedTarget = TargetCompileProfile(toolchain(catalog), listOf(entry.identity), WorkerLimits())
        assertEquals(1, IdeCompletionPlanner(catalog).plan(listOf(proposal), manifest, supportedTarget).size)

        assertEquals(
            "import compukter.redstone.Redstone",
            IdeCompletionPlanner(catalog).plan(listOf(proposal), manifest, supportedTarget).single().actionText,
        )
    }

    @Test
    fun `planner enables an addon module advertised only by the attached target`() {
        val catalog = catalog()
        val addon = addon(catalog)
        val identity = AnalysisModuleIdentity(addon.first.id.value, addon.first.contentHash)
        val proposal =
            CompletionItem(
                "Telemetry",
                "Telemetry",
                CompletionKind.Object,
                origin = DeclarationOrigin.Platform(identity),
                symbol = CompletionSymbol("fixture.telemetry.Telemetry", "fixture.telemetry.Telemetry"),
                additionalEdits = listOf(CompletionTextEdit(EditorRange(0, 0), "import fixture.telemetry.Telemetry\n\n")),
            )
        val module = addon.first
        val payload = addon.second
        val target = TargetCompileProfile(toolchain(catalog), listOf(module), WorkerLimits(), listOf(payload))

        val planned = IdeCompletionPlanner(catalog).plan(listOf(proposal), ProjectManifest.of("sample", emptySet()), target).single()

        assertEquals("import fixture.telemetry.Telemetry · enable fixture", planned.actionText)
        assertEquals(AddonId("fixture"), planned.addonRequirement?.id)
    }

    @Test
    fun `planner retains builtin member completion for an attached target`() {
        val catalog = catalog()
        val builtins = catalog.bundle.builtins
        val identity =
            AnalysisModuleIdentity(
                builtins.id.toString(),
                Hash256.of(PlatformBundleCodec.moduleContentHash(builtins).toByteArray()),
            )
        val proposal =
            CompletionItem(
                "toInt()",
                "toInt",
                CompletionKind.Function,
                origin = DeclarationOrigin.Platform(identity),
            )
        val target = TargetCompileProfile(toolchain(catalog), catalog.entries.map { it.identity }, WorkerLimits())

        val planned = IdeCompletionPlanner(catalog).plan(listOf(proposal), ProjectManifest.of("sample", emptySet()), target).single()

        assertEquals("toInt", planned.proposal.insertText)
        assertEquals(null, planned.addonRequirement)
    }

    private fun catalog(): PlatformCatalog {
        val builtins = module(PlatformModuleId("kotlin", "builtins"), "1.0.0")
        val redstone = module(PlatformModuleId("compukter", "redstone"), "2.0.0")
        return PlatformCatalog.of(
            PlatformBundleCodec.assemble("2.4", PlatformBundleCodec.SUPPORTED_PLATFORM_ABI, builtins, listOf(redstone)),
        )
    }

    private fun module(
        id: PlatformModuleId,
        version: String,
    ) = PlatformModule(
        id,
        version,
        emptyList(),
        ImmutableBytes.of(id.toString().encodeToByteArray()),
        null,
        emptyList(),
        emptyList(),
        emptyList(),
    )

    private fun addon(catalog: PlatformCatalog): Pair<ResolvedModule, TrustedBundlePayload> {
        val id = PlatformModuleId("fixture", "api")
        val path = "fixture/telemetry/Telemetry.kt"
        val source = "package fixture.telemetry\nprivate object Bindings { external fun read(): Int }\n"
        val capability = AddonCapabilityIdentity("fixture", "telemetry", 1, 0)
        val module =
            PlatformModule(
                id,
                "1.0.0",
                emptyList(),
                ImmutableBytes.of(byteArrayOf(1)),
                null,
                listOf(
                    ru.lazyhat.compukters.platform.bundle
                        .PlatformSource(path, ImmutableBytes.of(source.encodeToByteArray())),
                ),
                listOf(
                    ru.lazyhat.compukters.platform.bundle.PlatformDeclaration(
                        "fixture.telemetry.Bindings.read",
                        "fun():Int",
                        id,
                        path,
                        0,
                        source.length,
                        true,
                    ),
                ),
                emptyList(),
            )
        val bundle =
            AddonGuestApiBundleCodec.assemble(
                "fixture",
                catalog.bundle.identity.platformAbi,
                module,
                listOf(
                    AddonCapabilitySchema(capability, listOf(AddonCapabilityOperation(emptyList(), AddonCapabilityValueType.I32, false))),
                ),
                listOf(AddonGuestApiBinding("fixture.telemetry", "Bindings", "read", "fun():Int", capability, 0)),
            )
        val hash = Hash256.of(bundle.identity.contentHash.toByteArray())
        return ResolvedModule(ModuleId("fixture", "api"), ApiMajor(1), "1.0.0", hash) to
            TrustedBundlePayload(
                TrustedBundleIdentity.of("fixture", hash),
                BinaryValue.of(AddonGuestApiBundleCodec.encode(bundle)),
            )
    }

    private fun toolchain(catalog: PlatformCatalog) =
        ToolchainLockIdentity(
            "2.4.10",
            catalog.bundle.identity.languageVersion,
            1u,
            1u,
            1u,
            Hash256.of(ByteArray(32) { 1 }),
            Hash256.of(
                catalog.bundle.identity.contentHash
                    .toByteArray(),
            ),
        )

    private fun hash(value: Int) = Hash256.of(ByteArray(32) { value.toByte() })
}
