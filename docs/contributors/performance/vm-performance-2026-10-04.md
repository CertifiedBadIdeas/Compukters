---
layout: default
title: Ryzen 9 9950X3D VM measurements (2026-10-04)
section: contributors
permalink: /VM-PERFORMANCE-2026-10-04/
---

# Ryzen 9 9950X3D VM measurements

Fresh local measurements taken on 2026-10-04 in a separate worktree. These use the existing benchmark workloads; no VM, compiler or Guest library behavior was changed.

## Revisions and machine

- Compukters: `d0229d56cf523fbbc722fdba7a335068315efc11`.
- Compukter VM: `8416b99a35947fbb5950ceaa1dafda0e807b4564` (Runtime 0.18.2).
- AMD Ryzen 9 9950X3D, 16 physical cores / 32 logical CPUs, approximately 30 GiB physical RAM.
- Linux 7.2.8-zen1-2-zen, rustc 1.99.0 / LLVM 23.1.1, OpenJDK 25.0.4.1 for artifact generation.
- CPU governor `powersave`; boost enabled. No fixed affinity or clock. Benchmarks execute one case at a time.
- The desktop, IDE and another coding agent were active. All compilation launched for this measurement finished before timing, but unrelated CPU activity was not controlled. This is an exploratory local baseline, not an isolated hardware limit.

[Environment, exact command arguments, stage times and sampled host load]({{ '/benchmarks/ryzen-9950x3d-2026-10-04/environment.json' | relative_url }}).

## Native interpreter

Seven independent invocations per benchmark, with native benchmark order reversed on alternate rounds. Each interpreter invocation warms up for 100 slices and measures 1000 slices with a 4096-unit budget. Verification, admission, start and output formatting are outside the timers. The native crate release profile uses one codegen unit and thin LTO.

`ComputerMachine` uses the untraced execution path. The traced path includes diagnostic trace hashing. The following rates are VM bytecode instructions, not Kotlin statements or host CPU instructions. These tiny scalar loops have no managed allocation or external I/O.

| Workload | Untraced median, M instructions/s | Untraced min–max | Traced median, M instructions/s |
| --- | ---: | ---: | ---: |
| `hot_integer` | 126.70 | 124.07–128.08 | 29.07 |
| `mixed_branch_switch` | 145.51 | 141.40–148.00 | 16.12 |
| `nested_direct_calls` | 74.01 | 71.78–74.99 | 18.45 |
| `empty_quota_loop` | 265.64 | 253.88–267.69 | 36.71 |

These native rates come from Rust unit fixtures and include `cfg(test)` register-initialization instrumentation. For the later standalone interpreter measurements without that instrumentation, see [the production-path optimization report]({{ '/VM-OPTIMIZATION-2026-10-04/' | relative_url }}).

### Host exchange and managed storage

These also use seven native invocations. Request/resume is a traced Rust Session benchmark; it excludes JNI, FFM, JVM scheduling and Minecraft. Allocator rates measure pairs of reserve/commit/free operations. Field/array/string round trips include construction, start and execution of a fresh VM, so they do not isolate field or string instruction latency.

| Workload | Median rate per second | Unit |
| --- | ---: | --- |
| `request_resume` | 5,458,119 | pairs/s |
| `allocate_free_32` | 28,938,392 | iterations/s |
| `allocate_free_64` | 29,063,634 | iterations/s |
| `allocate_free_256` | 28,788,113 | iterations/s |
| `fragment_coalesce` | 6,549,658 | iterations/s |
| `gc_graph` | 112,452,131 | maintenance_units/s |
| `inherited_field_roundtrip` | 151,029 | iterations/s |
| `reference_array_roundtrip` | 147,359 | iterations/s |
| `compact_string_concat_equals` | 141,800 | iterations/s |
| `idle_instance_admission` | 1,322,599 | iterations/s |

The minimal idle fixture reserves 5314 mutable bytes per instance, including a 32-byte Guest heap arena. Its depth-64 fixture reserves 13,881 bytes. Shared execution-image storage and native allocator metadata are excluded; neither number describes a complete in-world computer. The GC graph fixture takes exactly 22 maintenance actions per cycle and uses one action per slice; these counters do not measure milliseconds of worst-case pause for arbitrary heaps.

[Native summary]({{ '/benchmarks/ryzen-9950x3d-2026-10-04/native-summary.tsv' | relative_url }}), [all native timing samples]({{ '/benchmarks/ryzen-9950x3d-2026-10-04/native-samples.tsv' | relative_url }}), [managed-storage sample log]({{ '/benchmarks/ryzen-9950x3d-2026-10-04/native-managed_heap_performance-1.log' | relative_url }}).

## Compiled Guest Kotlin workloads

The production K2 compiler generated 41 ordinary verified artifacts: 30 numeric-collection cases, eight loop/closure cases and three collection-reuse cases. Each case has one warmup and seven fresh-session timing samples, with case order reversed on alternate rounds, followed by a separate heap-pressure run. Every successful full execution checks its checksum and normal termination; harnesses also check agreement of metered hot work across samples.

These standalone harnesses use their own Cargo default release profile and `Session::admit`, which enables diagnostic trace hashing. Their elapsed times therefore include tracing and cannot be treated as untraced production-computer times. Compilation, artifact verification, admission and teardown are outside timing. Operation timing includes final scans and output request/text copying.

Measurement artifacts remove the compiler manifest admission floor and are reverified normally. Minimum heap means an empirically checked arena budget, not RSS or exact live payload. Fixed-heap cases do not search for a minimum. Work units measure VM metering; they are not CPU cycles or allocation counts.

### Numeric collections

