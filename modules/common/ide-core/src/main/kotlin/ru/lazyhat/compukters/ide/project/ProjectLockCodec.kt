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

package ru.lazyhat.compukters.ide.project

import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256

class ProjectLockException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object ProjectLockCodec {
    fun decode(
        source: String,
        limits: ProjectLimits = ProjectLimits(),
    ): ProjectLock {
        val bytes = validated("lock must be strict UTF-8") { TomlSupport.strictUtf8(source) }
        if (bytes.size > limits.lockBytes) throw ProjectLockException("lock byte count exceeds limit")
        val parsed = Toml.parse(source)
        if (parsed.hasErrors()) {
            val error = parsed.errors().first()
            throw ProjectLockException("lock TOML error at ${error.position()}: ${error.message}", error)
        }
        rejectUnknown(parsed, ROOT_FIELDS, "lock")
        val format = requiredLong(parsed, "format", "lock")
        if (format != ProjectLock.FORMAT.toLong()) throw ProjectLockException("unsupported lock format: $format")
        val toolchain = parsed["toolchain"] as? TomlTable ?: throw ProjectLockException("lock toolchain must be a table")
        rejectUnknown(toolchain, TOOLCHAIN_FIELDS, "toolchain")
        val identity =
            validated("invalid toolchain identity") {
                ToolchainLockIdentity(
                    compilerVersion = requiredString(toolchain, "compiler", "toolchain"),
                    languageVersion = requiredString(toolchain, "language", "toolchain"),
                    codegenAbi = requiredUInt(toolchain, "codegen_abi", "toolchain"),
                    artifactAbi = requiredUInt(toolchain, "artifact_abi", "toolchain"),
                    artifactWriterVersion = requiredUInt(toolchain, "artifact_writer", "toolchain"),
                    payloadHash = requiredHash(toolchain, "payload_sha256", "toolchain"),
                    platformAbi = requiredHash(toolchain, "platform_abi_sha256", "toolchain"),
                )
            }
        val addons = parseAddons(parsed["addons"], limits)
        return validated("invalid project lock") { ProjectLock.of(identity, addons, limits) }
    }

    fun encode(lock: ProjectLock): String =
        buildString {
            append("format = ").append(lock.format).append("\n\n")
            append("[toolchain]\n")
            append("compiler = ").append(TomlSupport.quoted(lock.toolchain.compilerVersion)).append('\n')
            append("language = ").append(TomlSupport.quoted(lock.toolchain.languageVersion)).append('\n')
            append("codegen_abi = ").append(lock.toolchain.codegenAbi).append('\n')
            append("artifact_abi = ").append(lock.toolchain.artifactAbi).append('\n')
            append("artifact_writer = ").append(lock.toolchain.artifactWriterVersion).append('\n')
            append("payload_sha256 = ").append(TomlSupport.quoted(lock.toolchain.payloadHash.hex())).append('\n')
            append("platform_abi_sha256 = ").append(TomlSupport.quoted(lock.toolchain.platformAbi.hex())).append('\n')
            lock.addons.forEach { addon ->
                append("\n[[addons]]\n")
                append("id = ").append(TomlSupport.quoted(addon.id.value)).append('\n')
                append("version = ").append(TomlSupport.quoted(addon.version)).append('\n')
                append("content_sha256 = ").append(TomlSupport.quoted(addon.contentHash.hex())).append('\n')
            }
        }

    private fun parseAddons(
        raw: Any?,
        limits: ProjectLimits,
    ): List<ResolvedAddon> {
        if (raw == null) return emptyList()
        val array = raw as? TomlArray ?: throw ProjectLockException("lock addons must be an array of tables")
        if (array.size() > limits.addons) throw ProjectLockException("project addon count exceeds limit")
        return List(array.size()) { index ->
            val table = array[index] as? TomlTable ?: throw ProjectLockException("lock addon $index must be a table")
            rejectUnknown(table, ADDON_FIELDS, "addon")
            validated("invalid locked addon at index $index") {
                ResolvedAddon(
                    id = AddonId(requiredString(table, "id", "addon")),
                    version = requiredString(table, "version", "addon"),
                    contentHash = requiredHash(table, "content_sha256", "addon"),
                )
            }
        }
    }

    private fun requiredHash(
        table: TomlTable,
        key: String,
        owner: String,
    ): Hash256 = validated("invalid $owner $key") { Hash256.fromHex(requiredString(table, key, owner)) }

    private fun requiredUInt(
        table: TomlTable,
        key: String,
        owner: String,
        maximum: UInt = UInt.MAX_VALUE,
    ): UInt {
        val value = requiredLong(table, key, owner)
        if (value < 0 || value.toULong() > maximum.toULong()) throw ProjectLockException("$owner $key is outside range")
        return value.toUInt()
    }

    private fun requiredLong(
        table: TomlTable,
        key: String,
        owner: String,
    ): Long = table[key] as? Long ?: throw ProjectLockException("$owner $key must be an integer")

    private fun requiredString(
        table: TomlTable,
        key: String,
        owner: String,
    ): String = table[key] as? String ?: throw ProjectLockException("$owner $key must be a string")

    private fun rejectUnknown(
        table: TomlTable,
        allowed: Set<String>,
        owner: String,
    ) {
        val unknown = table.keySet() - allowed
        if (unknown.isNotEmpty()) throw ProjectLockException("unknown $owner key: ${unknown.sorted().first()}")
    }

    private inline fun <T> validated(
        message: String,
        action: () -> T,
    ): T =
        try {
            action()
        } catch (exception: ProjectLockException) {
            throw exception
        } catch (exception: Exception) {
            throw ProjectLockException("$message: ${exception.message}", exception)
        }

    private val ROOT_FIELDS = setOf("format", "toolchain", "addons")
    private val TOOLCHAIN_FIELDS =
        setOf("compiler", "language", "codegen_abi", "artifact_abi", "artifact_writer", "payload_sha256", "platform_abi_sha256")
    private val ADDON_FIELDS = setOf("id", "version", "content_sha256")
}
