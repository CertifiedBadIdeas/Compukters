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

Before/after measured sequentially without parallel builds on 2026-09-27: parent starting revision
`021a6a80e879a8316d9cd6eed2f7dedd13d818de` plus the benchmark harness, baseline VM revision
`fa7661400c23547e76c4f2ee07f40c7b96853e55`. The baseline VM was built from a temporary
`git archive` snapshot; both revisions executed the same generated artifacts and harness. Intel Core i7-12700H,
Linux x86-64, rustc 1.98.1, release build, `powersave` governor, no affinity or fixed clock.
Timing observations are local, not portable guarantees.

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

## Compact-header implementation

VM revision `bbd9faa331fd095f8406bd4e4b9b820caa650643` reduces the allocated allocator header from 16 to 8 bytes,
retains the 8-byte type/identity header, and aligns blocks to 8 instead of 16 bytes. Two-Int objects now occupy
24 instead of 32 bytes; three-Int objects occupy 32 instead of 48 bytes. References still occupy four bytes.

Free-list links overlap type/identity storage only for free blocks. During roots/mark, the intrusive gray link borrows
the predecessor-size word; Guest execution and allocation are paused. Sweep restores predecessor sizes from its
forward cursor before coalescing. This keeps collection bounded without a second scan or per-object side table.
Object/array initialization, strings, and entry-argument arrays share the header-size constant.

| Fields | Object operation | Before budget | After budget | Reduction | After at 256 KiB |
| ---: | --- | ---: | ---: | ---: | --- |
| 2 | Construction / in-place update | 147,536 B | 114,752 B | 22.2% | Completed |
| 2 | Shallow copy | 165,920 B | 133,152 B | 19.8% | Completed |
| 2 | Deep copy | 294,992 B | 229,440 B | 22.2% | Completed |
| 2 | Pipeline | 304,240 B | 238,672 B | 21.5% | Completed |
| 3 | Construction / in-place update | 213,072 B | 147,520 B | 30.8% | Completed |
| 3 | Shallow copy | 231,456 B | 165,920 B | 28.3% | Completed |
| 3 | Deep copy | 426,048 B | 294,960 B | 30.8% | Exhausted |
| 3 | Pipeline | 435,312 B | 304,208 B | 30.1% | Exhausted |

The two-field deep copy and pipeline now complete at the unchanged 256 KiB heap. The three-field variants still
exhaust it: compact headers reduce overhead but do not eliminate the cost of simultaneously live object graphs.
These results do not flatten identity-bearing objects or change Kotlin source semantics.

Sequential 16 MiB-heap timing medians for two-field object records (milliseconds):

| Operation | Before | After |
| --- | ---: | ---: |
| Indexed reads | 24.78 | 24.64 |
| In-place update | 34.20 | 33.79 |
| Shallow copy | 48.64 | 47.96 |
| Deep copy | 80.32 | 79.24 |
| Pipeline | 95.87 | 94.12 |

These small timing differences do not establish a speedup. The unchanged indexed-read control and packed workloads
show similar variation; no material timing regression is apparent in this local sample. Fixed Guest work and hot
instruction counts match before/after for every case. Large-heap timings do not quantify 256 KiB GC-pressure latency;
the pressure-run work and status are reported separately in the TSVs.

Verification covers Rust VM unit/integration/doc tests, fmt and clippy, the 36 benchmark programs, reference-array,
object-model and mutable-field Kotlin-to-VM conformance, and four JVM/native runtime-host integration tests. A new
allocator regression repeatedly marks objects out of allocation order, retains their type/identity/payloads, sweeps
around existing free blocks, frees survivors in another order, and verifies the whole arena can be reused.
This is focused runtime evidence, not complete-checkout or release verification.

## Type-only managed header

The next reduction removes the unused four-byte allocation identity token and its ordinal counter. The allocator
header remains eight bytes; the managed header now stores only the four-byte runtime type identifier. Managed
references retain their direct non-moving offsets, so reference equality and shared mutations are unchanged.
No VM instruction exposes the removed identity hash. This is a VM storage change; Guest Kotlin APIs and the artifact,
native ABI, and filesystem formats are unchanged.

Block alignment remains eight bytes and the minimum remains 24 bytes. Two-Int records still occupy 24 bytes because
the smaller header leaves four bytes of rounding padding. Three-Int records shrink from 32 to 24 bytes. Payload reads
and writes operate on little-endian bytes, including wide fields crossing the arena's 16-byte backing units.
Free-list links overlap type/payload bytes only while a block is free; GC gray links still use the predecessor-size
word during the existing paused roots/mark phases.

