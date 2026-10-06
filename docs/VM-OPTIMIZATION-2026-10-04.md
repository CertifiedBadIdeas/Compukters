---
layout: default
title: Production-path VM optimization (2026-10-04)
section: contributors
---

# Production-path VM optimization

> Issue: [#689](https://github.com/CertifiedBadIdeas/Compukters/issues/689)

The VM now resolves each global managed type ID to its module/type key at admission and stores the reverse mapping in the shared immutable execution image. Managed-reference type lookup uses an indexed read instead of repeatedly searching module offsets. Invalid IDs still fail and module identity is retained across empty modules.

## Measurement path

The opt-in `guest-benchmark` binary compiles the existing interpreter and verifier source modules without `cfg(test)` register-initialization instrumentation and invokes the existing crate-private untraced Session path. It changes no public API, artifact encoding or exported native ABI. Its manifest inputs are the same generated Guest artifacts used in the earlier [Ryzen measurements]({{ '/VM-PERFORMANCE-2026-10-04/' | relative_url }}). It checks ready/checksum output, normal termination, and all eight construction/hot work counters; a traced control must agree with the untraced path.

The measurements below compare the same binary harness and artifacts before/after the type-key optimization, rather than comparing diagnostic traced timing against untraced execution. This is standalone interpreter evidence; it excludes Minecraft lifecycle/scheduling and JVM/native transport costs.

## Paired results

Ryzen 9 9950X3D, Linux 7.2.8-zen1-2-zen, Rust 1.99.0 / LLVM 23.1.1. Native release profile uses thin LTO, one codegen unit and debug line tables (`CARGO_PROFILE_RELEASE_DEBUG=1`). The governor remains `powersave`, without fixed affinity/frequency; unrelated desktop/agent activity was uncontrolled. No own compilation or profiling overlapped these timing runs.

Three paired rounds each use seven fresh-session samples per case, reversing baseline/candidate order in the second round. Case order also reverses inside each invocation. Each invocation warms up and checks a traced correctness control before timing. Verification, admission, start and teardown are outside timers; final scans/output text copies are included. Medians below combine 21 samples per version. The archive retains per-round medians, min/max and all raw samples.

| Workload | Before, ms | After, ms | Less elapsed time |
| --- | ---: | ---: | ---: |
| `fold-256-300` | 82.50 | 75.91 | 7.99% |
| `fold-prebuilt-256-300` | 84.75 | 77.82 | 8.18% |
| `fold-read-var-256-300` | 81.95 | 75.61 | 7.74% |
| `fold-ref-256-300` | 83.55 | 76.87 | 8.00% |
| `fold-val-256-300` | 81.83 | 75.39 | 7.87% |
| `fold-write-var-256-300` | 87.70 | 80.43 | 8.29% |
| `for-list-256-300` | 79.69 | 72.90 | 8.51% |
| `while-indexed-256-300` | 43.11 | 40.24 | 6.67% |
| `map-not-null-1024-100-fresh` | 271.42 | 248.13 | 8.58% |
| `map-not-null-1024-100-reuse-list` | 285.68 | 260.72 | 8.74% |
| `map-not-null-1024-100-reuse-objects` | 301.17 | 274.47 | 8.87% |

All 11 cases improve in each paired round. Aggregate median elapsed time decreases by 6.67–8.87%; these local ratios are not portable guarantees. The first object-transformation round had higher baseline times, so its larger 13–15% ratios are not used as the aggregate claim. Every case retains identical fixed/dynamic/maintenance units, block counts, executed/retired instructions and host request/response counts across versions and rounds. There are 462 ordinary timed samples in this comparison.

Loops use a 16 KiB Guest heap for 256 elements × 300 summation rounds; transformations use 256 KiB for 1024 two-Int records × 100 rounds. No fallback heap was used. The optimized image adds eight bytes of immutable mapping payload per declared type, plus one boxed-slice field and native allocation metadata per image. Mutable per-session arenas and Guest heap budgets are unchanged. Admission pays for construction of this additional table; admission elapsed time was not isolated in this experiment.

[Comparison TSV]({{ '/benchmarks/issue-689-2026-10-04/comparison.tsv' | relative_url }}), [environment, commands and binary hashes]({{ '/benchmarks/issue-689-2026-10-04/environment.json' | relative_url }}), [loop samples]({{ '/benchmarks/issue-689-2026-10-04/transient-allocations-samples.tsv' | relative_url }}), [transformation samples]({{ '/benchmarks/issue-689-2026-10-04/collection-reuse-samples.tsv' | relative_url }}).

## CPU profiles

Separate profiles used `perf record -e cycles:u -F 499 --call-graph dwarf` and 99 samples of one selected case. These runs are excluded from elapsed-time comparisons. Profiles include verification, admission, warmup/traced control and final termination, so percentages are whole-process sampling evidence rather than exact hot-loop attribution.

Before optimization, `ExecutionImage::type_key` accounts for 4.48–6.12% of sampled cycles in iterator/fold/object-transform profiles. The fold profile reports 6.12%. Afterward this function is absent from the standalone-symbol report at the 1% threshold: lookup work is inlined as an indexed read, not eliminated entirely. Other measured costs, including register access and virtual dispatch, remain candidates for separate work.

[Indexed loop]({{ '/benchmarks/issue-689-2026-10-04/perf-while-indexed.txt' | relative_url }}), [iterator loop]({{ '/benchmarks/issue-689-2026-10-04/perf-for-list.txt' | relative_url }}), [fold before]({{ '/benchmarks/issue-689-2026-10-04/perf-fold-256.txt' | relative_url }}), [transformation]({{ '/benchmarks/issue-689-2026-10-04/perf-map-not-null-1024-100-fresh.txt' | relative_url }}), [fold after]({{ '/benchmarks/issue-689-2026-10-04/perf-after-fold.txt' | relative_url }}). Raw perf.data and frozen benchmark binaries remain under ignored `build/reports/benchmarks/issue-689/`. A register-helper inlining trial gave mixed screening results and was not retained.

## Reproduction and verification

Generate the unchanged input programs using the artifact-generation commands in the earlier Ryzen report. The opt-in Gradle entrypoint runs the eight production-path loop cases:

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew-sandbox-dev-parallel-summary benchmarkGuestProductionLoops
```

For paired or profiling runs, build and retain an independent binary for each VM revision:

```sh
CARGO_TARGET_DIR=.toolchain/build/cargo/guest-benchmark CARGO_PROFILE_RELEASE_DEBUG=1 \
  cargo build --manifest-path host/compukter-vm/Cargo.toml --release --locked --offline \
  --features guest-benchmark --bin guest-benchmark
```

Invoke the retained binary with `ARTIFACT_DIR REPORT_DIR 7 HEAP_BYTES untraced`. Use `16384` for `transient-allocations` or `262144` for `collection-reuse`; an optional final ID prefix selects a profile case. Match the three round orders and exact arguments in environment.json. Baseline core is VM `8416b99`; the benchmark-only VM commit is `7cd0e87`; optimized VM is `7fea2f27691bc21ffae3eadac787e039d00abdbc`.

Verification passed 519 native crate tests (430 unit tests plus integrations/doc tests), clippy with warnings denied, formatting, build-script tests and the Gradle benchmark entrypoint. Six freshly executed Kotlin-to-VM scenarios passed: function values, generic interfaces, mapNotNull, mutable lists, tasks and exceptions. New native tests cover empty-module type-ID mapping, admitted ID round trips and unknown-ID rejection. [Verification summary]({{ '/benchmarks/issue-689-2026-10-04/verification.txt' | relative_url }}).

This is focused development verification. No full-checkout/release gate or fresh Minecraft server profile was run. The change was integrated into `dev` on 2026-10-06 together with the current Runtime ABI 1.13 implementation. Integration passed 613 Rust workspace tests, formatting, Clippy with warnings denied, six Kotlin-to-VM scenarios, runtime-host integration, and FFM/JNI verification. The benchmark executable also passed a traced/untraced correctness smoke check. These integration checks do not replace the dated timing measurements above.
