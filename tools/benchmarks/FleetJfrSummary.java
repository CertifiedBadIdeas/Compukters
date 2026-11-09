/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Stream JFR stacks without expanding repeated class-loader metadata into a large JSON dump. */
public final class FleetJfrSummary {
    private final Map<String, long[]> totals = new TreeMap<>();
    private final HashSet<Long> allocationThreads = new HashSet<>();

    private void add(String kind, String role, String key, long weight) {
        var value = totals.computeIfAbsent(kind + "\t" + role + "\t" + key, ignored -> new long[2]);
        value[0]++;
        value[1] += weight;
    }

    private static String role(RecordedThread thread) {
        if (thread == null) return "unknown";
        var name = thread.getJavaName();
        if ("main".equals(name)) return "owner";
        if (name != null && name.startsWith("compukters-vm-worker-")) return "worker";
        return "other";
    }

    private static String method(RecordedFrame frame) {
        var method = frame.getMethod();
        var type = method.getType().getName().replaceAll("/0x[0-9a-fA-F]+", "/<hidden>");
        return type + "." + method.getName() + ":" + frame.getLineNumber();
    }

    private static boolean project(String method) {
        return method.startsWith("ru.lazyhat.compukters.");
    }

    private void accept(RecordedEvent event) {
        var type = event.getEventType().getName();
        add("event", "all", type, 0);
        boolean cpu = type.equals("jdk.ExecutionSample") || type.equals("jdk.NativeMethodSample");
        boolean allocation = type.equals("jdk.ObjectAllocationSample");
        if (!cpu && !allocation) return;
        var thread = cpu ? event.getThread("sampledThread") : event.getThread();
        var role = role(thread);
        var stack = event.getStackTrace();
        List<RecordedFrame> frames = stack == null ? List.of() : stack.getFrames();
        if (cpu) {
            add("cpu_event", role, type, 0);
            add("cpu_state", role, event.getString("state"), 0);
            add("cpu_leaf", role, frames.isEmpty() ? "<missing>" : method(frames.getFirst()), 0);
            if (type.equals("jdk.ExecutionSample"))
                add("cpu_java_leaf", role, frames.isEmpty() ? "<missing>" : method(frames.getFirst()), 0);
            if (stack != null && stack.isTruncated()) add("cpu_truncated", role, type, 0);
            var seen = new HashSet<String>();
            for (var frame : frames) {
                var label = method(frame);
                if (project(label) && seen.add(label)) add("cpu_project_inclusive", role, label, 0);
            }
        } else {
            long weight = event.getLong("weight");
            var object = event.getClass("objectClass").getName();
            add("allocation_class", role, object, weight);
            var sites = frames.stream().map(FleetJfrSummary::method).filter(FleetJfrSummary::project).limit(5).toList();
            add("allocation_site", role, object + " @ " + String.join(" <- ", sites), weight);
            // The first weight can include allocation accumulated before this phase-local recording.
            var portion = allocationThreads.add(thread.getJavaThreadId()) ? "first" : "later";
            add("allocation_" + portion + "_class", role, object, weight);
            add("allocation_" + portion + "_site", role, object + " @ " + String.join(" <- ", sites), weight);
            if (stack != null && stack.isTruncated()) add("allocation_truncated", role, object, weight);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected input.jfr output.tsv");
        var summary = new FleetJfrSummary();
        try (var recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) summary.accept(recording.readEvent());
        }
        try (var output = Files.newBufferedWriter(Path.of(args[1]))) {
            output.write("kind\trole\tkey\tsamples\tweight\n");
            for (var entry : summary.totals.entrySet()) {
                output.write(entry.getKey() + "\t" + entry.getValue()[0] + "\t" + entry.getValue()[1] + "\n");
            }
        }
    }
}
