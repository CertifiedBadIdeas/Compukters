# Profiling Compukter Kraft

Compukter Kraft profiling has two layers:

1. **In-code domain metrics** from runtime, display, and compiler collectors.
2. **External CPU/allocation profiling** through JFR or async-profiler.

The in-code metrics explain what the VM/display/compiler did. External profilers explain where CPU time and allocations were spent.

## Runtime/display profiling workload

Run the bundled terminal profiling workload:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest --info
```

Run only the held-Enter backlog workload:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest.heldEnterWorkloadProducesBacklogProfilingMetrics --info
```

The output includes:

- `display:` operation counts, areas, and operation timings;
- `display-avg:` per-operation averages;
- `frames:` emitted frame/tile/payload counts;
- `frame-build:` dirty tile scan, tile serialization, front-copy, and build timings;
- `runtime:` server tick and slice request timings;
- `host:` host call drain/dispatch/delivery timings;
- `display-runtime:` frame drain/flush timings;
- `vm:` scheduler and execution window metrics;
- `signals:` VM signal distribution;
- `host-calls:` VM-level builtin/host-call distribution by `module.function`, with counts and wall-clock timings;
- `instructions:` VM bytecode instruction distribution by instruction kind, with counts and timings;
- `compiler:` compile totals;
- `compiler-phases:` parse/analyze/codegen metrics.

The held-Enter workload additionally prints a `held-enter:` line with accepted repeated Enter events, settle ticks,
maximum/final queued VM events, maximum/final pending host calls, and drained display frame count. It does not filter
repeated Enter; it measures terminal/shell backlog behavior under repeat input.

## JFR

JFR is available with the JDK and is the first external profiler to try.

```bash
./gradlew :v1_21_1-neoforge:test \
  --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest \
  --info \
  -Dorg.gradle.jvmargs="-XX:StartFlightRecording=filename=build/reports/profiling/runtime-display.jfr,settings=profile,dumponexit=true"
```

If Gradle daemon JVM arguments are already configured locally, stop daemons before rerunning:

```bash
./gradlew --stop
```

Open the `.jfr` file in JDK Mission Control or another JFR viewer. Compare CPU and allocation hotspots with the in-code summary printed by the workload.

## async-profiler

async-profiler is optional and depends on local OS/tooling setup.

Use it when JFR shows a broad hotspot and you need flamegraphs. Attach to the Gradle test JVM that is running `RuntimeDisplayProfilingTest`, collect CPU or allocation data, then compare the flamegraph with the in-code summaries.

## Native candidate heuristic

A path is a plausible JNI/Rust candidate only if all are true:

1. It is a measured CPU or allocation hotspot.
2. It can be invoked as a coarse batch.
3. Inputs and outputs can be represented as primitive buffers or compact handles.
4. It does not require frequent native-to-Java callbacks.
5. Expected savings exceed native packaging and crash-risk costs.

Likely candidates after measurement:

- full CKL VM slice runner;
- batched framebuffer operations;
- tile serialization or compression.

Unlikely candidates:

- individual `setPixel` calls;
- individual glyph calls;
- host filesystem calls;
- event queue bookkeeping;
- Minecraft UI glue.

## Interpretation notes

- High operation counts with low total nanos usually do not justify native code.
- High total nanos in small per-call operations suggests batching before native code.
- High tile payload bytes with high serialization nanos may justify native serialization or compression.
- High VM execution nanos plus many pause/yield signals points to VM interpreter work.
- High `host-calls:` counts point to chatty CKL-to-runtime builtins; prefer batching or moving work inside the VM before considering native code.
- High `host-calls:` nanos can include coroutine wait time for blocking APIs such as IPC reads or event polling. Treat these as latency/blocking signals first, then use JFR to confirm CPU cost.
- High `instructions:` nanos or counts identify interpreter opcode families to inspect with JFR before rewriting the VM.
- Compiler phase timings affect startup and IDE latency, not steady-state display FPS.
