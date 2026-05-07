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

The output is grouped into multi-line, indented sections:

- `display:` with `operations`, `frames`, and `frame-build` subsections for display counts, areas, payload bytes, timings, and averages;
- `runtime:` with `tick`, `host-queue`, `display-runtime`, `vm`, `signals`, `host-calls`, and `instructions` subsections;
- `compiler:` with `totals` and `phases` subsections for compile, parse, analyze, and codegen metrics.

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

## Rust VM prototype

The Rust VM prototype is local-development only until packaging is designed.

Run Rust crate tests:

```bash
cd native/ckl-vm && cargo test
```

Build the local JNI library:

```bash
cd native/ckl-vm && cargo build
```

Run Kotlin ABI and runner seam tests:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.BytecodeAbiTest --tests ru.lazyhat.compukterkraft.lang.runtime.VmRunnerSelectionTest
```

Run the optional Kotlin JNI smoke test with the local debug library:

```bash
./gradlew :compiler:test \
  --tests ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmRunnerJniTest \
  -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so
```

Run a Minecraft dev client with the Rust VM option enabled:

```bash
./gradlew :v1_21_1-neoforge:runClientRust
```

Run a Minecraft dev server with the Rust VM option enabled:

```bash
./gradlew :v1_21_1-neoforge:runServerRust
```

The Rust run tasks are `runClientRust`, `runClient2Rust`, `runClient3Rust`, and `runServerRust`. Each depends on `buildRustVmNativeLibrary`, which builds `native/ckl-vm/target/debug/libckl_vm.so` before launching Minecraft and passes the required `ckl.vm.runner=rust` and `ckl.vm.native.library=...` JVM properties.

The native runner is disabled unless both `-Dckl.vm.runner=rust` and `-Dckl.vm.native.library=/absolute/path/to/libckl_vm.so` are provided. The Kotlin VM remains the default runtime path.

## Interpretation notes

- High operation counts with low total nanos usually do not justify native code.
- High total nanos in small per-call operations suggests batching before native code.
- High tile payload bytes with high serialization nanos may justify native serialization or compression.
- High VM execution nanos plus many pause/yield signals points to VM interpreter work.
- High `host-calls:` counts point to chatty CKL-to-runtime builtins; prefer batching or moving work inside the VM before considering native code.
- High `host-calls:` nanos can include coroutine wait time for blocking APIs such as IPC reads or event polling. Treat these as latency/blocking signals first, then use JFR to confirm CPU cost.
- High `instructions:` nanos or counts identify interpreter opcode families to inspect with JFR before rewriting the VM.
- Compiler phase timings affect startup and IDE latency, not steady-state display FPS.