Two independent arrays of 4096 three-Int records have 96 KiB of field data and 32 KiB of references. Previously their
records added 128 KiB of headers and 32 KiB of padding; now they add 96 KiB of headers and no record padding. Their
combined persistent storage is approximately 224 instead of 288 KiB, plus the array headers. Two-Int records retain
approximately 224 KiB for both states. These layout sums are distinct from empirical whole-program heap thresholds.

Fresh sequential before/after measurements on 2026-09-27 use the same 36 generated artifacts and current harness.
The parent starting revision is `0748517b59e6d1499e3f5f296b7c32a7191ea54e`; the baseline VM is
`bbd9faa331fd095f8406bd4e4b9b820caa650643`; the type-only-header VM is
`a75a9b056faa2118c20f8e8a906070f160c9259d`. The machine and measurement method match the earlier local setup above.

| Fields | Object operation | Before budget | Type-only header budget | After at 256 KiB |
| ---: | --- | ---: | ---: | --- |
| 2 | Construction / in-place update | 114,752 B | 114,752 B | Completed |
| 2 | Shallow copy | 133,152 B | 133,152 B | Completed |
| 2 | Deep copy | 229,440 B | 229,440 B | Completed |
| 2 | Pipeline | 238,672 B | 238,672 B | Completed |
| 3 | Construction / in-place update | 147,520 B | 114,752 B | Completed |
| 3 | Shallow copy | 165,920 B | 133,152 B | Completed |
| 3 | Deep copy | 294,960 B | 229,440 B | Completed |
| 3 | Pipeline | 304,208 B | 238,672 B | Completed |

All 36 pressure runs now complete at 256 KiB and match their expected checksums. The original records remain live
through the operations. The three-field deep-copy threshold drops by 22.2%; the pipeline threshold drops by 21.5%.
The change does not introduce object pooling or require different Guest source code.

At 4096 records, three-field object deep-copy timing changes from 95.48 to 89.96 ms, while its packed control changes
from 56.21 to 54.12 ms. Object pipeline timing changes from 100.10 to 103.06 ms, while its packed control changes from
65.84 to 66.86 ms. Two-field object indexed reads change from 23.70 to 23.89 ms. Three timing samples do not establish
a speedup; the controls also vary, and no material timing regression is apparent in this local run. Timing heaps
remain 16 MiB on both sides. Fixed Guest work and instruction counts match for every case. Some dynamic counts change
by a small amount because physical string-initialization padding changes; they are not claimed identical.

Verification includes Rust unit/integration/doc tests, fmt and clippy, the 36 benchmark programs, and focused
Kotlin-to-VM and JVM/native runtime-host checks. New heap tests exercise independently mutable compact records,
aliases, type preservation, and wide-field accesses across backing-unit boundaries. Existing repeated mark/sweep/reuse
coverage continues to check payloads, types, and restoration of coalescing metadata. The string failed-retry fixture
uses a smaller heap because its two live concat results now fit the old budget. Vertical conformance retains the
same metered work totals; its layout-sensitive diagnostic digest changes. This is focused runtime evidence.

Checked-in type-only-header measurements: [before](benchmarks/object-arrays-2026-09-27-type-header-before.tsv),
[before samples](benchmarks/object-arrays-2026-09-27-type-header-before-samples.tsv),
[after](benchmarks/object-arrays-2026-09-27-type-header-after.tsv), and
[after samples](benchmarks/object-arrays-2026-09-27-type-header-after-samples.tsv).

## Reproduce

```text
./gradlew-sandbox-dev-parallel-summary benchmarkObjectArrayHeap
```

Generated Guest sources, artifacts, and the manifest are under
`modules/common/compiler-k2/build/generated/benchmarks/object-arrays/`; current-run TSV reports are under
`build/reports/benchmarks/object-arrays/`. The task always reruns measurements.

Checked-in baseline: [summary](benchmarks/object-arrays-2026-09-27-before.tsv) and
[timing samples](benchmarks/object-arrays-2026-09-27-before-samples.tsv). Compact-header results:
[summary](benchmarks/object-arrays-2026-09-27-after.tsv) and
[timing samples](benchmarks/object-arrays-2026-09-27-after-samples.tsv). Time columns use nanoseconds.
