---
layout: default
title: Prepared library linking, 2026-10-11
description: Reusing stable library hashes and export indexes across Guest compilations.
section: contributors
permalink: /PREPARED-LIBRARY-LINKING-2026-10-11/
---

# Prepared library linking, 2026-10-11

Repeated PID compilation fell from **455 ms to 401 ms**, approximately **12%**, in paired local measurements.
Hello World with the same library selection fell from **275 ms to 219 ms**, approximately **21%**.
Both workloads retained identical executable bytes. These are direct compiler-adapter measurements, excluding
JVM launch, worker IPC, dependency resolution and Minecraft UI latency.

## Change and ownership

[Issue #727](https://github.com/CertifiedBadIdeas/Compukters/issues/727) adds `PreparedLibraryModules` beside the
existing prepared library frontend. It computes library module semantic hashes and kind/name export indexes once
for the retained module selection. Duplicate exports remain separate entries, preserving incomplete/duplicate export
rejection and canonical defining-library precedence over embedded dependency copies.

Each link creates separate metadata caches for application modules and rewritten or relocated copies. Those
request-local entries never enter the prepared library maps. The input library models must remain immutable during
preparation's lifetime, as do the already retained parsed platform artifacts. The compiler's existing selection key
uses module identities and content hashes, replaces the one retained entry on selection changes, does not retain
failed preparation, and releases the entry on worker shutdown. Request identity, addon payload and closure validation
still runs before cache reuse. Future mutable user libraries require explicit invalidation.

This change preserves the Guest language surface, CPKT format and native ABI. It does not remove the linker's
validation serialization, add a project/result cache, or change IR/lowering scheduling.

## Workloads and procedure

Baseline tooling payload `6171ed8a031149bf042040f56b891fe975c19b3dff7ea6d2ec8c3cddd4a19aee` was built at `f7910556`;
its compiler sources match the task starting commit `45c2eb2a`. The changed payload was built from `1ea6ba82`.
Full payload identities, addon identities and source/probe hashes are recorded in
[provenance]({{ '/benchmarks/prepared-library-linking-727-2026-10-11/provenance.json' | relative_url }}).

PID uses frozen `pid_one_thruster` sources: two Kotlin files, 10,665 bytes. Hello World is 44 bytes and prints one
message. Both select `stdlib:core`, `compukter:core`, `propulsion:api` and `sable:api`, with identical trusted addon
bundle bytes in both variants. The original IDE project was not modified; its private source files are not published.

Each workload ran three alternating baseline/changed JVM pairs, ten actual compilations per JVM. Sample 1 is cold;
samples 2–3 are excluded from the warm aggregate; samples 4–10 produce 21 warm samples per workload/variant. Each JVM
owns one adapter and reuses its library preparation, with fresh project compilation each time. No phase timers,
output-result cache or instrumentation overrides were present in these trials.

Environment: Linux x86-64, Ryzen 9 9950X3D, powersave governor, Kotlin 2.4.10, OpenJDK 21, `-Xmx2g`. CPU affinity and
frequency were not fixed, and background activity was not controlled. The timings demonstrate a local effect, not
an in-world latency guarantee. Warm medians improved in all three pairs for both workloads.

| Workload | Baseline warm median | Prepared warm median | Saved | Baseline cold median | Prepared cold median |
| --- | ---: | ---: | ---: | ---: | ---: |
| PID | 454.6 ms | 400.9 ms | 53.7 ms / 11.8% | 4247.7 ms | 4322.0 ms |
| Hello World | 275.2 ms | 218.7 ms | 56.5 ms / 20.5% | 4091.8 ms | 3795.6 ms |

Preparation now also computes linker metadata, so the warm benefit need not improve first-use latency. Only three
cold observations were taken per variant; no reliable cold-compilation improvement is claimed.

## Compatibility evidence

All 120 compilations succeeded and all output hashes match the preceding method-pruning reference:

| Workload | Bytes, both variants | SHA256 |
| --- | ---: | --- |
| PID | 152916 | `aa39457e2bc544d584a94bfa138dc6f3e8136a3832f188dc53d41d461bc4f21c` |
| Hello World | 4632 | `b8f3d9dd969b6654964cfecc453f166910a7e29b57e93ab11f42ccec3ef17b68` |

All 120 outputs also passed stateless native ABI 22 verification. This does not assert session admission or flight
behavior for PID. Focused checks passed: 184 compiler-artifact tests, 192 compiler-k2 tests, and lint for both modules.
Five opt-in benchmark generator tests were skipped in the compiler suite. Native Kotlin-to-VM execution passed
`generic-library`, `generic-interface`, `dispatch` and `exceptions` scenarios.

New tests compare prepared/ordinary linker output across changed applications, replaced libraries and exported
fragments; preserve layout, missing/duplicate export and ambiguous-owner rejection; and prove library hash reuse
across requests while copied/project module hashes remain request-local. Existing compiler tests cover changed
project declarations/specializations, reordered and replaced selections, malformed content identities, request
limits and recovery.

No full-checkout or release-readiness claim is made. Integration into newer `dev` is checked separately from this
controlled benchmark: the baseline comparison intentionally holds the platform and addon selection constant.

## Data and reproduction

Raw [samples]({{ '/benchmarks/prepared-library-linking-727-2026-10-11/samples.json' | relative_url }}),
[summary]({{ '/benchmarks/prepared-library-linking-727-2026-10-11/summary.json' | relative_url }}),
[per-pair medians]({{ '/benchmarks/prepared-library-linking-727-2026-10-11/paired-medians.json' | relative_url }}),
[native verification]({{ '/benchmarks/prepared-library-linking-727-2026-10-11/verification.json' | relative_url }}) and
[focused checks]({{ '/benchmarks/prepared-library-linking-727-2026-10-11/checks.json' | relative_url }}) accompany this report.

Build compiler tooling at each compared revision with
`./gradlew-sandbox-dev-parallel-summary :tooling-runtime:prepareToolingRuntimeBundle` under JDK 25.
Use the resulting compiler-profile classpath under JDK 21 and an in-process `K2CompilerAdapter` probe. Select the same
trusted module/content identities and addon bundle bytes, compile immutable source snapshots ten times per adapter,
write every output, and compare SHA256 before drawing timing conclusions. Alternate variant order between JVM pairs;
record the compiler payload identity, input hashes and native verifier identity. The ignored task measurement
folder retains the probe, scripts, raw logs and outputs for the recorded private PID input.
