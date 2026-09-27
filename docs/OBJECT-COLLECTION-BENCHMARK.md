---
layout: default
title: Object-collection heap measurements
---

# Object-collection heap measurements

This benchmark measures the Guest heap budget needed to construct an object list and complete a transformation
while its original input remains live. The budget includes temporary allocations, fragmentation and GC; it is
not an exact live-payload or host RSS measurement.

## Workloads and method

Ten ordinary Guest Kotlin programs construct 4096 records with two mutable Int fields. Five workloads run against
an `ArrayList<Cell>(4096)` and an `ArrayList<Cell>(10)` populated through `add`: indexed reads, `map` creating new
records, `filter` retaining none or all, and `map` followed by a filter retaining half. Original fields are read
after the operation. Identity checks distinguish new records from retained references; an independently computed
checksum checks all input and output values.

Run `./gradlew-sandbox-dev-parallel-summary benchmarkObjectCollectionHeap`. Generated programs and artifacts live
under `modules/common/compiler-k2/build/generated/benchmarks/object-collections`; TSV reports are written under
`build/reports/benchmarks/object-collections`.

The production compiler and ordinary Rust verifier are used. Measurement artifacts remove only the compiler's
64 KiB admission floor. The harness finds empirical construction and complete-program thresholds by binary search
in 16-byte increments, checking the successful boundary and immediately smaller failure. This assumes monotonic
completion for these workloads rather than proving every smaller budget fails. A separate 256 KiB execution
distinguishes construction exhaustion from operation exhaustion.

Timing uses a 16 MiB heap, one warmup and three fresh-session samples, alternating case order. Admission,
verification and teardown are outside the timer; final checksum scans and output are included. Three samples
provide limited local timing evidence. Fixed, dynamic and maintenance work must agree across samples.

## Collection map capacity

The optimized `Collection<T>.map` reserves the collection's known size for its result. Calls whose receiver has
static type `List`, `ArrayList` or `Collection` select this overload. A receiver typed as general `Iterable` still
uses the growing result. Both paths traverse once and invoke the transform once per element in iteration order.
`filter` retains its growing result so an empty selection does not reserve space for the entire input.

The before/after runs use the compact-header VM revision `bbd9faa331fd095f8406bd4e4b9b820caa650643` and the parent
baseline `9a0ad71376e66b9e8c850ac42311407b8999988e` plus this benchmark. Only the after artifacts include the new
collection overload. Measurements were taken on 2026-09-27, Linux x86-64, Intel Core i7-12700H, release Rust build.
Timing runs overlap other focused checks and do not establish a CPU speedup.

| Input list | Operation | Before completion budget | After completion budget | Reduction | At 256 KiB, before → after |
| --- | --- | ---: | ---: | ---: | --- |
| Reserved 4096 | `map` | 261,600 B | 229,600 B | 12.2% | Completed → completed |
| Reserved 4096 | `map` → `filter` | 275,392 B | 245,168 B | 11.0% | Exhausted → completed |
| Grown from 10 | `map` | 307,392 B | 234,016 B | 23.9% | Exhausted → completed |
| Grown from 10 | `map` → `filter` | 307,392 B | 275,392 B | 10.4% | Exhausted → exhausted |

Construction thresholds remain 114,800 B for reserved input and 146,736 B for growing input. Indexed and filter
controls retain identical thresholds, checksums and metered work. `filter` retaining none completes at 114,944 B
or 146,736 B; retaining all completes at 151,296 B or 197,088 B respectively.

For `map`, fixed work falls from 2,431,130 to 2,265,683 units and dynamic work from 7,984 to 5,134. These deterministic
counters show less resizing/copying work, not a portable elapsed-time guarantee. Threshold differences between
input layouts include allocation history and fragmentation. Reserving the input capacity is still useful:
the growing-input pipeline exceeds 256 KiB even after this optimization.

The archived TSVs retain thresholds, pressure-run outcomes, checksums, timings and metered work:

- [Before measurements](benchmarks/object-collections-2026-09-27-before.tsv)
- [Before samples](benchmarks/object-collections-2026-09-27-before-samples.tsv)
- [After measurements](benchmarks/object-collections-2026-09-27-after.tsv)
- [After samples](benchmarks/object-collections-2026-09-27-after-samples.tsv)

For array storage and packed primitive controls, see [object-array measurements](OBJECT-ARRAY-BENCHMARK.md).

## Verification

The after run passed `benchmarkObjectCollectionHeap`, `testKotlinMapVmConformance`, `testKotlinFilterVmConformance`
and `:compiler-k2:lintKotlin`. Map conformance covers order, exactly-once transforms, generic forwarding, empty
collections, nullable boxes and reference identity on the general Iterable and collection paths. Build-script tests,
license policy and Rust harness formatting also passed. This is focused development evidence, not full-checkout
or release verification.
