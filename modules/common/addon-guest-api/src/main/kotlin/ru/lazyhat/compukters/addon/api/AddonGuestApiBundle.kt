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

import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import ru.lazyhat.compukters.worker.value.Sha256
import java.util.Collections

data class AddonGuestApiIdentity(
    val addon: String,
    val module: String,
    val version: String,
    val platformAbi: Int,
    val contentHash: Sha256,
)

data class AddonGuestApiBinding(
    val packageName: String,
    val owner: String?,
    val callableName: String,
    val signature: String,
    val capability: AddonCapabilityIdentity,
    val operation: Int,
) {
    val symbol: String = listOfNotNull(packageName.takeIf(String::isNotEmpty), owner, callableName).joinToString(".")
}

data class AddonCapabilityIdentity(
    val namespace: String,
    val name: String,
    val abiMajor: Int,
    val abiMinor: Int,
)

enum class AddonCapabilityValueType {
    UNIT,
    I32,
    I64,
    F32,
    F64,
    BOOL,
    CHAR,
    STRING,
}

class AddonCapabilityOperation(
    arguments: List<AddonCapabilityValueType>,
    val result: AddonCapabilityValueType,
    val asynchronous: Boolean,
) {
    val arguments: List<AddonCapabilityValueType> = Collections.unmodifiableList(arguments.toList())

    override fun equals(other: Any?): Boolean =
        other is AddonCapabilityOperation &&
            arguments == other.arguments &&
            result == other.result &&
            asynchronous == other.asynchronous

    override fun hashCode(): Int = listOf(arguments, result, asynchronous).hashCode()
}

class AddonCapabilitySchema(
    val identity: AddonCapabilityIdentity,
    operations: List<AddonCapabilityOperation>,
) {
    val operations: List<AddonCapabilityOperation> = Collections.unmodifiableList(operations.toList())

    override fun equals(other: Any?): Boolean =
        other is AddonCapabilitySchema &&
            identity == other.identity &&
            operations == other.operations

    override fun hashCode(): Int = 31 * identity.hashCode() + operations.hashCode()
}

class AddonGuestApiBundle internal constructor(
    val identity: AddonGuestApiIdentity,
    val moduleDescriptor: PlatformModule,
    metadataJar: ByteArray,
    sourcesJar: ByteArray?,
    capabilitySchemas: List<AddonCapabilitySchema>,
    bindings: List<AddonGuestApiBinding>,
) {
    val metadataJar: ImmutableBytes = ImmutableBytes.of(metadataJar)
    val sourcesJar: ImmutableBytes? = sourcesJar?.let(ImmutableBytes::of)
    val capabilitySchemas: List<AddonCapabilitySchema> = Collections.unmodifiableList(capabilitySchemas.toList())
    val bindings: List<AddonGuestApiBinding> = Collections.unmodifiableList(bindings.toList())

    override fun equals(other: Any?): Boolean =
        other is AddonGuestApiBundle &&
            identity == other.identity &&
            moduleDescriptor == other.moduleDescriptor &&
            metadataJar == other.metadataJar &&
            sourcesJar == other.sourcesJar &&
            capabilitySchemas == other.capabilitySchemas &&
            bindings == other.bindings

    override fun hashCode(): Int = listOf(identity, moduleDescriptor, metadataJar, sourcesJar, capabilitySchemas, bindings).hashCode()
}

class AddonGuestApiCatalog private constructor(
    bundles: List<AddonGuestApiBundle>,
) {
    val bundles: List<AddonGuestApiBundle> =
        Collections.unmodifiableList(bundles.sortedBy { bundle -> bundle.identity.module })

    init {
        require(this.bundles.size <= AddonGuestApiLimits.MAXIMUM_BUNDLES) { "addon guest API catalog contains too many bundles" }
        require(this.bundles.distinctBy { it.identity.addon }.size == this.bundles.size) {
            "addon guest API catalog contains duplicate addon identities"
        }
        require(this.bundles.distinctBy { it.identity.module }.size == this.bundles.size) {
            "addon guest API catalog contains duplicate module identities"
        }
        require(this.bundles.sumOf { AddonGuestApiBundleCodec.encode(it).size.toLong() } <= AddonGuestApiLimits.MAXIMUM_CATALOG_BYTES) {
            "addon guest API catalog exceeds byte limit"
        }
    }

    fun find(module: String): AddonGuestApiBundle? = bundles.singleOrNull { it.identity.module == module }

    companion object {
        fun of(bundles: List<AddonGuestApiBundle>): AddonGuestApiCatalog = AddonGuestApiCatalog(bundles)

        fun empty(): AddonGuestApiCatalog = AddonGuestApiCatalog(emptyList())
    }
}

object AddonGuestApiLimits {
    const val MAXIMUM_BUNDLES: Int = 64
    const val MAXIMUM_BUNDLE_BYTES: Int = 4 * 1024 * 1024
    const val MAXIMUM_CATALOG_BYTES: Long = 16L * 1024 * 1024
    const val MAXIMUM_METADATA_BYTES: Int = 2 * 1024 * 1024
    const val MAXIMUM_SOURCE_BYTES: Int = 2 * 1024 * 1024
    const val MAXIMUM_ARCHIVE_ENTRIES: Int = 512
    const val MAXIMUM_SOURCE_FILES: Int = 256
    const val MAXIMUM_DECLARATIONS: Int = 1024
    const val MAXIMUM_BINDINGS: Int = 256
    const val MAXIMUM_CAPABILITIES: Int = 28
    const val MAXIMUM_OPERATIONS: Int = 256
    const val MAXIMUM_ARGUMENTS: Int = 32
    const val MAXIMUM_TEXT_BYTES: Int = 4096
}
