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

import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.RuntimeExceptionKind
import ru.lazyhat.compukters.compiler.artifact.model.ScalarValueType
import java.util.BitSet

internal data class LiveBoundary(
    val block: BlockId,
    val instruction: UInt,
    val before: Set<RegisterId>,
    val after: Set<RegisterId>,
)

/** Shared control-flow analysis for GC roots and typed temporary allocation. */
internal object ValueLiveness {
    fun derive(
        module: Module,
        function: Function,
    ): List<LiveBoundary> {
        val firstBlock = function.firstBlock.value.toInt()
        val blockCount = function.blockCount.toInt()
        require(firstBlock >= 0 && blockCount >= 0 && firstBlock.toLong() + blockCount <= module.blocks.size) {
            "function block range is invalid"
        }
        val exceptionStart = function.firstException.toLong()
        val exceptionEnd = exceptionStart + function.exceptionCount.toLong()
        require(exceptionStart <= module.exceptions.size && exceptionEnd <= module.exceptions.size) {
            "function exception range is invalid"
        }
        val blockRange = firstBlock until firstBlock + blockCount
        val exceptions = module.exceptions.subList(exceptionStart.toInt(), exceptionEnd.toInt())
        val nodes =
            blockRange.flatMap { blockIndex ->
                module.blocks[blockIndex]
                    .instructions.indices
                    .map { instructionIndex -> Node(blockIndex, instructionIndex) }
            }
        if (nodes.isEmpty()) return emptyList()
        // Dense positions are local to this analysis, independent of physical register IDs or values.
        // This also preserves analysis of sparse IDs before artifact validation diagnoses invalid models.
        val registers = mutableListOf<RegisterId>()
        val registerIndices = mutableMapOf<RegisterId, Int>()

        fun registerIndex(register: RegisterId): Int = registerIndices.getOrPut(register) { registers.size.also { registers += register } }

        val blockOffsets = IntArray(blockCount + 1)
        for (index in 0 until blockCount) {
            blockOffsets[index + 1] = blockOffsets[index] + module.blocks[firstBlock + index].instructions.size
        }

        fun nodeIndex(node: Node): Int? {
            val block = node.block - firstBlock
            if (block !in 0 until blockCount || node.instruction !in module.blocks[node.block].instructions.indices) return null
            return blockOffsets[block] + node.instruction
        }

        val reads =
            Array(nodes.size) { index ->
                BitSet().apply {
                    val node = nodes[index]
                    module.blocks[node.block]
                        .instructions[node.instruction]
                        .readRegisters()
                        .forEach { set(registerIndex(it)) }
                }
            }
        val writes =
            Array(nodes.size) { index ->
                val node = nodes[index]
                module.blocks[node.block]
                    .instructions[node.instruction]
                    .writtenRegisters()
                    .map(::registerIndex)
                    .toIntArray()
            }
        val successors =
            Array(nodes.size) { index ->
                successorEdges(module, nodes[index], exceptions).mapNotNull { edge ->
                    nodeIndex(edge.target)?.let { target -> IndexedEdge(target, edge.definition?.let(::registerIndex) ?: -1) }
                }
            }
        val predecessorCounts = IntArray(nodes.size)
        successors.forEach { edges -> edges.forEach { predecessorCounts[it.target]++ } }
        val predecessors = Array(nodes.size) { IntArray(predecessorCounts[it]) }
        successors.forEachIndexed { source, edges ->
            edges.forEach { edge -> predecessors[edge.target][--predecessorCounts[edge.target]] = source }
        }

        val entry = nodeIndex(Node(firstBlock, 0)) ?: return emptyList()
        val reachable = BooleanArray(nodes.size)
        val reachableOrder = mutableListOf<Int>()
        val queue = NodeQueue(nodes.size)
        reachable[entry] = true
        queue.add(entry)
        while (!queue.isEmpty) {
            val node = queue.remove()
            reachableOrder += node
            successors[node].forEach { edge ->
                if (!reachable[edge.target]) {
                    reachable[edge.target] = true
                    queue.add(edge.target)
                }
            }
        }

        val liveBefore = Array(nodes.size) { BitSet() }
        val queued = BooleanArray(nodes.size)
        reachableOrder.asReversed().forEach { node ->
            queue.add(node)
            queued[node] = true
        }
        val liveOut = BitSet()
        val updated = BitSet()

        fun collectLiveOut(node: Int) {
            liveOut.clear()
            successors[node].forEach { edge ->
                // An exception register is defined only on this edge. Do not remove its liveness
                // contributed by a normal edge or another handler when merging successors.
                val alreadyLive = edge.definition >= 0 && liveOut[edge.definition]
                liveOut.or(liveBefore[edge.target])
                if (edge.definition >= 0 && !alreadyLive) liveOut.clear(edge.definition)
            }
        }
        while (!queue.isEmpty) {
            val node = queue.remove()
            queued[node] = false
            collectLiveOut(node)
            updated.clear()
            updated.or(liveOut)
            writes[node].forEach(updated::clear)
            updated.or(reads[node])
            val current = liveBefore[node]
            if (current != updated) {
                current.clear()
                current.or(updated)
                predecessors[node].forEach { predecessor ->
                    if (reachable[predecessor] && !queued[predecessor]) {
                        queued[predecessor] = true
                        queue.add(predecessor)
                    }
                }
            }
        }

        fun snapshot(bits: BitSet): Set<RegisterId> =
            buildSet {
                var index = bits.nextSetBit(0)
                while (index >= 0) {
                    add(registers[index])
                    index = bits.nextSetBit(index + 1)
                }
            }
        return nodes.mapIndexedNotNull { index, node ->
            if (!reachable[index]) return@mapIndexedNotNull null
            collectLiveOut(index)
            LiveBoundary(
                block = BlockId.of(node.block.toUInt()),
                instruction = node.instruction.toUInt(),
                before = snapshot(liveBefore[index]),
                after = snapshot(liveOut),
            )
        }
    }
}

