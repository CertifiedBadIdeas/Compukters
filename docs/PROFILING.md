# Profiling Compukter Kraft

Compukter Kraft profiling has two layers:

1. **In-code domain metrics** from runtime, display, and compiler collectors.
2. **External CPU/allocation profiling** through JFR or async-profiler.

The in-code metrics explain what the VM/display/compiler did. External profilers explain where CPU time and allocations were spent.

## Kraft16 VM microbenchmarks

The native Kraft16 VM has a dependency-free microbenchmark example for local
before/after comparisons:

```bash
cd rust/host/k16-vm
cargo run --release --example vm_microbenchmarks -- 100000 5
```

Each workload prints a `k16` row and a `native-rust` row. The native row runs an
equivalent host Rust algorithm and gives local diagnostic context for VM
overhead. `vs_native` and `native_pct` compare each row's best sample against
the native Rust row for the same workload. They are not portable performance
claims or CI budgets.

The current tracked local baseline is documented in
[`docs/benchmarks/k16-vm-baseline-2026-05-29.md`](benchmarks/k16-vm-baseline-2026-05-29.md).
These numbers are diagnostic points for optimization work, not CI performance
budgets.

The current per-commit snapshot lives at:

```text
docs/benchmarks/k16-vm-current.txt
```

It is plaintext and keeps the same table shape as the terminal output, with a
small metadata header. Refresh it manually with:

```bash
scripts/record-k16-vm-benchmark-current.sh
```

Optional arguments override the default `100000 5` benchmark size:

```bash
scripts/record-k16-vm-benchmark-current.sh 1000 3
```

There is no commit hook for this file. Benchmark snapshots are machine-local
diagnostics and should be refreshed only when the commit is intentionally about
benchmark state.

There is no separate append-only benchmark history file. Git is the history:

```bash
git diff docs/benchmarks/k16-vm-current.txt
git show <commit>:docs/benchmarks/k16-vm-current.txt
```

## K16 Runtime Wait Profiling

Use `profileK16RuntimeWait` to build the local debug K16 JNI library, boot the bundled K16 BIOS/storage resources through
`K16RuntimeDevice`, drive a small timer/input wait workload, and print the runtime profiling summary to the terminal:

```bash
./gradlew-sandbox-dev --parallel profileK16RuntimeWait -Pk16BuildJobs=$(nproc)
```

The task runs only `K16RuntimeWaitProfilingTest` and keeps normal gameplay on the default no-op collector. The profiling
path injects `RecordingRuntimeMetricsCollector` only for the report test.

The output is grouped into the existing multi-line runtime summary. The K16 execution line shows how many native
`tickUntilSignal` slices actually ran and which signal ended those slices:

```text
k16Execution: slices=..., time=..., haltSignals=..., waitSignals=..., yieldSignals=..., pauseSignals=...
```

- `slices` counts host calls into the K16 runtime loop.
- `time` is wall-clock time spent inside `tickUntilSignal`.
- `haltSignals`, `waitSignals`, `yieldSignals`, and `pauseSignals` classify the signal that returned control to the
  worker.

The K16 output line shows host-side output cache refresh work:

```text
k16Output: refreshes=..., time=..., serialSnapshotBytes=..., gpuBatches=..., gpuBytes=..., gpuFrames=...
```

- `refreshes` counts worker-side cache syncs after startup, runtime slices, and explicit output clears.
- `serialSnapshotBytes` sums the serial output snapshot sizes observed during those refreshes.
- `gpuBatches`, `gpuBytes`, and `gpuFrames` count non-empty GPU frame drain batches, raw frame payload bytes, and decoded
  display frames.

The K16 bus and device lines show the latest cumulative low-level Rust VM counters fetched through one JNI snapshot
during the worker cache refresh path:

```text
k16Bus: ramLoads=..., ramStores=..., ramBytesRead=..., ramBytesWritten=..., mmioLoads=..., mmioStores=..., mmioBytesRead=..., mmioBytesWritten=...
k16Devices: mapped=..., loads=..., stores=..., bytesRead=..., bytesWritten=...
k16Storage0: reads=..., writes=..., flushes=..., bytesRead=..., bytesWritten=..., failed=...
  device[...]: base=..., size=..., loads=..., stores=..., bytesRead=..., bytesWritten=...
```

- `k16Bus` separates regular RAM traffic from MMIO traffic.
- `k16Devices` aggregates mapped MMIO device traffic and then lists per-device counters by hardware device id.
- `k16Storage0` counts successful storage0 block read, block write, and flush commands, successful transfer bytes, and
  commands that reached the controller but completed with an error status.
- These counters are cumulative inside the Rust VM; the Kotlin profiling collector stores the latest snapshot instead of
  summing repeated snapshots.

The K16 wait line is the main scheduling signal:

```text
k16Wait: entries=..., timerWakeups=..., inputWakeups=..., idleSkips=...
```

