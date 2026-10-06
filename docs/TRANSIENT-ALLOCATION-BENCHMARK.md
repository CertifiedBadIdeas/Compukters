---
layout: default
title: Transient allocation benchmark
description: Reproduce Guest loop and transient allocation measurements.
section: contributors
permalink: /TRANSIENT-ALLOCATION-BENCHMARK/
---

# Guest loop and closure allocation benchmark

Run `./gradlew-sandbox-dev-parallel-summary benchmarkTransientAllocations`.
The opt-in generator compiles eight programs with the same 256-element `List<Int>` and 300 summation rounds.
Each program checks the same checksum, 86,957,440, and retains the original list for a final scan.
The harness verifies the artifacts with the Rust VM and separates construction from the repeated operation at
`println("ready")`. It takes three interleaved timing samples and a separate pressure run at a 16 KiB Guest heap.
This is managed Guest heap evidence, not total host process memory per VM.

Sources are retained under `modules/common/compiler-k2/build/generated/benchmarks/transient-allocations`.
Reports are under `build/reports/benchmarks/transient-allocations`.
The measurement artifact removes the compiler's normal 64 KiB admission floor; it does not bypass the Rust verifier.
Fixed-budget runs leave minimum-heap columns empty. `timing_heap_bytes` and `pressure_heap_bytes` distinguish timing
fallback from the requested pressure budget. No fallback was needed in the recorded baseline.

Baseline captured on 2026-09-27, before changing capture-cell selection:
[measurements]({{ '/benchmarks/transient-allocations-2026-09-27-before.tsv' | relative_url }}),
[raw samples]({{ '/benchmarks/transient-allocations-2026-09-27-before-samples.tsv' | relative_url }}).
All eight checksums passed. `fold-read-var` alone incurred GC work (737 maintenance units); its otherwise identical
`fold-val` control incurred none. The compiler allocated a separate typed cell for the read-only captured `var`.
The mutable control reassigns its captured variable after constructing the lambda and checks that the lambda sees
that new value. This checks aliasing rather than just a numerically equivalent sum.

Instruction and resource units are deterministic VM work counters, not allocation counts or allocated bytes.
Three timing samples are exploratory and insufficient to establish a small CPU speedup. The cases compare ordinary
indexed loops, iterator loops, uncaptured `fold`, scalar captures, shared mutable captures, reference captures, and
reuse of a prebuilt function value. They do not establish that all loops or all collection operations have the same cost.

## Read-only captured variables

The compiler now retains a shared cell only when it finds an assignment to that captured variable anywhere in the
relevant IR roots, including nested closures. This is conservative: even a dead or pre-capture assignment retains
its cell. No escape analysis, closure lifetime assumption, or change to Guest APIs is required.

After the change:
[measurements]({{ '/benchmarks/transient-allocations-2026-09-27-after.tsv' | relative_url }}),
[raw samples]({{ '/benchmarks/transient-allocations-2026-09-27-after-samples.tsv' | relative_url }}).
All eight programs and all 24 timed samples passed at the requested 16 KiB heap with no fallback.

| `fold-read-var`, 300 rounds | Before | After |
| --- | ---: | ---: |
| Extra captured-variable cells | 300 | 0 |
| Cumulative bytes allocated for those cells | 4,800 | 0 |
| Hot instructions | 4,707,575 | 4,630,175 |
| Hot fixed units | 10,030,866 | 9,875,466 |
| Hot dynamic units | 911 | 611 |
| Hot GC maintenance units | 737 | 0 |
| Artifact bytes | 57,944 | 57,688 |

The byte saving follows from one 16-byte Int capture cell per round and the emitted allocation path; it is
cumulative allocation traffic, not a 4,800-byte reduction in live memory. The closure and iterator still allocate.
The optimized case now matches the `fold-val` control in artifact size and deterministic hot work counters.
All seven other cases retained their artifact size and deterministic hot counters, including the mutable control.
Fixed work decreased by approximately 1.55% in this program. This is not a wall-clock CPU speedup claim: the after
run overlapped focused compiler/conformance checks, and wall-clock times also changed for unchanged controls.

Verification: compiler allocation-contract test, `testKotlinFunctionValuesVmConformance`,
`testKotlinDestinationVmConformance`, `testKotlinGenericFunctionsVmConformance`, compiler/engine Kotlin lint,
build-script tests, and the benchmark. These are focused checks, not complete checkout or release verification.
