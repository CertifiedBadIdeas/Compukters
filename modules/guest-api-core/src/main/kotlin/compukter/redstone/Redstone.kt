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

@file:Suppress("UNUSED_PARAMETER")

package compukter.redstone

enum class RedstoneSide {
    FRONT,
    BACK,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
}

@JvmInline
value class RedstoneSignal(val level: Int) {
    init {
        require(level in 0..15)
    }

    companion object {
        val MIN = RedstoneSignal(0)
        val MAX = RedstoneSignal(15)
    }
}

@JvmInline
value class RedstoneOutput internal constructor(internal val packed: Int) {
    val signal: RedstoneSignal
        get() = RedstoneSignal(packed and 0x0f)

    val direct: Boolean
        get() = packed and 0x10 != 0

    companion object {
        val MIN = RedstoneOutput(0)
        val MAX = RedstoneOutput(0x1f)
    }
}

@JvmInline
value class RedstoneOutputs internal constructor(internal val packed: Int) {
    operator fun get(side: RedstoneSide): RedstoneOutput =
        RedstoneOutput((packed ushr (redstoneSideIndex(side) * 5)) and 0x1f)

    fun with(
        side: RedstoneSide,
        output: RedstoneOutput,
    ): RedstoneOutputs {
        val shift = redstoneSideIndex(side) * 5
        return RedstoneOutputs((packed and (0x1f shl shift).inv()) or (output.packed shl shift))
    }

    companion object {
        val ALL_MIN = RedstoneOutputs(0)
        val ALL_MAX = RedstoneOutputs(0x3fff_ffff)
    }
}

object Redstone {
    fun input(side: RedstoneSide): RedstoneSignal =
        RedstoneSignal(RedstoneBindings.input(redstoneSideIndex(side)))

    fun awaitInputChange(side: RedstoneSide): RedstoneSignal =
        RedstoneSignal(RedstoneBindings.awaitInputChange(redstoneSideIndex(side)))

    fun awaitInput(
        side: RedstoneSide,
        signal: RedstoneSignal,
    ): RedstoneSignal =
        RedstoneSignal(RedstoneBindings.awaitInput(redstoneSideIndex(side), signal.level))

    fun awaitAtLeastInput(
        side: RedstoneSide,
        signal: RedstoneSignal,
    ): RedstoneSignal =
        RedstoneSignal(RedstoneBindings.awaitAtLeastInput(redstoneSideIndex(side), signal.level))

    fun awaitAtMostInput(
        side: RedstoneSide,
        signal: RedstoneSignal,
    ): RedstoneSignal =
        RedstoneSignal(RedstoneBindings.awaitAtMostInput(redstoneSideIndex(side), signal.level))

    fun output(
        signal: RedstoneSignal,
        direct: Boolean = false,
    ): RedstoneOutput = RedstoneOutput(signal.level or if (direct) 0x10 else 0)

    fun outputs(): RedstoneOutputs = RedstoneOutputs(RedstoneBindings.outputs())

    fun output(side: RedstoneSide): RedstoneOutput = outputs()[side]

    fun setOutput(
        side: RedstoneSide,
        output: RedstoneOutput,
    ): Unit = RedstoneBindings.setOutput(redstoneSideIndex(side), output.packed)

    fun setOutputs(outputs: RedstoneOutputs): Unit = RedstoneBindings.setOutputs(outputs.packed)
}

private object RedstoneBindings {
    fun input(side: Int): Int = 0

    fun awaitInputChange(side: Int): Int = 0

    fun awaitInput(
        side: Int,
        signal: Int,
    ): Int = 0

    fun awaitAtLeastInput(
        side: Int,
        signal: Int,
    ): Int = 0

    fun awaitAtMostInput(
        side: Int,
        signal: Int,
    ): Int = 0

    fun outputs(): Int = 0

    fun setOutput(
        side: Int,
        output: Int,
    ): Unit = Unit

    fun setOutputs(outputs: Int): Unit = Unit
}

private fun redstoneSideIndex(side: RedstoneSide): Int =
    when (side) {
        RedstoneSide.FRONT -> 0
        RedstoneSide.BACK -> 1
        RedstoneSide.LEFT -> 2
        RedstoneSide.RIGHT -> 3
        RedstoneSide.TOP -> 4
        RedstoneSide.BOTTOM -> 5
    }
