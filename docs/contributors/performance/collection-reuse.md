---
layout: default
title: Repeated collection reuse measurements
section: contributors
permalink: /COLLECTION-REUSE-BENCHMARK/
---

# Repeated collection reuse measurements

This benchmark compares 100 transformations of the same 1024 two-Int Guest records at a fixed 256 KiB heap budget.
Every transformation retains the even-indexed half, changes the first field by the round number and reads all result
fields. A host-computed checksum includes every round and a final scan of the unchanged input. The default run uses
1024 input records; `-PcompukterCollectionReuseCount=4096` runs the larger stress workload with 2048 result records per round.

## Workloads

The table describes the default 1024-record run. The 4096-record run uses 2048 result slots and pool records instead
of 512, with the same operations and 100 rounds.

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

Run `./gradlew-sandbox-dev-parallel-summary benchmarkCollectionReuse -PcompukterCollectionReuseCount=4096` for the
larger workload. Its artifacts and reports use `collection-reuse-4096` directories, preserving the default outputs.
The Gradle property must be a positive even integer and participates in the generator's inputs.

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

## Results at 1024 records

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

- [Measurements]({{ '/benchmarks/collection-reuse-2026-09-27.tsv' | relative_url }})
- [Timing samples]({{ '/benchmarks/collection-reuse-2026-09-27-samples.tsv' | relative_url }})

## Stress results at 4096 records

Measured on 2026-09-27 against parent baseline `701f660a644dae9134f76b9ee05982511b67c73c` plus the count parameter.
The VM and production compiler/library behavior remain the same as in the 1024-record run. Each round retains
2048 transformed records; all original records remain live through the final scan. All variants complete 100 rounds
at 256 KiB with identical checksums, including all nine timing samples and the separate pressure executions.

| Variant | Median operation time | Fixed work | Dynamic work | Maintenance work |
| --- | ---: | ---: | ---: | ---: |
| Fresh | 35.75 s | 132,195,814 | 377,413 | 1,246,812 |
| Reuse list | 34.73 s | 131,122,325 | 205,013 | 1,070,845 |
| Reuse objects | 45.74 s | 142,182,952 | 313 | 0 |

Reusing the list reduces maintenance work by 14.1% and dynamic work by 45.7%, with 0.8% less fixed work. The timing
median is 2.8% lower, but the three samples overlap the fresh timing range, so this is not strong evidence of a CPU
speedup. Pooling records removes measured maintenance work but increases fixed work by 7.6%; its local timing median
is 27.9% higher. The pooled program therefore remains a memory/CPU tradeoff in this workload.

Four times as many input records produce roughly four times the operation time, while maintenance work rises by
14.6 times for fresh lists and 15.7 times for reused lists relative to the archived 1024-record run. This is consistent
with the larger retained input leaving less headroom and increasing collection work at the same heap budget;
maintenance units do not identify the number of GC cycles. These are separate local runs, not controlled portable
scaling guarantees.

Fresh and list-reuse variants each construct 204,800 result records across the timed rounds, accounting for
4,915,200 bytes of 24-byte record-block traffic alone. Pooling creates 2048 result records before `ready` and none
during the rounds. The experiment confirms that this allocation traffic can be reclaimed within 256 KiB;
it does not measure the minimum required heap or peak live payload. Pooling is unnecessary for completion here.

- [4096-record measurements]({{ '/benchmarks/collection-reuse-4096-2026-09-27.tsv' | relative_url }})
- [4096-record timing samples]({{ '/benchmarks/collection-reuse-4096-2026-09-27-samples.tsv' | relative_url }})

The stress run passed `benchmarkCollectionReuse :compiler-k2:lintKotlin -PcompukterCollectionReuseCount=4096`.
Manifest dimensions, equal checksums, all pressure outcomes, all sample heaps and the nine samples were checked.
The default 1024-record report files remain byte-for-byte identical to their original archives. This stage changes
only benchmark configuration, test workloads and documentation; it introduces no Guest API or runtime changes.

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

For the earlier single-operation measurements, see [object-collection measurements]({{ '/OBJECT-COLLECTION-BENCHMARK/' | relative_url }}).
