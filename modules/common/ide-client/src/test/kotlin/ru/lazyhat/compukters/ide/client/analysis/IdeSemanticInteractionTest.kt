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

import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundlePayload
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotId
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IdeSemanticInteractionTest {
    @Test
    fun `attached source catalog copies text and requires exact bundle identity`() {
        val bundle = AnalysisModuleIdentity("std.core", hash(3))
        val replacement = AnalysisModuleIdentity("std.core", hash(4))
        val path = VirtualSourcePath.kotlin("compukter/api/Sample.kt")
        val files = mutableMapOf(path to "class Sample")
        val sources = mutableMapOf(bundle to files)
        val catalog = IdeAttachedSourceCatalog.of(sources, 1, 1, 64, 64)

        files[path] = "changed"
        sources.clear()

        assertEquals("class Sample", catalog.text(bundle, path))
        assertNull(catalog.text(replacement, path))
    }

    @Test
    fun `attached source catalog enforces strict UTF-8 budgets`() {
        val bundle = AnalysisModuleIdentity("std.core", hash(3))
        val first = VirtualSourcePath.kotlin("first/first.kt")
        val second = VirtualSourcePath.kotlin("second.kt")

        assertFailsWith<IllegalArgumentException> {
            IdeAttachedSourceCatalog.of(mapOf(bundle to mapOf(first to "😀")), 1, 1, 3, 4)
        }
        assertFailsWith<IllegalArgumentException> {
            IdeAttachedSourceCatalog.of(mapOf(bundle to mapOf(first to "é", second to "é")), 1, 2, 2, 3)
        }
        assertFailsWith<IllegalArgumentException> {
            IdeAttachedSourceCatalog.of(mapOf(bundle to mapOf(first to "\uD800")), 1, 1, 8, 8)
        }
        assertFailsWith<IllegalArgumentException> {
            IdeAttachedSourceCatalog.of(mapOf(bundle to mapOf(first to "a", second to "b")), 1, 1, 8, 8)
        }
    }

    @Test
    fun `attached addons have a separate bounded source pool and a shared module limit`() {
        val packaged = AnalysisModuleIdentity("stdlib:core", hash(3))
        val path = VirtualSourcePath.kotlin("base.kt")
        val first = addonPayload("first", "first.kt" to "a")
        val second = addonPayload("second", "second.kt" to "b")
        val catalog = IdeAttachedSourceCatalog.of(mapOf(packaged to mapOf(path to "x")), 3, 1, 8, 1)
        val combined = catalog.withAddonBundles(listOf(first))
        val firstIdentity = AnalysisModuleIdentity("first:api", first.identity.hash)

        assertEquals("x", combined.text(packaged, path))
        assertEquals("a", combined.text(firstIdentity, VirtualSourcePath.kotlin("first/first.kt")))
        assertFailsWith<IllegalArgumentException> { combined.withAddonBundles(listOf(second)) }
        assertFailsWith<IllegalArgumentException> { catalog.withAddonBundles(listOf(first, second)) }
        assertFailsWith<IllegalArgumentException> {
            catalog.withAddonBundles(listOf(addonPayload("large", "large.kt" to "ab")))
        }
        assertFailsWith<IllegalArgumentException> {
            IdeAttachedSourceCatalog
                .of(mapOf(packaged to mapOf(path to "x")), 1, 1, 8, 1)
                .withAddonBundles(listOf(first))
        }
    }

    @Test
    fun `larger packaged source budget preserves smaller addon limits across additions`() {
        val packaged = AnalysisModuleIdentity("stdlib:core", hash(3))
        val path = VirtualSourcePath.kotlin("base.kt")
        val first = addonPayload("first", "first.kt" to "a")
        val second = addonPayload("second", "second.kt" to "b")
        val catalog =
            IdeAttachedSourceCatalog.of(
                mapOf(packaged to mapOf(path to "large packaged source", VirtualSourcePath.kotlin("other.kt") to "other")),
                maximumModules = 3,
                maximumFiles = 2,
                maximumFileBytes = 64,
                maximumTotalBytes = 128,
                maximumAddonFiles = 1,
                maximumAddonFileBytes = 1,
                maximumAddonTotalBytes = 1,
            )
        val combined = catalog.withAddonBundles(listOf(first))
        assertEquals("large packaged source", combined.text(packaged, path))
        assertEquals(
            "a",
            combined.text(AnalysisModuleIdentity("first:api", first.identity.hash), VirtualSourcePath.kotlin("first/first.kt")),
        )
        assertFailsWith<IllegalArgumentException> { combined.withAddonBundles(listOf(second)) }
        assertFailsWith<IllegalArgumentException> { catalog.withAddonBundles(listOf(first, second)) }
        assertFailsWith<IllegalArgumentException> { catalog.withAddonBundles(listOf(addonPayload("large", "large.kt" to "ab"))) }
    }

    @Test
    fun `token range admits the complete identifier around a UTF-16 offset`() {
        assertEquals(EditorRange(4, 10), KotlinSourceTokenRange.find("val answer = 42", 7))
        assertEquals(EditorRange(4, 14), KotlinSourceTokenRange.find("val `odd name` = 1", 8))
        assertEquals(EditorRange(4, 8), KotlinSourceTokenRange.find("val café = 1", 6))
    }

    @Test
    fun `token range rejects offsets outside source and inside surrogate pairs`() {
        assertNull(KotlinSourceTokenRange.find("answer", -1))
        assertNull(KotlinSourceTokenRange.find("answer", 7))
        assertNull(KotlinSourceTokenRange.find("a😀b", 2))
        assertNull(KotlinSourceTokenRange.find("val answer", 3))
    }

    @Test
    fun `chooser copies and bounds declaration targets`() {
        val targets = mutableListOf(projectTarget("src/a.kt", 4, 5))
        val chooser = IdeSemanticInteraction.Chooser(anchor(), targets, selectedIndex = 0, maximumTargets = 1)

        targets.clear()

        assertEquals(1, chooser.targets.size)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (chooser.targets as MutableList<IdeDeclarationTarget>).clear()
        }
        assertFailsWith<IllegalArgumentException> {
            IdeSemanticInteraction.Chooser(
                anchor(),
                List(2) { projectTarget("src/a.kt", it, it + 1) },
                selectedIndex = 0,
                maximumTargets = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            IdeSemanticInteraction.Chooser(anchor(), listOf(projectTarget("src/a.kt", 0, 1)), 1, 1)
        }
    }

    @Test
    fun `link copies declaration locations`() {
        val locations =
            mutableListOf(
                DeclarationLocation.Source(
                    DeclarationOrigin.Project,
                    VirtualSourcePath.kotlin("src/main.kt"),
                    EditorRange(4, 10),
                ),
            )
        val link = IdeSemanticInteraction.Link(anchor(), locations)

        locations.clear()

        assertEquals(1, link.locations.size)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (link.locations as MutableList<DeclarationLocation>).clear()
        }
    }

    private fun addonPayload(
        name: String,
        vararg sources: Pair<String, String>,
    ): TrustedBundlePayload {
        val bundle =
            AddonGuestApiBundleCodec.assemble(
                addon = name,
                platformAbi = PlatformBundleCodec.SUPPORTED_PLATFORM_ABI,
                module =
                    PlatformModule(
                        PlatformModuleId(name, "api"),
                        "1.0.0",
                        emptyList(),
                        ImmutableBytes.of(byteArrayOf(1)),
                        null,
                        sources.map { (path, text) -> PlatformSource("$name/$path", ImmutableBytes.of(text.encodeToByteArray())) },
                        emptyList(),
                        emptyList(),
                    ),
                capabilitySchemas = emptyList(),
                bindings = emptyList(),
            )
        return TrustedBundlePayload(
            TrustedBundleIdentity.of(bundle.identity.id, Hash256.of(bundle.identity.contentHash.toByteArray())),
            BinaryValue.of(AddonGuestApiBundleCodec.encode(bundle)),
        )
    }

    private fun projectTarget(
        path: String,
        start: Int,
        end: Int,
    ) = IdeDeclarationTarget.Project(ProjectPath.file(path), EditorRange(start, end))

    private fun anchor() =
        IdeSemanticAnchor(
            AnalysisSnapshotIdentity(SourceSnapshotId(hash(0)), AnalysisProfileIdentity(hash(1))),
            VirtualSourcePath.kotlin("src/main.kt"),
            documentRevision = 0,
            offsetUtf16 = 4,
            tokenRange = EditorRange(4, 10),
        )

    private fun hash(seed: Int) = Hash256.of(ByteArray(32) { seed.toByte() })
}