private data class Node(
    val block: Int,
    val instruction: Int,
)

private data class Edge(
    val target: Node,
    val definition: RegisterId? = null,
)

private data class IndexedEdge(
    val target: Int,
    val definition: Int,
)

/** Reachability and propagation enqueue each node at most once at a time. */
private class NodeQueue(
    capacity: Int,
) {
    private val values = IntArray(capacity)
    private var head = 0
    private var tail = 0
    private var size = 0

    val isEmpty: Boolean get() = size == 0

    fun add(node: Int) {
        check(size < values.size) { "liveness queue capacity exceeded" }
        values[tail] = node
        tail++
        if (tail == values.size) tail = 0
        size++
    }

    fun remove(): Int {
        check(size > 0) { "liveness queue is empty" }
        val node = values[head]
        head++
        if (head == values.size) head = 0
        size--
        return node
    }
}

private fun successorEdges(
    module: Module,
    node: Node,
    exceptions: List<ru.lazyhat.compukters.compiler.artifact.model.ExceptionEntry>,
): List<Edge> {
    val instructions = module.blocks[node.block].instructions
    val instruction = instructions[node.instruction]
    val normal =
        if (node.instruction + 1 < instructions.size) {
            listOf(Edge(Node(node.block, node.instruction + 1)))
        } else {
            instruction.successors().mapNotNull { successor ->
                module.blocks
                    .getOrNull(successor.value.toInt())
                    ?.instructions
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Edge(Node(successor.value.toInt(), 0)) }
            }
        }
    if (!instruction.mayThrow()) return normal
    val exceptional =
        exceptions.mapNotNull { exception ->
            val protectedStart = exception.firstProtectedBlock.value.toLong()
            val protectedEnd = protectedStart + exception.protectedBlockCount.toLong()
            if (node.block.toLong() !in protectedStart until protectedEnd) return@mapNotNull null
            module.blocks
                .getOrNull(exception.handlerBlock.value.toInt())
                ?.instructions
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    Edge(
                        target = Node(exception.handlerBlock.value.toInt(), 0),
                        definition = exception.exceptionRegister,
                    )
                }
        }
    return normal + exceptional
}

