/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import java.lang.instrument.Instrumentation;

/** Memory-only shallow sizes from the actual JVM, without assumed object layouts. */
public final class KotlinJvmSizeAgent {
    private static Instrumentation instrumentation;
    public static void premain(String options, Instrumentation value) { instrumentation = value; }
    public static long bytes(Object object) { return instrumentation.getObjectSize(object); }
}
