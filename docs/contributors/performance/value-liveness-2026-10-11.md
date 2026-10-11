---
layout: default
title: Value liveness with indexed bitsets
parent: Performance
nav_exclude: true
---

# Value liveness with indexed bitsets — 2026-10-11

Replacing the value-liveness fixed point's temporary hash sets with indexed bitsets reduces repeated PID
compilation from 312 to 280 ms without edits, and from 315 to 293 ms after constant edits in this experiment.
The main compilation thread allocates 370 to 306 MiB per unchanged PID compilation in a separate profile.
All corresponding outputs remain byte-identical. First-build results show a smaller, less certain benefit.

## Implementation and invariants

The baseline is `f5f7b4372b24187f75619d49673395cb38a96257`; the implementation is
`06fccf7fd5bd740c18e892b30a750e244b36ad0e`. This changes the internal compiler-artifact analysis only.
It does not change a public API, artifact format, ABI, runtime collector, or library preparation policy.

The old solver repeatedly computed instruction reads and writes, merged sets, subtracted definitions, and
queued object-keyed graph nodes. The new solver indexes instruction nodes and register identities locally
to each function, computes reads and writes once, and propagates `BitSet` values through predecessor arrays.
A bounded integer queue admits each node at most once. Final boundary sets are independent snapshots;
neither mutable scratch data nor project data survives the analysis invocation.

Exception definitions remain specific to their outgoing edge. A catch register excluded from one edge must
still survive when another edge contributes it. Reachability, empty entry blocks, function boundaries,
cycles, and sparse register IDs retain their previous behavior. The seven new characterization tests passed
against the old solver before the implementation changed. Existing reference-liveness and register-compaction
tests protect the consumers of this analysis, including GC root maps and suspension boundaries.

## Method

Freeze baseline and optimized tooling payloads from the same checkout. Only
`compiler/lib/compiler-artifact-0.6.0.jar` changes, together with its compiler manifest and enclosing tooling
manifest. All other payload files are identical. Both use Kotlin 2.4.10, language 2.4, codegen ABI 1,
artifact writer 1, and platform identity
`6ebe484a185fd12ede912ec1284cd968ae6863f951e2ef556bf8b7a687c2a75c`.
This includes the current reference-array support and differs from the older first-build report's payload.

Run the real `K2CompilerAdapter` directly in fresh JDK 21 JVMs with `-Xmx2g`, on Linux x86-64 and a
Ryzen 9 9950X3D using the powersave governor. CPU affinity, frequency and background activity are uncontrolled.
Each workload/mode has three baseline/optimized pairs, with order alternating between pairs. Each JVM compiles
ten times. Sample 1 measures its first compilation; samples 2–3 are excluded from the warm aggregate;
samples 4–10 provide 21 warm observations per variant. JVM warmup can continue during those observations.
Timing covers `adapter.compile`, excluding JVM startup, source collection, dependency resolution, IPC,
request construction, and output writes. There is no compilation-result cache or profiling in these runs.

In **cold** mode there is no explicit library preparation before the first compilation. Subsequent requests
reuse the same adapter and libraries. In **prepared** mode library preparation finishes before sample 1.
The private PID snapshot has two source files and the Sable/Propulsion guest API bundles. Prepared PID samples
2–10 replace the original `HOVER_KN = 33.0` with `33.1` through `33.9`, each starting from the original snapshot.
The original project is untouched and those changed thrust values are never executed in a flight session.
The editor input is the frozen public `system/programs/edit.kt`, 19,260 bytes and 614 lines, with base libraries
only. Both editor modes submit unchanged sources. PID sources are not published; their hashes are recorded.

## Results

Times are medians in milliseconds. First-compilation medians have only three observations. Warm reductions
compare pooled medians; the last column shows the three separate paired reductions, including the negative one.

| Workload/mode | First baseline → optimized | Warm baseline → optimized | Warm reduction | Paired reductions |
| --- | ---: | ---: | ---: | --- |
| PID, cold then unchanged | 4,775.2 → 4,278.7 | 311.5 → 280.1 | 10.1% | 9.7 / 16.5 / 7.9% |
| PID, prepared then constant edits | 1,119.8 → 1,070.1 | 315.3 → 293.4 | 6.9% | 17.9 / 15.5 / 0.8% |
| Editor, cold then unchanged | 3,850.5 → 3,662.1 | 235.4 → 223.9 | 4.9% | 13.6 / −3.4 / 6.5% |
| Editor, prepared then unchanged | 947.0 → 924.9 | 246.7 → 211.6 | 14.2% | 15.3 / 13.8 / 17.6% |

