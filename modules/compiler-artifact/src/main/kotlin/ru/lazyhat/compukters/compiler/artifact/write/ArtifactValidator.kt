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
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.ExportVisibility
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType

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

    data class TypeIdentity(
        val module: Int,
        val type: Int,
    )

    data class FunctionIdentity(
        val module: Int,
        val function: Int,
    )

    fun resolveType(
        sourceModule: Int,
        reference: TypeRef,
    ): TypeIdentity? =
        when (reference) {
            is TypeRef.Local -> {
                reference.id.value.toInt().takeIf { it in artifact.modules[sourceModule].types.indices }?.let {
                    TypeIdentity(sourceModule, it)
                }
            }

            is TypeRef.Imported -> {
                val import = artifact.modules[sourceModule].imports.getOrNull(reference.id.value.toInt())
                if (import?.kind != SymbolKind.TYPE) {
                    null
                } else {
                    val targetModule = artifact.modules.getOrNull(import.targetModule.value.toInt())
                    val targetModuleIndex = import.targetModule.value.toInt()
                    targetModule
                        ?.exports
                        ?.singleOrNull { it.kind == SymbolKind.TYPE && it.name == import.targetName }
                        ?.localSymbol
                        ?.toInt()
                        ?.takeIf { it in targetModule.types.indices }
                        ?.let { TypeIdentity(targetModuleIndex, it) }
                }
            }
        }

    fun nominalAssignable(
        source: TypeIdentity,
        destination: TypeIdentity,
    ): Boolean {
        val pending = ArrayDeque<TypeIdentity>()
        val visited = mutableSetOf<TypeIdentity>()
        pending += source
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (current == destination) return true
            if (!visited.add(current)) continue
            when (val nominal = artifact.modules[current.module].types[current.type]) {
                is NominalType.Class -> {
                    nominal.superType?.let { resolveType(current.module, it) }?.let(pending::add)
                    nominal.interfaces.mapNotNull { resolveType(current.module, it) }.forEach(pending::add)
                }

                is NominalType.Interface -> {
                    nominal.superType?.let { resolveType(current.module, it) }?.let(pending::add)
                    nominal.interfaces.mapNotNull { resolveType(current.module, it) }.forEach(pending::add)
                }

                is NominalType.Array,
                is NominalType.Function,
                -> {}
            }
        }
        return false
    }

    fun valueAssignable(
        sourceModule: Int,
        source: ValueType,
        destinationModule: Int,
        destination: ValueType,
    ): Boolean =
        when {
            source is ValueType.Ref && destination is ValueType.Ref -> {
                (!source.nullable || destination.nullable) &&
                    resolveType(sourceModule, source.type)
                        ?.let { sourceIdentity ->
                            resolveType(destinationModule, destination.type)
                                ?.let { destinationIdentity -> nominalAssignable(sourceIdentity, destinationIdentity) }
                        } == true
            }

            source is ValueType.Ref || destination is ValueType.Ref -> {
                false
            }

            else -> {
                source == destination
            }
        }

    fun valueTypesMatch(
        leftModule: Int,
        left: ValueType,
        rightModule: Int,
        right: ValueType,
    ): Boolean =
        when {
            left is ValueType.Ref && right is ValueType.Ref -> {
                left.nullable == right.nullable && resolveType(leftModule, left.type) == resolveType(rightModule, right.type)
            }

            left is ValueType.Ref || right is ValueType.Ref -> {
                false
            }

            else -> {
                left == right
            }
        }

    fun signaturesMatch(
        leftModule: Int,
        left: TypeRef,
        rightModule: Int,
        right: TypeRef,
    ): Boolean {
        val leftIdentity = resolveType(leftModule, left) ?: return false
        val rightIdentity = resolveType(rightModule, right) ?: return false
        val leftType = artifact.modules[leftIdentity.module].types[leftIdentity.type]
        val rightType = artifact.modules[rightIdentity.module].types[rightIdentity.type]
        return if (leftType is NominalType.Function && rightType is NominalType.Function) {
            leftType.suspending == rightType.suspending &&
                valueTypesMatch(leftIdentity.module, leftType.result, rightIdentity.module, rightType.result) &&
                leftType.parameters.size == rightType.parameters.size &&
                leftType.parameters.zip(rightType.parameters).all { (leftParameter, rightParameter) ->
                    valueTypesMatch(leftIdentity.module, leftParameter, rightIdentity.module, rightParameter)
                }
        } else {
            leftIdentity == rightIdentity
        }
    }

    fun resolveFunction(
        sourceModule: Int,
        reference: FunctionRef,
    ): FunctionIdentity? =
        when (reference) {
            is FunctionRef.Local -> {
                reference.id.value.toInt().takeIf { it in artifact.modules[sourceModule].functions.indices }?.let {
                    FunctionIdentity(sourceModule, it)
                }
            }

            is FunctionRef.Imported -> {
                val import = artifact.modules[sourceModule].imports.getOrNull(reference.id.value.toInt())
                if (import?.kind != SymbolKind.FUNCTION) {
                    null
                } else {
                    val targetModule = artifact.modules.getOrNull(import.targetModule.value.toInt())
                    val targetModuleIndex = import.targetModule.value.toInt()
                    targetModule
                        ?.exports
                        ?.singleOrNull {
                            it.kind == SymbolKind.FUNCTION &&
                                it.name == import.targetName &&
                                signaturesMatch(sourceModule, import.expectedSignature, targetModuleIndex, it.signature)
                        }?.localSymbol
                        ?.toInt()
                        ?.takeIf { it in targetModule.functions.indices }
                        ?.let { FunctionIdentity(targetModuleIndex, it) }
                }
            }
        }

    fun stringIdentity(): TypeIdentity? {
        val matches =
            artifact.modules.flatMapIndexed { moduleIndex, module ->
                if (module.kind != ModuleKind.LIBRARY) {
                    emptyList()
                } else {
                    module.exports.mapNotNull { export ->
                        val isString =
                            export.kind == SymbolKind.TYPE &&
                                export.visibility == ExportVisibility.PUBLIC_LIBRARY &&
                                module.strings.getOrNull(export.name.value.toInt())?.toString() == "kotlin.String"
                        export.localSymbol
                            .toInt()
                            .takeIf { isString && it in module.types.indices }
                            ?.takeIf {
                                val type = module.types[it]
                                type is NominalType.Class && !type.abstract && type.final && type.fieldCount == 0u
                            }?.let { TypeIdentity(moduleIndex, it) }
                    }
                }
            }
        return matches.singleOrNull()
    }

    if (artifact.entry.module.value
            .toLong() >= artifact.modules.size
    ) {
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
            val owner = module.functions.getOrNull(block.owner.value.toInt())
            if (owner != null) {
                val ownerStart = owner.firstBlock.value.toLong()
                val ownerEnd = ownerStart + owner.blockCount.toLong()
                if (blockIndex.toLong() !in ownerStart until ownerEnd) {
                    add(
                        ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                        "block index is outside its declared owner function range",
                        ArtifactWriteLocation(moduleLocation, "BLOCKS", blockIndex.toUInt()),
                    )
                }
                val allocations =
                    block.instructions.withIndex().filter { (_, instruction) ->
                        instruction is Instruction.NewObject ||
                            instruction is Instruction.NewArray ||
                            instruction is Instruction.StringConcat
                    }
                if (allocations.size > 1 || allocations.singleOrNull()?.index?.let { it != 0 } == true) {
                    add(
                        ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                        "allocation must be the first and only allocating instruction in its block",
                        ArtifactWriteLocation(moduleLocation, "CODE", blockIndex.toUInt()),
                    )
                }
                block.instructions.forEachIndexed { instructionIndex, instruction ->
                    val location =
                        ArtifactWriteLocation(
                            module = moduleLocation,
                            table = "CODE",
                            record = blockIndex.toUInt(),
                            instruction = instructionIndex.toUInt(),
                        )

                    fun register(
                        id: RegisterId,
                        role: String,
                    ): ValueType? =
                        owner.registers.getOrNull(id.value.toInt()).also {
                            if (it ==
                                null
                            ) {
                                add(
                                    ArtifactWriteErrorCode.BAD_REFERENCE,
                                    "$role register is outside the owning function table",
                                    location,
                                )
                            }
                        }

                    fun destination(destination: Destination): ValueType? =
                        when (destination) {
                            is Destination.Register -> register(destination.id, "destination")
                            Destination.Unit -> null
                        }

                    fun successor(
                        blockId: BlockId,
                        role: String,
                    ) {
                        val first = owner.firstBlock.value.toLong()
                        val end = first + owner.blockCount.toLong()
                        if (blockId.value.toLong() !in first until end) {
                            add(ArtifactWriteErrorCode.BAD_REFERENCE, "$role block is outside the owning function range", location)
                        }
                    }

                    fun call(
                        targetReference: FunctionRef,
                        callDestination: Destination,
                        arguments: List<RegisterId>,
                        suspending: Boolean,
                    ) {
                        val argumentTypes = arguments.map { register(it, "argument") }
                        val identity = resolveFunction(moduleIndex, targetReference)
                        if (identity == null) {
                            val kind = if (targetReference is FunctionRef.Local) "local" else "imported"
                            add(ArtifactWriteErrorCode.BAD_REFERENCE, "$kind function reference does not resolve", location)
                            destination(callDestination)
                            return
                        }
                        val targetModule = artifact.modules[identity.module]
                        val target = targetModule.functions[identity.function]
                        if (!suspending && FunctionFlag.ABSTRACT in target.flags) {
                            add(ArtifactWriteErrorCode.INVALID_RANGE, "direct call targets an abstract function", location)
                        }
                        if (suspending != (FunctionFlag.SUSPENDING in target.flags)) {
                            val detail =
                                if (suspending) {
                                    "suspending call targets a non-suspending function"
                                } else {
                                    "direct call targets a suspending function"
                                }
                            add(
                                ArtifactWriteErrorCode.INVALID_RANGE,
                                detail,
                                location,
                            )
                        }
                        val signatureIdentity = resolveType(identity.module, target.signature)
                        val signature = signatureIdentity?.let { artifact.modules[it.module].types[it.type] as? NominalType.Function }
                        if (signature == null) {
                            add(ArtifactWriteErrorCode.BAD_REFERENCE, "call target signature is not a function type", location)
                            destination(callDestination)
                            return
                        }
                        val metadataIsConsistent =
                            target.parameterCount.toInt() == signature.parameters.size &&
                                target.registers.size >= signature.parameters.size &&
                                (FunctionFlag.SUSPENDING in target.flags) == signature.suspending &&
                                target.registers
                                    .take(signature.parameters.size)
                                    .zip(signature.parameters)
                                    .all { (register, parameter) ->
                                        valueTypesMatch(identity.module, register, requireNotNull(signatureIdentity).module, parameter)
                                    }
                        if (!metadataIsConsistent) {
                            add(
                                ArtifactWriteErrorCode.INVALID_RANGE,
                                "target function metadata disagrees with its signature",
                                location,
                            )
                        }
                        if (arguments.size != signature.parameters.size) {
                            add(ArtifactWriteErrorCode.INVALID_RANGE, "call arity disagrees with its signature", location)
                        } else {
                            argumentTypes.zip(signature.parameters).forEach { (actual, expected) ->
                                if (actual != null &&
                                    !valueAssignable(moduleIndex, actual, requireNotNull(signatureIdentity).module, expected)
                                ) {
                                    add(ArtifactWriteErrorCode.INVALID_RANGE, "call argument type disagrees with its signature", location)
                                }
                            }
                        }
                        val actualDestination = destination(callDestination)
                        val destinationIsValid =
                            when (signature.result) {
                                ValueType.Unit -> {
                                    callDestination == Destination.Unit
                                }

                                else -> {
                                    actualDestination != null &&
                                        valueAssignable(
                                            requireNotNull(signatureIdentity).module,
                                            signature.result,
                                            moduleIndex,
                                            actualDestination,
                                        )
                                }
                            }
                        if (!destinationIsValid) {
                            add(ArtifactWriteErrorCode.INVALID_RANGE, "call result destination disagrees with its signature", location)
                        }
                    }

                    if (
                        instruction.isSuspendingTerminator() &&
                        FunctionFlag.SUSPENDING !in owner.flags
                    ) {
                        add(
                            ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                            "suspending terminator appears in a non-suspending function",
                            location,
                        )
                    }

                    when (instruction) {
                        is Instruction.Call -> {
                            call(instruction.function, instruction.destination, instruction.arguments, suspending = false)
                        }

                        is Instruction.CallSuspend -> {
                            call(instruction.function, instruction.destination, instruction.arguments, suspending = true)
                            successor(instruction.resumeBlock, "resume")
                        }

                        is Instruction.StringConcat -> {
                            val expected = stringIdentity()
                            listOf(
                                register(instruction.destination, "destination"),
                                register(instruction.left, "source"),
                                register(instruction.right, "source"),
                            ).forEach { actual ->
                                val actualIdentity =
                                    (actual as? ValueType.Ref)?.takeIf { !it.nullable }?.let {
                                        resolveType(
                                            moduleIndex,
                                            it.type,
                                        )
                                    }
                                if (actual != null && (expected == null || actualIdentity != expected)) {
                                    add(
                                        ArtifactWriteErrorCode.INVALID_RANGE,
                                        "string concat register is not the non-null kotlin.String type",
                                        location,
                                    )
                                }
                            }
                        }

                        is Instruction.CapabilityCallAsync -> {
                            destination(instruction.destination)
                            instruction.arguments.forEach { register(it, "argument") }
                            val capability = artifact.capabilities.getOrNull(instruction.capability.value.toInt())
                            if (capability == null) {
                                add(ArtifactWriteErrorCode.BAD_REFERENCE, "capability id is outside the capability table", location)
                            } else if (instruction.operation >= capability.operationCount) {
                                add(ArtifactWriteErrorCode.BAD_REFERENCE, "capability operation is outside the descriptor range", location)
                            }
                            successor(instruction.resumeBlock, "resume")
                        }

                        is Instruction.Jump -> {
                            successor(instruction.target, "successor")
                        }

                        is Instruction.Branch -> {
                            val condition = register(instruction.condition, "condition")
                            if (condition != null && condition != ValueType.Bool) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "branch condition register is not Bool", location)
                            }
                            successor(instruction.trueTarget, "successor")
                            successor(instruction.falseTarget, "successor")
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    return errors
}

private fun Instruction.isTerminator(): Boolean =
    this is Instruction.Jump ||
        this is Instruction.Branch ||
        this is Instruction.Return ||
        this is Instruction.Throw ||
        this is Instruction.CallSuspend ||
        this is Instruction.CapabilityCallAsync

private fun Instruction.isSuspendingTerminator(): Boolean = this is Instruction.CallSuspend || this is Instruction.CapabilityCallAsync