internal fun Instruction.readRegisters(): List<RegisterId> =
    when (this) {
        is Instruction.InlineConstruct -> components

        is Instruction.InlineComponent -> listOf(source)

        is Instruction.Const,
        is Instruction.Null,
        is Instruction.NewObject,
        is Instruction.Jump,
        Instruction.Unreachable,
        -> emptyList()

        is Instruction.Move -> listOf(source)

        is Instruction.Convert -> listOf(source)

        is Instruction.MathUnary -> listOf(source)

        is Instruction.MathBinary -> listOf(left, right)

        is Instruction.Add -> listOf(left, right)

        is Instruction.Subtract -> listOf(left, right)

        is Instruction.Multiply -> listOf(left, right)

        is Instruction.Divide -> listOf(left, right)

        is Instruction.Remainder -> listOf(left, right)

        is Instruction.BitAnd -> listOf(left, right)

        is Instruction.BitOr -> listOf(left, right)

        is Instruction.BitXor -> listOf(left, right)

        is Instruction.ShiftLeft -> listOf(left, right)

        is Instruction.ShiftRight -> listOf(left, right)

        is Instruction.ShiftUnsigned -> listOf(left, right)

        is Instruction.Equal -> listOf(left, right)

        is Instruction.RefEqual -> listOf(left, right)

        is Instruction.RefNotEqual -> listOf(left, right)

        is Instruction.Less -> listOf(left, right)

        is Instruction.LessOrEqual -> listOf(left, right)

        is Instruction.Greater -> listOf(left, right)

        is Instruction.GreaterOrEqual -> listOf(left, right)

        is Instruction.NewArray -> listOf(length)

        is Instruction.ArrayLength -> listOf(array)

        is Instruction.ArrayLoad -> listOf(array, index)

        is Instruction.ArrayStore -> listOf(array, index, value)

        is Instruction.ArrayCopy -> listOf(source, destination, sourceStart, destinationStart, length)

        is Instruction.FieldGet -> listOf(receiver)

        is Instruction.FieldSet -> listOf(receiver, value)

        is Instruction.StaticGet -> emptyList()

        is Instruction.StaticSet -> listOf(value)

        is Instruction.IsType -> listOf(value)

        is Instruction.CheckedCast -> listOf(value)

        is Instruction.Call -> arguments

        is Instruction.CallVirtual -> arguments

        is Instruction.CallInterface -> arguments

        is Instruction.CallSuspend -> arguments

        is Instruction.TaskSpawn -> arguments

        is Instruction.TaskJoin -> listOf(task)

        is Instruction.ChannelCreate -> listOf(capacity)

        is Instruction.ChannelSend -> listOf(channel, value)

        is Instruction.ChannelReceive -> listOf(channel)

        is Instruction.StringConcat -> listOf(left, right)

        is Instruction.ValueHash -> listOf(source)

        is Instruction.StringHash -> listOf(string)

        is Instruction.StringValueOf -> listOf(source)

        is Instruction.StringLength -> listOf(string)

        is Instruction.StringGet -> listOf(string, index)

        is Instruction.StringEquals -> listOf(left, right)

        is Instruction.StringSubstring -> listOf(string, start, end)

        is Instruction.StringFromCharArray -> listOf(array, start, end)

        is Instruction.CapabilityCallSync -> arguments

        is Instruction.CapabilityCallAsync -> arguments

        is Instruction.Branch -> listOf(condition)

        is Instruction.Return -> (value as? Destination.Register)?.let { listOf(it.id) }.orEmpty()

        is Instruction.Throw -> listOf(exception)
    }

