---
layout: default
title: Object-array heap measurements
---

# Object-array heap measurements

This benchmark measures the Guest heap needed to construct records and complete operations while the original input
remains live. It does not measure fleet RSS, arena admission cost, or exact peak live payload.

## Workloads and method

- 62 ordinary Guest Kotlin programs: 54 cases with 1024 or 4096 records, one, two, or three mutable Int fields, and
  object-array or packed primitive-array storage. Object storage uses `arrayOfNulls<Cell>` filled with fresh instances; packed storage uses
  one `IntArray` per field. Packed controls compare numeric work and storage, not Kotlin reference semantics.
  Eight additional cases hold boxed Int values in `Array<Any?>`, with indexed reads, shallow/deep copies, and pipelines.
  Their values start at 1000; deep copies and pipelines create fresh boxes, while shallow copies retain references.
  Boxes are not tested as mutable records, so they have no in-place-update workload.
- Indexed reads, in-place updates, shallow reference copies (reference storage only), deep copies with the first field incremented,
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

## Sixteen-byte minimum block

Reducing the minimum block from 24 to 16 bytes removes eight bytes of minimum-size padding from empty objects and
one-Int objects, including managed Int boxes. The allocated header remains 12 bytes and block alignment remains
eight bytes. Free-list metadata fits in 16 bytes; a 16-byte remainder is now split and reused, while an eight-byte
remainder is absorbed. Two-Int and three-Int objects remain 24 bytes. Heap admission still uses the same 16-byte
granularity and 32-byte lower bound; no reference, artifact, native ABI, or filesystem format changes.

The benchmark now includes one-field records and boxed Int arrays in addition to the previous two/three-field
controls. Fresh measurements use the same 62 compiled artifacts on both VM versions, with three timing samples per
case at 16 MiB and separate 256 KiB pressure runs. The parent starting revision is
`8d70a33919fafefd583706bf32670461913848a1`; the archived baseline VM is
`a75a9b056faa2118c20f8e8a906070f160c9259d`; the 16-byte-minimum VM is
`21f85a45598191e58c016d3da6ebcb6493ae5ebe`. Baseline and changed-VM timing samples ran sequentially after other
builds/tests finished. The machine and harness method match the local setup above.

At 4096 elements, one-Int mutable records and boxed Int arrays have the same measured heap thresholds:

| Operation | Before budget | 16-byte minimum budget | Reduction |
| --- | ---: | ---: | ---: |
| Construction / indexed reads | 114,752 B | 81,984 B | 28.6% |
| In-place update, mutable records | 114,752 B | 81,984 B | 28.6% |
| Shallow reference copy | 133,152 B | 100,384 B | 24.6% |
| Deep copy retaining input | 229,440 B | 163,888 B | 28.6% |
| Pipeline retaining input | 238,672 B | 173,136 B | 27.5% |

Two independent 4096-record one-Int states remove 65,536 bytes of block padding. Their records and reference arrays
occupy approximately 160 instead of 224 KiB, plus the array headers. The deep-copy completion threshold now equals
that combined layout size; other thresholds still include allocation history, temporaries, fragmentation, and GC.
Two/three-field and packed controls retain their previous heap thresholds. All 62 pressure runs complete and match
the host-computed checksums. Fixed Guest work, dynamic Guest work, and instruction counts match for every case.

At 16 MiB, one-field mutable-record indexed timing changes from 18.92 to 18.87 ms, deep copy from 63.13 to 62.10 ms,
and pipeline from 79.97 to 76.55 ms. The packed deep-copy control changes from 35.28 to 34.36 ms. Boxed deep copy changes
from 56.33 to 50.37 ms, but several unchanged controls also become faster; these samples do not establish a speedup.
No material large-heap timing regression is apparent in this local run.

GC-pressure work is not monotonic in object size. At 256 KiB, the two-round one-field deep copy drops from 21,847
maintenance units to zero, while the pipeline increases from 21,164 to 28,685 units (35.5%). Both mutable-record and
boxed cases show this change. These are measured maintenance units, not collection counts. Smaller blocks change
allocation history and when collection runs; reduced heap requirements do not establish lower GC cost in every
workload. The separate pressure timing experiment below measures this boundary explicitly.

Verification includes 386 Rust unit tests, Rust integration/doc tests, fmt, clippy, release vertical heap conformance,
the 62 benchmark programs, seven focused Kotlin-to-VM scenarios, four JVM/native runtime-host tests, compiler lint,
and build-script tests. Coverage includes split/reused 16-byte tails, absorbed eight-byte tails, independent one-Int
records and aliases, repeated mixed-size gray queue/sweep/coalescing, initialization, rollback, and allocation retries.
The retry/failure fixtures use one Long field to keep a 32-byte heap under pressure now that two empty objects fit;
the vertical test's added dynamic units and changed digest reflect that fixture change. This is focused evidence.

