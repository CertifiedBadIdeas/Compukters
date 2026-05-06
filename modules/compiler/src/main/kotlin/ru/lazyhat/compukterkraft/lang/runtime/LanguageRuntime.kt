/*
 * The Compukter Kraft Developers
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

package ru.lazyhat.compukterkraft.lang.runtime

import ru.lazyhat.compukterkraft.lang.api.BinaryOperator
import ru.lazyhat.compukterkraft.lang.api.BytecodeFunction
import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
import ru.lazyhat.compukterkraft.lang.api.Instruction
import ru.lazyhat.compukterkraft.lang.api.UnaryOperator

sealed interface VmValue {
    data object UnitValue : VmValue

    data object NullValue : VmValue

    data class BoolValue(
        val value: Boolean,
    ) : VmValue

    data class IntValue(
        val value: Int,
    ) : VmValue

    data class LongValue(
        val value: Long,
    ) : VmValue

    data class StringValue(
        val value: String,
    ) : VmValue

    data class RecordValue(
        val typeName: String,
        val fields: Map<String, VmValue>,
    ) : VmValue

    data class ObjectRef(
        val id: Int,
    ) : VmValue
}

data class BytecodeVmSnapshot(
    val frames: List<FrameSnapshot>,
    val halted: Boolean,
    val lastResult: VmValue?,
)

data class FrameSnapshot(
    val functionIndex: Int,
    val instructionPointer: Int,
    val locals: List<VmValue>,
    val stack: List<VmValue>,
)

sealed interface VmSignal {
    data object Halt : VmSignal

    data object Pause : VmSignal

    data object Yield : VmSignal

    data class Sleep(
        val ticks: Long,
    ) : VmSignal

    data class WaitEvent(
        val filter: String?,
    ) : VmSignal

    data class HostCall(
        val moduleName: String,
        val functionName: String,
        val arguments: List<VmValue>,
    ) : VmSignal
}

private val VmSignal.kind: VmSignalKind
    get() =
        when (this) {
            VmSignal.Halt -> VmSignalKind.HALT
            VmSignal.Pause -> VmSignalKind.PAUSE
            VmSignal.Yield -> VmSignalKind.YIELD
            is VmSignal.Sleep -> VmSignalKind.SLEEP
            is VmSignal.WaitEvent -> VmSignalKind.WAIT_EVENT
            is VmSignal.HostCall -> VmSignalKind.HOST_CALL
        }

class BytecodeComputerProgram(
    private val module: BytecodeModule,
) : DeviceProgram {
    override suspend fun run(runtime: DeviceRuntime) {
        val bridge = RuntimeHostBridge(runtime)
        val vm =
            BytecodeVirtualMachine(
                module,
                instructionBudgetPerSlice = runtime.profile.resources.cpu.instructionsPerSlice,
                maxVmRamBytes = runtime.profile.resources.memory.vmRamBytes,
            )
        while (true) {
            val signal = vm.runUntilSignal()
            runtime.metrics.recordVmSignal(signal.kind)
            when (signal) {
                VmSignal.Halt -> {
                    return
                }

                VmSignal.Pause -> {
                    runtime.yield()
                }

                VmSignal.Yield -> {
                    runtime.yield()
                    vm.resumeWith(VmValue.UnitValue)
                }

                is VmSignal.HostCall -> {
                    vm.resumeWith(bridge.invoke(signal.moduleName, signal.functionName, signal.arguments))
                }

                is VmSignal.Sleep -> {
                    runtime.sleep(signal.ticks)
                    vm.resumeWith(VmValue.UnitValue)
                }

                is VmSignal.WaitEvent -> {
                    vm.resumeWith(bridge.fromEvent(runtime.pullEvent(signal.filter)))
                }
            }
        }
    }
}

class BytecodeVirtualMachine(
    private val module: BytecodeModule,
    snapshot: BytecodeVmSnapshot? = null,
    instructionBudgetPerSlice: Int = DEFAULT_INSTRUCTION_BUDGET,
    private val maxVmRamBytes: Long = Long.MAX_VALUE,
) {
    private val frames = ArrayDeque<FrameState>()
    private var lastResult: VmValue? = snapshot?.lastResult
    private var halted: Boolean = snapshot?.halted ?: false
    private var instructionsSinceYield = 0
    private val instructionBudgetPerSlice = instructionBudgetPerSlice.coerceAtLeast(1)
    private val heap = mutableMapOf<Int, VmObject>()
    private var nextObjectId = 1

    init {
        if (snapshot != null) {
            snapshot.frames.forEach { frame ->
                frames +=
                    FrameState(
                        functionIndex = frame.functionIndex,
                        instructionPointer = frame.instructionPointer,
                        locals = frame.locals.toMutableList(),
                        stack = frame.stack.toMutableList(),
                    )
            }
        } else {
            frames += createFrame(module.entryFunctionIndex, emptyList())
        }
    }

    fun snapshot(): BytecodeVmSnapshot =
        BytecodeVmSnapshot(
            frames =
                frames.map { frame ->
                    FrameSnapshot(
                        functionIndex = frame.functionIndex,
                        instructionPointer = frame.instructionPointer,
                        locals = frame.locals.toList(),
                        stack = frame.stack.toList(),
                    )
                },
            halted = halted,
            lastResult = lastResult,
        )

    fun resumeWith(value: VmValue) {
        frames.lastOrNull()?.stack?.add(value)
    }

    fun runUntilSignal(): VmSignal {
        while (!halted) {
            val frame = frames.lastOrNull() ?: return VmSignal.Halt.also { halted = true }
            val instruction =
                currentFunction(frame).instructions.getOrNull(frame.instructionPointer)
                    ?: return handleReturn(frame.popOrDefault())
            frame.instructionPointer += 1
            instructionsSinceYield += 1
            when (instruction) {
                is Instruction.Binary -> {
                    applyBinary(frame, instruction.operator)
                }

                is Instruction.CallBuiltin -> {
                    val args = frame.popMany(instruction.argumentCount)
                    return when (instruction.moduleName) {
                        null -> {
                            when (instruction.functionName) {
                                "yield" -> VmSignal.Yield
                                "sleep" -> VmSignal.Sleep(args.single().asLong())
                                else -> error("Unknown global builtin ${instruction.functionName}")
                            }
                        }

                        "events" -> {
                            if (instruction.functionName == "pull") {
                                VmSignal.WaitEvent(args.singleOrNull()?.asString())
                            } else {
                                VmSignal.HostCall(instruction.moduleName, instruction.functionName, args)
                            }
                        }

                        else -> {
                            VmSignal.HostCall(instruction.moduleName, instruction.functionName, args)
                        }
                    }
                }

                is Instruction.CallFunction -> {
                    val args = frame.popMany(instruction.argumentCount)
                    frames += createFrame(instruction.functionIndex, args)
                }

                is Instruction.CallMethod -> {
                    val args = frame.popMany(instruction.argumentCount)
                    val receiver = frame.pop()
                    val objectRef = receiver as? VmValue.ObjectRef ?: error("Method receiver is not an object.")
                    val objectState = heap[objectRef.id] as? VmClassObject ?: error("Object #${objectRef.id} is not a class instance.")
                    val classInfo =
                        module.classes.firstOrNull { it.name == objectState.className }
                            ?: error("Class ${objectState.className} is missing.")
                    val functionIndex =
                        classInfo.instanceMethods[instruction.methodName]
                            ?: error("Class ${objectState.className} has no method ${instruction.methodName}.")
                    frames += createFrame(functionIndex, listOf(receiver) + args)
                }

                is Instruction.CallStaticMethod -> {
                    val args = frame.popMany(instruction.argumentCount)
                    val classInfo =
                        module.classes.firstOrNull { it.name == instruction.className }
                            ?: error("Class ${instruction.className} is missing.")
                    val functionIndex =
                        classInfo.staticMethods[instruction.methodName]
                            ?: error("Class ${instruction.className} has no static method ${instruction.methodName}.")
                    frames += createFrame(functionIndex, args)
                }

                is Instruction.ConstructRecord -> {
                    val values = frame.popMany(instruction.fieldNames.size)
                    frame.stack += VmValue.RecordValue(instruction.typeName, instruction.fieldNames.zip(values).toMap())
                }

                is Instruction.ConstructClass -> {
                    val values = frame.popMany(instruction.fieldNames.size)
                    val id = nextObjectId++
                    heap[id] =
                        VmClassObject(
                            instruction.className,
                            instruction.fieldNames
                                .zip(values)
                                .toMap()
                                .toMutableMap(),
                        )
                    frame.stack += VmValue.ObjectRef(id)
                }

                Instruction.ConstructArray -> {
                    val default = frame.pop()
                    val size = frame.pop().asInt()
                    check(size >= 0) { "Array size must be non-negative." }
                    frame.stack += allocate(VmArrayObject(MutableList(size) { default }))
                }

                is Instruction.ConstructList -> {
                    val values = frame.popMany(instruction.elementCount)
                    frame.stack += allocate(VmListObject(values.toMutableList()))
                }

                is Instruction.ConstructMap -> {
                    val values = frame.popMany(instruction.entryCount * 2)
                    val entries = LinkedHashMap<VmMapKey, VmValue>()
                    values.chunked(2).forEach { (key, value) ->
                        entries[mapKey(key)] = value
                    }
                    frame.stack += allocate(VmMapObject(entries))
                }

                is Instruction.GetField -> {
                    val receiver = frame.pop()
                    val value =
                        when (receiver) {
                            is VmValue.RecordValue -> receiver.fields[instruction.fieldName]
                            is VmValue.ObjectRef -> (heap[receiver.id] as? VmClassObject)?.fields?.get(instruction.fieldName)
                            else -> null
                        } ?: error("Field ${instruction.fieldName} is missing.")
                    frame.stack += value
                }

                is Instruction.SetField -> {
                    val value = frame.pop()
                    val receiver = frame.pop()
                    val objectRef = receiver as? VmValue.ObjectRef ?: error("Field assignment receiver is not an object.")
                    val objectState = heap[objectRef.id] as? VmClassObject ?: error("Object #${objectRef.id} is not a class instance.")
                    objectState.fields[instruction.fieldName] = value
                    frame.stack += VmValue.UnitValue
                }

                Instruction.IndexGet -> {
                    applyIndexGet(frame)
                }

                Instruction.IndexSet -> {
                    applyIndexSet(frame)
                }

                is Instruction.CallCollectionMethod -> {
                    applyCollectionMethod(frame, instruction.methodName, instruction.argumentCount)
                }

                is Instruction.Jump -> {
                    frame.instructionPointer = instruction.target
                }

                is Instruction.JumpIfFalse -> {
                    if (!frame.pop().asBoolean()) frame.instructionPointer = instruction.target
                }

                is Instruction.JumpIfTrue -> {
                    if (frame.pop().asBoolean()) frame.instructionPointer = instruction.target
                }

                is Instruction.LoadLocal -> {
                    frame.stack += frame.locals[instruction.slot]
                }

                Instruction.Pop -> {
                    frame.pop()
                }

                is Instruction.PushBool -> {
                    frame.stack += VmValue.BoolValue(instruction.value)
                }

                is Instruction.PushInt -> {
                    frame.stack += VmValue.IntValue(instruction.value)
                }

                is Instruction.PushLong -> {
                    frame.stack += VmValue.LongValue(instruction.value)
                }

                Instruction.PushNull -> {
                    frame.stack += VmValue.NullValue
                }

                is Instruction.PushString -> {
                    frame.stack += VmValue.StringValue(instruction.value)
                }

                Instruction.PushUnit -> {
                    frame.stack += VmValue.UnitValue
                }

                Instruction.Return -> {
                    return handleReturn(frame.popOrDefault())
                }

                is Instruction.StoreLocal -> {
                    ensureLocal(frame, instruction.slot)
                    frame.locals[instruction.slot] = frame.pop()
                }

                is Instruction.Unary -> {
                    applyUnary(frame, instruction.operator)
                }
            }
            ensureWithinMemoryLimit()
            if (instructionsSinceYield >= instructionBudgetPerSlice) {
                instructionsSinceYield = 0
                return VmSignal.Pause
            }
        }
        return VmSignal.Halt
    }

    private fun handleReturn(result: VmValue): VmSignal {
        frames.removeLastOrNull()
        if (frames.isEmpty()) {
            halted = true
            lastResult = result
            return VmSignal.Halt
        }
        frames.last().stack += result
        return runUntilSignal()
    }

    private fun applyUnary(
        frame: FrameState,
        operator: UnaryOperator,
    ) {
        val value = frame.pop()
        frame.stack +=
            when (operator) {
                UnaryOperator.NEGATE -> {
                    when (value) {
                        is VmValue.IntValue -> VmValue.IntValue(-value.value)
                        is VmValue.LongValue -> VmValue.LongValue(-value.value)
                        else -> error("Unary minus expects a numeric value.")
                    }
                }

                UnaryOperator.NOT -> {
                    VmValue.BoolValue(!value.asBoolean())
                }

                UnaryOperator.BIT_NOT -> {
                    when (value) {
                        is VmValue.IntValue -> VmValue.IntValue(value.value.inv())
                        is VmValue.LongValue -> VmValue.LongValue(value.value.inv())
                        else -> error("Bitwise not expects a numeric value.")
                    }
                }
            }
    }

    private fun applyBinary(
        frame: FrameState,
        operator: BinaryOperator,
    ) {
        val right = frame.pop()
        val left = frame.pop()
        frame.stack +=
            when (operator) {
                BinaryOperator.ADD -> {
                    when {
                        left is VmValue.StringValue || right is VmValue.StringValue -> {
                            VmValue.StringValue(left.render() + right.render())
                        }

                        left is VmValue.IntValue && right is VmValue.IntValue -> {
                            VmValue.IntValue(left.value + right.value)
                        }

                        else -> {
                            VmValue.LongValue(left.asLong() + right.asLong())
                        }
                    }
                }

                BinaryOperator.SUBTRACT -> {
                    if (left is VmValue.IntValue && right is VmValue.IntValue) {
                        VmValue.IntValue(left.value - right.value)
                    } else {
                        VmValue.LongValue(left.asLong() - right.asLong())
                    }
                }

                BinaryOperator.MULTIPLY -> {
                    if (left is VmValue.IntValue && right is VmValue.IntValue) {
                        VmValue.IntValue(left.value * right.value)
                    } else {
                        VmValue.LongValue(left.asLong() * right.asLong())
                    }
                }

                BinaryOperator.DIVIDE -> {
                    if (left is VmValue.IntValue && right is VmValue.IntValue) {
                        VmValue.IntValue(left.value / right.value)
                    } else {
                        VmValue.LongValue(left.asLong() / right.asLong())
                    }
                }

                BinaryOperator.EQUALS -> {
                    VmValue.BoolValue(left == right)
                }

                BinaryOperator.NOT_EQUALS -> {
                    VmValue.BoolValue(left != right)
                }

                BinaryOperator.LESS -> {
                    VmValue.BoolValue(left.asLong() < right.asLong())
                }

                BinaryOperator.LESS_EQUALS -> {
                    VmValue.BoolValue(left.asLong() <= right.asLong())
                }

                BinaryOperator.GREATER -> {
                    VmValue.BoolValue(left.asLong() > right.asLong())
                }

                BinaryOperator.GREATER_EQUALS -> {
                    VmValue.BoolValue(left.asLong() >= right.asLong())
                }

                BinaryOperator.AND -> {
                    VmValue.BoolValue(left.asBoolean() && right.asBoolean())
                }

                BinaryOperator.OR -> {
                    VmValue.BoolValue(left.asBoolean() || right.asBoolean())
                }

                BinaryOperator.BIT_AND -> {
                    if (left is VmValue.IntValue && right is VmValue.IntValue) {
                        VmValue.IntValue(left.value and right.value)
                    } else {
                        VmValue.LongValue(left.asLong() and right.asLong())
                    }
                }

                BinaryOperator.BIT_OR -> {
                    if (left is VmValue.IntValue && right is VmValue.IntValue) {
                        VmValue.IntValue(left.value or right.value)
                    } else {
                        VmValue.LongValue(left.asLong() or right.asLong())
                    }
                }

                BinaryOperator.BIT_XOR -> {
                    if (left is VmValue.IntValue && right is VmValue.IntValue) {
                        VmValue.IntValue(left.value xor right.value)
                    } else {
                        VmValue.LongValue(left.asLong() xor right.asLong())
                    }
                }

                BinaryOperator.SHIFT_LEFT -> {
                    if (left is VmValue.IntValue) {
                        VmValue.IntValue(left.value shl right.asInt())
                    } else {
                        VmValue.LongValue(left.asLong() shl right.asInt())
                    }
                }

                BinaryOperator.SHIFT_RIGHT -> {
                    if (left is VmValue.IntValue) {
                        VmValue.IntValue(left.value shr right.asInt())
                    } else {
                        VmValue.LongValue(left.asLong() shr right.asInt())
                    }
                }
            }
    }

    private fun allocate(value: VmObject): VmValue.ObjectRef {
        val id = nextObjectId++
        heap[id] = value
        return VmValue.ObjectRef(id)
    }

    private fun heapObject(value: VmValue): VmObject {
        val ref = value as? VmValue.ObjectRef ?: error("Expected collection reference but got ${value.render()}.")
        return heap[ref.id] ?: error("Object #${ref.id} is missing.")
    }

    private fun mapKey(value: VmValue): VmMapKey = VmMapKey(value)

    private fun checkedIndex(
        index: Int,
        size: Int,
    ): Int {
        check(index >= 0 && index < size) { "Index $index out of bounds for size $size." }
        return index
    }

    private fun applyIndexGet(frame: FrameState) {
        val index = frame.pop()
        val receiver = frame.pop()
        frame.stack +=
            when (val collection = heapObject(receiver)) {
                is VmArrayObject -> collection.elements[checkedIndex(index.asInt(), collection.elements.size)]
                is VmListObject -> collection.elements[checkedIndex(index.asInt(), collection.elements.size)]
                is VmMapObject -> collection.entries[mapKey(index)] ?: VmValue.NullValue
                is VmClassObject -> error("Class instance is not indexable.")
            }
    }

    private fun applyIndexSet(frame: FrameState) {
        val value = frame.pop()
        val index = frame.pop()
        val receiver = frame.pop()
        when (val collection = heapObject(receiver)) {
            is VmArrayObject -> collection.elements[checkedIndex(index.asInt(), collection.elements.size)] = value
            is VmListObject -> collection.elements[checkedIndex(index.asInt(), collection.elements.size)] = value
            is VmMapObject -> collection.entries[mapKey(index)] = value
            is VmClassObject -> error("Class instance is not index-assignable.")
        }
        frame.stack += VmValue.UnitValue
    }

    private fun applyCollectionMethod(
        frame: FrameState,
        methodName: String,
        argumentCount: Int,
    ) {
        val args = frame.popMany(argumentCount)
        val receiver = frame.pop()
        frame.stack +=
            when (val collection = heapObject(receiver)) {
                is VmArrayObject -> applyArrayMethod(collection, methodName, args)
                is VmListObject -> applyListMethod(collection, methodName, args)
                is VmMapObject -> applyMapMethod(collection, methodName, args)
                is VmClassObject -> error("Class instance has no collection method $methodName.")
            }
    }

    private fun applyArrayMethod(
        array: VmArrayObject,
        methodName: String,
        args: List<VmValue>,
    ): VmValue =
        when (methodName) {
            "size" -> VmValue.IntValue(array.elements.size)
            "get" -> array.elements[checkedIndex(args[0].asInt(), array.elements.size)]
            "set" -> {
                array.elements[checkedIndex(args[0].asInt(), array.elements.size)] = args[1]
                VmValue.UnitValue
            }
            "getOrNull" -> array.elements.getOrNull(args[0].asInt()) ?: VmValue.NullValue
            else -> error("Unknown Array method $methodName.")
        }

    private fun applyListMethod(
        list: VmListObject,
        methodName: String,
        args: List<VmValue>,
    ): VmValue =
        when (methodName) {
            "size" -> VmValue.IntValue(list.elements.size)
            "isEmpty" -> VmValue.BoolValue(list.elements.isEmpty())
            "get" -> list.elements[checkedIndex(args[0].asInt(), list.elements.size)]
            "set" -> {
                list.elements[checkedIndex(args[0].asInt(), list.elements.size)] = args[1]
                VmValue.UnitValue
            }
            "getOrNull" -> list.elements.getOrNull(args[0].asInt()) ?: VmValue.NullValue
            "add" -> {
                list.elements += args[0]
                VmValue.UnitValue
            }
            "insert" -> {
                val index = args[0].asInt()
                check(index >= 0 && index <= list.elements.size) { "Index $index out of bounds for size ${list.elements.size}." }
                list.elements.add(index, args[1])
                VmValue.UnitValue
            }
            "removeAt" -> list.elements.removeAt(checkedIndex(args[0].asInt(), list.elements.size))
            "clear" -> {
                list.elements.clear()
                VmValue.UnitValue
            }
            else -> error("Unknown List method $methodName.")
        }

    private fun applyMapMethod(
        map: VmMapObject,
        methodName: String,
        args: List<VmValue>,
    ): VmValue =
        when (methodName) {
            "size" -> VmValue.IntValue(map.entries.size)
            "isEmpty" -> VmValue.BoolValue(map.entries.isEmpty())
            "containsKey" -> VmValue.BoolValue(map.entries.containsKey(mapKey(args[0])))
            "get" -> map.entries[mapKey(args[0])] ?: VmValue.NullValue
            "getOrDefault" -> map.entries[mapKey(args[0])] ?: args[1]
            "set" -> {
                map.entries[mapKey(args[0])] = args[1]
                VmValue.UnitValue
            }
            "remove" -> map.entries.remove(mapKey(args[0])) ?: VmValue.NullValue
            "clear" -> {
                map.entries.clear()
                VmValue.UnitValue
            }
            "keys" -> allocate(VmListObject(map.entries.keys.map { it.value }.toMutableList()))
            "values" -> allocate(VmListObject(map.entries.values.toMutableList()))
            else -> error("Unknown Map method $methodName.")
        }

    private fun createFrame(
        functionIndex: Int,
        arguments: List<VmValue>,
    ): FrameState {
        val function = module.functions[functionIndex]
        val locals = MutableList<VmValue>(function.locals.size.coerceAtLeast(function.parameters.size)) { VmValue.UnitValue }
        arguments.forEachIndexed { index, value ->
            ensureLocal(locals, index)
            locals[index] = value
        }
        return FrameState(functionIndex, 0, locals, mutableListOf())
    }

    private fun currentFunction(frame: FrameState): BytecodeFunction = module.functions[frame.functionIndex]

    private fun ensureLocal(
        frame: FrameState,
        slot: Int,
    ) = ensureLocal(frame.locals, slot)

    private fun ensureLocal(
        locals: MutableList<VmValue>,
        slot: Int,
    ) {
        while (locals.size <= slot) {
            locals += VmValue.UnitValue
        }
    }

    private fun ensureWithinMemoryLimit() {
        if (maxVmRamBytes == Long.MAX_VALUE) return
        val usedBytes = frames.sumOf(FrameState::estimatedMemoryBytes) + heap.values.sumOf(VmObject::estimatedMemoryBytes)
        check(usedBytes <= maxVmRamBytes) { "VM out of memory: $usedBytes > $maxVmRamBytes" }
    }

    private data class FrameState(
        val functionIndex: Int,
        var instructionPointer: Int,
        val locals: MutableList<VmValue>,
        val stack: MutableList<VmValue>,
    ) {
        fun pop(): VmValue = stack.removeLast()

        fun popOrDefault(): VmValue = if (stack.isEmpty()) VmValue.UnitValue else stack.removeLast()

        fun popMany(count: Int): List<VmValue> =
            buildList {
                repeat(count) { add(pop()) }
            }.asReversed()

        fun estimatedMemoryBytes(): Long = 16L + locals.sumOf(VmValue::estimatedMemoryBytes) + stack.sumOf(VmValue::estimatedMemoryBytes)
    }

    private sealed interface VmObject {
        fun estimatedMemoryBytes(): Long
    }

    private data class VmClassObject(
        val className: String,
        val fields: MutableMap<String, VmValue>,
    ) : VmObject {
        override fun estimatedMemoryBytes(): Long = className.length.toLong() + fields.entries.sumOf { it.key.length.toLong() + it.value.estimatedMemoryBytes() }
    }

    private data class VmArrayObject(
        val elements: MutableList<VmValue>,
    ) : VmObject {
        override fun estimatedMemoryBytes(): Long = 16L + elements.sumOf(VmValue::estimatedMemoryBytes)
    }

    private data class VmListObject(
        val elements: MutableList<VmValue>,
    ) : VmObject {
        override fun estimatedMemoryBytes(): Long = 16L + elements.sumOf(VmValue::estimatedMemoryBytes)
    }

    private data class VmMapObject(
        val entries: LinkedHashMap<VmMapKey, VmValue>,
    ) : VmObject {
        override fun estimatedMemoryBytes(): Long = 16L + entries.entries.sumOf { it.key.value.estimatedMemoryBytes() + it.value.estimatedMemoryBytes() }
    }

    private data class VmMapKey(
        val value: VmValue,
    )

    private companion object {
        const val DEFAULT_INSTRUCTION_BUDGET = 64
    }
}

private fun VmValue.estimatedMemoryBytes(): Long =
    when (this) {
        VmValue.UnitValue -> {
            0L
        }

        VmValue.NullValue -> {
            0L
        }

        is VmValue.BoolValue -> {
            1L
        }

        is VmValue.IntValue -> {
            4L
        }

        is VmValue.LongValue -> {
            8L
        }

        is VmValue.StringValue -> {
            value.length.toLong()
        }

        is VmValue.RecordValue -> {
            typeName.length.toLong() +
                fields.entries.sumOf { it.key.length.toLong() + it.value.estimatedMemoryBytes() }
        }

        is VmValue.ObjectRef -> {
            4L
        }
    }