- `entries` counts guest `WAIT` exits observed by the K16 runtime worker.
- `timerWakeups` counts waits resumed by a later server tick/game tick.
- `inputWakeups` counts waits resumed by queued input.
- `idleSkips` counts worker tick commands that found the guest already waiting with no new event to process.

These numbers are local diagnostics, not historical benchmark artifacts. Git remains the history for committed benchmark
snapshots such as `docs/benchmarks/k16-vm-current.txt`.

## K16 VM Stats API

The Rust VM owns low-level traffic counters. `MachineBus::stats_snapshot()` reports RAM and MMIO loads/stores plus byte
counts, and `K16ComputerHandle::stats_snapshot()` wraps those counters with named computer devices such as `debug`,
`control`, `gpu0`, and `storage0`.

Use the Rust-only VM stats report when the question is about VM work rather than host integration overhead:

```bash
cd rust/host/k16-vm
cargo run --release --example vm_stats_report -- 100000
```

The report runs the existing K16 benchmark workloads and prints deterministic counters such as CPU steps, RAM traffic,
MMIO traffic, and mapped MMIO device count. It intentionally does not include JNI, Kotlin runtime scheduling, display
cache refresh, or Minecraft-side timing.

This is the stable in-process observability boundary. JNI exposes the same low-level Rust VM counters as a single
aggregated `K16ComputerEndpoint.statsSnapshot()` call for Kotlin/Minecraft-side reports. Hot RAM/MMIO/device operations
must not call into Kotlin.

## JFR

JFR is available with the JDK and is the first external profiler to try.

```bash
./gradlew-sandbox-dev --parallel \
  -Pk16BuildJobs=$(nproc) \
  -Dorg.gradle.jvmargs="-XX:StartFlightRecording=filename=build/reports/profiling/runtime-display.jfr,settings=profile,dumponexit=true" \
  profileK16RuntimeWait
```

If Gradle daemon JVM arguments are already configured locally, stop daemons before rerunning:

```bash
./gradlew --stop
```

Open the `.jfr` file in JDK Mission Control or another JFR viewer. Compare CPU and allocation hotspots with the in-code summary printed by the workload.

## async-profiler

async-profiler is optional and depends on local OS/tooling setup.

Use it when JFR shows a broad hotspot and you need flamegraphs. Attach to the Gradle test JVM that is running
`profileK16RuntimeWait`, collect CPU or allocation data, then compare the flamegraph with the in-code summaries.

## Native candidate heuristic

A path is a plausible JNI/Rust candidate only if all are true:

1. It is a measured CPU or allocation hotspot.
2. It can be invoked as a coarse batch.
3. Inputs and outputs can be represented as primitive buffers or compact handles.
4. It does not require frequent native-to-Java callbacks.
5. Expected savings exceed native packaging and crash-risk costs.

Likely candidates after measurement:

- full CKL VM slice runner;
- batched gpu0 pixel operations;
- tile serialization or compression.

Unlikely candidates:

- individual `setPixel` calls;
- individual glyph calls;
- host filesystem calls;
- event queue bookkeeping;
- Minecraft UI glue.

## K16 VM local checks

The K16 VM crate owns the native runtime and JNI library used by dev runs.
For a code-level map of the Rust VM execution path, see
[`k16-vm-code-flow.md`](k16-vm-code-flow.md).

Run Rust crate tests:

```bash
cd rust/host/k16-vm && cargo test
```

Build the local JNI library:

```bash
./gradlew-sandbox-dev --parallel :v1_21_1-neoforge:buildK16VmNativeLibrary -Pk16BuildJobs=$(nproc)
```

Run native-runtime JNI boundary tests:

```bash
./gradlew-sandbox-dev --parallel :native-runtime:test \
  -Pk16BuildJobs=$(nproc) \
  -Dk16.vm.native.library=$PWD/.toolchain/build/cargo/k16-vm/debug/libk16_vm.so
```

Run a Minecraft dev client with the K16 VM native library:

```bash
./gradlew :v1_21_1-neoforge:runClient
```

Run a Minecraft dev server with the K16 VM native library:

```bash
./gradlew :v1_21_1-neoforge:runServer
```

The Loom run tasks depend on `buildK16VmNativeLibrary`, which builds
`.toolchain/build/cargo/k16-vm/debug/libk16_vm.so` before launching Minecraft and
passes `k16.vm.native.library=...` to the JVM.

## Interpretation notes

- High operation counts with low total nanos usually do not justify native code.
- High total nanos in small per-call operations suggests batching before native code.
- High tile payload bytes with high serialization nanos may justify native serialization or compression.
- High VM execution nanos plus many pause/yield signals points to VM interpreter work.
- K16 guest/host interaction is MMIO-driven; high device costs usually point to a specific MMIO device implementation or host-side frame/storage plumbing.
- High K16 benchmark `vs_native` ratios identify interpreter overhead to inspect before rewriting guest algorithms.
- High `instructions:` nanos or counts in older profiling reports identify interpreter opcode families to inspect with JFR before rewriting the VM.
- Compiler phase timings affect startup and IDE latency, not steady-state display FPS.
