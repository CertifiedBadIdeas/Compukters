---
layout: default
title: Object-array heap measurements
---

# Object-array heap measurements

This benchmark measures the Guest heap needed to construct records and complete operations while the original input
remains live. It does not measure fleet RSS, arena admission cost, or exact peak live payload.

## Workloads and method

- 36 ordinary Guest Kotlin programs: 1024 or 4096 records, two or three mutable Int fields, object arrays or packed
  primitive-array controls. Object storage uses `arrayOfNulls<Cell>` filled with fresh instances; packed storage uses
  one `IntArray` per field. Packed controls compare numeric work and storage, not Kotlin reference semantics.
- Indexed reads, in-place updates, shallow reference copies (objects only), deep copies with the first field incremented,
  and a pipeline that creates transformed records then selects even-index records into another array.
- Two operation rounds. Reading every original field after the operations keeps all input records live. Update also
  checks mutation through a retained alias; shallow copies check shared identity; deep copies and pipelines check new
  record identity. A host-computed checksum checks every program's result and normal termination.
- The production K2 compiler writes artifacts; measurement copies remove only the compiler's 64 KiB admission floor
  and pass the ordinary Rust verifier. No production compiler or interpreter shortcut is used.
- Binary search over heap budgets in 16-byte increments finds empirical construction-to-`ready` and complete-program
  thresholds. Each successful boundary and the immediately smaller failing budget are checked. These budgets include
  temporary objects, allocator rounding/search behavior, fragmentation, GC, and output materialization. Binary search
  assumes monotonic completion for these workloads; the search is not an exhaustive proof over all smaller budgets.
- Timings use a 16 MiB heap and a 4096-unit Guest/maintenance slice. Verification, admission, start, and teardown are
  outside the timers; the final original-input scan and output request are included in operation time.
- One warmup, three fresh-session timing samples, reversing case order on alternate rounds. Reported percentiles use
  nearest rank; three samples are limited timing evidence. Metered fixed/dynamic/maintenance work must agree across
  samples. Maintenance units are work, not a GC cycle count or an allocation count.
- A separate 256 KiB run distinguishes exhaustion during construction from exhaustion during the operation. Zero time
  and maintenance placeholders on failed runs are not successful measurements.

Baseline measured on 2026-09-27: parent starting revision `021a6a80e879a8316d9cd6eed2f7dedd13d818de` plus the benchmark
harness, VM revision `fa7661400c23547e76c4f2ee07f40c7b96853e55`. Intel Core i7-12700H, Linux x86-64, rustc 1.98.1,
release build, `powersave` governor, no affinity or fixed clock. Timing observations are local, not portable guarantees.

## Baseline at 4096 records

| Fields | Storage | Construction budget | Deep-copy completion budget | Pipeline completion budget | Deep/pipeline at 256 KiB |
| ---: | --- | ---: | ---: | ---: | --- |
| 2 | Objects | 147,536 B | 294,992 B | 304,240 B | Exhausted |
| 2 | Packed | 34,848 B | 67,728 B | 83,152 B | Completed |
| 3 | Objects | 213,072 B | 426,048 B | 435,312 B | Exhausted |
| 3 | Packed | 51,264 B | 100,560 B | 124,208 B | Completed |

In-place updates complete at the construction budget. Shallow copies require 165,920 B for two-field records and
231,456 B for three-field records, and both complete at 256 KiB. A new array of existing references has a different
memory cost from constructing new objects.

The object layout explains much of the difference: 24 bytes of allocator/object headers, rounded to a 16-byte block
boundary, produce 32-byte two-Int objects and 48-byte three-Int objects. Each array adds a four-byte reference per
record. Packed controls have no independent record identities or per-record headers.

## Reproduce

```text
./gradlew-sandbox-dev-parallel-summary benchmarkObjectArrayHeap
```

Generated Guest sources, artifacts, and the manifest are under
`modules/common/compiler-k2/build/generated/benchmarks/object-arrays/`; current-run TSV reports are under
`build/reports/benchmarks/object-arrays/`. The task always reruns measurements.

Checked-in baseline: [summary](benchmarks/object-arrays-2026-09-27-before.tsv) and
[timing samples](benchmarks/object-arrays-2026-09-27-before-samples.tsv). Time columns use nanoseconds.
