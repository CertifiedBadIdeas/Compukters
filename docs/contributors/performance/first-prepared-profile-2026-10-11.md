---
layout: default
title: First prepared compilation profile
section: contributors
---

# First prepared compilation profile — 2026-10-11

The first project build after library preparation spends its extra time across FIR-to-IR conversion, Guest
lowering, and linking/writing. Frontend project analysis is a smaller part of the difference. The first build
loads approximately 1600 additional classes that are largely absent from later builds. Library-only IR
conversion can move part of that cost before Build, but its own elapsed time exceeds the observed saving in
the first compilation. No additional production prewarming was enabled.

## Inputs and method

This follows the [first-build measurements](first-build-2026-10-11.md) and
[guest editor measurements](edit-program-first-build-2026-10-11.md). Use exactly their frozen indexed compiler
payload, equivalent to compiler sources at `aa748215`, and the same private PID sources and public editor snapshot.
The task documentation checkout starts at `cd98af30`; that newer checkout includes reference-array changes and
IDE work that are outside this frozen compiler measurement. PID includes Sable and Propulsion bundles; the
editor uses only `stdlib:core` and `compukter:core` with their dependency closure.

Three fresh JVMs per workload prepare the libraries and compile the project five times. Unlike the prior
subprocess latency experiment, this probe calls the real `K2CompilerAdapter` directly. JVM/worker startup,
IPC, input collection, dependency resolution, output file writes, and Minecraft UI are outside compilation
timers. Library preparation is explicitly completed before the first project compilation.

There are three separate experiments:

1. **CPU/class-loading profiles:** JFR enabled before preparation, with execution samples at 1 ms, class-load
   events without stack traces, compilation/JIT events, garbage collection, and sampled allocations. Custom
   phase events delimit preparation and each project compilation. Three fresh JVMs per workload.
2. **Method-boundary profiles:** repeat those six JVMs with a temporary Java agent placing JFR events around
   project environment creation, frontend compilation, FIR-to-IR conversion, Guest lowering, and linker/write.
   This avoids assigning deep/truncated CPU stacks to an incorrect outer phase.
3. **Uninstrumented library-IR experiment:** three alternating prepared/IR-warmup JVM pairs per workload,
   ten project compilations per JVM. No JFR or Java agent. After ordinary preparation, optionally call
   `CompuktersFir2IrPipeline.convert` on the admitted library source outputs with `preserveFir = true`, discard
   the returned IR, then compile the project. Reflection is confined to the scratch probe to access the existing
   prepared library outputs; it is not a production interface or a proposed implementation.

The agent and probes are diagnostic artifacts. Production code, protocols, versioned contracts, and the IDE
preparation policy were not changed. Java-agent timing includes instrumentation and initial class transformation;
the uninstrumented experiment is the appropriate comparison for the warmup option. Environment remains Linux
x86-64, Ryzen 9 9950X3D, powersave governor, JDK 21 with `-Xmx2g`. CPU affinity, frequency, and background activity
are uncontrolled. Only three first-build observations per variant are available.

## First-build breakdown

Method-boundary medians in milliseconds. First columns cover three first builds; repeated columns cover samples
4–5 from each JVM. Individual phase medians must not be summed to reconstruct the median total. Residual time
includes admission, setup, diagnostics, cleanup, and class initialization outside the marked method bodies.

| Phase | PID first | PID repeated | Editor first | Editor repeated |
| --- | ---: | ---: | ---: | ---: |
| Project environment | 1.7 | 1.9 | 1.7 | 1.9 |
| Project frontend | 63.9 | 47.0 | 122.4 | 78.8 |
| FIR → IR | 374.7 | 57.0 | 401.0 | 59.9 |
| Guest lowering | 369.0 | 95.0 | 305.8 | 79.1 |
| Link/write | 194.2 | 75.6 | 180.2 | 67.4 |
| Outside marked methods | 72.8 | 59.6 | 32.5 | 8.1 |
| Whole compilation | 1068.9 | 342.7 | 1074.7 | 289.9 |

The independent CPU/class-loading profiles record 1605 main-thread class-load events in each first PID build,
and 1587–1589 in the first editor builds. By compilation 5 there are only 0–1 events per build. In the
method-boundary experiment, approximately 1080 of those events per first build begin inside FIR-to-IR conversion;
Guest lowering accounts for another 265–285 per build, and link/write for 145–155. These counts locate first-use
work but do not quantify milliseconds saved by class preloading. Class-load durations can nest and are not
summed as independent elapsed time. JIT activity and GC also overlap the compilation windows; their event counts
are not CPU-time or pause-time attribution.

The nearest Compukters CPU frame is frequently `ValueLiveness.derive`, both on first and later builds. This makes
it a candidate for reducing actual compiler work, unlike an extra warmup. Sampling counts are recorded in the
CPU summary. They do not establish a projected speedup; deep stacks, inlining, non-Java execution, and sampling
bias prevent treating them as complete wall-clock phase percentages.

## Library-only IR warmup experiment

