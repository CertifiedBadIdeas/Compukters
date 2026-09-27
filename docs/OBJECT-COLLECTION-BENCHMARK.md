---
layout: default
title: Object-collection heap measurements
---

# Object-collection heap measurements

This benchmark measures the Guest heap budget needed to construct an object list and complete a transformation
while its original input remains live. The budget includes temporary allocations, fragmentation and GC; it is
not an exact live-payload or host RSS measurement.

## Workloads and method

Fourteen ordinary Guest Kotlin programs construct 4096 records with two mutable Int fields. Seven workloads run against
an `ArrayList<Cell>(4096)` and an `ArrayList<Cell>(10)` populated through `add`: indexed reads, `map` creating new
records, `filter` retaining none or all, and `map` followed by a filter retaining half. Original fields are read
after the operation. Identity checks distinguish new records from retained references; an independently computed
checksum checks all input and output values. Two explicit `mapNotNull` workloads retain the same half of the records:
one constructs every transformed record before checking the predicate, while the selective variant constructs
only records that will be retained. Both fuse traversal and omit the intermediate mapped list. The original
capacity-optimization archives below contain the first ten workloads.

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

## Explicit non-null transformation

`mapNotNull` lets the programmer combine transformation and selection without materializing the mapped list.
It invokes the transform once per element and appends only non-null results. It does not reserve the entire input
size because the selection can be empty. For example, a transform can create a record and then decide to retain it:

```kotlin
val result = data.mapNotNull { cell ->
    val mapped = Cell(cell.x + 1, cell.y)
    if ((mapped.x - 1001) % 2 == 0) mapped else null
}
```

This performs transformation and selection together for each element. A separate `map` then `filter` transforms
all elements before selecting any, so replacement requires considering side effects. The selective benchmark
instead checks the original field first and constructs only the retained half. Both produce the same records and
checksum in this pure workload; the selective variant also avoids allocating discarded records.

Measured on 2026-09-27 against parent baseline `f40faf328` plus the new library function and workloads, using the
same compact-header VM, host and harness. All three variants below come from one 14-case run, with identical
checksums and the original input retained until the final scan.

| Input list | `map` → `filter` | `mapNotNull`, constructs all | `mapNotNull`, constructs selected |
| --- | ---: | ---: | ---: |
| Reserved 4096 | 245,168 B | 216,080 B | 179,376 B |
| Grown from 10 | 275,392 B | 199,712 B | 179,936 B |

Both `mapNotNull` variants complete at 256 KiB for both input layouts. For growing input, combining traversal
and removing the intermediate list reduces the completion budget by 27.5%; avoiding discarded records increases
the reduction to 34.7%. These are measured completion budgets, whose sensitivity to allocation history and GC
means they are not exact sums of live object sizes. In particular, the constructs-all variant has a lower threshold
on growing input than on reserved input.

Fixed operation work falls from 2,970,166 units for the pipeline to 1,857,952 for constructs-all and 1,806,752 for
selective construction; dynamic work falls from 6,859 to 5,831 and 3,783. Timings remain local observations rather
than portable CPU claims. The ten original control cases retain identical heap thresholds, checksums, pressure
outcomes and fixed/dynamic work compared with the capacity-optimization after archive.

- [Non-null transformation measurements](benchmarks/object-collections-2026-09-27-map-not-null.tsv)
- [Non-null transformation samples](benchmarks/object-collections-2026-09-27-map-not-null-samples.tsv)

## Verification

The after run passed `benchmarkObjectCollectionHeap`, `testKotlinMapVmConformance`, `testKotlinFilterVmConformance`
and `:compiler-k2:lintKotlin`. Map conformance covers order, exactly-once transforms, generic forwarding, empty
collections, nullable boxes and reference identity on the general Iterable and collection paths. Build-script tests,
license policy and Rust harness formatting also passed. This is focused development evidence, not full-checkout
or release verification.

The non-null transformation run additionally passed `testKotlinMapNotNullVmConformance`,
`testKotlinFilterNotNullVmConformance` and `:guest-platform:test`, alongside the 14-case benchmark, existing map
conformance, compiler lint, build-script tests and license policy. Its dedicated conformance scenario covers
exactly-once transforms, iteration order, nullable input, empty and all-null results, independent result types,
generic forwarding, Int unboxing and preserved reference/box identity under bounded VM slices.