Checked-in minimum-block measurements: [before](benchmarks/object-arrays-2026-09-27-min-block-before.tsv),
[before samples](benchmarks/object-arrays-2026-09-27-min-block-before-samples.tsv),
[after](benchmarks/object-arrays-2026-09-27-min-block-after.tsv), and
[after samples](benchmarks/object-arrays-2026-09-27-min-block-after-samples.tsv).

### Timing at 256 KiB

A matched six-case subset repeats deep copy and pipeline for 4096 one-field mutable records, two-field mutable-record
controls, and boxed Int arrays. The harness uses `fixed-heap` mode, one warmup and seven fresh samples per case,
alternating case order. Every timing sample runs at 262,144 bytes; no large-heap fallback occurs. Both revisions use
the same artifacts and run sequentially after the other checks finish.

| Storage | Operation | Before median | After median | Maintenance units, before → after |
| --- | --- | ---: | ---: | ---: |
| One-Int records | Deep copy | 63.08 ms | 61.19 ms | 21,847 → 0 |
| One-Int records | Pipeline | 77.44 ms | 75.95 ms | 21,164 → 28,685 |
| Two-Int records | Deep copy | 76.85 ms | 75.03 ms | 21,847 → 21,847 |
| Two-Int records | Pipeline | 91.61 ms | 89.27 ms | 21,164 → 21,164 |
| Boxed Int | Deep copy | 51.36 ms | 49.11 ms | 21,847 → 0 |
| Boxed Int | Pipeline | 64.29 ms | 62.39 ms | 21,164 → 28,685 |

The p10/p90 ranges overlap for every matched case, and the unchanged two-field controls show a similar timing shift.
This local experiment shows no material pressure-timing regression, including the pipeline with more maintenance
work; it does not establish a CPU speedup or lower GC work for every workload.

Pressure timing archives: [before](benchmarks/object-arrays-2026-09-27-min-block-pressure-before.tsv),
[before samples](benchmarks/object-arrays-2026-09-27-min-block-pressure-before-samples.tsv),
[after](benchmarks/object-arrays-2026-09-27-min-block-pressure-after.tsv), and
[after samples](benchmarks/object-arrays-2026-09-27-min-block-pressure-after-samples.tsv).

## Unified 64-bit block header

The next VM change packs allocation flags, aligned block size, predecessor size or GC gray link, and the runtime
type ID into one eight-byte header. Admission selects the compact format only when the heap bound and number of
types fit its bit fields; otherwise the previous 12-byte header remains available. The production 256 KiB profile
uses 16 size bits, 15 link bits, and 30 type bits. Managed references stay 32-bit non-moving offsets, and reference
arrays are unchanged. The 16-byte minimum block still applies. Two-Int records therefore shrink from 24 to 16 bytes;
one-Int and three-Int records remain 16 and 24 bytes respectively. The reserved arena size per VM is unchanged.

The same 62 generated artifacts were measured sequentially on 2026-09-28 against baseline VM
`b140c26243e06140241297bc74e995e44a729865` and the changed checkout. The baseline was built from a temporary
`git archive`; both runs used the same release harness, one warmup and three timing samples per case. The current
16 MiB timing heap also selects the compact format because these programs have few types. All 62 pressure runs
completed at 256 KiB and passed their checksums.

| 4096-record object operation | Before heap budget | Compact heap budget | Reduction |
| --- | ---: | ---: | ---: |
| Two-Int construction / indexed reads | 114,752 B | 81,968 B | 28.6% |
| Two-Int shallow copy | 133,152 B | 100,368 B | 24.6% |
| Two-Int deep copy | 229,440 B | 163,888 B | 28.6% |
| Two-Int pipeline | 238,672 B | 173,120 B | 27.5% |

The two-Int records alone use 64 instead of 96 KiB for 4096 instances. A retained input and a deep-copied state
save 64 KiB of record blocks in total, before reference arrays and temporary objects. One-Int and three-Int object
thresholds differ by at most 32 bytes from the baseline, reflecting changed string padding rather than record size.

At 16 MiB, two-Int deep-copy medians were 28.67 ms before and 27.98 ms after; the pipeline medians were 28.58 ms
and 29.68 ms. The unchanged three-Int pipeline moved from 32.17 to 34.51 ms. Three samples with overlapping
workload variation do not establish a CPU improvement or regression. Fixed work and instruction counts match;
dynamic units can move by one as string padding changes. At 256 KiB, two-Int deep-copy maintenance fell from
21,847 to zero units, while pipeline maintenance rose from 21,164 to 28,685 units. Smaller objects change the
collection schedule, so less heap use does not imply less GC work for every operation. The 256 KiB pressure timing
is a single observation per case and is not used for a latency claim.

Checked-in matched measurements: [before](benchmarks/object-arrays-2026-09-28-header-before.tsv),
[before samples](benchmarks/object-arrays-2026-09-28-header-before-samples.tsv),
[after](benchmarks/object-arrays-2026-09-28-header-after.tsv), and
[after samples](benchmarks/object-arrays-2026-09-28-header-after-samples.tsv).

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
