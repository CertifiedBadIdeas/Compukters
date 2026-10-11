---
layout: default
title: Single executable serialization, 2026-10-11
description: Removing duplicate final validation and encoding from Guest compilation.
section: contributors
permalink: /SINGLE-ARTIFACT-WRITE-2026-10-11/
---

# Single executable serialization, 2026-10-11

The pooled warm PID median fell from **427 ms to 390 ms**, approximately **9%**, in three local baseline/changed
JVM pairs. All three PID pairs improved, by 1–10%; the variation limits the precision of that percentage. Hello World
had mixed pair results, so no consistent improvement is claimed for that workload. Executable bytes stayed identical.
These measurements cover direct compiler-adapter calls, excluding JVM launch, worker IPC, dependency resolution and
Minecraft UI latency.

## Change and limits

[Issue #729](https://github.com/CertifiedBadIdeas/Compukters/issues/729) removes the redundant final executable write.
Previously, `LibraryModuleLinker.link` wrote the linked model to validate it under default limits, then
`K2CompilerAdapter` validated and encoded it again under request limits. The adapter now consumes
`LibraryModuleLinker.linkAndWrite`, which shares the same assembly, reachability, relocation and ABI inference path
and calls `ArtifactWriter` once for the final executable.

Each resource ceiling is the minimum of the default linker limit and the caller's limit. The diagnostic budget
remains the caller's. This preserves restrictions from both previous passes, including when a caller requests limits
above the defaults. Ordinary model-returning `link` entrypoints still validate and throw their existing invalid-model
exception; the serialized path returns writer failures to the adapter's existing artifact diagnostic handler.
Assembly and dependency resolution errors retain their existing rejection path.

No Guest language, CPKT format, platform ABI, native ABI or project/result cache changes are involved. Library semantic
hash encoding remains separate from this final executable write.

## Workloads and procedure

The baseline was built at `4bbfeb7046403303831716db99cddac700f52d43`; the changed implementation is
`9a3290c05f3f28098811a7c03c741f331c66e23f`. Compiler payloads were frozen separately before measurement. Only the
compiler-artifact and compiler-k2 JARs differ between these payloads. Both use the same packaged platform and frozen
Sable/Propulsion addon bundle bytes.

PID has two frozen Kotlin files totalling 10,665 bytes; Hello World prints one message from a 44-byte source.
Both resolve `stdlib:core`, `compukter:core`, `sable:api` and `propulsion:api` with the same identities. The original
IDE project was preserved, and its private sources are not published. Hashes, payload manifests and verifier identity
are recorded in [provenance]({{ '/benchmarks/single-artifact-write-729-2026-10-11/provenance.json' | relative_url }}).

For each workload, three fresh JVM pairs alternate baseline/changed order. Each JVM retains one adapter and performs
ten actual compilations without phase instrumentation or an output-result cache. Sample 1 is cold; samples 2–3 are
excluded from the warm aggregate; samples 4–10 give 21 warm samples per workload/variant. Compilation timing excludes
probe setup and JVM launch. The preliminary smoke run was excluded from measurements.

Environment: Linux x86-64, Ryzen 9 9950X3D, powersave governor, Kotlin 2.4.10, OpenJDK 21, `-Xmx2g`. Frequency, CPU
affinity and background activity were not controlled. Test/build commands finished before the paired trials.

| Workload | Baseline warm median | Single-write warm median | Difference | Baseline cold median | Single-write cold median |
| --- | ---: | ---: | ---: | ---: | ---: |
| PID | 427.1 ms | 389.8 ms | 37.3 ms / 8.7% | 4960.1 ms | 5021.1 ms |
| Hello World | 240.0 ms | 233.7 ms | 6.3 ms / 2.6% | 4708.3 ms | 4512.2 ms |

Pooled medians are not the median of paired speedups. The per-pair warm medians show the variation:

| Workload / pair | Baseline | Single write | Saved |
| --- | ---: | ---: | ---: |
| PID / 1 | 402.0 ms | 396.7 ms | 1.3% |
| PID / 2 | 434.3 ms | 389.2 ms | 10.4% |
| PID / 3 | 404.2 ms | 389.8 ms | 3.6% |
| Hello World / 1 | 231.0 ms | 238.3 ms | -3.2% |
| Hello World / 2 | 240.0 ms | 233.7 ms | 2.6% |
| Hello World / 3 | 249.0 ms | 228.0 ms | 8.4% |

Only three cold observations were taken per variant; no reliable first-compilation improvement is claimed.

## Compatibility and verification

All 120 compilations succeeded, and all 60 outputs for each workload match across both variants:

| Workload | Bytes | SHA256 |
| --- | ---: | --- |
| PID | 152916 | `bc01f4194e6f7c247ef71b9cccf16145f44bccd1e959d83c3f149530556027ee` |
| Hello World | 4632 | `b8f3d9dd969b6654964cfecc453f166910a7e29b57e93ab11f42ccec3ef17b68` |

All outputs passed stateless native ABI 22 verification. This does not establish PID session admission or flight
behavior. PID's current SHA256 differs from the earlier #727 measurement despite equal source hashes and size;
this comparison uses a freshly built baseline with the current packaged platform.

Focused checks passed: 187 compiler-artifact tests, 194 compiler-k2 tests, and lint for both modules. Five opt-in
benchmark generators were skipped. Real VM execution passed `generic-library`, `generic-interface`, `dispatch`,
`exceptions` and `tasks` scenarios.

Tests compare serialized bytes/digests against ordinary linking across changed projects, replaced libraries and
exported fragments; exercise exact and restrictive byte limits, code/section limits, invalid entry contracts and
subsequent recovery; and reject a 400-library fragment exceeding default section limits even when the caller raises
the limit. Existing adapter tests cover request limits, identities, selection changes and recovery. Verification is
focused; no full-checkout or release-readiness claim is made.

## Data and reproduction

Raw [samples]({{ '/benchmarks/single-artifact-write-729-2026-10-11/samples.json' | relative_url }}),
[summary]({{ '/benchmarks/single-artifact-write-729-2026-10-11/summary.json' | relative_url }}),
[paired medians]({{ '/benchmarks/single-artifact-write-729-2026-10-11/paired-medians.json' | relative_url }}),
[native verification]({{ '/benchmarks/single-artifact-write-729-2026-10-11/verification.json' | relative_url }}) and
[focused checks]({{ '/benchmarks/single-artifact-write-729-2026-10-11/checks.json' | relative_url }}) accompany the report.

Build tooling at each compared revision with
`./gradlew-sandbox-dev-parallel-summary :tooling-runtime:prepareToolingRuntimeBundle` under JDK 25, freeze each
payload, then use its compiler-profile classpath under JDK 21 with an in-process `K2CompilerAdapter` probe. Select
identical module/content identities and addon bytes, compile the same immutable sources ten times per retained
adapter, alternate variant order between JVM pairs and record every artifact. Compare bytes and perform native
verification before drawing timing conclusions. The ignored task benchmark directory retains the probe, frozen
inputs, logs and outputs; public provenance contains their hashes.
