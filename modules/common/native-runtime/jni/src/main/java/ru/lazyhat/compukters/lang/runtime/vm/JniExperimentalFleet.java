/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.lang.runtime.vm;

/** Private entry points available only in the experimental native build. */
final class JniExperimentalFleet {
    private JniExperimentalFleet() {}

    static native int create(byte[] artifact, byte[] schemas, int count, int[] config,
        byte[] namespace, byte[] name, byte[] overBudgetDetail,
        int major, int minor, int operation, long[] handle);
    static native int grant(long handle, long machine, long tick, int retirements, long deadlineNanos);
    static native int stop(long handle, long machine);
    static native int terminalKey(long handle, long machine, int key, int action, int modifiers);
    static native int resolveCost(long handle, long machine, int task, long request, int units);
    static native int reply(long handle, long machine, int task, long request, int tag, long bits, byte[] payload);
    static native int replyWire(long handle, long machine, int task, long request, byte[] payload);
    static native int replyFailure(long handle, long machine, int task, long request, int kind, byte[] detail);
    static native int poll(long handle, byte[] output, long[] written);
    static native int close(long handle);
}
