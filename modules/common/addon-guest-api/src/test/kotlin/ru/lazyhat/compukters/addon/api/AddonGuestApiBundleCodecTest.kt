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

package ru.lazyhat.compukters.addon.api

import ru.lazyhat.compukters.platform.bundle.PlatformCompletionDeclaration
import ru.lazyhat.compukters.platform.bundle.PlatformCompletionKind
import ru.lazyhat.compukters.platform.bundle.PlatformDeclaration
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.io.ByteArrayOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AddonGuestApiBundleCodecTest {
    @Test
    fun `bundle round trip is deterministic and preserves optional sources`() {
        val first = bundle()
        val second = bundle()

        assertContentEquals(AddonGuestApiBundleCodec.encode(first), AddonGuestApiBundleCodec.encode(second))
        assertEquals(first, AddonGuestApiBundleCodec.decode(AddonGuestApiBundleCodec.encode(first)))
        assertEquals("fixture:meters", first.identity.module)
        assertEquals(listOf("fixture/meters/Meters.kt"), first.moduleDescriptor.sources.map(PlatformSource::path))
    }

    @Test
    fun `bundle without sources remains usable metadata`() {
        val bundle = bundle(includeSources = false)

        assertNull(bundle.sourcesJar)
        assertEquals(emptyList(), bundle.moduleDescriptor.sources)
        assertEquals(bundle, AddonGuestApiBundleCodec.decode(AddonGuestApiBundleCodec.encode(bundle)))
    }

    @Test
    fun `bundle hash changes with schemas bindings and sources`() {
        val baseline = bundle()
        val changedSource = bundle(source = SOURCE.replace("package ", "package  "))
        val changedSchema = bundle(result = AddonCapabilityValueType.F32, signature = "fun(Int):Float")
        val changedBinding = bundle(operation = 1, extraOperation = true)

        assertNotEquals(baseline.identity.contentHash, changedSource.identity.contentHash)
        assertNotEquals(baseline.identity.contentHash, changedSchema.identity.contentHash)
        assertNotEquals(baseline.identity.contentHash, changedBinding.identity.contentHash)
    }

    @Test
    fun `binding must exactly match an external declaration and its operation schema`() {
        assertFailsWith<IllegalArgumentException> { bundle(callableName = "spoof") }
        assertFailsWith<IllegalArgumentException> { bundle(signature = "fun(Int):Float") }
        assertFailsWith<IllegalArgumentException> { bundle(operation = 1) }
    }

    @Test
    fun `addon cannot claim declarations or capabilities in another namespace`() {
        assertFailsWith<IllegalArgumentException> { bundle(declarationSymbol = "foreign.MeterBindings.read") }
        assertFailsWith<IllegalArgumentException> { bundle(capability = AddonCapabilityIdentity("foreign", "meters", 1, 0)) }
    }

    @Test
    fun `unsupported capability ABI types are rejected`() {
        assertFailsWith<IllegalArgumentException> { bundle(signature = "fun(List<Int>):Int") }
    }

    @Test
    fun `metadata archive rejects executable JVM content`() {
        val encoded = AddonGuestApiBundleCodec.encode(bundle())
        val decoded = AddonGuestApiBundleCodec.decode(encoded)
        val badJar = archive("fixture/Injected.class", byteArrayOf(1, 2, 3))
        val replacement = replaceMetadataJar(encoded, decoded.metadataJar.toByteArray(), badJar)

        assertFailsWith<IllegalArgumentException> { AddonGuestApiBundleCodec.decode(replacement) }
    }

    @Test
    fun `catalog rejects duplicate addon and module identities`() {
        val first = bundle()

        assertFailsWith<IllegalArgumentException> { AddonGuestApiCatalog.of(listOf(first, first)) }
    }

    @Test
    fun `bundle capability collections cannot be changed through their inputs`() {
        val operations = mutableListOf(AddonCapabilityOperation(listOf(AddonCapabilityValueType.I32), AddonCapabilityValueType.I32, false))
        val schema = AddonCapabilitySchema(AddonCapabilityIdentity("fixture", "meters", 1, 0), operations)

        operations.clear()

        assertEquals(1, schema.operations.size)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (schema.operations as MutableList<AddonCapabilityOperation>).clear()
        }
    }

    private fun bundle(
        source: String = SOURCE,
        result: AddonCapabilityValueType = AddonCapabilityValueType.I32,
        signature: String = "fun(Int):Int",
        operation: Int = 0,
        extraOperation: Boolean = false,
        callableName: String = "read",
        declarationSymbol: String = "fixture.meters.MeterBindings.read",
        capability: AddonCapabilityIdentity = AddonCapabilityIdentity("fixture", "meters", 1, 0),
        includeSources: Boolean = true,
    ): AddonGuestApiBundle {
        val path = "fixture/meters/Meters.kt"
        val moduleId = PlatformModuleId("fixture", "meters")
        val declaration =
            PlatformDeclaration(
                declarationSymbol,
                signature,
                moduleId,
                path,
                0,
                source.length,
                trustedExternal = true,
            )
        val module =
            PlatformModule(
                moduleId,
                "1.0.0",
                listOf(PlatformModuleId("stdlib", "core")),
                ImmutableBytes.of(byteArrayOf(1, 2, 3)),
                null,
                listOf(PlatformSource(path, ImmutableBytes.of(source.encodeToByteArray()))),
                listOf(declaration),
                listOf(
                    PlatformCompletionDeclaration(
                        "fixture.meters.Meter",
                        "Meter",
                        "object",
                        PlatformCompletionKind.OBJECT,
                        moduleId,
                        path,
                        0,
                        source.length,
                        false,
                    ),
                ),
            )
        val operations = mutableListOf(AddonCapabilityOperation(listOf(AddonCapabilityValueType.I32), result, asynchronous = false))
        if (extraOperation) operations += AddonCapabilityOperation(listOf(AddonCapabilityValueType.I32), result, asynchronous = false)
        return AddonGuestApiBundleCodec.assemble(
            "fixture",
            platformAbi = 1,
            module,
            listOf(AddonCapabilitySchema(capability, operations)),
            listOf(AddonGuestApiBinding("fixture.meters", "MeterBindings", callableName, signature, capability, operation)),
            includeSources,
        )
    }

    private fun archive(
        path: String,
        bytes: ByteArray,
    ): ByteArray =
        ByteArrayOutputStream()
            .also { output ->
                JarOutputStream(output).use { jar ->
                    JarEntry("META-INF/MANIFEST.MF").also { entry ->
                        jar.putNextEntry(entry)
                        jar.write("Manifest-Version: 1.0\r\n\r\n".encodeToByteArray())
                        jar.closeEntry()
                    }
                    JarEntry(path).also { entry ->
                        jar.putNextEntry(entry)
                        jar.write(bytes)
                        jar.closeEntry()
                    }
                }
            }.toByteArray()

    private fun replaceMetadataJar(
        encoded: ByteArray,
        original: ByteArray,
        replacement: ByteArray,
    ): ByteArray {
        val index = encoded.indexOfSubsequence(original)
        require(index >= 4)
        val output =
            encoded.copyOfRange(0, index - 4) +
                byteArrayOf(
                    (replacement.size ushr 24).toByte(),
                    (replacement.size ushr 16).toByte(),
                    (replacement.size ushr 8).toByte(),
                    replacement.size.toByte(),
                ) + replacement + encoded.copyOfRange(index + original.size, encoded.size)
        return output
    }

    private fun ByteArray.indexOfSubsequence(value: ByteArray): Int =
        indices.firstOrNull { start -> start + value.size <= size && copyOfRange(start, start + value.size).contentEquals(value) } ?: -1

    private companion object {
        const val SOURCE = "package fixture.meters\nprivate object MeterBindings { external fun read(handle: Int): Int }\n"
    }
}
