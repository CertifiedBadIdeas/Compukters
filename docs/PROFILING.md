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

Install the repository pre-commit hook to refresh and stage the current snapshot
automatically before each commit:

```bash
scripts/install-git-hooks.sh
```

The hook refuses unrelated unstaged or untracked files so the snapshot reflects
the tree being committed. `docs/benchmarks/k16-vm-current.txt` is the only path
the hook may rewrite while committing.

There is no separate append-only benchmark history file. Git is the history:

```bash
git diff docs/benchmarks/k16-vm-current.txt
git show <commit>:docs/benchmarks/k16-vm-current.txt
```

## K16 Runtime Wait Profiling

Use `profileK16RuntimeWait` to build the local debug K16 JNI library, boot the bundled K16 BIOS/storage resources through
`K16RuntimeDevice`, drive a small timer/input wait workload, and print the runtime profiling summary to the terminal:

```bash
./gradlew-sandbox profileK16RuntimeWait
```

The task runs only `K16RuntimeWaitProfilingTest` and keeps normal gameplay on the default no-op collector. The profiling
path injects `RecordingRuntimeMetricsCollector` only for the report test.

The output is grouped into the existing multi-line runtime summary. The K16 wait line is the main scheduling signal:

```text
k16Wait: entries=..., timerWakeups=..., inputWakeups=..., idleSkips=...
```

- `entries` counts guest `WAIT` exits observed by the K16 runtime worker.
- `timerWakeups` counts waits resumed by a later server tick/game tick.
- `inputWakeups` counts waits resumed by queued input.
- `idleSkips` counts worker tick commands that found the guest already waiting with no new event to process.

These numbers are local diagnostics, not historical benchmark artifacts. Git remains the history for committed benchmark
snapshots such as `docs/benchmarks/k16-vm-current.txt`.

## JFR

JFR is available with the JDK and is the first external profiler to try.

```bash
./gradlew-sandbox \
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
./gradlew-sandbox :v1_21_1-neoforge:buildK16VmNativeLibrary
```

Run native-runtime JNI boundary tests:

```bash
./gradlew-sandbox :native-runtime:test \
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
