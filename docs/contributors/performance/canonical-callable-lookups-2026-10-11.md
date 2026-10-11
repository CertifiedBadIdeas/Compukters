---
layout: default
title: Indexed callable lookup, 2026-10-11
description: Avoiding repeated full registry scans during Guest lowering.
section: contributors
permalink: /CANONICAL-CALLABLE-LOOKUPS-2026-10-11/
---

# Indexed callable lookup, 2026-10-11

Repeated PID compilation fell from **446 ms to 305 ms**, approximately **31%**, in three local paired measurements.
Hello World fell from **245 ms to 159 ms**, approximately **35%**. All three pairs improved for both workloads, and
all executable bytes stayed identical. The measurements cover direct compiler-adapter calls, excluding JVM launch,
worker IPC, dependency resolution and Minecraft UI latency.

## Cause and change

After the single-write optimization, a JFR recording of 60 warmed PID compilations identified
`resolveTrustedIntrinsic` as the nearest Compukters frame in 18.2% of main-thread execution samples. It scanned the
entire admitted intrinsic registry for each call, reconstructing every candidate's full callable name. Platform
function searches likewise recomputed an IR function's full name inside linear scans, including parameter lowering.

`TrustedIntrinsicRegistry` now computes an index by full callable name and signature when its immutable handler map
is constructed. Lookup still filters candidates by the known source module, or by selected modules when the source
is unknown. A unique-handler query retains `singleOrNull` semantics; inline admission retains its distinct
existence-query semantics. Matching entries are kept separately rather than overwritten, including structurally
different callable IDs that project to the same full name.

Each `CompilationSession` takes a snapshot of platform function links and groups all overloads by symbol. Lowering
computes the queried function's name once, computes its canonical signature only when named candidates exist, and
compares those candidates. Constructor and inline admission use the same request-owned index. These indexes contain
immutable callable metadata; they neither cache IR or resolved project calls nor persist project data across requests.

The Guest surface, capability/module admission rules, CPKT format and native ABI remain unchanged. Only the
compiler-k2-engine JAR differs between the compared compiler payloads; the packaged platform is byte-identical.

## Measurements

Task start: `e2e984b8aaffbb670b37954cd9233cd447b7426d`. Implementation:
`2bf92aa74958e223a535f4c3537818777a23ee7c`.
The frozen baseline payload was built at `9a3290c0`; its compiler sources match the task start. Intervening changes
only affected Minecraft UI and documentation. Both payloads use the same packaged platform, addon bundles and source
snapshots. These are fresh paired comparisons, not a comparison of timings from different optimization reports.

PID has two Kotlin files totalling 10,665 bytes. Hello World prints one message from a 44-byte source. Both resolve
`stdlib:core`, `compukter:core`, `sable:api` and `propulsion:api` using identical trusted content identities. The original
IDE project was preserved; its private sources are not published. Payload manifests, input hashes and verifier
identity are recorded in [provenance]({{ '/benchmarks/canonical-callable-lookups-2026-10-11/provenance.json' | relative_url }}).

Each workload ran three fresh JVM pairs, alternating baseline/indexed order, ten actual compilations per JVM. Each
JVM retained one adapter and its prepared libraries. Sample 1 is cold; samples 2–3 are excluded from the warm
aggregate; samples 4–10 yield 21 warm samples per workload/variant. The trials had no profiler, phase instrumentation
or output-result cache; tests/build commands completed before measurement.

Environment: Linux x86-64, Ryzen 9 9950X3D, powersave governor, Kotlin 2.4.10, OpenJDK 21, `-Xmx2g`. CPU affinity,
frequency and background activity were not controlled. These local results do not establish in-world UI latency.

| Workload | Baseline warm median | Indexed warm median | Saved | Baseline cold median | Indexed cold median |
| --- | ---: | ---: | ---: | ---: | ---: |
| PID | 445.5 ms | 305.3 ms | 140.2 ms / 31.5% | 5559.2 ms | 5436.1 ms |
| Hello World | 245.2 ms | 158.7 ms | 86.4 ms / 35.3% | 4757.2 ms | 4920.9 ms |

Pooled warm medians are not the median of paired speedups. Per-pair warm medians:

