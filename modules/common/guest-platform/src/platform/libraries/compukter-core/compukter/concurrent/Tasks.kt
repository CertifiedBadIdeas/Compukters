/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.concurrent

/** A bounded cooperative Guest task. */
public value class Task internal constructor(internal val id: Int) {
    /**
     * Blocks the current task until this task completes.
     *
     * If the task failed, every join throws its original exception object. An unjoined task failure does not
     * terminate other tasks; the failure is retained until the process finishes.
     */
    public external fun join()
}

/** Starts bounded cooperative work within the current VM. */
public object Tasks {
    /**
     * Starts [block] and returns its task handle.
     *
     * Accepts a non-null zero-argument [Unit] function value, including a lambda with captures.
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
