---
layout: default
title: First build and library prewarming
section: contributors
---

# First build and library prewarming — 2026-10-11

The existing asynchronous IDE library preparation removes most of the cold compiler work from a later Build.
It does not exercise the full project compilation pipeline. A first PID compilation after preparation still
costs approximately one second in these local trials. An additional Hello World compilation reduces that
first PID latency, but performs extra work and has not been enabled in production.

## Method and provenance

The experiment uses the frozen indexed compiler payload from the
[canonical callable lookup measurements](canonical-callable-lookups-2026-10-11.md), source implementation
`2bf92aa74958e223a535f4c3537818777a23ee7c`, equivalent to compiler sources at `aa748215`.
The documentation checkout starts at `69ac7f7b`, which includes a concurrent reference-array change.
**These measurements do not cover that newer compiler/platform.** Freezing one payload isolates the preparation
experiment from concurrent development.

Each trial launches a real compiler subprocess with `JdkWorkerProcessFactory`, verifies its handshake identity
and limits, and exchanges real worker protocol frames. Startup covers process launch through receipt and decoding
of the handshake. Preparation covers the existing `LibraryPreparationRequest` round trip. Compilation includes
request encoding, IPC, compilation, and result decoding, but excludes writing the returned artifact to the report
folder. Parent-side payload validation, source collection, dependency resolution, and Minecraft UI are outside
the timers. This measures phase latency, not the total time of pressing Build in the client.

Three fresh workers run each variant. Trial order alternates between cold/prepared/hello and its reverse.
Each worker compiles the same frozen private two-file PID project five times, with identical selected libraries
and addon bundles. No result cache, profiler, sleeps, or instrumentation inside the compiler is used.

- **Cold:** compile PID immediately after the handshake.
- **Prepared:** prepare the selected libraries, then compile PID.
- **Hello:** prepare the same libraries, compile a synthetic Hello World with the same selection, then compile PID.

The synthetic source is `fun main() { println("Hello, world!") }` followed by a newline, at `src/main.kt`.
This is an experiment to exercise the compiler pipeline; it does not cache project files or change the prepared
library contract. All variants use OpenJDK 21, `-Xms16m`, `-Xmx2048m`, and `-XX:MaxMetaspaceSize=512m`.
Linux x86-64, Ryzen 9 9950X3D, powersave governor. CPU frequency, affinity, and background activity are uncontrolled.

## Results

All entries are milliseconds. Phase columns are separate medians over three workers and must not be added to
reconstruct the median total. The total column is computed from each trial's actual serial sum first.

| Variant | Startup | Preparation | Hello warmup | First PID | PID samples 4–5 | Serial total to first PID |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Cold | 230 | — | — | 5041 | 355 | 5271 |
| Prepared | 246 | 3687 | — | 1161 | 338 | 5094 |
| Hello | 253 | 4116 | 710 | 644 | 318 | 5701 |

First-PID times per trial:

| Trial | Cold | Prepared | Hello |
| --- | ---: | ---: | ---: |
| 1 | 5041 | 1604 | 678 |
| 2 | 5357 | 991 | 587 |
| 3 | 4745 | 1161 | 644 |

Preparation itself varies from 3678 to 4923 ms in the prepared variant. The medians and the three trials are
insufficient to establish tail latency or a reliable total-work improvement. An already completed background
preparation allows Build to omit its elapsed time from the wait; pressing Build while preparation is running
can still wait for that work.

The Hello experiment reduces first-PID median by 517 ms, about 45%, after its own roughly 710 ms warmup.
Its measured serial total is higher. This is evidence that exercising more of the compilation pipeline can move
latency earlier, not evidence that it reduces total work. This experiment does not isolate class loading, JIT,
frontend, IR conversion, lowering, and linking individually, so none is assigned sole causality.

## Consequences

Worker startup is a relatively small part of the cold path. IDE already starts preparation asynchronously;
adding another library-only preparation mechanism would duplicate the existing behavior.

The next useful optimization target is the first project compilation after preparation. Before enabling a
full-pipeline warmup, profile that first compilation and check whether immutable library-only work can move
into preparation. If a synthetic compile is retained as an option, measure CPU/memory cost and interference
with analysis and actual Build requests in the client, including Build during warmup and cancellation. The
ordinary project compilation, diagnostics, and output must remain request-local. No production behavior or
worker protocol was changed by this investigation.

## Verification and reproduction

All 45 PID outputs are byte-identical to one another and to the previous indexed report:
152,916 bytes, SHA-256 `bc01f4194e6f7c247ef71b9cccf16145f44bccd1e959d83c3f149530556027ee`.
All 45 PID outputs and all three synthetic outputs pass the native ABI-22 stateless artifact verifier.
This does not establish flight behavior, session admission, IDE responsiveness, or release readiness.
No production tests were rerun for this documentation-only stage.

[Raw samples]({{ '/benchmarks/first-build-2026-10-11/samples.json' | relative_url }}),
[summary]({{ '/benchmarks/first-build-2026-10-11/summary.json' | relative_url }}),
[provenance]({{ '/benchmarks/first-build-2026-10-11/provenance.json' | relative_url }}), and
[verification]({{ '/benchmarks/first-build-2026-10-11/verification.json' | relative_url }}) accompany the report.
The directory also contains nine raw stdout logs and `FirstBuildProbe.kt`; private PID sources are not published.

Compile the probe with the pinned payload's Kotlin CLI compiler, using `-jvm-target 17`. Use the compiler profile's
classpath for compilation and for the parent probe JVM. Run `FirstBuildProbeKt` with arguments in this order:
project root, fresh output directory, frozen tooling payload root, Sable `.cagb`, Propulsion `.cagb`.
Set `BENCH_MODE` to `cold`, `prepared`, or `hello`. The probe uses the JDK 21 executable at
`/usr/lib/jvm/java-21-openjdk/bin/java`; adapt that path for another installation. Start a fresh probe JVM
and a fresh output directory per trial. Use the same payload and input bytes for all variants; verify every
returned CPKT and preserve raw logs before calculating medians.
