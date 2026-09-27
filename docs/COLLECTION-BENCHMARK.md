---
layout: default
title: Collection representation measurements
description: Compare unboxed Int lists, boxed storage, and universal read bridges in the native VM.
---

# Collection representation measurements

Measured on 2026-09-27 against stdlib/compiler revision `1a4d13d523364f9021ff0f05f975b669a5d9b3be` and VM revision
`fa7661400c23547e76c4f2ee07f40c7b96853e55`. Production lowering and VM execution were not changed for this experiment.

## What is compared

| Name | Storage | Access |
| --- | --- | --- |
| Scalar | `ArrayList<Int>` with an `IntArray` | `List<Int>` |
| Boxed | `ArrayList<Any>` with Int boxes allocated on insertion | `List<Any>`, cast each value to `Int` |
| Bridge | `ArrayList<Int>` with an `IntArray` | `List<Any>`, box each read and cast it to `Int` |

Boxed is a proxy for the storage and access cost of reference-based generics. It still uses the current compiler and
specializes the collection to `Any`; it is **not an implementation of generic erasure**. These measurements cannot establish
the compilation, verification, code sharing, or complete execution cost of an erased-generics design. Only Int workloads
are covered; results do not quantify the benefit of specializing reference element types.

## Method

- Native Rust VM built with `cargo test --release --locked --offline`; no Minecraft, JVM execution, or actor scheduler.
- Intel Core i7-12700H, Linux 7.2.6-zen2-1-zen, x86-64; rustc 1.98.1, LLVM 22.1.8.
- CPU governor was `powersave`, with no affinity or fixed clock. Treat elapsed times as local observations, not portable guarantees.
- 30 programs: three representations, two sizes (1024 and 4096), five operations. Values start at 1000.
- Capacity is preallocated to the element count. Each operation runs eight passes; search performs a last-element hit and a miss per pass.
- Update reads and increments every element. For Bridge, writes use the original typed list while reads use the universal view.
- Pipeline runs `map` (+1), `filter` (even values), and sums the result. Boxed keeps mapped and filtered elements boxed;
  Scalar and Bridge produce typed Int result lists. Each pass starts from the original input.
- Every program emits `ready` after construction and a checksum after the operation; the harness validates both and normal termination.
- Verification, admission, start, teardown, and host response handling are outside elapsed execution timers. Each timed phase
  includes VM advance calls and the final output request/text copy; there is no I/O inside the measured loops.
- One untimed warmup per program, then seven fresh-session samples. Scenario order reverses every other round.
- Normal timing uses a 16 MiB heap and a 4096-unit slice. Fixed/dynamic/maintenance hot-phase counters are identical across samples.
- A separate one-sample run uses a 256 KiB heap to exercise GC/allocation pressure. Its elapsed time is not used for the main medians.
- Minimum ready heap is found by binary search in 16-byte increments, then checked at the successful boundary and one step below.
  This is the minimum arena budget that reaches `ready`, including allocator behavior and temporary setup allocations, **not RSS or exact live payload bytes**.
- The compiler normally declares a 64 KiB minimum heap in its manifest. Measurement copies remove only this admission floor
  using the standard artifact reader/writer and are reverified by the ordinary Rust verifier. Production artifacts retain their normal floor.

## Minimum heap to construct the list

| Elements | Scalar | Boxed | Bridge | Boxed / Scalar |
| ---: | ---: | ---: | ---: | ---: |
| 1024 | 4,688 B | 37,024 B | 4,688 B | 7.90× |
| 4096 | 18,512 B | 147,616 B | 18,512 B | 7.97× |

## Elapsed execution at 4096 elements

Medians of seven samples, in milliseconds. Construction uses the indexed workload's setup phase; other rows exclude setup.