Explicit preparation medians are 3,805.7 → 3,561.1 ms for PID and 2,691.6 → 2,532.4 ms for the editor.
These costs are excluded from the prepared first-compilation column. Differences between warm cold/prepared
rows include different warmup histories and do not establish a causal effect of preparation. The pair spread
limits the precision of speedup estimates; there is no guaranteed percentage improvement or UI-latency claim.

## Separate CPU and allocation profile

Each variant runs 70 unchanged PID compilations in another fresh JVM. JFR records only samples 11–70, using
2 ms execution sampling and default stack depth 64. An exact `ThreadMXBean` allocation counter surrounds each
`adapter.compile` on the main thread. These profiled runs do not contribute to the timing table.

| Measure across the 60 recorded compilations | Baseline | Optimized |
| --- | ---: | ---: |
| Median bytes allocated on the main thread, MiB | 370.23 | 306.30 |
| Main-thread execution samples | 3,372 | 2,942 |
| Samples whose nearest project frame is in `ValueLiveness` | 232 (6.88%) | 96 (3.26%) |
| Recorded GC events | 258 | 223 |

Main-thread allocation falls by 17.27%, about 64 MiB per compilation. This measures allocated bytes during
compilation, not peak heap, process RSS, other compiler threads, or guest runtime allocations. The CPU grouping
includes `ValueLiveness` helper methods and consistently excludes the separate `ValueLivenessKt` register-read
helpers. Sample attribution is affected by JIT inlining and stack truncation; it is not phase wall time.
GC event counts are descriptive and do not establish a pause-duration reduction.

## Verification and reproduction

The complete compiler-artifact test suite passes: 194 tests, including the seven characterization tests.
The compiler-k2 suite has 194 passing tests and five skipped opt-in benchmark generators, with no failures.
Compiler-artifact main/test lint passes. Five real Kotlin-to-VM conformance scenarios pass: generic-library,
generic-interface, dispatch, tasks, and exceptions. These checks cover the affected compiler paths and do
not claim complete checkout verification or release readiness.

All 240 timing outputs and 140 profile outputs pass native ABI-22 stateless artifact verification. Every
corresponding baseline/optimized output is byte-identical, including the nine distinct constant-edit variants.
PID outputs remain 152,916 bytes; editor outputs remain 151,112 bytes. Verification uses the pinned VM
`d776945fafe131eaa7664aa4cf47349a1ffd4b15`; native library identity is recorded alongside output hashes.
The conformance scenarios provide execution evidence separately from stateless benchmark verification.

[Samples]({{ '/benchmarks/value-liveness-2026-10-11/samples.json' | relative_url }}),
[summary]({{ '/benchmarks/value-liveness-2026-10-11/summary.json' | relative_url }}),
[paired medians]({{ '/benchmarks/value-liveness-2026-10-11/paired-medians.json' | relative_url }}),
[profile]({{ '/benchmarks/value-liveness-2026-10-11/profile-summary.json' | relative_url }}),
[provenance]({{ '/benchmarks/value-liveness-2026-10-11/provenance.json' | relative_url }}),
[checks]({{ '/benchmarks/value-liveness-2026-10-11/checks.json' | relative_url }}), and
[output verification]({{ '/benchmarks/value-liveness-2026-10-11/verification.json' | relative_url }})
preserve the evidence. Raw stdout logs and the two Kotlin probes are beside them. Raw local JFR recordings,
CPKT files, frozen tooling payloads and private input snapshots are retained under
`build/reports/benchmarks/value-liveness/` in the main checkout.

Compile `CompileProbe.kt` against the chosen tooling profile's manifest classpath. Run it with the project
root, a fresh output directory, and the tooling payload root as the first three arguments, followed by Sable
and Propulsion `.cagb` paths for PID only. Set `BENCH_MODE=cold` or `prepared`. Use the same probe and frozen
inputs for both payloads and alternate order across three pairs. `ProfileProbe.kt` uses the same argument
shape and writes its separate 70-compilation profile to its output directory. Source hashes in provenance
refer to the executed probes before the license header was added to their published copies.
