---
layout: default
title: Object-collection heap measurements
section: contributors
---

# Object-collection heap measurements

This benchmark measures the Guest heap budget needed to construct an object list and complete a transformation
while its original input remains live. The budget includes temporary allocations, fragmentation and GC; it is
not an exact live-payload or host RSS measurement.

For 100 repeated transformations with reusable lists and objects at 256 KiB, see
[collection reuse measurements]({{ '/COLLECTION-REUSE-BENCHMARK/' | relative_url }}).

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

## Temporary register reuse

The 2026-09-28 comparison uses baseline artifacts preserved from `af889b858` and compiler implementation `21abf4428`
([#667](https://github.com/CertifiedBadIdeas/Compukters/issues/667)). All fourteen Guest sources are identical.
The linker assigns one register to exact-type temporary values with nonoverlapping lifetimes, then rebuilds GC maps,
module hashes and frame requirements. Parameters and caught-exception destinations retain dedicated slots. Instructions,
control flow, collection storage and the artifact/runtime ABI are unchanged.

| Operation | Main compact frame (B), before → after | Allocated frame arena (B), before → after | Peak active frames, sized (B), before → after | Peak active frames, growing (B), before → after |
| --- | ---: | ---: | ---: | ---: |
| `indexed` | 168 → 48 | 2688 → 896 | 400 → 152 | 496 → 200 |
| `map` | 312 → 96 | 4992 → 1536 | 544 → 200 | 640 → 248 |
| `filter-none` | 272 → 80 | 4352 → 1280 | 504 → 184 | 600 → 232 |
| `filter-all` | 288 → 88 | 4608 → 1408 | 616 → 240 | 616 → 240 |
| `pipeline` | 424 → 104 | 6784 → 1664 | 752 → 256 | 752 → 256 |
| `map-not-null` | 376 → 96 | 6016 → 1536 | 704 → 248 | 704 → 248 |
| `map-not-null-selective` | 368 → 96 | 5888 → 1536 | 696 → 248 | 696 → 248 |

The frame arena shrinks by 66.7–75.5%, saving 1792–5120 bytes per session on these workloads. For 1000–2000 sessions,
that corresponds to approximately 1.7–9.8 MiB of frame arena storage, depending on workload and session count; this is
not an estimate of total server memory. Peak simultaneously active frames shrink by 59.7–66.0%. Main register counts
fall from 42–106 to 12–25. The main function is no longer always the largest: the indexed cases retain a 56-byte
library frame, so their arena is 56 × 16 = 896 bytes rather than 48 × 16.

The two frame metrics measure different things. The static compact size comes from canonical `ExecutionStorage`
alignment rules. The VM allocates its frame arena from the artifact's `requiredStackBytes`, which reserves the largest
frame multiplied by maximum call depth (16 in these programs). The profile's 1 MiB frame limit is an admission limit,
not the allocated arena capacity. Each program keeps that same profile limit before and after optimization.

Exact active-frame high-water measurements use VM `b140c26243e06140241297bc74e995e44a729865` in both phases. Test-only
counters track every successful frame reservation/release inside `FrameArena`, including suspended task/caller frames
and peaks within an execution slice. They exclude frame records, static storage, preallocated unused arena capacity
and image metadata. The VM's reported mutable execution storage reservation decreases by exactly the arena saving;
the report's 16 MiB heap capacity is unchanged. Test instrumentation is absent from production builds. Neither metric
measures process RSS, allocator overhead or total shared image memory.

Heap construction/completion thresholds, checksums, 256 KiB outcomes, executed instruction counts and fixed, dynamic
and maintenance VM work are identical in all fourteen cases. Artifacts shrink by 2432–3096 bytes.
Timing samples are archived for reproducibility; the baseline overlaps verification and does not establish CPU speedup.
Object sizes, backing arrays and independently retained before/after states are unchanged.

To reproduce the exact frame measurement after generating the benchmark artifacts:

```sh
COMPUKTER_FRAME_BENCHMARK_ARTIFACTS="$PWD/modules/common/compiler-k2/build/generated/benchmarks/object-collections" \
COMPUKTER_FRAME_BENCHMARK_REPORT=/tmp/object-collection-active-frames.tsv \
cargo test --release --manifest-path host/compukter-vm/Cargo.toml --locked --offline \
  --lib object_collection_active_frame_measurement -- --ignored
```

Verification: 130 artifact tests, 37 engine tests, 142 worker tests (five existing benchmark skips), all 48 registered
Kotlin-to-VM conformance scenarios, 386 Rust library tests and explicit 14-case active-frame measurements. These checks
cover this compiler/runtime boundary; they do not establish full-checkout or release readiness.

Raw evidence:

- [Before measurements]({{ '/benchmarks/object-collections-2026-09-28-frame-reuse-before.tsv' | relative_url }}) and
  [samples]({{ '/benchmarks/object-collections-2026-09-28-frame-reuse-before-samples.tsv' | relative_url }}).
- [After measurements]({{ '/benchmarks/object-collections-2026-09-28-frame-reuse-after.tsv' | relative_url }}) and
  [samples]({{ '/benchmarks/object-collections-2026-09-28-frame-reuse-after-samples.tsv' | relative_url }}).
- [Before static frames]({{ '/benchmarks/object-collections-2026-09-28-frame-reuse-before-frames.tsv' | relative_url }}) and
  [after static frames]({{ '/benchmarks/object-collections-2026-09-28-frame-reuse-after-frames.tsv' | relative_url }}).
- [Before active frames]({{ '/benchmarks/object-collections-2026-09-28-frame-reuse-before-active-frames.tsv' | relative_url }}) and
  [after active frames]({{ '/benchmarks/object-collections-2026-09-28-frame-reuse-after-active-frames.tsv' | relative_url }}).

## Collection callback inlining

The 2026-09-28 comparison uses parent baseline `1022800de` and implementation `c723c96a8`
([#666](https://github.com/CertifiedBadIdeas/Compukters/issues/666)), both with VM
`21f85a45598191e58c016d3da6ebcb6493ae5ebe`. All fourteen generated Guest sources are byte-for-byte identical.
Callback-taking extensions now inline their direct lambdas; source-only generic wrappers keep their concrete emitter
specialization. Stored callbacks retain managed ownership. The VM and collection storage layout are unchanged.

| Operation | Sized completion heap (B), before → after | Growing completion heap (B), before → after | Main compact frame (B), before → after | Hot VM work change, sized / growing |
| --- | ---: | ---: | ---: | ---: |
| `indexed` | 114,784 → 114,784 | 146,672 → 146,672 | 168 → 168 | +0.00% / +0.00% |
| `map` | 229,568 → 229,552 | 233,984 → 233,968 | 216 → 312 | -0.90% / -0.90% |
| `filter-none` | 114,912 → 114,896 | 146,672 → 146,672 | 192 → 272 | -1.98% / -1.98% |
| `filter-all` | 151,264 → 151,248 | 197,008 → 196,992 | 208 → 288 | -0.87% / -0.87% |
| `pipeline` | 245,088 → 245,056 | 275,280 → 275,248 | 232 → 424 | -1.38% / -1.38% |
| `map-not-null` | 215,984 → 215,968 | 199,584 → 199,568 | 216 → 376 | -1.10% / -1.10% |
| `map-not-null-selective` | 179,296 → 179,280 | 179,824 → 179,808 | 216 → 368 | -1.13% / -1.13% |

Hot work is the sum of fixed, dynamic and maintenance units between the construction marker and final checksum output;
it includes result/input scans. Callback workloads use 0.87–1.98% fewer metered units, while executing 8,193 more
instructions per single-stage program and 16,386 more in the pipeline: inline block moves/jumps replace more expensive
dispatch. This is not a measured CPU speedup. Construction thresholds, checksums and 256 KiB pressure outcomes are unchanged.
Both indexed-control artifacts are byte-for-byte identical before/after. Capture-free callbacks save at most 16 bytes
per callback in these completion thresholds (32 bytes for the two-stage pipeline); growing filter-none remains bounded
by its construction peak. The grown pipeline still exceeds 256 KiB. Object and backing-array storage are unchanged.

Callback artifacts shrink by 64–408 bytes. Single-stage cases drop five types and three functions; the pipeline drops
ten types and six functions. Main compact frames grow by 80–192 bytes, so heap savings are not a net memory claim.

The compact frame figures are static artifact measurements using the canonical `ExecutionStorage` physical component
sizes and alignments: align each component, add its byte size, then round the frame to eight bytes. The archived
maximum includes all artifact functions; in these cases it equals the main frame. It excludes frame metadata,
simultaneously active callees, allocation capacity and host object overhead, and is not a peak resident/RSS measurement.
Caller frame growth can offset the small callback heap savings; no net resident-memory reduction is established.

Timing samples are archived, but the baseline overlaps focused verification, so these runs do not establish a CPU
speedup. Metered VM work and empirical heap thresholds are the deterministic comparison. The 16 MiB timing heap and
256 KiB pressure heap are recorded explicitly in each measurement row.

Raw evidence:

- [Before measurements]({{ '/benchmarks/object-collections-2026-09-28-inline-before.tsv' | relative_url }}) and
  [samples]({{ '/benchmarks/object-collections-2026-09-28-inline-before-samples.tsv' | relative_url }}).
- [After measurements]({{ '/benchmarks/object-collections-2026-09-28-inline-after.tsv' | relative_url }}) and
  [samples]({{ '/benchmarks/object-collections-2026-09-28-inline-after-samples.tsv' | relative_url }}).
- [Before compact frames]({{ '/benchmarks/object-collections-2026-09-28-inline-before-frames.tsv' | relative_url }}) and
  [after compact frames]({{ '/benchmarks/object-collections-2026-09-28-inline-after-frames.tsv' | relative_url }}).

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

- [Before measurements]({{ '/benchmarks/object-collections-2026-09-27-before.tsv' | relative_url }})
- [Before samples]({{ '/benchmarks/object-collections-2026-09-27-before-samples.tsv' | relative_url }})
- [After measurements]({{ '/benchmarks/object-collections-2026-09-27-after.tsv' | relative_url }})
- [After samples]({{ '/benchmarks/object-collections-2026-09-27-after-samples.tsv' | relative_url }})

For array storage and packed primitive controls, see [object-array measurements]({{ '/OBJECT-ARRAY-BENCHMARK/' | relative_url }}).

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

- [Non-null transformation measurements]({{ '/benchmarks/object-collections-2026-09-27-map-not-null.tsv' | relative_url }})
- [Non-null transformation samples]({{ '/benchmarks/object-collections-2026-09-27-map-not-null-samples.tsv' | relative_url }})

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

The collection callback inlining stage passed 37 engine tests, 123 worker lowering tests (five existing benchmark skips),
12 compiler adapter tests, 16 metadata tests and 14 native IDE diagnostic tests. Engine/compiler/IDE lint and the engine
boundary check passed. Executed VM scenarios: map, collection-selection, fold, filter, map-not-null, destination,
filter-not-null, transparent-call, function-values, list-any and inline-blocks. Both 14-case before/after release-VM
benchmark runs completed. This is focused development evidence, not full-checkout or release verification.
