---
layout: default
title: Executable method reachability (2026-10-11)
section: contributors
permalink: /EXECUTABLE-METHOD-REACHABILITY-2026-10-11/
---

# Executable method reachability

Final executable linking now removes unused class/interface methods and their otherwise unreachable dependencies.
On frozen `pid_one_thruster` sources this reduces the physical artifact by 61,184 bytes (28.6%). Source diagnostics,
GC checkpoints and exported library method surfaces remain present. Native ABI 22 and CPKT 3.0 are unchanged.
The work is tracked by [#723](https://github.com/CertifiedBadIdeas/Compukters/issues/723).

Baseline: `fd220ec4`; compiler implementation: `d9ef4f62`; pinned VM: `d776945f` (0.22.0).
Both measurements freshly compile identical source snapshots with Kotlin 2.4.10. Both inputs select the same
`stdlib:core`, `compukter:core`, Sable and Propulsion modules and identical addon payloads; Hello World deliberately
keeps that same dependency selection to check final pruning. The user project and addon sources are unchanged. Frozen PID sources remain in the local measurement directory;
this archive records their hashes and does not include a public PID source reproducer.

## Measured sizes

| Input | Before, bytes | After, bytes | Saving | Functions before → after |
| --- | ---: | ---: | ---: | ---: |
| `pid_one_thruster` | 214,100 | 152,916 | 28.6% | 232 → 52 |
| Hello World | 6,708 | 4,632 | 30.9% | 9 → 3 |

PID contains two source files totaling 10,665 bytes. Hello World is a 44-byte source containing
`fun main() { println("Hello, world!") }` with ordinary line breaks. Fifteen separately generated outputs per
input and compiler variant have identical hashes. All 60 paired outputs pass the public native artifact verifier.
The compiled PID was not exercised against moving Minecraft constructions in this investigation.

The largest PID reductions are function records/indexes (15,052 bytes), safepoint roots (15,708), instructions/block
index (9,204), types (7,936), and block tables (6,856). DEBUG, source-position and path sections are byte-size unchanged;
this optimization does not strip debug information. Root checkpoints belonging to retained code remain derived by
the existing liveness pass. The earlier read-only linker model used dynamic sites even in otherwise dead bodies;
its 29,443-byte candidate estimate therefore understated this fixed-point transformation and associated dependencies.

## Reachability and compatibility

Calls, imports and class initializers seed method reachability. Every retained virtual or interface-owned declaration
retains conservative matching name/parameter-count candidates on all reachable types, including implementations,
inherited methods, defaults, bridges and declarations that influence default-method selection. Newly discovered types
and bodies extend this closure to a fixed point. Retained direct-call targets with virtual flags also trigger the
closure because native admission resolves all retained dispatch declarations. More exact signature/ancestry pruning
is outside this change. Relocation rebuilds both method start and count, retaining sorted contiguous ranges.
Library fragment assembly preserves complete method ranges; only final executables become smaller.

Minimum semantic Runtime ABI follows retained instructions and type layouts. Array/exception root superclass methods
explicitly require ABI 1.10: before this work, retained unused hash bodies could incidentally supply a higher floor.
Programs without those bodies can now have a lower correct minimum. Explicit caller-declared floors are preserved by
default. The represented type/instruction contracts, native ABI and debug/root reader capabilities are unchanged.

## Compilation time

Three alternating before/after JVM pairs per input each perform five fresh compilations. Sample 1 is cold, sample 2
is JIT warm-up, and samples 3–5 contribute nine warm observations per variant. Timing uses the actual compiler adapter
from its built tooling payload, excludes JVM launch, adapter construction and result caching, and follows completed
Gradle verification. Libraries are retained within each JVM; project compilation remains fresh. The host is a Ryzen
9950X3D with powersave governor, no fixed affinity; these timings are observations, not a latency guarantee.

| Input | Cold median before → after, ms | Warm median before → after, ms |
| --- | ---: | ---: |
| PID | 4,221.6 → 4,278.7 | 494.5 → 503.1 |
| Hello World | 4,133.7 → 3,831.1 | 303.9 → 300.0 |

These samples do not demonstrate a material compile-speed improvement. The transformation runs after specialization
and lowering; it saves executable records rather than avoiding frontend or method-body generation. Cold measurements
include preparing all selected library modules and differ from an IDE build after its asynchronous preparation.

## Verification and reproduction

181 compiler-artifact tests, 192 compiler-K2 tests, 64 compiler-client tests and both affected module lint checks pass.
Five opt-in benchmark generators are skipped in the ordinary K2 suite. Twenty-nine Kotlin-to-Rust execution scenarios
pass, covering dispatch/defaults/super calls, imported and generic libraries, providers, collections, exceptions,
initializers, constructors, accessors, value classes/MFVC, closures, tasks, numeric operations and nullable/array use.
The conformance lockfile now matches the unchanged pinned VM 0.22.0; native commands still use `--locked --offline`.
A local-only Gradle init script shares one Cargo target directory between selected scenarios, avoiding repeated VM
compilation without changing harness arguments or checks. No full-checkout or tagged-release readiness is claimed.

Rebuild each revision with `:tooling-runtime:prepareToolingRuntimeBundle`; compile the same frozen project with
`K2CompilerAdapter`/`CompileRequest`, exact module identities and addon payload bytes. Run
`python3 docs/_tools/analyze_artifact_size.py BEFORE.cpkt AFTER.cpkt` for physical section accounting.
`MethodReachabilityTest` protects pruning, sparse/empty ranges, dispatch closure order/arity, unreferenced types,
ABI inference and library export preservation. The selected native task names are listed in the verification archive.

[Section inventories]({{ '/benchmarks/method-reachability-723-2026-10-11/artifact-size-analysis.json' | relative_url }}),
[raw alternating timing samples]({{ '/benchmarks/method-reachability-723-2026-10-11/paired-samples.json' | relative_url }}),
[timing summary]({{ '/benchmarks/method-reachability-723-2026-10-11/paired-summary.json' | relative_url }}),
[source/payload identities and environment]({{ '/benchmarks/method-reachability-723-2026-10-11/provenance.json' | relative_url }}),
[verification scope]({{ '/benchmarks/method-reachability-723-2026-10-11/verification.json' | relative_url }}).
