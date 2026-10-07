---
layout: default
title: VM optimization opportunities and builtin sizes (2026-10-07)
section: contributors
permalink: /VM-OPTIMIZATION-SURVEY-2026-10-07/
---

# VM optimization opportunities and builtin sizes

This investigation follows the [Runtime 0.20.0 baseline]({{ '/VM-PERFORMANCE-2026-10-07/' | relative_url }}). It measures the current executable format and interpreter to rank possible improvements. No production optimization is implemented by this report. The user’s roughly 1000-line Propulsion drone PID/vector-thrust controller is no longer available, so its specific size or runtime behavior cannot be reproduced.

Parent revision: `d85f8096a1574443dff43b88951c157bc752390b`; VM: `18103f01963874510fa25d4396a2dc3e7ee47640` (Runtime 0.20.0). The current compiler freshly generated all six builtin artifacts and the two larger collection-conformance inputs; earlier cached artifacts were not used for these inventories. Source line counts include comments/blank lines and directly compiled helpers, excluding linked platform-library sources. Sizes use KiB = 1024 bytes.

## Builtin program sizes

| Program | Source lines | Artifact, KiB | CODE | DEBUG + source positions | GC root maps |
| --- | ---: | ---: | ---: | ---: | ---: |
| `boot` | 52 | 39.96 | 10.7% | 19.4% | 27.1% |
| `edit` | 630 | 214.96 | 8.1% | 53.7% | 22.9% |
| `kotlinc` | 71 | 39.79 | 9.2% | 32.1% | 24.4% |
| `shell` | 302 | 145.18 | 9.2% | 41.3% | 26.2% |
| `vmbench-agent` | 104 | 42.00 | 10.2% | 31.6% | 25.3% |
| `vmbench` | 133 | 47.53 | 10.4% | 26.8% | 25.3% |

`shell` includes Lexer.kt; both vmbench variants include vmbench-workload.kt. The remaining bytes are type/function/string/constant/block/exception tables, manifests and container overhead. CODE is the encoded section including its block index; it is not a measurement of native machine code.

The editor’s 630 source lines generate a 215 KiB artifact: debug information occupies 53.7%, GC root maps 22.9%, and CODE 8.1%. Artifact size is driven by per-instruction metadata and linked library/type structure, not just source-line count or instruction payload. The fresh large mapNotNull conformance executable is 548.02 KiB, with 51.0% debug metadata and about 6.7% CODE; this independently reproduces a half-megabyte artifact without the unavailable drone source.

[Builtin size TSV]({{ '/benchmarks/optimization-survey-2026-10-07/builtin-sizes.tsv' | relative_url }}), [exact per-module section inventories and artifact hashes]({{ '/benchmarks/optimization-survey-2026-10-07/artifact-size-analysis.json' | relative_url }}).

## Size opportunities

1. **Intern debug source paths.** Each DEBUG record currently stores function/block/instruction IDs, UTF-16 range, inline parent, then a length and the full UTF-8 source path. Library paths repeat hundreds or thousands of times. A model replacing path bytes with an ID, plus one indexed path pool and directory entry per module, predicts the savings below while retaining diagnostic information. These are calculated payload estimates, not an implemented codec; exact alignment and format/version details remain to be designed.

| Artifact | Modeled path-pool saving | Fraction of whole artifact |
| --- | ---: | ---: |
| `edit` | 37.43 KiB | 17.4% |
| `shell` | 21.02 KiB | 14.5% |
| `kotlin-map-not-null` | 159.60 KiB | 29.1% |
| `kotlin-mutable-list` | 125.49 KiB | 30.6% |

This requires coordinated artifact writer/reader work and an explicit compatibility/version decision; reinterpreting the existing DEBUG layout would break readers. Paths, line/column positions, UTF-16 ranges and inline parents should remain available. Simply omitting debug metadata gives larger size savings but sacrifices diagnostics and is not the recommended default.

2. **Coalesce repeated debug spans.** The native diagnostic lookup already selects the closest preceding debug entry within the same function/block. Removing consecutive entries with identical path/span/position/inline parent could preserve lookup results using the current format. The conservative model keeps every entry referenced as an inline parent and estimates 3.8% savings for edit and 0.9% for shell; sparse lookup and relocated parent/position indices still need behavioral tests. These estimates overlap with path interning and must not be added to its percentages.

