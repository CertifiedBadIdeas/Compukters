/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.redstone

import compukter.peripheral.Peripheral
import compukter.peripheral.TypedPeripheralProvider

/** A transient six-face redstone device, exclusively owned by the acquiring program. */
public value class RedstonePort internal constructor(private val handle: Int) : Peripheral {
    public companion object : TypedPeripheralProvider<RedstonePort>("compukter:redstone_port") {
        override fun wrap(handle: Int): RedstonePort = RedstonePort(handle)
    }

    /** Output selection for this port, separate from peripheral adjacency. */
    public enum class Side { ALL, FRONT, BACK, LEFT, RIGHT, TOP, BOTTOM }

    /** ALL updates all six outputs in one host operation. */
    public fun set(level: Int, power: Redstone.Power = Redstone.Power.WEAK, side: RedstonePort.Side = RedstonePort.Side.ALL) {
        val output = checkedPortLevel(level) or if (power == Redstone.Power.DIRECT) 16 else 0
        when (side) {
            Side.ALL -> RedstonePortBindings.setAllOutput(handle, output)
            Side.FRONT -> RedstonePortBindings.setOutput(handle, 0, output)
            Side.BACK -> RedstonePortBindings.setOutput(handle, 1, output)
            Side.LEFT -> RedstonePortBindings.setOutput(handle, 2, output)
            Side.RIGHT -> RedstonePortBindings.setOutput(handle, 3, output)
            Side.TOP -> RedstonePortBindings.setOutput(handle, 4, output)
            Side.BOTTOM -> RedstonePortBindings.setOutput(handle, 5, output)
        }
    }

    public val front: RedstonePortSide get() = RedstonePortSide(handle, 0)
    public val back: RedstonePortSide get() = RedstonePortSide(handle, 1)
    public val left: RedstonePortSide get() = RedstonePortSide(handle, 2)
    public val right: RedstonePortSide get() = RedstonePortSide(handle, 3)
    public val top: RedstonePortSide get() = RedstonePortSide(handle, 4)
    public val bottom: RedstonePortSide get() = RedstonePortSide(handle, 5)

    /** Releases this handle; the last release clears all port outputs. */
    public fun close() {
        RedstonePortBindings.close(handle)
    }
}

/** Input readings and waits relative to the port's front, independently of the computer's facing. */
public value class RedstonePortSide internal constructor(private val handle: Int, private val index: Int) {
    /** Asynchronous host I/O returning the latest server-tick input sample. */
    public fun get(): Int = RedstonePortBindings.input(handle, index)

    /** Wait for a newly sampled change; zero is a valid result. */
    public fun await(): Int = RedstonePortBindings.awaitChange(handle, index)

    /** Completes immediately if the current sample equals level. */
    public fun await(level: Int): Int = RedstonePortBindings.awaitLevel(handle, index, 1, checkedPortLevel(level))

    public fun awaitAtLeast(level: Int): Int = RedstonePortBindings.awaitLevel(handle, index, 2, checkedPortLevel(level))
}

private fun checkedPortLevel(level: Int): Int {
    require(level in 0..15)
    return level
}

private object RedstonePortBindings {
    external fun input(handle: Int, side: Int): Int
    external fun setOutput(handle: Int, side: Int, output: Int)
    external fun awaitChange(handle: Int, side: Int): Int
    external fun awaitLevel(handle: Int, side: Int, mode: Int, level: Int): Int
    external fun setAllOutput(handle: Int, output: Int)
    external fun close(handle: Int)
}
