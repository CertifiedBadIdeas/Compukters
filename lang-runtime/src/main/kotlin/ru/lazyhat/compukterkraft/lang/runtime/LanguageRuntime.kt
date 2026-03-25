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
import ru.lazyhat.compukterkraft.machine.ComputerCapability
import ru.lazyhat.compukterkraft.machine.ComputerProgram
import ru.lazyhat.compukterkraft.machine.ComputerRuntime
import ru.lazyhat.compukterkraft.machine.VmEvent

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

class BytecodeComputerProgram(
    private val module: BytecodeModule,
) : ComputerProgram {
    override suspend fun run(runtime: ComputerRuntime) {
        val bridge = RuntimeHostBridge(runtime)
        val vm = BytecodeVirtualMachine(module)
        while (true) {
            when (val signal = vm.runUntilSignal()) {
                VmSignal.Halt -> return
                VmSignal.Yield -> {
                    runtime.yield()
                    vm.resumeWith(VmValue.UnitValue)
                }

                is VmSignal.HostCall -> vm.resumeWith(bridge.invoke(signal.moduleName, signal.functionName, signal.arguments))
                is VmSignal.Sleep -> {
                    runtime.sleep(signal.ticks)
                    vm.resumeWith(VmValue.UnitValue)
                }

                is VmSignal.WaitEvent -> vm.resumeWith(bridge.fromEvent(runtime.pullEvent(signal.filter)))
            }
        }
    }
}

class BytecodeVirtualMachine(
    private val module: BytecodeModule,
    snapshot: BytecodeVmSnapshot? = null,
) {
    private val frames = ArrayDeque<FrameState>()
    private var lastResult: VmValue? = snapshot?.lastResult
    private var halted: Boolean = snapshot?.halted ?: false
    private var instructionsSinceYield = 0

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
            val instruction = currentFunction(frame).instructions.getOrNull(frame.instructionPointer)
                ?: return handleReturn(frame.popOrDefault())
            frame.instructionPointer += 1
            instructionsSinceYield += 1
            when (instruction) {
                is Instruction.Binary -> applyBinary(frame, instruction.operator)
                is Instruction.CallBuiltin -> {
                    val args = frame.popMany(instruction.argumentCount)
                    return when (instruction.moduleName) {
                        null ->
                            when (instruction.functionName) {
                                "yield" -> VmSignal.Yield
                                "sleep" -> VmSignal.Sleep(args.single().asLong())
                                else -> error("Unknown global builtin ${instruction.functionName}")
                            }

                        "events" ->
                            if (instruction.functionName == "pull") {
                                VmSignal.WaitEvent(args.singleOrNull()?.asString())
                            } else {
                                error("Unknown events builtin ${instruction.functionName}")
                            }

                        else -> {
                            val moduleName = instruction.moduleName ?: error("Missing module name for builtin call.")
                            VmSignal.HostCall(moduleName, instruction.functionName, args)
                        }
                    }
                }

                is Instruction.CallFunction -> {
                    val args = frame.popMany(instruction.argumentCount)
                    frames += createFrame(instruction.functionIndex, args)
                }

                is Instruction.ConstructRecord -> {
                    val values = frame.popMany(instruction.fieldNames.size)
                    frame.stack += VmValue.RecordValue(instruction.typeName, instruction.fieldNames.zip(values).toMap())
                }

                is Instruction.GetField -> {
                    val receiver = frame.pop()
                    val value =
                        (receiver as? VmValue.RecordValue)?.fields?.get(instruction.fieldName)
                            ?: error("Record field ${instruction.fieldName} is missing.")
                    frame.stack += value
                }

                is Instruction.Jump -> frame.instructionPointer = instruction.target
                is Instruction.JumpIfFalse -> if (!frame.pop().asBoolean()) frame.instructionPointer = instruction.target
                is Instruction.LoadLocal -> frame.stack += frame.locals[instruction.slot]
                Instruction.Pop -> frame.pop()
                is Instruction.PushBool -> frame.stack += VmValue.BoolValue(instruction.value)
                is Instruction.PushInt -> frame.stack += VmValue.IntValue(instruction.value)
                is Instruction.PushLong -> frame.stack += VmValue.LongValue(instruction.value)
                Instruction.PushNull -> frame.stack += VmValue.NullValue
                is Instruction.PushString -> frame.stack += VmValue.StringValue(instruction.value)
                Instruction.PushUnit -> frame.stack += VmValue.UnitValue
                Instruction.Return -> return handleReturn(frame.popOrDefault())
                is Instruction.StoreLocal -> {
                    ensureLocal(frame, instruction.slot)
                    frame.locals[instruction.slot] = frame.pop()
                }

                is Instruction.Unary -> applyUnary(frame, instruction.operator)
            }
            if (instructionsSinceYield >= YIELD_INTERVAL) {
                instructionsSinceYield = 0
                return VmSignal.Yield
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
                UnaryOperator.NEGATE ->
                    when (value) {
                        is VmValue.IntValue -> VmValue.IntValue(-value.value)
                        is VmValue.LongValue -> VmValue.LongValue(-value.value)
                        else -> error("Unary minus expects a numeric value.")
                    }

                UnaryOperator.NOT -> VmValue.BoolValue(!value.asBoolean())
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
                BinaryOperator.ADD ->
                    when {
                        left is VmValue.StringValue || right is VmValue.StringValue ->
                            VmValue.StringValue(left.render() + right.render())
                        left is VmValue.IntValue && right is VmValue.IntValue -> VmValue.IntValue(left.value + right.value)
                        else -> VmValue.LongValue(left.asLong() + right.asLong())
                    }

                BinaryOperator.SUBTRACT ->
                    if (left is VmValue.IntValue && right is VmValue.IntValue) {
                        VmValue.IntValue(left.value - right.value)
                    } else {
                        VmValue.LongValue(left.asLong() - right.asLong())
                    }

                BinaryOperator.MULTIPLY ->
                    if (left is VmValue.IntValue && right is VmValue.IntValue) {
                        VmValue.IntValue(left.value * right.value)
                    } else {
                        VmValue.LongValue(left.asLong() * right.asLong())
                    }

                BinaryOperator.DIVIDE ->
                    if (left is VmValue.IntValue && right is VmValue.IntValue) {
                        VmValue.IntValue(left.value / right.value)
                    } else {
                        VmValue.LongValue(left.asLong() / right.asLong())
                    }

                BinaryOperator.EQUALS -> VmValue.BoolValue(left == right)
                BinaryOperator.NOT_EQUALS -> VmValue.BoolValue(left != right)
                BinaryOperator.LESS -> VmValue.BoolValue(left.asLong() < right.asLong())
                BinaryOperator.LESS_EQUALS -> VmValue.BoolValue(left.asLong() <= right.asLong())
                BinaryOperator.GREATER -> VmValue.BoolValue(left.asLong() > right.asLong())
                BinaryOperator.GREATER_EQUALS -> VmValue.BoolValue(left.asLong() >= right.asLong())
                BinaryOperator.AND -> VmValue.BoolValue(left.asBoolean() && right.asBoolean())
                BinaryOperator.OR -> VmValue.BoolValue(left.asBoolean() || right.asBoolean())
            }
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
    }

    private companion object {
        const val YIELD_INTERVAL = 64
    }
}

