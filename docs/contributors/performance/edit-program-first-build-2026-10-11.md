---
layout: default
title: Guest editor first compilation
section: contributors
---

# Guest editor first compilation — 2026-10-11

The checked-in guest `edit.kt` program reproduces the first-build pattern observed in PID: preparation removes
most cold work, but the first project compilation still takes roughly a second. A synthetic Hello World moves
more work before that compilation without demonstrating a reduction in total work.

## Method

Use the same real subprocess probe, timings, alternating trial order, JVM settings, and frozen compiler payload
as the [PID first-build report](first-build-2026-10-11.md). Three fresh workers per variant compile the editor five
times. The source snapshot is `system/programs/edit.kt` last changed at `a4a9997122116d118b51a20f3d106da801984d60`:
614 lines, 19,260 bytes, SHA-256 `430233e599ace8858740b1f015bb125b0a171a34a8fdd5d07a475ff90e741348`.
The probe submits it at virtual path `src/edit.kt`; this path participates in diagnostic metadata.

The compiler payload matches sources at `aa748215`, before the concurrent reference-array changes, while the
editor source includes its newer cursor/paste changes. All runs use exactly that combination, which compiled
successfully. This is not a measurement of the latest complete checkout or a comparison to historical editor
size measurements with different source and platform identities.

The editor selects `stdlib:core` and `compukter:core`, including their dependency closure, without Sable or
Propulsion addon bundles. PID selected those addons, so its preparation time should not be interpreted as a
same-library comparison with this editor. Compiler startup includes the real handshake; compilation includes
IPC and result decoding, excluding source collection, dependency resolution, output writes, and Minecraft UI.

Variants are cold compilation, library preparation followed by compilation, and library preparation plus a
synthetic Hello World compilation followed by compilation. Environment: Linux x86-64, Ryzen 9 9950X3D, powersave
CPU governor, JDK 21, 2 GiB worker heap, 512 MiB metaspace. Frequency, affinity, and background activity are uncontrolled.

## Results

Milliseconds, with separate phase medians over three trials. The serial-total median is calculated from each
trial's actual sum, not by adding column medians. Repeated compilation covers samples 4–5, six observations per
variant. Three first compilations per variant are insufficient for tail-latency claims.

| Variant | Startup | Preparation | Hello warmup | First editor compilation | Repeated compilation | Serial total to first result |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Cold | 242 | — | — | 3914 | 272 | 4166 |
| Prepared | 239 | 2690 | — | 944 | 261 | 3977 |
| Hello | 233 | 2645 | 649 | 554 | 257 | 4087 |

| Trial | Cold first compilation | Prepared first compilation | Hello first compilation |
| --- | ---: | ---: | ---: |
| 1 | 3587 | 944 | 551 |
| 2 | 3914 | 1047 | 554 |
| 3 | 4031 | 911 | 607 |

After the synthetic compile, the first editor compilation is approximately 390 ms faster than after library
preparation alone, a 41% reduction. The synthetic compile itself takes approximately 649 ms. The measured serial
total is higher, so this is latency shifted into potential background time, not a demonstrated total-work
optimization. Existing IDE preparation already runs asynchronously. The experiment does not test the CPU
interference or perceived responsiveness of adding another background compilation.

## Verification and reproduction

All 45 editor outputs are byte-identical: 151,112 bytes, SHA-256
`0874242b0a24e76ed3d386c9570f2bacb69a876c4c9a6692af1b031b6fd6e23b`.
All 45 editor outputs and all three Hello World outputs pass the native ABI-22 stateless artifact verifier.
Terminal interaction, file editing, and in-game behavior were not exercised. No production code or protocols
changed, and no production test suite was rerun for this documentation-only stage.

[Samples]({{ '/benchmarks/edit-program-first-build-2026-10-11/samples.json' | relative_url }}),
[summary]({{ '/benchmarks/edit-program-first-build-2026-10-11/summary.json' | relative_url }}),
[provenance]({{ '/benchmarks/edit-program-first-build-2026-10-11/provenance.json' | relative_url }}), and
[verification]({{ '/benchmarks/edit-program-first-build-2026-10-11/verification.json' | relative_url }}) accompany
nine raw logs. Use `FirstBuildProbe.kt` from the PID first-build report with this frozen editor source copied to
`src/edit.kt` in a scratch project. Pass project root, fresh output directory, and frozen tooling payload root;
omit addon arguments. Set `BENCH_MODE` to `cold`, `prepared`, or `hello`, and start a fresh JVM per trial.
