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

package ru.lazyhat.compukters.compiler.k2.engine.build

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/** Resolved server operations, with original Guest sources when analyzed on the native platform. */
data class DeclarativeGuestContract(
    val types: List<GuestContractType>,
    val nativeSources: Map<String, String>,
    val boundaryRecords: Map<String, GuestContractType> = emptyMap(),
    val nativeInterfaces: List<NativeGuestInterface> = emptyList(),
) {
    val operations: List<GuestContractOperation> get() = types.flatMap { it.operations }
    val commands: Set<String>
        get() = operations.filter { it.kind == GuestOperationKind.COMMAND }.mapTo(sortedSetOf()) { it.binding }

    /** Source-level ABI names remain stable even when native implementation owners change. */
    fun bindingSymbol(operation: GuestContractOperation): String {
        val owner = operation.binding.substringBeforeLast('.')
        val generatedOwner = "${owner.substringBeforeLast('.')}.__Compukters${owner.substringAfterLast('.')}"
        return "$generatedOwner.${operation.binding.substringAfterLast('.')}"
    }

    val bindingAliases: Map<String, String> get() = operations.associate { bindingSymbol(it) to it.binding }
}

enum class GuestOperationKind { READ, CALL, COMMAND, WAIT }

data class GuestContractType(
    val packageName: String,
    val name: String,
    val deviceId: String?,
    val record: Boolean,
    val resource: Boolean,
    val fields: List<GuestContractParameter>,
    val operations: List<GuestContractOperation>,
    val constants: List<Pair<String, String>>,
    val documentation: String,
    val construction: GuestRecordConstruction? = null,
)

data class GuestRecordConstruction(
    val factory: String,
    val normalize: String,
)

data class GuestContractOperation(
    val name: String,
    val result: String,
    val kind: GuestOperationKind,
    val binding: String,
    val device: Boolean,
    val parameters: List<GuestContractParameter>,
    val documentation: String,
)

data class GuestContractParameter(
    val name: String,
    val type: String,
    val constraints: List<GuestArgumentConstraint>,
)

data class GuestArgumentConstraint(
    val kind: String,
    val minimum: String = "",
    val maximum: String = "",
) {
    fun condition(
        value: String,
        type: String,
    ): String =
        when (kind) {
            "Finite" -> {
                if (type ==
                    "Float"
                ) {
                    "$value >= -Float.MAX_VALUE && $value <= Float.MAX_VALUE"
                } else {
                    "$value >= -Double.MAX_VALUE && $value <= Double.MAX_VALUE"
                }
            }

            "DoubleRange", "IntRange" -> {
                "$value >= $minimum && $value <= $maximum"
            }

            "MaxLength" -> {
                "$value.${if (type == "String") "length" else "size"} <= $maximum"
            }

            else -> {
                error("Unknown constraint")
            }
        }
}

/** Exact native compiler inputs, including original executable methods and generated server plumbing. */
fun generateGuestContractSources(contract: DeclarativeGuestContract): Map<String, String> = generateNativeGuestSources(contract)

fun writeGeneratedGuestSources(
    contract: DeclarativeGuestContract,
    root: Path,
) {
    Files.createDirectories(root)
    val sources = generateGuestContractSources(contract)
    Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.forEach { path ->
            if (root.relativize(path).invariantSeparatorsPathString !in sources) Files.delete(path)
        }
    }
    sources.forEach { (relative, text) ->
        val target = root.resolve(relative).normalize()
        require(target.startsWith(root.normalize()))
        Files.createDirectories(target.parent)
        if (!Files.exists(target) || Files.readString(target) != text) Files.writeString(target, text)
    }
}