| Workload / pair | Baseline | Indexed | Saved |
| --- | ---: | ---: | ---: |
| PID / 1 | 414.5 ms | 288.5 ms | 30.4% |
| PID / 2 | 421.4 ms | 346.9 ms | 17.7% |
| PID / 3 | 467.1 ms | 290.3 ms | 37.9% |
| Hello World / 1 | 241.6 ms | 160.0 ms | 33.8% |
| Hello World / 2 | 273.9 ms | 168.3 ms | 38.6% |
| Hello World / 3 | 239.0 ms | 154.3 ms | 35.4% |

Only three cold observations were taken per variant; no reliable first-compilation improvement is claimed.

## Profile confirmation

Separate before/after JFR runs each compiled PID 70 times, recording iterations 11–70 with 2 ms execution sampling,
default stack depth 64, allocation sampling and GC events. They were excluded from the speed trials. The nearest
Compukters frame in main-thread samples changed as follows:

| Frame | Before, 5084 samples | After, 3155 samples |
| --- | ---: | ---: |
| `resolveTrustedIntrinsic` | 926 / 18.2% | 1 / 0.03% |
| `loweredParameters` | 181 / 3.6% | 3 / 0.10% |

The lookup ceased to be a sampled hotspot. These shares are not disjoint compiler-phase timings: stack truncation
and JIT inlining affect attribution. GC event counts were 218 and 120 over the respective recording windows; this is
supporting evidence of changed workload, not a precise allocation measurement. The recordings cover the repeated
compile loop, including writing its outputs. Both profile runs produced the same PID hash as the paired trials.

## Compatibility and verification

All 120 measured compilations succeeded, and all 60 outputs per workload are byte-identical across variants:

| Workload | Bytes | SHA256 |
| --- | ---: | --- |
| PID | 152916 | `bc01f4194e6f7c247ef71b9cccf16145f44bccd1e959d83c3f149530556027ee` |
| Hello World | 4632 | `b8f3d9dd969b6654964cfecc453f166910a7e29b57e93ab11f42ccec3ef17b68` |

Every measured artifact passed stateless native ABI 22 verification. This does not establish PID session admission
or flight behavior. Focused checks passed: 54 engine tests, 194 compiler-worker tests, engine lint and real VM
execution for `generic-library`, `generic-interface`, `dispatch`, `tasks` and `exceptions`. Five opt-in compiler
benchmark generators were skipped.

New tests preserve exact overload/signature lookup, source module precedence, selected-module rejection,
unknown-source ambiguity (including a primitive handler), existence-query behavior, colliding full names, duplicate
platform links and request-local metadata snapshots. Existing lowering and adapter suites cover inline boundaries,
object receivers, primitive/capability calls, addon spoof rejection, specialization, changed selections and recovery.
No full-checkout or release-readiness claim is made.

## Data and reproduction

Raw [samples]({{ '/benchmarks/canonical-callable-lookups-2026-10-11/samples.json' | relative_url }}),
[summary]({{ '/benchmarks/canonical-callable-lookups-2026-10-11/summary.json' | relative_url }}),
[paired medians]({{ '/benchmarks/canonical-callable-lookups-2026-10-11/paired-medians.json' | relative_url }}),
[profile summary]({{ '/benchmarks/canonical-callable-lookups-2026-10-11/profile-summary.json' | relative_url }}),
[native verification]({{ '/benchmarks/canonical-callable-lookups-2026-10-11/verification.json' | relative_url }}) and
[focused checks]({{ '/benchmarks/canonical-callable-lookups-2026-10-11/checks.json' | relative_url }}) accompany the report.

Build tooling at each compared revision using
`./gradlew-sandbox-dev-parallel-summary :tooling-runtime:prepareToolingRuntimeBundle` under JDK 25. Freeze the payloads
and use their compiler-profile classpaths under JDK 21 with the same in-process `K2CompilerAdapter` probe, selected
identities, addon bytes and immutable sources. Alternate variant order between JVM pairs, retain every output, and
compare bytes/native verification before timing conclusions. Keep JFR runs separate from those trials. The ignored
task benchmark directory retains probe code, inputs, logs and outputs; the workspace profiling folder retains the
baseline JFR, and the task directory retains the indexed JFR. Public provenance records their identities and hashes.