Each operation performs eight passes over 4096 elements, starting at 1000. Scalar stores Int values in an IntArray; Boxed stores Int boxes in an Any list; Bridge reads scalar storage through a List<Any> view. This compares these concrete implementations, not entire generic compilation strategies. Timing uses a 16 MiB heap; pressure uses 256 KiB.

| Operation, 4096 elements | Scalar median, ms | Boxed median, ms | Bridge median, ms |
| --- | ---: | ---: | ---: |
| indexed | 105.86 | 113.31 | 152.91 |
| iteration | 205.19 | 222.22 | 288.78 |
| update | 244.33 | 288.56 | 298.31 |
| pipeline | 1199.85 | 1328.05 | 1446.23 |
| search | 333.03 | 481.36 | 713.82 |

Minimum construction heap at 4096 elements is 16,464 bytes for Scalar/Bridge and 82,000 bytes for Boxed (4.98×). All 30 pressure executions finish at 256 KiB, including the boxed 4096-element pipeline. This differs from the September archive; compiler/library/runtime revisions also differ, so it is not evidence of a CPU-only speedup.

[Measurements]({{ '/benchmarks/ryzen-9950x3d-2026-10-04/collections/measurements.tsv' | relative_url }}), [210 timing samples]({{ '/benchmarks/ryzen-9950x3d-2026-10-04/collections/samples.tsv' | relative_url }}).

### Loops and captured variables

Each program sums the same 256-element list for 300 rounds and checks checksum 86,957,440. Timing and pressure use a 16 KiB heap.

| Workload | Median operation, ms | Hot instructions | Hot maintenance units |
| --- | ---: | ---: | ---: |
| `while-indexed` | 263.48 | 2,702,375 | 0 |
| `for-list` | 502.81 | 4,474,175 | 0 |
| `fold` | 520.38 | 4,937,075 | 0 |
| `fold-val` | 517.79 | 4,860,875 | 0 |
| `fold-read-var` | 518.31 | 4,860,875 | 0 |
| `fold-write-var` | 628.25 | 4,714,475 | 0 |
| `fold-ref` | 556.79 | 4,937,075 | 0 |
| `fold-prebuilt` | 596.16 | 4,630,475 | 0 |

All eight cases and all 56 timed samples finish at the requested 16 KiB budget without timing fallback. Read-only captured var and captured val retain identical deterministic hot work; their elapsed-time difference is sample noise evidence, not a difference in emitted work.

[Measurements]({{ '/benchmarks/ryzen-9950x3d-2026-10-04/transient-allocations/measurements.tsv' | relative_url }}), [56 timing samples]({{ '/benchmarks/ryzen-9950x3d-2026-10-04/transient-allocations/samples.tsv' | relative_url }}).

### Repeated collection reuse

Each case transforms the same 1024 two-Int input records for 100 rounds, retaining 512 results per round. Timing and pressure use 256 KiB. Fresh and list reuse construct 51,200 result records during operations; object reuse mutates an explicit prebuilt pool and changes retained-reference semantics.

| Variant | Median operation, ms | Hot instructions | Hot maintenance units |
| --- | ---: | ---: | ---: |
| `fresh` | 2164.09 | 15,321,345 | 82,617 |
| `reuse-list` | 2298.74 | 16,090,765 | 55,913 |
| `reuse-objects` | 2644.87 | 17,268,672 | 0 |

All three pressure executions and all 21 timed samples finish at 256 KiB without fallback. Pooling reduces allocation/maintenance work but must be judged alongside instruction counts and operation time, not assumed to improve CPU throughput.

[Measurements]({{ '/benchmarks/ryzen-9950x3d-2026-10-04/collection-reuse/measurements.tsv' | relative_url }}), [21 timing samples]({{ '/benchmarks/ryzen-9950x3d-2026-10-04/collection-reuse/samples.tsv' | relative_url }}).

## Reproduction and verification scope

Generate artifacts separately so compilation does not overlap timed execution:

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew-sandbox-dev-parallel-summary \
  :compiler-k2:generateCollectionBenchmarkArtifacts \
  :compiler-k2:generateTransientAllocationBenchmarkArtifacts \
  :compiler-k2:generateCollectionReuseBenchmarkArtifacts --offline --max-workers=4
```

For the native suite, repeat each filter seven times with `cargo test --release --locked --offline --manifest-path host/compukter-vm/Cargo.toml --lib FILTER -- --ignored --nocapture --test-threads=1`: `tier0_computer_machine_performance_baseline`, `tier0_performance_baseline`, `host_session_performance_baseline`, and `managed_heap_performance`. Preserve a separate log per invocation.

For Guest harnesses, invoke `cargo test --release --locked --offline --manifest-path modules/common/compiler-artifact/src/test/rust/executable-conformance/Cargo.toml --test TEST -- ARTIFACT_DIR REPORT_DIR 7 EXTRA_ARGS`. Use:

| Suite | TEST | EXTRA_ARGS |
| --- | --- | --- |
| collections | collections_bench | none |
| transient-allocations | object_arrays_bench | `8 fixed-heap=16384` |
| collection-reuse | object_arrays_bench | `3 fixed-heap` |

Artifact directories are under `modules/common/compiler-k2/build/generated/benchmarks/SUITE`. Use the separate native and Guest Cargo target directories recorded in environment.json. The archive includes generated manifests, measurement-artifact SHA-256 hashes and Guest harness logs.

Validation covered 49 passing native benchmark tests, 147 native timing rows, 41 Guest programs and 287 Guest timing samples. Archived summaries were checked against manifests and sample medians, fixed timing/pressure budgets, sample counts and successful outcomes. This does not establish full-checkout or release readiness.

No fresh Minecraft server, actor-fleet, JNI or FFM throughput profile was taken. September in-world profiles remain historical; these native measurements do not establish the number of sustainable in-world computers.