| Phase | Scalar | Boxed | Bridge | Boxed / Scalar | Bridge / Scalar |
| --- | ---: | ---: | ---: | ---: | ---: |
| Construction | 113.4 | 118.3 | 117.9 | 1.04× | 1.04× |
| Indexed reads | 273.8 | 286.7 | 359.7 | 1.05× | 1.31× |
| Iteration | 480.8 | 476.2 | 630.9 | 0.99× | 1.31× |
| Update | 659.1 | 770.7 | 775.4 | 1.17× | 1.18× |
| Map/filter pipeline | 4027.0 | 4056.3 | 4012.9 | 1.01× | 1.00× |
| Search | 821.9 | 1309.2 | 1581.4 | 1.59× | 1.92× |

Indexed Boxed reads are about 5% slower here; iteration and pipeline medians are close and their sample ranges overlap.
Update is about 17% slower and search about 59% slower. Bridge costs are larger for reads/iteration/search because they
allocate temporary boxes repeatedly. These timing ratios describe the complete operation, including casts, dispatch,
value equality, and list implementation calls; they do not isolate the CPU cost of allocation alone.

## Deterministic VM work at 4096 elements

Work is the sum of fixed Guest units, dynamic Guest units, and maintenance units. It is a metering metric, not CPU cycles.

| Operation | Scalar units | Boxed / Scalar | Bridge / Scalar |
| --- | ---: | ---: | ---: |
| indexed | 2,195,942 | 1.09× | 1.36× |
| iteration | 3,769,190 | 1.05× | 1.35× |
| update | 4,719,108 | 1.14× | 1.17× |
| pipeline | 19,916,942 | 1.05× | 1.07× |
| search | 4,457,165 | 1.34× | 1.57× |

## Allocation pressure

At 256 KiB, all Scalar and Bridge workloads and all 1024-element Boxed workloads complete. The 4096-element Boxed
map/filter pipeline reports allocation exhaustion. Its failed-run timing and maintenance columns are zero placeholders
in the TSV and must not be interpreted as successful measurements.

For the 4096-element cases, hot maintenance units are:

| Operation | Scalar | Boxed | Bridge |
| --- | ---: | ---: | ---: |
| indexed | 0 | 0 | 30752 |
| iteration | 0 | 0 | 30770 |
| update | 0 | 142929 | 30756 |
| pipeline | 410 | allocation exhausted | 31524 |
| search | 0 | 0 | 61568 |

Maintenance units expose VM maintenance work, including GC; they are not a GC cycle count or an allocation counter.
The experiment measures memory budgets and metered work, not the exact number of allocated boxes.

## Artifact size

The indexed 4096-element Scalar program has 97 application functions and a 55,744-byte artifact; the Boxed proxy has
84 application functions and a 51,672-byte artifact. Both still use current specialization. This shows a local code-size
cost but does not predict the size of a complete erased-generics implementation. Debug and metadata are included in artifact bytes.

## Interpretation

The strongest measured benefit of unboxed Int collections is heap capacity: approximately eight times less minimum
ready heap in this workload, and completion of a pipeline that exhausts the Boxed version's 256 KiB budget. Runtime
benefits vary by operation, from no clear timing difference to a substantial search advantage. Current universal read
bridges can cost more than storing boxes once.

These results support retaining an efficient explicit numeric path, but do not by themselves require specializing every
generic type. A uniform reference-based generic model with explicit `IntArray` for dense numeric workloads remains a
design option; deciding it requires representative application workloads and/or an actual erasure prototype.

## Reproduce and inspect

```bash
./gradlew-sandbox-dev-parallel-summary benchmarkCollectionRepresentations
```

The task is opt-in, always reruns measurements, and is not part of normal tests or `verifyLocalFull`. It uses the same
compiled Guest stdlib and VM as conformance tests. Generated source programs, artifacts, and their manifest are under
`modules/common/compiler-k2/build/generated/benchmarks/collections/`; current-run reports are under
`build/reports/benchmarks/collections/`.

Checked-in results: [summary TSV](benchmarks/collections-2026-09-27.tsv) and
[all elapsed-time samples](benchmarks/collections-2026-09-27-samples.tsv). Summary time columns use nanoseconds.

Source: `CollectionBenchmarkCase.kt` defines the workloads; the opt-in test in `MinimalScriptLoweringTest.kt` compiles
them; `collections_bench.rs` verifies and runs the native measurement matrix.
