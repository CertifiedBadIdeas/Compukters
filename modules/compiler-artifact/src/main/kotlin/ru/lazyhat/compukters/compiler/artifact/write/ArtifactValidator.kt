/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukters.compiler.artifact.write

import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind

internal fun validateArtifact(
    artifact: Artifact,
    limits: ArtifactWriteLimits,
): List<ArtifactWriteError> {
    val errors = mutableListOf<ArtifactWriteError>()

    fun add(
        code: ArtifactWriteErrorCode,
        detail: String,
        location: ArtifactWriteLocation? = null,
    ) {
        if (errors.size < limits.diagnostics) errors += ArtifactWriteError(code, location, detail)
    }

    if (artifact.entry.module.value.toLong() >= artifact.modules.size) {
        add(ArtifactWriteErrorCode.BAD_REFERENCE, "entry module is outside the module table")
    }
    if (artifact.modules.size > limits.modules) {
        add(ArtifactWriteErrorCode.LIMIT_EXCEEDED, "module count exceeds ${limits.modules}")
    }
    if (artifact.capabilities.size > limits.capabilities) {
        add(ArtifactWriteErrorCode.LIMIT_EXCEEDED, "capability count exceeds ${limits.capabilities}")
    }
    if (artifact.modules.count { it.kind == ModuleKind.APPLICATION } != 1) {
        add(ArtifactWriteErrorCode.INCONSISTENT_RANGE, "artifact must contain exactly one application module")
    }
    if (artifact.manifest.compilerAbi.size != 32 || artifact.manifest.standardLibraryAbi.size != 32) {
        add(ArtifactWriteErrorCode.INVALID_RANGE, "manifest ABI identities must contain 32 bytes")
    }
    if (artifact.manifest.minimumSliceCost < artifact.manifest.maximumBlockCost) {
        add(ArtifactWriteErrorCode.INVALID_RANGE, "minimum slice cost is below maximum block cost")
    }

    artifact.modules.forEachIndexed { moduleIndex, module ->
        val moduleLocation = moduleIndex.toUInt()
        if (module.blocks.size > limits.blocks) {
            add(
                ArtifactWriteErrorCode.LIMIT_EXCEEDED,
                "blocks exceed ${limits.blocks}",
                ArtifactWriteLocation(module = moduleLocation, table = "BLOCKS"),
            )
        }
        if (module.functions.size > limits.functions) {
            add(
                ArtifactWriteErrorCode.LIMIT_EXCEEDED,
                "functions exceed ${limits.functions}",
                ArtifactWriteLocation(module = moduleLocation, table = "FUNCTIONS"),
            )
        }
        if (module.strings.zipWithNext().any { (left, right) -> left >= right }) {
            add(
                ArtifactWriteErrorCode.NON_CANONICAL_ORDER,
                "metadata strings are not strictly canonical",
                ArtifactWriteLocation(module = moduleLocation, table = "STRINGS"),
            )
        }
        if (module.utf16Literals.zipWithNext().any { (left, right) -> left >= right }) {
            add(
                ArtifactWriteErrorCode.NON_CANONICAL_ORDER,
                "UTF-16 literals are not strictly canonical",
                ArtifactWriteLocation(module = moduleLocation, table = "UTF16_LITERALS"),
            )
        }
        module.functions.forEachIndexed { functionIndex, function ->
            if (function.registers.size > limits.registersPerFunction || function.parameterCount.toLong() > function.registers.size) {
                add(
                    ArtifactWriteErrorCode.INVALID_RANGE,
                    "function register or parameter count is invalid",
                    ArtifactWriteLocation(moduleLocation, "FUNCTIONS", functionIndex.toUInt()),
                )
            }
            val blockEnd = function.firstBlock.value.toLong() + function.blockCount.toLong()
            if (blockEnd > module.blocks.size) {
                add(
                    ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                    "function block range is outside BLOCKS",
                    ArtifactWriteLocation(moduleLocation, "FUNCTIONS", functionIndex.toUInt()),
                )
            }
        }
        module.blocks.forEachIndexed { blockIndex, block ->
            if (block.owner.value.toLong() >= module.functions.size) {
                add(
                    ArtifactWriteErrorCode.BAD_REFERENCE,
                    "block owner is outside FUNCTIONS",
                    ArtifactWriteLocation(moduleLocation, "BLOCKS", blockIndex.toUInt()),
                )
            }
            if (block.instructions.lastOrNull()?.isTerminator() != true) {
                add(
                    ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                    "block must end in exactly one terminator",
                    ArtifactWriteLocation(moduleLocation, "BLOCKS", blockIndex.toUInt()),
                )
            }
            if (block.instructions.dropLast(1).any(Instruction::isTerminator)) {
                add(
                    ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                    "block contains a terminator before its end",
                    ArtifactWriteLocation(moduleLocation, "BLOCKS", blockIndex.toUInt()),
                )
            }
        }
    }

    return errors
}

private fun Instruction.isTerminator(): Boolean =
    this is Instruction.Jump ||
        this is Instruction.Branch ||
        this is Instruction.Return ||
        this is Instruction.Throw
