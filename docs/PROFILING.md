# Profiling Compukter Kraft

Compukter Kraft profiling has two layers:

1. **In-code domain metrics** from runtime, display, and compiler collectors.
2. **External CPU/allocation profiling** through JFR or async-profiler.

The in-code metrics explain what the VM/display/compiler did. External profilers explain where CPU time and allocations were spent.

## Kraft16 VM microbenchmarks

The native Kraft16 VM has a dependency-free microbenchmark example for local
before/after comparisons:

```bash
cd host/k16-vm
cargo run --release --example vm_microbenchmarks -- 100000 5
```

Each workload prints `k16`, `k16-cached`, and `native-rust` rows. The `k16-cached`
row runs through the benchmark-local cached decoder proof path; it is diagnostic
only and does not enable decode caching for normal VM execution. The native row
runs an equivalent host Rust algorithm and gives local diagnostic context for VM
overhead. `vs_native` and `native_pct` compare each row's best sample against the
native Rust row for the same workload. They are not portable performance claims
or CI budgets.

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
./gradlew-sandbox-dev-parallel profileK16RuntimeWait
```

Use `profileK16RuntimeTextIo` for a terminal-focused workload that waits for the bundled shell, sends one command as
individual character input, sends another command as paste input, compares a normal `ls /bin` with a scroll-provoking
`ls /bin`, and prints the same runtime profiling summary plus per-phase `k16Phase` delta lines:

```bash
./gradlew-sandbox-dev-parallel profileK16RuntimeTextIo
```

The `k16Phase` lines split selected text-I/O scenarios into named checkpoints such as `bios.splash.visible`,
`bios.splash.wait`, `shell.prompt.after_splash`, `*.input`, `*.visible`, and `*.idle`. Each line reports elapsed wall time
and deltas between two runtime metric snapshots:

```text
k16Phase: name=ls:/bin.visible, elapsed=..., slices=..., runTime=..., inputBytes=..., gpuFrameBytes=..., displayFrames=..., displayBytes=..., storageReadCommands=..., storageWriteCommands=..., storageMediaReadBlocks=..., storageMediaWriteBlocks=..., storageUniqueReadBlocks=..., storageRepeatedReadBlocks=..., storagePartitionTableReadBlocks=..., storageBootMetadataReadBlocks=..., storageBootDataReadBlocks=..., storageRootMetadataReadBlocks=..., storageRootDataReadBlocks=..., storageUnknownReadBlocks=..., storageBytesRead=..., pathLookups=..., inodeLoads=..., dirEntryScans=..., fileOpens=..., fileReads=..., statCalls=..., programLoadBytes=..., dynamicImportBytes=..., libraryLoadBytes=..., genericFileDataReadBlocks=..., genericFileDataReadBytes=..., readDirDataReadBlocks=..., readDirDataReadBytes=..., programDataReadBlocks=..., programDataReadBytes=..., dynamicImportDataReadBlocks=..., dynamicImportDataReadBytes=..., libraryDataReadBlocks=..., libraryDataReadBytes=...
```

Use these phase lines when deciding whether a slowdown is in command dispatch, command execution/storage reads, display
frame production, guest filesystem/path/stat work, or post-command idle work. The older `k16LsCommand*` aggregate lines
remain available for comparison with previous profiling runs.

The bundled BIOS intentionally shows a splash frame and waits for 20 game ticks before loading the bootloader.
`bios.splash.wait` isolates the pure wait portion before the release tick; bootloader/kernel/shell work after the splash
deadline is counted under `shell.prompt.after_splash`. Treat the splash wait as intentional startup latency, not as
bootloader/kernel/shell execution cost. Use `shell.prompt.after_splash` and later command-specific phases when comparing
real post-splash responsiveness. In startup phases, compare `programLoadBytes`, `dynamicImportBytes`, and
`libraryLoadBytes` against `storageBytesRead` to distinguish executable payload reads, dynamic import metadata reads, and
shared-library payload reads. Compare `storageUniqueReadBlocks` and `storageRepeatedReadBlocks` to identify whether
backend storage cost comes from new LBAs or repeated reads of LBAs that already missed the storage0 block cache earlier.
`storageReadCommands` and `storageWriteCommands` count guest-visible storage0 transfer commands. `storageMediaReadBlocks`
and `storageMediaWriteBlocks` count backend media blocks actually read or written by those commands. The
`storage*ReadBlocks` ownership counters split backend media reads into the K16PT table, BOOT K16FS metadata/data, ROOT
K16FS metadata/data, and unknown blocks.
The `*DataReadBlocks` and `*DataReadBytes` OS counters split guest K16FS data-block reads by loader/file category:
generic user file reads, directory listings, executable payload reads, dynamic import metadata reads, and shared-library
payload reads. These are attribution counters and do not issue extra storage0 media reads.

Use `profileK16ManyVmServerBudget` for a server-focused workload that boots several K16 runtime devices, measures idle
tick cost after all shells are waiting, then runs active production-path command scenarios. The printed lines include
both server-tick wall time and a `sync` time that waits for worker snapshots after the timed tick loop:

```bash
./gradlew-sandbox-dev-parallel profileK16ManyVmServerBudget
```

Scale the workload with Gradle properties:

```bash
./gradlew-sandbox-dev-parallel profileK16ManyVmServerBudget -Pk16ManyVmCount=100 -Pk16ManyVmIdleTicks=200
```

The tasks run only their dedicated profiling tests and keep normal gameplay on the default no-op collector. The profiling
path injects `RecordingRuntimeMetricsCollector` only for the report tests.

The many-VM boot report is split so the intentional BIOS splash does not hide the remaining boot work:

```text
k16ManyVmSplash: ...
k16ManyVmSplashWait: ...
k16ManyVmBootAfterSplash: ...
k16ManyVmBoot: ...
```

Use `k16ManyVmBootAfterSplash` for bootloader/kernel/shell startup cost after the 20-tick BIOS splash boundary. The
aggregate `k16ManyVmBoot` line remains useful for player-visible power-on latency.

After boot, the report separates idle cost from highload production paths:

```text
k16ManyVmIdle: ...
k16ManyVmCpuHighload: ...
k16ManyVmTextDisplayHighload: ...
k16ManyVmStorageHighload: ...
k16ManyVmOneActive: ...
```

- `k16ManyVmIdle` measures ordinary server ticks while all VMs are waiting at the shell prompt.
- `k16ManyVmCpuHighload` dispatches `ticks` to every VM and is the first place to look for interpreter, cached decode,
  and scheduler budget costs under active CPU work.
- `k16ManyVmTextDisplayHighload` dispatches `ls /bin` to every VM and is the first place to look for terminal text
  rendering, dirty display payloads, and frame drain costs.
- `k16ManyVmStorageHighload` dispatches `cat /etc/motd` to every VM and is the first place to look for storage0, K16FS,
  and host storage media costs.
- `k16ManyVmOneActive` keeps the older mixed workload where one VM is active while the rest stay idle.

The output is grouped into the existing multi-line runtime summary. The K16 execution line shows how many native
`tickUntilSignal` slices actually ran and which signal ended those slices:

```text
k16Execution: slices=..., time=..., haltSignals=..., waitSignals=..., yieldSignals=..., pauseSignals=...
```

- `slices` counts host calls into the K16 runtime loop.
- `time` is wall-clock time spent inside `tickUntilSignal`.
- `haltSignals`, `waitSignals`, `yieldSignals`, and `pauseSignals` classify the signal that returned control to the
  worker.

The K16 output lines show host-side output cache refresh work and split text output from display frame payloads:

```text
k16Output: refreshes=..., time=...
k16TextOutput: snapshots=..., snapshotBytes=...
k16DisplayFrames: batches=..., bytes=..., frames=...
k16TextInput: events=..., bytes=..., time=...
```

- `refreshes` counts worker-side cache syncs after startup, runtime slices, and explicit output clears.
- `time` is wall-clock time spent refreshing the host-side output caches.
- `k16TextOutput.snapshots` counts refreshes that observed a non-empty serial/stdout snapshot.
- `k16TextOutput.snapshotBytes` sums the serial/stdout snapshot sizes observed during refreshes.
- `k16DisplayFrames.batches`, `bytes`, and `frames` count non-empty GPU frame drain batches, raw frame payload bytes, and
  decoded display frames.
- `k16TextInput.events` counts accepted text input enqueue operations: character input, paste input, and legacy raw
  serial input.
- `k16TextInput.bytes` counts the text input bytes delivered by those operations.
- `k16TextInput.time` is wall-clock time spent pushing those bytes into the native K16 endpoint, excluding later guest
  execution after an input wakeup.

The K16 bus and device lines show the latest cumulative low-level Rust VM counters fetched through one JNI snapshot
during the worker cache refresh path:

```text
k16Bus: ramLoads=..., ramStores=..., ramBytesRead=..., ramBytesWritten=..., mmioLoads=..., mmioStores=..., mmioBytesRead=..., mmioBytesWritten=...
k16Devices: mapped=..., loads=..., stores=..., bytesRead=..., bytesWritten=...
k16Storage0: readCommands=..., writeCommands=..., flushes=..., bytesRead=..., bytesWritten=..., failed=..., mediaReadBlocks=..., mediaWriteBlocks=..., uniqueReadBlocks=..., repeatedReadBlocks=..., partitionTableReadBlocks=..., bootMetadataReadBlocks=..., bootDataReadBlocks=..., rootMetadataReadBlocks=..., rootDataReadBlocks=..., unknownReadBlocks=...
k16Os: pathLookups=..., inodeLoads=..., dirEntryScans=..., fileOpens=..., fileReads=..., statCalls=..., programLoadBytes=..., dynamicImportBytes=..., libraryLoadBytes=..., genericFileDataReadBlocks=..., genericFileDataReadBytes=..., readDirDataReadBlocks=..., readDirDataReadBytes=..., programDataReadBlocks=..., programDataReadBytes=..., dynamicImportDataReadBlocks=..., dynamicImportDataReadBytes=..., libraryDataReadBlocks=..., libraryDataReadBytes=...
  device[...]: base=..., size=..., loads=..., stores=..., bytesRead=..., bytesWritten=...
