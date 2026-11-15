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
    static native int completeCompilation(long handle, long machine, long token, int kind, byte[] payload);
    static native int attachSession(long handle, long session, long[] machine);
    static native int detach(long handle, long machine);
    static native int restoreSession(long handle, long machine, long[] session);
    static native int terminalKey(long handle, long machine, int key, int action, int modifiers);
    static native int terminalUpdate(long handle, long machine, long request, long revision);
    static native int resourceSnapshot(long handle, long machine, long request);
    static native int filesystemGeneration(long handle, long machine, long request);
    static native int filesystemQuery(long handle, long machine, long request, int kind, byte[] path, byte[] cursor, long offset, int maximum, long generation);
    static native int redstoneInput(long handle, long machine, int packet);
    static native int confirmRedstoneOutput(long handle, long machine, int packed);
    static native int terminalText(long handle, long machine, int[] codePoints);
    static native int canonicalLine(long handle, long machine, char[] line);
    static native int resolveCost(long handle, long machine, int task, long request, int units);
    static native int reply(long handle, long machine, int task, long request, int tag, long bits, byte[] payload);
    static native int replyWire(long handle, long machine, int task, long request, byte[] payload);
    static native int replyFailure(long handle, long machine, int task, long request, int kind, byte[] detail);
    static native int poll(long handle, byte[] output, long[] written);
    static native int close(long handle);
}