This experiment converts only already admitted library FIR, preserves declaration bodies, and keeps neither
project state nor converted IR. The project still receives a fresh IR conversion and lowering on every build.
All output artifacts remain byte-identical after that additional conversion and across subsequent builds.

| Workload | Ordinary first compilation | First after library IR warmup | Warmup itself | Warmup + first compilation | Warm compilation medians, ordinary / IR |
| --- | ---: | ---: | ---: | ---: | ---: |
| PID | 1030.0 ms | 802.0 ms | 327.1 ms | 1129.1 ms | 286.6 / 281.9 ms |
| Editor | 906.6 ms | 709.3 ms | 351.2 ms | 1060.7 ms | 224.5 / 217.1 ms |

First-compilation medians decrease by 228 ms for PID and 197 ms for the editor, approximately 22% in each case.
The combined warmup-plus-first-build interval is higher than the ordinary first build. This supports moving
some first-use work earlier, not reducing the time spent doing it. Warm medians cover samples 4–10, 21 observations
per variant/workload; their small differences are not a reliable performance improvement.

Including ordinary frontend preparation, serial-total medians are 4621 / 4772 ms for PID and 3697 / 3668 ms for
the editor (ordinary / IR). The editor's preparation medians themselves differ by about 199 ms between variants,
even though both run identical code before the optional IR step. That uncontrolled difference must not be
attributed to the subsequent warmup. These trials do not show a reliable full cold-path improvement.

## Decision and next target

Keep the current asynchronous library preparation policy. The first-build penalty crosses several phases and
largely disappears on subsequent builds. The profile does not identify a single immutable project result that
can simply be cached without changing ownership. Current FIR-to-IR includes admitted source libraries alongside
the project, and lowering processes request-owned mutable IR. Retaining or sharing that IR would need separate
semantic/lifetime evidence; it is not equivalent to retaining immutable library metadata.

Library-only conversion is a viable experiment for shifting latency earlier, but adding it by default would
increase background work whenever an IDE project opens. Before enabling it, measure actual client contention,
Build pressed during warmup, cancellation, and memory cost. No claim about in-game typing responsiveness is
made here. The next localized compiler optimization candidate is `ValueLiveness`, with unchanged artifacts and
paired first/repeated build measurements as validation.

## Verification and reproduction

All **180** artifacts from the three experiments pass native ABI-22 stateless artifact verification: 30 from
CPU profiles, 30 from method-boundary profiles, and 120 from uninstrumented comparisons. Every PID and editor
output is byte-identical to its corresponding unmodified baseline. This verifies serialization after the
extra library conversion; it does not establish flight/session execution or client responsiveness. No production
suite was rerun for this profiling-only stage.

[CPU summary]({{ '/benchmarks/first-prepared-profile-2026-10-11/cpu-profile-summary.json' | relative_url }}),
[method timings and CPU windows]({{ '/benchmarks/first-prepared-profile-2026-10-11/method-profile-summary.json' | relative_url }}),
[warmup samples]({{ '/benchmarks/first-prepared-profile-2026-10-11/warmup-samples.json' | relative_url }}),
[warmup summary]({{ '/benchmarks/first-prepared-profile-2026-10-11/warmup-summary.json' | relative_url }}),
[provenance]({{ '/benchmarks/first-prepared-profile-2026-10-11/provenance.json' | relative_url }}), and
[verification]({{ '/benchmarks/first-prepared-profile-2026-10-11/verification.json' | relative_url }}) accompany
24 stdout logs and the four diagnostic source files. Private PID sources and large binary profiles are not
published. Raw JFR recordings are retained locally under `build/reports/benchmarks/first-prepared-profile/`
and `build/reports/benchmarks/first-prepared-phases/`; their hashes are recorded in provenance.

Compile each Kotlin probe with the pinned payload's Kotlin CLI compiler, using `-jvm-target 17`, using its compiler
profile classpath. Arguments are project root, fresh output directory, tooling payload root, followed by addon
bundle paths for PID; omit addon paths for the editor. `FirstPreparedProfileKt` enables its own recording and
writes `profile.jfr`. The method-enabled source must be compiled separately: it declares the same marker class
and main function as the CPU-only source. Use that source with `-javaagent:phase-agent.jar`.

Build `PhaseAgent.java` with JDK 21 and the payload's Kotlin compiler JAR on the compilation classpath (it supplies
shaded ASM). Package all generated `PhaseAgent*.class` files with a manifest containing `Premain-Class: PhaseAgent`.
The agent marks only the five successful-path methods used in this experiment; it is not general-purpose tracing
for failing compilations. Method events must lie inside their compilation marker window, with one event per
method per compilation. Summaries check those boundaries before aggregating samples. Export only the selected
JFR events rather than all recording types.

For the uninstrumented experiment run `LibraryIrWarmupProbeKt` with `BENCH_MODE=prepared` or `BENCH_MODE=ir`, using
fresh JVMs and output directories, alternating variant order. Keep sample 1 as first compilation, exclude samples
2–3 from the warm aggregate, and use samples 4–10 for warm comparisons. Preserve and verify all returned artifacts.
