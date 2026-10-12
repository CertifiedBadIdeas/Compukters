/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.redstone

import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralFailure
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire

/** Server-thread state of one physical port. Outputs and ownership are deliberately transient. */
class RedstonePortState(
    private val changed: (Int) -> Unit = {},
) {
    private data class Lease(
        val valid: () -> Boolean,
    )

    private val leases = linkedMapOf<Any, Lease>()
    private val inputs = IntArray(6)
    private val revisions = LongArray(6)
    private var outputs = 0

    /** Common peripheral ownership has already admitted this handle. */
    fun acquire(
        token: Any,
        valid: () -> Boolean,
    ) {
        expire()
        check(token !in leases) { "Redstone port lease is already acquired" }
        leases[token] = Lease(valid)
    }

    fun owns(token: Any): Boolean = token in leases

    fun release(token: Any) {
        leases.remove(token)
        if (leases.isEmpty()) clearOutputs()
    }

    fun expire() {
        leases.entries.removeIf { !it.value.valid() }
        if (leases.isEmpty()) clearOutputs()
    }

    fun reset() {
        leases.clear()
        clearOutputs()
    }

    fun sample(levels: IntArray) {
        require(levels.size == 6 && levels.all { it in 0..15 })
        levels.forEachIndexed { side, level ->
            if (inputs[side] != level) {
                inputs[side] = level
                revisions[side] += 1
            }
        }
    }

    fun input(side: Int): Int {
        require(side in 0..5)
        return inputs[side]
    }

    fun revision(side: Int): Long {
        require(side in 0..5)
        return revisions[side]
    }

    fun output(side: Int): Int = RedstoneWire.output(outputs, side)

    fun set(
        token: Any,
        side: Int,
        output: Int,
    ) {
        require(side in 0..5 && output in 0..RedstoneWire.OUTPUT_MASK)
        expire()
        if (token !in leases) throw PeripheralFailure(HostFailureKind.INPUT_OUTPUT, "Redstone port access expired")
        val previous = output(side)
        outputs = RedstoneWire.replaceOutput(outputs, side, output)
        if (previous != output) changed(1 shl side)
    }

    /** Commit every output before issuing a single neighbor notification. */
    fun setAll(
        token: Any,
        output: Int,
    ) {
        require(output in 0..RedstoneWire.OUTPUT_MASK)
        expire()
        if (token !in leases) throw PeripheralFailure(HostFailureKind.INPUT_OUTPUT, "Redstone port access expired")
        var mask = 0
        for (side in 0..5) {
            if (output(side) != output) mask = mask or (1 shl side)
            outputs = RedstoneWire.replaceOutput(outputs, side, output)
        }
        if (mask != 0) changed(mask)
    }

    private fun clearOutputs() {
        val mask = (0..5).fold(0) { mask, side -> if (output(side) != 0) mask or (1 shl side) else mask }
        outputs = 0
        if (mask != 0) changed(mask)
    }
}
