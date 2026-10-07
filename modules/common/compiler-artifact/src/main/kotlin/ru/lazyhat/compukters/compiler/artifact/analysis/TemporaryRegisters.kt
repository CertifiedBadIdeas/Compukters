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

package ru.lazyhat.compukters.compiler.artifact.analysis

import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionValue
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId

/** Reuses exact-type temporary registers; the runtime's ordinary layout remains authoritative. */
object TemporaryRegisters {
    fun compact(module: Module): Module {
        val blocks = module.blocks.toMutableList()
        val exceptions = module.exceptions.toMutableList()
        val functions =
            module.functions.map { function ->
                if (FunctionFlag.ABSTRACT in function.flags || function.values.isEmpty()) return@map function
                val boundaries = ValueLiveness.derive(module, function)
                val interference = List(function.values.size) { mutableSetOf<Int>() }
                val pinned = (0 until function.parameterCount.toInt()).toMutableSet()
                val exceptionRange = function.firstException.toInt() until (function.firstException + function.exceptionCount).toInt()
                exceptionRange.forEach {
                    pinned +=
                        module.exceptions[it]
                            .exceptionRegister.value
                            .toInt()
                }
                val reachable = boundaries.map { it.block.value.toInt() to it.instruction.toInt() }.toSet()
                val blockRange = function.firstBlock.value.toInt() until (function.firstBlock.value + function.blockCount).toInt()
                // Preserve dedicated storage for operands in unreachable code too.
                blockRange.forEach { block ->
                    module.blocks[block].instructions.forEachIndexed { index, instruction ->
                        if (block to index !in reachable) {
                            pinned += (instruction.readRegisters() + instruction.writtenRegisters()).map { it.value.toInt() }
                        }
                    }
                }
                boundaries.forEach { boundary ->
                    val instruction = module.blocks[boundary.block.value.toInt()].instructions[boundary.instruction.toInt()]
                    // Keep inputs and results distinct even when inputs die at this instruction: calls, GC and
                    // suspension may retain the pre-instruction root map while materializing the destination.
                    val simultaneous = boundary.before + boundary.after + instruction.writtenRegisters()
                    simultaneous
                        .filter { it.value.toInt() !in pinned }
                        .groupBy { function.values[it.value.toInt()] }
                        .values
                        .forEach { group ->
                            group.forEachIndexed { index, left ->
                                for (right in group.drop(index + 1)) {
                                    interference[left.value.toInt()] += right.value.toInt()
                                    interference[right.value.toInt()] += left.value.toInt()
                                }
                            }
                        }
                }
                val mapping = IntArray(function.values.size)
                val values = mutableListOf<FunctionValue>()
                val members = mutableListOf<MutableSet<Int>>()
                val candidates = mutableMapOf<FunctionValue, MutableList<Int>>()
                function.values.forEachIndexed { old, value ->
                    val slot =
                        if (old in pinned) {
                            null
                        } else {
                            candidates[value]?.firstOrNull { candidate ->
                                members[candidate].none { it in interference[old] }
                            }
                        }
                    if (slot != null) {
                        mapping[old] = slot
                        members[slot] += old
                    } else {
                        mapping[old] = values.size
                        if (old !in pinned) candidates.getOrPut(value, ::mutableListOf) += values.size
                        values += value
                        members += mutableSetOf(old)
                    }
                }
                val remap: (RegisterId) -> RegisterId = { RegisterId.of(mapping[it.value.toInt()].toUInt()) }
                blockRange.forEach { block ->
                    blocks[block] =
                        module.blocks[block].copy(instructions = module.blocks[block].instructions.map { it.remapRegisters(remap) })
                }
                exceptionRange.forEach { index ->
                    exceptions[index] = module.exceptions[index].copy(exceptionRegister = remap(module.exceptions[index].exceptionRegister))
                }
                function.copy(values = values, safepointRoots = emptyList())
            }
        return ReferenceLiveness.derive(module.copy(functions = functions, blocks = blocks, exceptions = exceptions))
    }
}

private fun Destination.remap(register: (RegisterId) -> RegisterId): Destination =
    when (this) {
        Destination.Unit -> this
        is Destination.Register -> Destination.Register(register(id))
    }

