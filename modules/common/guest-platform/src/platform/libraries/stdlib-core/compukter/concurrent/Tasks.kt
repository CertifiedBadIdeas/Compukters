/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.concurrent

/** A bounded cooperative Guest task. */
public value class Task internal constructor(internal val id: Int) {
    /** Blocks the current task until this task completes. */
    public external fun join()
}

/** Starts bounded cooperative work within the current VM. */
public object Tasks {
    /**
     * Starts [block] and returns its task handle.
     *
     * Compukters currently accepts only a direct reference to a top-level, zero-argument function.
     */
    public external fun launch(block: () -> Unit): Task

    /**
     * Suspends the current task for at least [ticks] server ticks.
     *
     * A zero duration yields until the next server tick. Other runnable tasks in this VM may continue.
     */
    public fun sleepTicks(ticks: Int) {
        if (ticks < 0) throw IllegalArgumentException("ticks must not be negative")
        TimerBindings.sleepTicks(ticks)
    }
}

private object TimerBindings {
    external fun sleepTicks(ticks: Int)
}

/**
 * A bounded FIFO channel carrying [Int] values between cooperative Guest tasks.
 *
 * Declare channels as top-level immutable properties. [send] suspends while the channel is full,
 * and [receive] suspends while it is empty. A positive [capacity] is reserved when the program is admitted.
 */
public value class IntChannel public constructor(internal val capacity: Int) {
    /** Sends [value], blocking the current task until bounded channel storage is available. */
    public external fun send(value: Int)

    /** Receives the oldest queued value, blocking the current task until one is available. */
    public external fun receive(): Int
}
