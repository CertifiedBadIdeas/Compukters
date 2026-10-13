---
layout: default
title: Guest sequence cache measurements
section: contributors
---

# Guest sequence cache measurements — 2026-10-13

[Issue #781](https://github.com/CertifiedBadIdeas/Compukters/issues/781).

## Result

All twelve non-null primitive caches now use scalar fields. The reference cache retains nullable
values/identity and clears consumed references. A filtering iterator allocates one cache object;
accepted primitive elements allocate no cache boxes. This uses the existing compiler primitive/reference
selection mechanism shared with list storage, without a VM opcode or host ABI change.

For a full 4096-element lazy traversal, the minimum tested completion heap fell from 36,864 to
32,064 bytes (13.0%) and dynamic Guest units from 66,634 to 50,258 (24.6%). Instructions increased
from 6,084,950 to 6,117,766 (0.54%): cache method calls cost more than direct nullable-field access.
Thus removing boxes reduces allocation cost and heap pressure but does not establish a speedup.

Full eager traversal still has the lower measured processing time. Lazy early stopping performs much
less work: take(8) uses 26,852 instructions against eager's 6,529,204; firstOrNull uses 2,343 against
6,527,263. Both eager cases first materialize the full filter/map result, then consume their selected
prefix. These comparisons apply to those pipelines; a hand-written loop that stops early is another
implementation and is not measured here.

| Case (4096 inputs, 8 rounds) | Instructions | Fixed + dynamic Guest units | Minimum execution heap | Median processing time |
| --- | ---: | ---: | ---: | ---: |
| full-4096-eager | 7,608,422 | 14,007,088 | 42,272 B | 1065.458 ms |
| full-4096-lazy | 6,117,766 | 11,742,824 | 32,064 B | 1176.016 ms |
| take8-4096-eager | 6,529,204 | 12,011,723 | 42,224 B | 922.097 ms |
| take8-4096-lazy | 26,852 | 51,491 | 16,768 B | 5.442 ms |
| first-4096-eager | 6,527,263 | 12,008,518 | 43,168 B | 1032.116 ms |
| first-4096-lazy | 2,343 | 4,782 | 16,624 B | 0.446 ms |

For 32 inputs, full lazy traversal requires 560 bytes versus eager's 448; the previous boxed lazy
cache required 544. take(8) remains at 512 bytes, and firstOrNull at 368. A specialized cache object
has an upfront cost, so small inputs and a single accepted element may see no allocation benefit.
All eager instruction/unit/heap measurements match before and after exactly.

## Method and limits

The twelve opt-in programs cover 32/4096 Int values (1000 + index), filter even values, map each
accepted value to twice its value, then fully materialize, take eight into a list or select the first.
Each performs eight traversals. Generated sources are byte-identical between stages; every run checks
its expected checksum. Before: stdlib:core 1.22.0, parent baseline fb55c45186f62e8c5dbccbf127e11dfb23988623;
benchmark tooling commit 54a21d6b347f04f8341d0fa4e5b266a79bcfc54e. After: stdlib:core 1.23.0,
optimization commit 3cd7c88bad5dc53b7762eb5c4befe977b086bcc6. Both use pinned VM bb592ea2c3f69d10503efdbf7c98186ae0b1eb5b
(0.24.0), release-mode native code and the same measurement harness.

Artifacts are preverified. VM admission/start/teardown are outside processing time. Building the shared
input list and the ready print is measured separately. Hot measurement begins after the ready request;
accounting excludes list creation but includes final checksum output. No execution trace is enabled.
Seven samples alternate forward/reverse case order after warmup; before and after are separate runs.
Timing uses a 16 MiB Guest heap. The 256 KiB pressure run also succeeds for every case.

Minimum execution heap is a 16-byte-granularity bisection threshold at which the entire program completes,
including input creation and all traversals, with the preceding threshold checked for exhaustion. It is
Guest arena capacity, not allocated byte volume, live retained payload, native frame storage or host RSS.
Measurement copies remove only the compiler's 64 KiB admission floor; normal compiled programs retain it.
Dynamic Guest units are accounting units, not bytes. Raw counters and p10/p50/p90 timings are in the TSVs.

Linux x86_64 on AMD Ryzen 9 9950X3D; rustc version and environment recorded in the completion evidence.
Other development work was active on the same machine; elapsed medians are descriptive and should not
be treated as controlled causal before/after speed measurements. Deterministic instruction/unit counts,
checksums and inspected scalar layouts support the representation claims.

## Reproduce

Run in a clean isolated checkout with its pinned VM:

```sh
./gradlew-sandbox-dev-parallel-summary benchmarkSequencePipelines -PsequenceBenchmarkLabel=current
```

For the boxed baseline, use the benchmark-tooling commit above. For specialized caches, use the
optimization commit or a descendant. Reports appear under build/reports/benchmarks/sequences/current;
generated sources/artifacts are under modules/common/compiler-k2/build/generated/benchmarks/sequences.
Do not overwrite one stage's artifacts while preserving the other stage's report.

The opt-in generator and release runner validate all twelve programs. Focused Sequence conformance
also covers primitive boundaries/NaN/signed zero, nullable/reference identity, independent iterator state,
early stopping and callback failures, and inspects scalar fields and absence of per-element boxing.
Mutable-list VM conformance protects the shared factory selection. No full-checkout or release claim.
