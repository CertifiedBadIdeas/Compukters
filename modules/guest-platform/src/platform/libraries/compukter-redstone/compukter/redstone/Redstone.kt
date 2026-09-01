/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.redstone

public enum class RedstoneSide {
    FRONT,
    BACK,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
}

public value class RedstoneSignal(public val level: Int) {
    init {
        require(level in 0..15)
    }

    public companion object {
        public val MIN: RedstoneSignal = RedstoneSignal(0)
        public val MAX: RedstoneSignal = RedstoneSignal(15)
    }
}

public value class RedstoneOutput internal constructor(internal val packed: Int) {
    public val signal: RedstoneSignal
        get() = RedstoneSignal(packed and 0x0f)

    public val direct: Boolean
        get() = packed and 0x10 != 0

    public companion object {
        public val MIN: RedstoneOutput = RedstoneOutput(0)
        public val MAX: RedstoneOutput = RedstoneOutput(0x1f)
    }
}

public value class RedstoneOutputs internal constructor(internal val packed: Int) {
    public operator fun get(side: RedstoneSide): RedstoneOutput =
        RedstoneOutput((packed ushr (redstoneSideIndex(side) * 5)) and 0x1f)

    public fun with(side: RedstoneSide, output: RedstoneOutput): RedstoneOutputs {
        val shift = redstoneSideIndex(side) * 5
        return RedstoneOutputs((packed and (0x1f shl shift).inv()) or (output.packed shl shift))
    }

    public companion object {
        public val ALL_MIN: RedstoneOutputs = RedstoneOutputs(0)
        public val ALL_MAX: RedstoneOutputs = RedstoneOutputs(0x3fff_ffff)
    }
}

public object Redstone {
    public fun input(side: RedstoneSide): RedstoneSignal = RedstoneSignal(RedstoneBindings.input(redstoneSideIndex(side)))

    public fun awaitInputChange(side: RedstoneSide): RedstoneSignal =
        RedstoneSignal(RedstoneBindings.awaitInputChange(redstoneSideIndex(side)))

    public fun awaitInput(side: RedstoneSide, signal: RedstoneSignal): RedstoneSignal =
        RedstoneSignal(RedstoneBindings.awaitInput(redstoneSideIndex(side), signal.level))

    public fun awaitAtLeastInput(side: RedstoneSide, signal: RedstoneSignal): RedstoneSignal =
        RedstoneSignal(RedstoneBindings.awaitAtLeastInput(redstoneSideIndex(side), signal.level))

    public fun awaitAtMostInput(side: RedstoneSide, signal: RedstoneSignal): RedstoneSignal =
        RedstoneSignal(RedstoneBindings.awaitAtMostInput(redstoneSideIndex(side), signal.level))

    public fun output(signal: RedstoneSignal, direct: Boolean = false): RedstoneOutput =
        RedstoneOutput(signal.level or if (direct) 0x10 else 0)

    public fun outputs(): RedstoneOutputs = RedstoneOutputs(RedstoneBindings.outputs())

    public fun output(side: RedstoneSide): RedstoneOutput = outputs()[side]

    public fun setOutput(side: RedstoneSide, output: RedstoneOutput) {
        RedstoneBindings.setOutput(redstoneSideIndex(side), output.packed)
    }

    public fun setOutputs(outputs: RedstoneOutputs) {
        RedstoneBindings.setOutputs(outputs.packed)
    }
}

private object RedstoneBindings {
    external fun input(side: Int): Int

    external fun awaitInputChange(side: Int): Int

    external fun awaitInput(side: Int, signal: Int): Int

    external fun awaitAtLeastInput(side: Int, signal: Int): Int

    external fun awaitAtMostInput(side: Int, signal: Int): Int

    external fun outputs(): Int

    external fun setOutput(side: Int, output: Int)

    external fun setOutputs(outputs: Int)
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