```

- `k16Bus` separates regular RAM traffic from MMIO traffic.
- `k16Devices` aggregates mapped MMIO device traffic and then lists per-device counters by hardware device id.
- `k16Storage0` counts successful storage0 backend media reads, block write commands, flush commands, successful backend
  transfer bytes, and commands that reached the controller but completed with an error status. `uniqueReadBlocks` counts
  first-time backend LBA reads, while `repeatedReadBlocks` counts backend LBA reads for LBAs that were already fetched
  earlier by the same storage device. `partitionTableReadBlocks`, `bootMetadataReadBlocks`, `bootDataReadBlocks`,
  `rootMetadataReadBlocks`, `rootDataReadBlocks`, and `unknownReadBlocks` attribute backend reads by the decoded K16PT
  and K16FS block ranges observed from already-read blocks. Read cache hits still complete the guest `READ_BLOCKS`
  command but do not increase `reads`, `bytesRead`, `uniqueReadBlocks`, `repeatedReadBlocks`, or ownership counters.
- `k16Os` is exported by KraftOS through a registered RAM counter block. `pathLookups` counts K16FS path resolver calls,
  `inodeLoads` counts inode table loads, `dirEntryScans` counts directory-entry slots scanned by lookup/listing,
  `fileOpens` counts kernel open attempts, `fileReads` counts kernel file read attempts, and `statCalls` counts kernel
  stat attempts. `programLoadBytes` counts loaded executable payload bytes, `dynamicImportBytes` counts loader metadata
  bytes such as dynamic headers, needed/import sections, shared-library headers, and export sections, and
  `libraryLoadBytes` counts loaded shared-library payload bytes. `genericFileDataReadBlocks`/`Bytes`,
  `readDirDataReadBlocks`/`Bytes`, `programDataReadBlocks`/`Bytes`, `dynamicImportDataReadBlocks`/`Bytes`, and
  `libraryDataReadBlocks`/`Bytes` attribute guest K16FS data-block reads to user file reads, directory listing,
  executable payload, dynamic import metadata, and shared-library payload categories.
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
cd host/k16-vm
cargo run --release --example vm_stats_report -- 100000
```