internal fun Instruction.writtenRegisters(): List<RegisterId> =
    when (this) {
        is Instruction.InlineConstruct -> listOf(destination)

        is Instruction.InlineComponent -> listOf(destination)

        is Instruction.Move -> listOf(destination)

        is Instruction.Const -> listOf(destination)

        is Instruction.Null -> listOf(destination)

        is Instruction.Convert -> listOf(destination)

        is Instruction.MathUnary -> listOf(destination)

        is Instruction.MathBinary -> listOf(destination)

        is Instruction.Add -> listOf(destination)

        is Instruction.Subtract -> listOf(destination)

        is Instruction.Multiply -> listOf(destination)

        is Instruction.Divide -> listOf(destination)

        is Instruction.Remainder -> listOf(destination)

        is Instruction.BitAnd -> listOf(destination)

        is Instruction.BitOr -> listOf(destination)

        is Instruction.BitXor -> listOf(destination)

        is Instruction.ShiftLeft -> listOf(destination)

        is Instruction.ShiftRight -> listOf(destination)

        is Instruction.ShiftUnsigned -> listOf(destination)

        is Instruction.Equal -> listOf(destination)

        is Instruction.RefEqual -> listOf(destination)

        is Instruction.RefNotEqual -> listOf(destination)

        is Instruction.Less -> listOf(destination)

        is Instruction.LessOrEqual -> listOf(destination)

        is Instruction.Greater -> listOf(destination)

        is Instruction.GreaterOrEqual -> listOf(destination)

        is Instruction.NewObject -> listOf(destination)

        is Instruction.NewArray -> listOf(destination)

        is Instruction.ArrayLength -> listOf(destination)

        is Instruction.ArrayLoad -> listOf(destination)

        is Instruction.FieldGet -> listOf(destination)

        is Instruction.StaticGet -> listOf(destination)

        is Instruction.IsType -> listOf(destination)

        is Instruction.CheckedCast -> listOf(destination)

        is Instruction.Call -> (destination as? Destination.Register)?.let { listOf(it.id) }.orEmpty()

        is Instruction.CallVirtual -> (destination as? Destination.Register)?.let { listOf(it.id) }.orEmpty()

        is Instruction.CallInterface -> (destination as? Destination.Register)?.let { listOf(it.id) }.orEmpty()

        is Instruction.CallSuspend -> (destination as? Destination.Register)?.let { listOf(it.id) }.orEmpty()

        is Instruction.TaskSpawn -> listOf(destination)

        is Instruction.TaskJoin -> (destination as? Destination.Register)?.let { listOf(it.id) }.orEmpty()

        is Instruction.ChannelCreate -> listOf(destination)

        is Instruction.ChannelReceive -> listOf(destination)

        is Instruction.StringConcat -> listOf(destination)

        is Instruction.ValueHash -> listOf(destination)

        is Instruction.StringHash -> listOf(destination)

        is Instruction.StringValueOf -> listOf(destination)

        is Instruction.StringLength -> listOf(destination)

        is Instruction.StringGet -> listOf(destination)

        is Instruction.StringEquals -> listOf(destination)

        is Instruction.StringSubstring -> listOf(destination)

        is Instruction.StringFromCharArray -> listOf(destination)

        is Instruction.CapabilityCallSync -> (destination as? Destination.Register)?.let { listOf(it.id) }.orEmpty()

        is Instruction.CapabilityCallAsync -> (destination as? Destination.Register)?.let { listOf(it.id) }.orEmpty()

        is Instruction.ArrayStore,
        is Instruction.ArrayCopy,
        is Instruction.ChannelSend,
        is Instruction.FieldSet,
        is Instruction.StaticSet,
        is Instruction.Jump,
        is Instruction.Branch,
        is Instruction.Return,
        is Instruction.Throw,
        Instruction.Unreachable,
        -> emptyList()
    }

