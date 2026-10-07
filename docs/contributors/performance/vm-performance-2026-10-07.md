---
layout: default
title: Runtime 0.20.0 VM baseline (2026-10-07)
section: contributors
permalink: /VM-PERFORMANCE-2026-10-07/
---

# Runtime 0.20.0 VM baseline

Fresh local measurements on the Ryzen 9 9950X3D after integration of the earlier VM optimizations and subsequent compiler/runtime changes. The current compiler regenerated all eleven Guest programs. This task changes no VM, compiler or Guest library behavior.

## Revisions and environment

- Compukters: `dadd76c72154f6f725295519da66eaf21ac72bf8`.
- Compukter VM: `18103f01963874510fa25d4396a2dc3e7ee47640` (Runtime 0.20.0).
- AMD Ryzen 9 9950X3D, 16 cores / 32 logical CPUs; approximately 30.9 GiB physical RAM.
- Linux 7.2.8-zen1-2-zen, rustc 1.99.0 / LLVM 23.1.1; OpenJDK 25.0.4.1 for compilation.
- Release build uses thin LTO, one codegen unit and `CARGO_PROFILE_RELEASE_DEBUG=1`.
- Governor `powersave`, boost enabled. No explicit CPU affinity or fixed clock.
- Desktop and other agent activity were uncontrolled. Memory, swap, CPU/memory pressure and host load are recorded before/after each invocation. Our compilation and profiling did not overlap timing.

[Environment, timestamps, exact commands and binary hash]({{ '/benchmarks/runtime-0.20.0-2026-10-07/environment.json' | relative_url }}).

## Measurement path

The opt-in `guest-benchmark` compiles the production interpreter source modules without `cfg(test)` register instrumentation and runs `Session::admit_untraced`. Every invocation verifies the artifacts, warms each case, checks a traced control against untraced construction/hot work counters, and checks ready output, expected checksum and normal termination. Repeated fresh sessions must have identical deterministic work counters.

Three invocations of seven samples per case give 21 samples each and 231 total timed samples. Suite order reverses in round two; case order reverses on alternate samples within each invocation. Verification, admission, session start and teardown are outside timers. Construction measures execution up to ready; the hot operation includes final scans and output copying.

Loops sum a 256-element list for 300 rounds, with a 16 KiB Guest heap and checksum 86,957,440. Transformations process 1024 two-Int records for 100 rounds, with a 256 KiB Guest heap and checksum 160,459,776. No fallback heap was used.

## Results

| Workload | Construction median, ms | Hot median, ms | Hot min–max, ms | Hot retired instructions | Maintenance units |
| --- | ---: | ---: | ---: | ---: | ---: |
| `fold-256-300` | 0.335 | 73.31 | 72.36–75.00 | 4,937,332 | 0 |
| `fold-prebuilt-256-300` | 0.337 | 74.30 | 73.74–76.11 | 4,630,732 | 0 |
| `fold-read-var-256-300` | 0.333 | 72.54 | 71.57–74.82 | 4,861,132 | 0 |
| `fold-ref-256-300` | 0.336 | 73.80 | 72.98–77.29 | 4,937,332 | 0 |
| `fold-val-256-300` | 0.335 | 72.56 | 71.85–75.70 | 4,861,132 | 0 |
| `fold-write-var-256-300` | 0.335 | 76.79 | 75.82–79.08 | 4,714,732 | 0 |
| `for-list-256-300` | 0.332 | 69.85 | 69.03–70.54 | 4,474,432 | 0 |
| `while-indexed-256-300` | 0.333 | 41.50 | 41.16–42.76 | 2,779,732 | 0 |
| `map-not-null-1024-100-fresh` | 1.493 | 243.87 | 241.99–248.82 | 15,372,745 | 82,617 |
| `map-not-null-1024-100-reuse-list` | 1.502 | 253.83 | 252.21–256.78 | 16,090,765 | 55,913 |
| `map-not-null-1024-100-reuse-objects` | 2.232 | 266.55 | 265.35–273.78 | 17,268,672 | 0 |

All cases use identical fixed/dynamic/maintenance units, block counts, executed/retired instructions and request/response counts across all three invocations. This demonstrates repeatable semantic work, while elapsed-time variability remains visible in the raw data.

[Aggregate measurements]({{ '/benchmarks/runtime-0.20.0-2026-10-07/measurements.tsv' | relative_url }}), [loop samples]({{ '/benchmarks/runtime-0.20.0-2026-10-07/transient-allocations-samples.tsv' | relative_url }}), [transformation samples]({{ '/benchmarks/runtime-0.20.0-2026-10-07/collection-reuse-samples.tsv' | relative_url }}). Manifests, generated Guest sources and artifact SHA-256 hashes are retained in the same archive. Archived source text removes trailing spaces/tabs only; source-sha256.tsv records both original and archived hashes.

## Historical context and limits

The [October 4 dispatch report]({{ '/VM-DISPATCH-2026-10-04/' | relative_url }}) used Runtime 0.18.2 and older compiled artifacts. Compiler output and library/runtime revisions changed, including the compact frame representation. These new timings establish a baseline for the recorded current revisions; differences from October 4 cannot be attributed to one isolated VM change or CPU conditions.

This is native interpreter evidence and excludes Minecraft lifecycle/scheduling and JVM/native transport costs. It does not measure admission latency, GC pause distribution or maximum server fleet capacity. A profile and matched before/after trials are still needed before selecting the next optimization.

## Reproduction and verification

Generate the artifacts from the recorded parent revision:

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew-sandbox-dev-parallel-summary :compiler-k2:generateTransientAllocationBenchmarkArtifacts :compiler-k2:generateCollectionReuseBenchmarkArtifacts --offline --no-build-cache --max-workers=4
```

Build the VM binary from the recorded submodule revision:

```sh
CARGO_TARGET_DIR=.toolchain/build/cargo/guest-benchmark CARGO_PROFILE_RELEASE_DEBUG=1 cargo build --manifest-path host/compukter-vm/Cargo.toml --release --locked --offline --features guest-benchmark --bin guest-benchmark
```

Invoke the retained binary with `ARTIFACT_DIR REPORT_DIR 7 HEAP_BYTES untraced`. Use 16384 for transient-allocations and 262144 for collection-reuse. Repeat three rounds with suite order reversed in round two; exact absolute arguments and timestamps are in environment.json.

Both artifact-generation tests and all six benchmark invocations passed. The archive validator checked all 231 samples, 21 per case, raw/summary min/median/max and all eight hot counters. [Verification evidence]({{ '/benchmarks/runtime-0.20.0-2026-10-07/verification.txt' | relative_url }}). No new production code was written, so this task does not claim a new Rust unit/conformance run or full-checkout/release/Minecraft verification.
