/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

/** Server-owner lifetime and delivery; independent of machine admission and Guest execution. */
interface ProgramRuntimeLifecycle : AutoCloseable {
    /** Transport may progress with zero observation allowance, without delivering callbacks. */
    val supportsCommandOnlyPump: Boolean get() = false

    fun beginCapacityFrame(worldTick: Long)

    fun flushCapacityFrame()

    fun observePreviousCapacityFrame()

    fun pump(
        maximumEvents: Int,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): Int

    fun runtimeMetrics(): ProgramRuntimeActorMetrics

    fun calibratedCapacity(): Long

    fun currentSafeCapacity(): Long

    fun capacityFallbackReason(): String?

    fun acceptCapacityCalibration(measurement: VmCapacityCalibration)

    fun rejectCapacityCalibration(reason: String)
}
