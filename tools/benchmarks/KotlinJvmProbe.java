/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Locale;

/** Observes the unchanged fleet-collections.kt through its two println markers. */
public final class KotlinJvmProbe {
    private static final com.sun.management.ThreadMXBean THREADS =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int CHECKSUM = -1947596288;
    private static volatile int sink;

    private static final class Markers extends PrintStream {
        private long startWall;
        private long startCpu;
        private long startAllocated;
        private long wall;
        private long cpu;
        private long allocated;
        private int state;

        Markers() { super(OutputStream.nullOutputStream()); }

        @Override public void println(Object value) {
            if (!"ready".equals(value) || state != 0) throw new AssertionError("start marker: " + value);
            state = 1;
            startAllocated = THREADS.getCurrentThreadAllocatedBytes();
            startCpu = THREADS.getCurrentThreadCpuTime();
            startWall = System.nanoTime();
        }

        @Override public void println(String value) { println((Object) value); }

        @Override public void println(int value) {
            wall = System.nanoTime() - startWall;
            cpu = THREADS.getCurrentThreadCpuTime() - startCpu;
            allocated = THREADS.getCurrentThreadAllocatedBytes() - startAllocated;
            if (state != 1 || value != CHECKSUM) throw new AssertionError("checksum: " + value);
            sink = value;
            state = 2;
        }

        void invoke(Method main) throws Exception {
            state = 0;
            main.invoke(null);
            if (state != 2) throw new AssertionError("missing markers");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("timing warmup blocks iterations | memory");
        THREADS.setThreadCpuTimeEnabled(true);
        THREADS.setThreadAllocatedMemoryEnabled(true);
        PrintStream report = System.out;
        Markers markers = new Markers();
        System.setOut(markers);
        long lookupStart = System.nanoTime();
        Method main = Class.forName("Fleet_collectionsKt").getMethod("main");
        long lookupWall = System.nanoTime() - lookupStart;
        markers.invoke(main);
        report.printf(Locale.ROOT, "COLD\t%d\t%d\t%d\t%d%n",
            lookupWall, markers.wall, markers.cpu, markers.allocated);

        if (args[0].equals("cold")) return;

        if (args[0].equals("memory")) {
            for (int i = 0; i < 10000; i++) markers.invoke(main);
            memory(report);
            return;
        }
        if (!args[0].equals("timing") || args.length != 4) throw new IllegalArgumentException("timing arguments");
        int warmup = Integer.parseInt(args[1]);
        int blocks = Integer.parseInt(args[2]);
        int iterations = Integer.parseInt(args[3]);
        if (warmup <= 0 || blocks <= 0 || iterations <= 0) throw new IllegalArgumentException("positive counts");
        report.println("phase\tblock\titerations\twall_ns\tcpu_ns\tallocated_bytes\tcompilation_ms\tgc_count");
        for (int b = -warmup; b < blocks; b++) {
            long wall = 0, cpu = 0, allocated = 0;
            for (int i = 0; i < iterations; i++) {
                markers.invoke(main);
                wall += markers.wall;
                cpu += markers.cpu;
                allocated += markers.allocated;
            }
            report.printf(Locale.ROOT, "%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d%n",
                b < 0 ? "warmup" : "measured", b, iterations, wall, cpu, allocated,
                ManagementFactory.getCompilationMXBean().getTotalCompilationTime(),
                ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x -> Math.max(0, x.getCollectionCount())).sum());
        }
        report.println("CHECKSUM\t" + sink);
    }

    // This fixture retains only the data graph constructed before the Kotlin loop. It does
    // not represent suspended JVM executions, stacks, sandboxing or computer services.
    private static volatile Object[] roots;

    private static void memory(PrintStream report) throws Exception {
        var constructor = Class.forName("Particle").getDeclaredConstructor(int.class, int.class, int.class);
        constructor.setAccessible(true);
        var backing = ArrayList.class.getDeclaredField("elementData");
        backing.setAccessible(true);
        // Resolve fixture reflection before the baseline snapshot.
        var empty = new ArrayList<>(64);
        backing.get(empty);
        KotlinJvmSizeAgent.bytes(empty);
        var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        for (int population : new int[] {0, 32, 2000, 0}) {
            Object[] next = new Object[population * 2];
            long graphBytes = 0;
            for (int p = 0; p < population; p++) {
                var particles = new ArrayList<Object>(64);
                var totals = new int[64];
                for (int i = 0; i < 64; i++) {
                    Object particle = constructor.newInstance(i, i % 7 + 1, i % 5 + 1);
                    particles.add(particle);
                    graphBytes += KotlinJvmSizeAgent.bytes(particle);
                }
                graphBytes += KotlinJvmSizeAgent.bytes(particles) + KotlinJvmSizeAgent.bytes(backing.get(particles))
                    + KotlinJvmSizeAgent.bytes(totals);
                next[p * 2] = particles;
                next[p * 2 + 1] = totals;
            }
            roots = next;
            report.printf(Locale.ROOT, "MEMORY\t%d\t%d\t%d\t%d%n", population, graphBytes,
                population * 67, KotlinJvmSizeAgent.bytes(next));
            report.flush();
            if (!"continue".equals(input.readLine())) throw new AssertionError("memory controller acknowledgement");
        }
    }
}
