---
layout: default
title: Performance reports
description: Find reproducible workloads and recorded VM optimization evidence.
section: contributors
permalink: /PERFORMANCE/
---

# Performance reports

Start with [in-world VM benchmarks]({{ '/VM-BENCHMARK/' | relative_url }}) to measure server-tick behavior. Native
interpreter measurements answer different questions: they exclude Minecraft lifecycle and JVM/native transport costs.

<a id="recorded-measurements"></a>

## Compiler and IDE measurements

| Report | Scope |
| --- | --- |
| [Value liveness, 2026-10-11](value-liveness-2026-10-11.md) | Indexed bitsets, matched compilation timings, allocation profiles and byte-identical outputs |
| [First prepared compilation profile, 2026-10-11](first-prepared-profile-2026-10-11.md) | FIR-to-IR, lowering/linking costs and class-loading observations |
| [First build and prewarming, 2026-10-11](first-build-2026-10-11.md) | Cold versus prepared compilation and the cost of additional warmup |
| [PID recompilation after edits, 2026-10-11](edit-build-2026-10-11.md) | Unchanged, comment-edited and constant-edited sources in matched workers |
| [Guest editor first build, 2026-10-11](edit-program-first-build-2026-10-11.md) | First-build behavior for the packaged editor workload |
| [Indexed callable lookup, 2026-10-11](canonical-callable-lookups-2026-10-11.md) | Lowering lookup indexes, compilation timings and output compatibility |
| [Single executable serialization, 2026-10-11](single-artifact-write-729-2026-10-11.md) | Artifact writer work, sizes and paired compilation timings |
| [Prepared library linking, 2026-10-11](prepared-library-linking-727-2026-10-11.md) | Library metadata lifetime, PID/Hello timings and compatibility checks |
| [Executable method reachability, 2026-10-11](method-reachability-723-2026-10-11.md) | PID/Hello sizes, matched compilation timings and dispatch conformance |
| [Linked methods and devirtualization survey, 2026-10-07](linker-survey-2026-10-07.md) | Method inventories and optimization candidates |

## Runtime and artifact measurements

| Report | Scope |
| --- | --- |
| [Direct final-class calls, 2026-10-08](final-calls-2026-10-08.md) | Compiler devirtualization, matched CPU trials and dispatch conformance |
| [Compact debug paths, 2026-10-07](debug-paths-704-2026-10-07.md) | Source-path encoding size and compatible reader/writer behavior |
| [Compact GC root ranges, 2026-10-07](root-ranges-706-2026-10-07.md) | Root metadata size and decoder/verifier coverage |
| [CPU trials and interpreter experiments, 2026-10-07](dispatch-cpu-2026-10-07.md) | Paired native timings and limits of experimental changes |
| [Builtin sizes and optimization survey, 2026-10-07](optimization-survey-2026-10-07.md) | Section inventories, metadata models and CPU profiles |
| [Runtime 0.20.0 baseline, 2026-10-07](vm-performance-2026-10-07.md) | Fresh compiled Guest workloads through the untraced interpreter |
| [VM performance, 2026-10-04](vm-performance-2026-10-04.md) | Recorded hardware and workload baseline |
| [Production-path optimization, 2026-10-04](vm-optimization-2026-10-04.md) | Paired managed type lookup measurements |
| [Virtual dispatch, 2026-10-04](vm-dispatch-2026-10-04.md) | Paired per-type dispatch lookup measurements |

## Reproduce a workload

- [Collection loops]({{ '/COLLECTION-BENCHMARK/' | relative_url }}).
- [Collection reuse]({{ '/COLLECTION-REUSE-BENCHMARK/' | relative_url }}).
- [Object collections]({{ '/OBJECT-COLLECTION-BENCHMARK/' | relative_url }}).
- [Object arrays]({{ '/OBJECT-ARRAY-BENCHMARK/' | relative_url }}).
- [Transient allocations]({{ '/TRANSIENT-ALLOCATION-BENCHMARK/' | relative_url }}).

Each report describes its recorded revisions, environment and limits. Compare matched workloads and inspect the
verification evidence before generalizing a timing result to live Minecraft servers.