private class RuntimeHostBridge(
    private val runtime: ComputerRuntime,
) {
    suspend fun invoke(
        moduleName: String,
        functionName: String,
        arguments: List<VmValue>,
    ): VmValue {
        ensureCapability(moduleName)
        return when (moduleName) {
            "filesystem" ->
                when (functionName) {
                    "exists" -> VmValue.BoolValue(runtime.filesystem.exists(arguments[0].asString()))
                    "readText" -> VmValue.StringValue(runtime.filesystem.readText(arguments[0].asString()) ?: "")
                    "writeText" -> {
                        runtime.filesystem.writeText(arguments[0].asString(), arguments[1].asString())
                        VmValue.UnitValue
                    }

                    else -> error("Unknown filesystem function $functionName")
                }

            "system" ->
                when (functionName) {
                    "computerId" -> VmValue.IntValue(runtime.system.computerId)
                    "currentTick" -> VmValue.LongValue(runtime.system.currentTick)
                    "label" -> VmValue.StringValue(runtime.system.label.orEmpty())
                    "profileName" -> VmValue.StringValue(runtime.profile.displayName)
                    "log" -> {
                        runtime.system.log(arguments[0].asString())
                        VmValue.UnitValue
                    }

                    "shutdown" -> {
                        runtime.system.shutdown()
                        VmValue.UnitValue
                    }

                    "reboot" -> {
                        runtime.system.reboot()
                        VmValue.UnitValue
                    }

                    else -> error("Unknown system function $functionName")
                }

            "terminal" ->
                when (functionName) {
                    "write" -> {
                        runtime.terminal.write(arguments[0].asString())
                        VmValue.UnitValue
                    }

                    "printLine" -> {
                        runtime.terminal.printLine(arguments[0].asString())
                        VmValue.UnitValue
                    }

                    "clear" -> {
                        runtime.terminal.clear()
                        VmValue.UnitValue
                    }

                    "setCursor" -> {
                        runtime.terminal.setCursor(arguments[0].asInt(), arguments[1].asInt())
                        VmValue.UnitValue
                    }

                    else -> error("Unknown terminal function $functionName")
                }

            else -> error("Unknown module $moduleName")
        }
    }

    fun fromEvent(event: VmEvent): VmValue.RecordValue =
        VmValue.RecordValue(
            typeName = "Event",
            fields = mapOf("name" to VmValue.StringValue(event.name)),
        )

    private fun ensureCapability(moduleName: String) {
        val capability =
            when (moduleName) {
                "filesystem" -> ComputerCapability.FILESYSTEM
                "system" -> ComputerCapability.SYSTEM
                "terminal" -> ComputerCapability.TERMINAL
                "events" -> ComputerCapability.EVENTS
                else -> null
            }
        if (capability != null && capability !in runtime.profile.allowedCapabilities) {
            error("Capability $moduleName is not allowed for this computer profile.")
        }
    }
}

private fun VmValue.asBoolean(): Boolean =
    when (this) {
        is VmValue.BoolValue -> value
        else -> error("Expected Bool but got ${render()}")
    }

private fun VmValue.asInt(): Int =
    when (this) {
        is VmValue.IntValue -> value
        is VmValue.LongValue -> value.toInt()
        else -> error("Expected Int but got ${render()}")
    }

private fun VmValue.asLong(): Long =
    when (this) {
        is VmValue.IntValue -> value.toLong()
        is VmValue.LongValue -> value
        else -> error("Expected Long but got ${render()}")
    }

private fun VmValue.asString(): String =
    when (this) {
        is VmValue.StringValue -> value
        VmValue.NullValue -> ""
        else -> render()
    }

private fun VmValue.render(): String =
    when (this) {
        is VmValue.BoolValue -> value.toString()
        is VmValue.IntValue -> value.toString()
        is VmValue.LongValue -> value.toString()
        is VmValue.RecordValue -> "$typeName${fields.mapValues { it.value.render() }}"
        is VmValue.StringValue -> value
        VmValue.UnitValue -> "unit"
        VmValue.NullValue -> "null"
    }