internal fun Instruction.successors(): List<BlockId> =
    when (this) {
        is Instruction.Jump -> listOf(target)
        is Instruction.Branch -> listOf(trueTarget, falseTarget)
        is Instruction.CallSuspend -> listOf(resumeBlock)
        is Instruction.TaskJoin -> listOf(resumeBlock)
        is Instruction.ChannelSend -> listOf(resumeBlock)
        is Instruction.ChannelReceive -> listOf(resumeBlock)
        is Instruction.CapabilityCallAsync -> listOf(resumeBlock)
        else -> emptyList()
    }

/** Implicit managed factory dependencies of the instruction, retained even without an explicit catch. */
fun Instruction.runtimeExceptionKinds(): Set<RuntimeExceptionKind> {
    when (this) {
        is Instruction.CapabilityCallSync, is Instruction.CapabilityCallAsync,
        -> return setOf(RuntimeExceptionKind.ILLEGAL_STATE, RuntimeExceptionKind.IO)

        is Instruction.NewArray -> return setOf(RuntimeExceptionKind.NEGATIVE_ARRAY_SIZE)

        is Instruction.ArrayLoad, is Instruction.ArrayStore, is Instruction.ArrayCopy,
        is Instruction.StringGet, is Instruction.StringSubstring, is Instruction.StringFromCharArray,
        -> return setOf(RuntimeExceptionKind.INDEX_OUT_OF_BOUNDS, RuntimeExceptionKind.NULL_POINTER)

        is Instruction.CheckedCast -> return setOf(RuntimeExceptionKind.CLASS_CAST, RuntimeExceptionKind.NULL_POINTER)

        is Instruction.ArrayLength, is Instruction.FieldGet, is Instruction.FieldSet,
        is Instruction.StaticGet, is Instruction.StaticSet, is Instruction.CallVirtual, is Instruction.CallInterface,
        is Instruction.StringHash, is Instruction.StringLength, is Instruction.StringEquals, is Instruction.StringConcat,
        -> return setOf(RuntimeExceptionKind.NULL_POINTER)

        is Instruction.ChannelCreate, is Instruction.ChannelSend, is Instruction.ChannelReceive,
        -> return setOf(RuntimeExceptionKind.ILLEGAL_ARGUMENT)

        else -> Unit
    }
    val scalar =
        when (this) {
            is Instruction.Divide -> type
            is Instruction.Remainder -> type
            else -> null
        }
    return if (scalar == ScalarValueType.I32 ||
        scalar == ScalarValueType.I64 || scalar == ScalarValueType.U32 || scalar == ScalarValueType.U64
    ) {
        setOf(RuntimeExceptionKind.ARITHMETIC)
    } else {
        emptySet()
    }
}

/** Conservative exceptional-edge contract shared by lowering, verification and liveness. */
fun Instruction.mayThrow(): Boolean =
    this is Instruction.Convert ||
        this is Instruction.Divide ||
        this is Instruction.Remainder ||
        this is Instruction.NewObject ||
        this is Instruction.NewArray ||
        this is Instruction.ArrayLength ||
        this is Instruction.ArrayLoad ||
        this is Instruction.ArrayStore ||
        this is Instruction.ArrayCopy ||
        this is Instruction.FieldGet ||
        this is Instruction.FieldSet ||
        this is Instruction.StaticGet ||
        this is Instruction.StaticSet ||
        this is Instruction.CheckedCast ||
        this is Instruction.Call ||
        this is Instruction.CallVirtual ||
        this is Instruction.CallInterface ||
        this is Instruction.CallSuspend ||
        this is Instruction.TaskSpawn ||
        this is Instruction.TaskJoin ||
        this is Instruction.ChannelCreate ||
        this is Instruction.ChannelSend ||
        this is Instruction.ChannelReceive ||
        this is Instruction.CapabilityCallSync ||
        this is Instruction.CapabilityCallAsync ||
        this is Instruction.StringGet ||
        this is Instruction.StringHash ||
        this is Instruction.StringLength ||
        this is Instruction.StringEquals ||
        this is Instruction.StringConcat ||
        this is Instruction.StringValueOf ||
        this is Instruction.StringSubstring ||
        this is Instruction.StringFromCharArray ||
        this is Instruction.Throw
