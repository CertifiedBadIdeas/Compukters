# Profiling Compukter Kraft

Compukter Kraft profiling has two layers:

1. **In-code domain metrics** from runtime, display, and compiler collectors.
2. **External CPU/allocation profiling** through JFR or async-profiler.

The in-code metrics explain what the VM/display/compiler did. External profilers explain where CPU time and allocations were spent.

## Runtime/display profiling workload

Runtime VM profiling now records the Rust image runtime path only. Use `profileRuntimeVmImage` to build the native library, run the profiling workload, and write `build/reports/profiling/runtime-vm-image.tsv`.

```bash
./gradlew profileRuntimeVmImage
```

The task builds the local Rust JNI library and runs the terminal profiling workloads through the same image runtime path used by computer programs. It writes the raw profile to:

```text
modules/v1_21_1/v1_21_1-neoforge/build/reports/profiling/runtime-vm-image.tsv
```

Use this raw profile for before/after comparisons across commits. It includes per-workload runtime, display, compiler, host-call, and held-Enter backlog metrics. The task runs a short warm-up before collecting measurements, but the workloads are still integration diagnostics rather than strict microbenchmarks.

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

Run Kotlin CKIM/image backend and image runner seam tests:

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --tests '*CkVmImageComputerProgramTest'
```

Run optional image JNI smoke tests with the local debug library:

```bash
./gradlew buildRustVmNativeLibrary :compiler:test \
  --tests '*NativeImageVmRunnerJniTest' \
  --tests '*NativeImageVmBindingsJniTest' \
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

The Rust run tasks are `runClientRust`, `runClient2Rust`, `runClient3Rust`, and `runServerRust`. Each depends on `buildRustVmNativeLibrary`, which builds `native/ckl-vm/target/debug/libckl_vm.so` before launching Minecraft and passes `ckl.vm.native.library=...` to the JVM.

Runtime execution in this branch is Rust-image based. Kotlin still owns frontend analysis and temporary image lowering scaffolding, but JVM bytecode VM execution is no longer maintained here.

## Interpretation notes

- High operation counts with low total nanos usually do not justify native code.
- High total nanos in small per-call operations suggests batching before native code.
- High tile payload bytes with high serialization nanos may justify native serialization or compression.
- High VM execution nanos plus many pause/yield signals points to VM interpreter work.
- High `host-calls:` counts point to chatty CKL-to-runtime builtins; prefer batching or moving work inside the VM before considering native code.
- High `host-calls:` nanos can include coroutine wait time for blocking APIs such as IPC reads or event polling. Treat these as latency/blocking signals first, then use JFR to confirm CPU cost.
- High `instructions:` nanos or counts identify interpreter opcode families to inspect with JFR before rewriting the VM.
- Compiler phase timings affect startup and IDE latency, not steady-state display FPS.
