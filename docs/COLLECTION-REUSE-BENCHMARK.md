---
layout: default
title: Repeated collection reuse measurements
---

# Repeated collection reuse measurements

This benchmark compares 100 transformations of the same 1024 two-Int Guest records at a fixed 256 KiB heap budget.
Every transformation retains the even-indexed half, changes the first field by the round number and reads all result
fields. A host-computed checksum includes every round and a final scan of the unchanged input.

## Workloads

| Variant | Result storage | Result records |
| --- | --- | --- |
| Fresh | New `mapNotNull` result on every round | 512 new records each round |
| Reuse list | Preallocated 512-slot list, explicitly cleared, refilled by `mapNotNullTo` | 512 new records each round |
| Reuse objects | Preallocated result list and 512-record pool, list cleared and refilled by `mapNotNullTo` | Pool records updated in place |

The input remains live through all rounds. In the fresh variant, the previous result remains live while its replacement
is constructed. The reused list retains backing capacity but clears references to old records. The pooled variant
keeps its records through a separate pool and checks their identity after the final round. Each variant produces the
same numeric result, while pooled records have intentionally different lifetime and alias behavior: retained references
observe later updates. This is explicit application code rather than an automatic change to Kotlin semantics.

Across the timed rounds, fresh and list-reuse variants each construct 51,200 result records; the pooled variant
constructs none. Pool construction happens before `ready` and is included in construction time. This does not make
the pooled program allocation-free: iterators, closures and output can still allocate.

The destination helpers append rather than replace contents. Reusing a list therefore requires explicit clearing:

```kotlin
val output = ArrayList<Cell>(512)
// For each operation:
output.clear()
data.mapNotNullTo(output) { cell ->
    if ((cell.x - 1000) % 2 == 0) Cell(cell.x + 1, cell.y) else null
}
```

This reuses the list's backing storage while still constructing new `Cell` instances. Reusing records requires
an explicit pool or updating the existing records; the helpers do not change object identity automatically.

## Method

Run `./gradlew-sandbox-dev-parallel-summary benchmarkCollectionReuse`. The production K2 compiler generates three
ordinary verified artifacts under `modules/common/compiler-k2/build/generated/benchmarks/collection-reuse`.
Measurement copies remove only the compiler's 64 KiB admission floor. Reports are written under
`build/reports/benchmarks/collection-reuse`.

The shared heap harness has a `fixed-heap` mode for repeated workloads. It uses one warmup and three fresh-session
timing samples at 256 KiB, with reversed case order on alternate rounds, followed by a separate pressure execution.
If a variant exhausts 256 KiB, the report retains that pressure failure and samples the valid program at 16 MiB;
`timing_heap_bytes` records the actual sample heap. Blank minimum-heap columns mean thresholds were not searched.
The original object-array/collection mode retains its threshold search and 16 MiB samples.

VM slices allow 4096 Guest and maintenance units each. Verification, admission and teardown are outside timers;
result scans, final input scan and output are included. Every run checks checksum and normal termination. Fixed,
dynamic and maintenance work must agree across samples. Maintenance units measure VM work rather than GC cycles,
allocated bytes or host RSS. The fixed budget establishes completion under that limit, not a minimum required heap.
Three local samples provide limited elapsed-time evidence.

## Results

Measured on 2026-09-27 against parent baseline `ccfb751aa6532edb988c662be7eb5cb575a27613` plus the destination
helpers, compiler corrections and benchmark. The VM remains at compact-header revision
`bbd9faa331fd095f8406bd4e4b9b820caa650643`. Linux x86-64, Intel Core i7-12700H, release Rust build. The Gradle batch
also generated focused conformance artifacts; timings are exploratory local measurements rather than portable guarantees.

All variants complete 100 rounds at 256 KiB with identical checksums. All nine timing samples actually use that
budget; none use the 16 MiB fallback.

| Variant | Median operation time | Fixed work | Dynamic work | Maintenance work |
| --- | ---: | ---: | ---: | ---: |
| Fresh | 8.94 s | 33,404,301 | 102,513 | 85,369 |
| Reuse list | 8.42 s | 32,798,328 | 51,413 | 68,097 |
| Reuse objects | 11.13 s | 35,564,555 | 313 | 0 |

Reusing the list reduces maintenance work by 20.2% and dynamic work by 49.8%, while still allocating the same
51,200 result records. Reserving capacity avoids repeated backing-array growth; `clear()` and refill have their own
work. Fixed work falls by 1.8%. The local timing median improves by 5.8%, which is limited three-sample evidence.

Pooling records removes measured maintenance work during the operations and nearly all dynamic work. It increases
fixed work by 6.5% and the local timing median by 24.6% compared with fresh results. Pool lookup, captured position
updates, field mutation and list refill replace straightforward construction. This demonstrates a memory/CPU tradeoff
for this implementation, not a general claim that record reuse is faster. Pool setup also increases construction
time: 84.4 ms compared with 45.7 ms fresh and 41.5 ms for list reuse.

The 24-byte two-Int record blocks account for 1,228,800 bytes of allocation traffic in the timed rounds for fresh
and list-reuse variants, excluding arrays, closures and iterators. Pooling constructs 512 such records before `ready`
and none during the rounds. Traffic over many rounds is distinct from simultaneously live memory; all variants fit
the fixed budget because temporary allocations can be reclaimed. Minimum heap and peak live payload were not measured.

- [Measurements](benchmarks/collection-reuse-2026-09-27.tsv)
- [Timing samples](benchmarks/collection-reuse-2026-09-27-samples.tsv)

## Verification

The final run passed `benchmarkCollectionReuse`, `testKotlinDestinationVmConformance`,
`testKotlinFunctionValuesVmConformance`, `testKotlinGenericInterfaceVmConformance`, `testKotlinMapNotNullVmConformance`,
compiler and compiler-engine lint, platform tests, build-script tests and license policy. Generic-function and mutable-list
conformance also passed while validating destination dispatch. The destination scenario covers append behavior, concrete
return type and identity, empty/all-null results, nullable values, generic forwarding, wider destinations, explicit clear/refill,
and immutable/mutable captures of generic collection instances.

The shared harness's normal mode was checked against the indexed 4096-record control: its original 114,800-byte
completion threshold is retained. Fixed mode was checked against the known growing-input pipeline that exceeds 256 KiB:
it reports operation exhaustion and marks fallback samples as 16 MiB, leaving minimum-heap columns blank. Rust harness
formatting and TSV row/sample consistency checks passed. This is focused development evidence, not release verification.

For the earlier single-operation measurements, see [object-collection measurements](OBJECT-COLLECTION-BENCHMARK.md).
