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

import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ValueType

/** Conservatively infers the version gate before or after nominal import relocation. */
fun Module.hasHeterogeneousReferenceComparison(): Boolean =
    blocks.any { block ->
        val values = functions[block.owner.value.toInt()].values
        block.instructions.any comparison@{ instruction ->
            val operands =
                when (instruction) {
                    is Instruction.RefEqual -> instruction.left to instruction.right
                    is Instruction.RefNotEqual -> instruction.left to instruction.right
                    else -> return@comparison false
                }
            val left = values[operands.first.value.toInt()].semanticType as? ValueType.Ref
            val right = values[operands.second.value.toInt()].semanticType as? ValueType.Ref
            left != null && right != null && left.type != right.type
        }
    }
