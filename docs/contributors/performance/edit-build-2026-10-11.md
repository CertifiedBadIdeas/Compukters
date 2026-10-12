---
layout: default
title: PID recompilation after source edits
section: contributors
---

# PID recompilation after source edits — 2026-10-11

Changing a source file does not bring back the cold compiler cost in this experiment. After five real warmup
compilations, a repeated PID build takes about 273 ms without changes, 277 ms after a comment edit, and 286 ms
after changing a numeric constant. The small differences do not establish a meaningful edit penalty with only
three workers per variant and uncontrolled background activity.

## Method

Use the same frozen indexed compiler payload and private PID snapshot as the
[first-build experiment](first-build-2026-10-11.md). This payload represents compiler sources at `aa748215`,
preceding the concurrent reference-array changes. This report does not measure the newer compiler/platform.
All variants retain the same real worker process and selected prepared libraries across requests. Request
encoding, IPC, compilation, and result decoding are timed. Source mutation and writing the output are excluded.
Neither the IDE persistent compilation cache nor an output-result cache is used.

Each of three fresh workers per variant prepares its libraries and compiles the original PID five times.
The next ten compilations are measured. Variants alternate order between trials:

- **Unchanged:** submit the original sources again.
- **Comment:** append a trailing comment containing the edit revision to `src/main.kt`, producing different source bytes each time.
- **Code:** replace the existing `HOVER_KN` constant's value, cycling through nine distinct decimal values. Each edit starts from the original snapshot.

The code change is a compiler measurement only; changed thrust values are never executed in a flight session.
All other file bytes and library content identities remain fixed. The user project is untouched and its sources
are not published. The environment matches the first-build report: JDK 21, 2 GiB worker heap, 512 MiB metaspace,
Ryzen 9 9950X3D, powersave governor. CPU frequency, affinity, and background activity are uncontrolled.

## Results

Each aggregate covers 30 measured compilations, ten from each worker. First-edit medians cover only three
observations. These are worker round trips, not keystroke or edit-to-visible-diagnostics latency in Minecraft.

| Variant | Median of ten recompilations | First measured recompilation | Trial medians |
| --- | ---: | ---: | --- |
| Unchanged | 272.6 ms | 291.6 ms | 261.9 / 292.6 / 258.1 ms |
| Comment | 277.5 ms | 295.5 ms | 264.5 / 300.9 / 276.4 ms |
| Constant | 285.7 ms | 319.7 ms | 272.2 / 291.1 / 279.4 ms |

JVM warmup continues during the measured requests; these samples are not guaranteed steady state. The timings
are consistent with retained library preparation surviving ordinary source edits. They do not establish
incremental project compilation: project processing is still performed on each request.

## Verification and reproduction

All 135 outputs, including the 45 warmup outputs, pass native ABI-22 stateless artifact verification.
Unchanged, warmup, and comment outputs are byte-identical to the PID artifact from the first-build report.
All 30 changed-code outputs differ from that baseline; each of the nine constant values reproduces the same
hash across its occurrences. Every output is 152,916 bytes. Changed code was not executed. Production code and
protocols were untouched; no production test suite was rerun for this documentation-only stage.

[Samples]({{ '/benchmarks/edit-build-2026-10-11/samples.json' | relative_url }}),
[summary]({{ '/benchmarks/edit-build-2026-10-11/summary.json' | relative_url }}),
[provenance]({{ '/benchmarks/edit-build-2026-10-11/provenance.json' | relative_url }}), and
[verification]({{ '/benchmarks/edit-build-2026-10-11/verification.json' | relative_url }}) include the timings,
input identities, mutation rules, and output hashes. Nine raw stdout logs and `EditBuildProbe.kt` are beside them.

Compile the probe and pass the same five arguments as in the first-build report. Set `BENCH_MODE` to
`unchanged`, `comment`, or `code`. Use a fresh output directory and JVM per trial. Keep the first five
compilations as warmup, then aggregate samples 6–15. Never replace the original project files on disk.