3. **Encode GC root maps more compactly.** In edit, 1112 of 1870 maps repeat the preceding map’s reference components within the same function/block. In shell, 470 of 1414 do so. Root metadata occupies 22.9% and 26.2% of these artifacts. A range/delta or shared-set representation could reduce it, but must reconstruct exactly the same roots for every execution boundary; removal of checkpoints is not justified. This changes the artifact/verifier boundary and needs coordinated Kotlin/Rust evidence. No exact saving is claimed yet.

4. **Compress stored/transferred artifacts.** As a separate offline experiment, gzip level 9 reduces edit from 215.0 to 45.7 KiB and shell from 145.2 to 32.7 KiB; the 548.0 KiB conformance executable becomes 89.4 KiB. This demonstrates redundancy. It is not a supported CPKT format or a measured admission improvement: an envelope would need bounded decoding, logical/physical size accounting and integrity/compatibility rules. It is a separate storage/transfer tradeoff rather than the first interpreter change.

5. **Refine library method reachability.** The linker already performs dead-record elimination, but marking a nominal class/interface type marks its whole declared method range. More precise receiver/call-site reachability may retain fewer methods and their associated metadata. This has semantic risk around virtual dispatch, interface defaults, constructors and runtime exceptions; the small CODE fraction suggests investigating metadata first.

## Interpreter opportunities

Profiles use the frozen baseline release binary on the same Ryzen 9950X3D with powersave governor and no fixed affinity. Each separate perf run selects one case and collects 99 samples with `cycles:u`, 499 Hz and DWARF call graphs. These runs include setup and traced warmup/control, and their timing samples are excluded from ordinary baseline comparisons. Percentages are whole-process sampled self cycles, not projected speedups.

| Sampled symbol | Indexed loop | Fold | Fresh transformation |
| --- | ---: | ---: | ---: |
| run_slice_inner | 52.65% | 52.45% | 51.71% |
| dispatch_target | 9.32% | 6.26% | 6.90% |
| read_register | 8.32% | 7.26% | 7.96% |
| execute_scalar | 10.66% | 8.52% | 8.64% |

The best localized runtime candidates are reuse of active frame/function access metadata and a faster resolved receiver/method lookup, checked with paired timings and unchanged work counters. The scalar frame helper is already forced inline; the earlier blanket register-helper inlining experiment had mixed results, so simply adding inline annotations is not evidence of a solution. Resolving operand access further at admission may reduce repeated lookup but can increase immutable image memory. Compiler devirtualization at provably exact receiver sites is another possibility, with null/dispatch semantics protected. None has a measured speedup here.

These collection profiles do not establish the hottest work in a Double/vector PID controller or the cost of Propulsion host calls. A representative control-loop workload should be added when that source is available.

[Indexed profile]({{ '/benchmarks/optimization-survey-2026-10-07/perf-while-indexed.txt' | relative_url }}), [fold profile]({{ '/benchmarks/optimization-survey-2026-10-07/perf-fold-256.txt' | relative_url }}), [object profile]({{ '/benchmarks/optimization-survey-2026-10-07/perf-map-not-null-1024-100-fresh.txt' | relative_url }}).

## Recommended order and verification

For the size concern, start with shared debug source paths: it preserves the useful diagnostics and has the largest quantified targeted saving. Sparse identical spans are a smaller existing-format experiment. Treat compact root-map encoding as a separate versioned stage. Runtime register/dispatch trials can proceed independently against the preserved baseline, with no promised percentage before paired verification.

Nine compiler-generation tests passed: six builtin determinism checks, one mapNotNull scenario and two mutable-list scenarios. All three perf invocations completed and their eight hot work counters match the unprofiled baseline. The analyzer checks CPKT v3 header/directory bounds, SHA-256 trailer and relevant indexed-table accounting; it is not a substitute for the full native verifier. Manual checks rejected a corrupt digest and invalid indexed endpoints and confirmed byte totals for all nine measured artifacts.

Reproduce with `python3 docs/_tools/analyze_artifact_size.py ARTIFACT...`. The CLI only reads inputs and prints JSON. [Generator/profile commands and provenance]({{ '/benchmarks/optimization-survey-2026-10-07/provenance.json' | relative_url }}), [profile commands]({{ '/benchmarks/optimization-survey-2026-10-07/profile-commands.json' | relative_url }}), [verification]({{ '/benchmarks/optimization-survey-2026-10-07/verification.txt' | relative_url }}). No production behavior, ABI or release claim changes in this investigation.