The report runs the existing K16 benchmark workloads and prints deterministic counters such as CPU steps, RAM traffic,
MMIO traffic, and mapped MMIO device count. It intentionally does not include JNI, Kotlin runtime scheduling, display
cache refresh, or Minecraft-side timing.

This is the stable in-process observability boundary. JNI exposes the same low-level Rust VM counters as a single
aggregated `K16ComputerEndpoint.statsSnapshot()` call for Kotlin/Minecraft-side reports. Hot RAM/MMIO/device operations
must not call into Kotlin.

Runtime profiling also folds selected device counters into the human-readable summary:

```text
k16Gpu: blits=..., blitPixels=..., blitBytes=..., presents=..., frames=..., tiles=..., frameBytes=...
```

- `blits`, `blitPixels`, and `blitBytes` count guest `gpu0` `BLIT_BUFFER` commands and their source payload.
- `presents` counts guest `PRESENT` commands.
- `frames`, `tiles`, and `frameBytes` count the dirty-tile display frames produced by VM-side `PRESENT` handling.

For terminal workloads, compare `k16TextOutput.snapshotBytes`, `k16Gpu.blitBytes`, and `k16Gpu.frameBytes` to estimate
how much text output expands into guest-side render traffic and host display payload.

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
cd host/k16-vm && cargo test
```

Build the local JNI library:

```bash
./gradlew-sandbox-dev-parallel :v1_21_1-neoforge:buildK16VmNativeLibrary
```

Run native-runtime JNI boundary tests:

```bash
./gradlew-sandbox-dev-parallel :native-runtime:test \
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