private fun Instruction.remapRegisters(register: (RegisterId) -> RegisterId): Instruction =
    when (this) {
        is Instruction.InlineConstruct -> {
            copy(destination = register(destination), components = components.map(register))
        }

        is Instruction.InlineComponent -> {
            copy(destination = register(destination), source = register(source))
        }

        is Instruction.Move -> {
            copy(destination = register(destination), source = register(source))
        }

        is Instruction.Const -> {
            copy(destination = register(destination))
        }

        is Instruction.Null -> {
            copy(destination = register(destination))
        }

        is Instruction.Convert -> {
            copy(destination = register(destination), source = register(source))
        }

        is Instruction.Add -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.Subtract -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.Multiply -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.Divide -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.Remainder -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.BitAnd -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.BitOr -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.BitXor -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.ShiftLeft -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.ShiftRight -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.ShiftUnsigned -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.Equal -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.RefEqual -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.RefNotEqual -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.Less -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.LessOrEqual -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.Greater -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.GreaterOrEqual -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.NewObject -> {
            copy(destination = register(destination))
        }

        is Instruction.NewArray -> {
            copy(destination = register(destination), length = register(length))
        }

        is Instruction.ArrayLength -> {
            copy(destination = register(destination), array = register(array))
        }

        is Instruction.ArrayLoad -> {
            copy(destination = register(destination), array = register(array), index = register(index))
        }

        is Instruction.ArrayStore -> {
            copy(array = register(array), index = register(index), value = register(value))
        }

        is Instruction.ArrayCopy -> {
            copy(
                source = register(source),
                destination = register(destination),
                sourceStart = register(sourceStart),
                destinationStart = register(destinationStart),
                length = register(length),
            )
        }

        is Instruction.FieldGet -> {
            copy(destination = register(destination), receiver = register(receiver))
        }

        is Instruction.FieldSet -> {
            copy(receiver = register(receiver), value = register(value))
        }

        is Instruction.StaticGet -> {
            copy(destination = register(destination))
        }

        is Instruction.StaticSet -> {
            copy(value = register(value))
        }

        is Instruction.IsType -> {
            copy(destination = register(destination), value = register(value))
        }

        is Instruction.CheckedCast -> {
            copy(destination = register(destination), value = register(value))
        }

        is Instruction.Call -> {
            Instruction.Call(destination.remap(register), function, arguments.map(register))
        }

        is Instruction.CallVirtual -> {
            Instruction.CallVirtual(destination.remap(register), function, arguments.map(register))
        }

        is Instruction.CallInterface -> {
            Instruction.CallInterface(destination.remap(register), function, arguments.map(register))
        }

        is Instruction.CallSuspend -> {
            Instruction.CallSuspend(destination.remap(register), function, arguments.map(register), resumeBlock)
        }

        is Instruction.StringConcat -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.ValueHash -> {
            copy(destination = register(destination), source = register(source))
        }

        is Instruction.StringHash -> {
            copy(destination = register(destination), string = register(string))
        }

        is Instruction.StringValueOf -> {
            copy(destination = register(destination), source = register(source))
        }

        is Instruction.StringLength -> {
            copy(destination = register(destination), string = register(string))
        }

        is Instruction.StringGet -> {
            copy(destination = register(destination), string = register(string), index = register(index))
        }

        is Instruction.StringEquals -> {
            copy(destination = register(destination), left = register(left), right = register(right))
        }

        is Instruction.StringSubstring -> {
            copy(
                destination = register(destination),
                string = register(string),
                start = register(start),
                end = register(end),
            )
        }

        is Instruction.StringFromCharArray -> {
            copy(
                destination = register(destination),
                array = register(array),
                start = register(start),
                end = register(end),
            )
        }

        is Instruction.CapabilityCallSync -> {
            Instruction.CapabilityCallSync(
                destination.remap(register),
                capability,
                operation,
                arguments.map(register),
            )
        }

        is Instruction.TaskSpawn -> {
            Instruction.TaskSpawn(register(destination), function, arguments.map(register))
        }

        is Instruction.TaskJoin -> {
            copy(destination = destination.remap(register), task = register(task))
        }

        is Instruction.ChannelCreate -> {
            copy(destination = register(destination), capacity = register(capacity))
        }

        is Instruction.ChannelSend -> {
            copy(channel = register(channel), value = register(value))
        }

        is Instruction.ChannelReceive -> {
            copy(destination = register(destination), channel = register(channel))
        }

        is Instruction.CapabilityCallAsync -> {
            Instruction.CapabilityCallAsync(
                destination.remap(register),
                capability,
                operation,
                arguments.map(register),
                resumeBlock,
            )
        }

        is Instruction.Jump -> {
            this
        }

        is Instruction.Branch -> {
            copy(condition = register(condition))
        }

        is Instruction.Return -> {
            copy(value = value.remap(register))
        }

        is Instruction.Throw -> {
            copy(exception = register(exception))
        }

        Instruction.Unreachable -> {
            this
        }
    }
