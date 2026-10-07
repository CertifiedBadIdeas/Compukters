---
layout: default
title: VM CPU timing and rejected interpreter experiments (2026-10-07)
section: contributors
permalink: /VM-CPU-TRIALS-2026-10-07/
---

# VM CPU timing and interpreter experiments

Two localized interpreter experiments did not establish a consistent improvement on the current workstation.
Neither change is retained in the VM. The accepted change adds thread CPU timing to the opt-in Guest benchmark;
production execution and artifact sizes retain their previous behavior.

Task starting parent: `ec4ab12c`; VM execution baseline: `9968700`. The benchmark change is VM `bac6789`, tracked
by parent `8291660e`. This follows the [artifact size improvements]({{ '/VM-ROOT-RANGES-2026-10-07/' | relative_url }})
and the earlier [interpreter profiles]({{ '/VM-OPTIMIZATION-SURVEY-2026-10-07/' | relative_url }}).

## Measurement improvement

The benchmark now retains wall time and also records construction/hot thread CPU nanoseconds on 64-bit Linux,
using `CLOCK_THREAD_CPUTIME_ID`. Other platforms leave the additional CPU columns empty. Existing wall-time and
work-counter columns retain their meaning. This is an opt-in tool change, outside the public VM API and production
Runtime bundles; no dependency, ABI or artifact version changes.

Thread CPU time excludes periods when the benchmark thread waits to be scheduled. It does not eliminate changes
in CPU frequency, competing memory/cache traffic or interruptions while the thread runs. A direct helper check
slept for 50 ms while charging 5790 ns of CPU time, then measured a busy loop at 2239103 ns. This verifies the
clock distinction on this host; it is not a VM performance measurement.

## Experiments

**Caller metadata reuse:** obtain the caller frame/function once when validating and copying call arguments,
instead of repeating lookups per argument. All eleven workloads completed with unchanged work counters in
unrestricted trials, but host load substantially affected timings. A follow-up on logical CPU 6 measured loop
median reductions of 2.05–4.83%, alongside a 5.08% increase for fresh mapNotNull. These wall-time results did not
justify retaining the candidate or attributing a general improvement to it. The experiment predates the CPU clock;
its [four-case comparison]({{ '/benchmarks/dispatch-cpu-2026-10-07/rejected-call-frame.json' | relative_url }}) is diagnostic evidence.

**Direct managed type dispatch:** use the type ID already stored in a live heap object, avoiding the conversion
to module/local type key and back before virtual/interface dispatch. Heap reference validation and checked method
lookup remain in the experiment. Baseline and candidate use exactly the same updated benchmark harness, build
profile, frozen artifacts, heap budgets and CPU affinity. The archived patch describes the rejected candidate;
it is not active runtime code. Reproduce it on VM `bac6789` with `git apply --unidiff-zero candidate.patch`.

Three rounds of seven samples per variant give 21 samples per case and 252 timed samples. Variant order reverses
in round two; case order alternates within each invocation. Loops use a 16 KiB heap, transformations 256 KiB.
Each invocation checks a traced control, ready marker, expected checksum, termination and deterministic counters.
Verification, admission, start and teardown are excluded from construction/hot timers.

| Workload | Before hot CPU, ms | Candidate hot CPU, ms | CPU time change |
| --- | ---: | ---: | ---: |
| `while-indexed-256-300` | 42.30 | 42.32 | +0.06% |
| `for-list-256-300` | 71.29 | 70.98 | −0.43% |
| `fold-256-300` | 74.48 | 74.74 | +0.35% |
| `map-not-null-1024-100-fresh` | 248.63 | 247.15 | −0.60% |
| `map-not-null-1024-100-reuse-list` | 256.99 | 261.23 | +1.65% |
| `map-not-null-1024-100-reuse-objects` | 271.49 | 269.83 | −0.61% |

Negative changes mean less CPU time. Small mixed aggregate changes and shifts between individual rounds do not
establish a consistent improvement. The experiment is therefore reverted. Raw min/max and per-round comparisons
remain available; no global percentage speedup is claimed.

## Provenance and verification

The Ryzen 9 9950X3D host retained its powersave governor, enabled boost and concurrent desktop/other-agent activity.
Final paired trials pin the process to logical CPU 6 without reserving that core or fixing clock frequency. Our
compilation/tests did not overlap the final paired CPU trials. Preliminary wall-only trials are excluded from the
final table. This is native interpreter evidence, excluding Minecraft scheduling and JVM/native transport costs.
It does not represent the unavailable drone PID controller.

- Identical frozen artifact hashes match all eleven original October 7 benchmark artifacts; selected workloads
  and counters are preserved. No compiler regeneration confounds the comparisons.
- The archive validator checks all 252 samples, exact per-round summary medians/min/max, complete variant/trial
  coverage and equality of all eight hot work counters. Both wall and CPU clocks are retained.
- The final retained VM passes 548 Rust tests (10 ignored), formatting and Clippy with the benchmark feature enabled. The clock
  helper and a three-sample production-path smoke run pass. No full-checkout, Minecraft or release gate is claimed.

[Raw samples]({{ '/benchmarks/dispatch-cpu-2026-10-07/samples.tsv' | relative_url }}),
[round summaries]({{ '/benchmarks/dispatch-cpu-2026-10-07/measurements.tsv' | relative_url }}),
[CPU/wall comparisons]({{ '/benchmarks/dispatch-cpu-2026-10-07/comparison.json' | relative_url }}),
[revisions, binary/harness/artifact hashes and environment]({{ '/benchmarks/dispatch-cpu-2026-10-07/provenance.json' | relative_url }}),
[exact trial commands and timestamps]({{ '/benchmarks/dispatch-cpu-2026-10-07/environment.json' | relative_url }}),
[verification commands]({{ '/benchmarks/dispatch-cpu-2026-10-07/verification.txt' | relative_url }}).

Validate the retained archive:

```sh
python3 docs/benchmarks/dispatch-cpu-2026-10-07/compare_trials.py
```

For future trials, build the same harness for both VM revisions with the recorded release command, then use the
recorded `taskset -c 6` invocations. Preserve paired order, exact inputs and both clocks. Next candidates should
focus on removing work at compilation/admission, rather than assuming that a shorter interpreter path yields a
measurable improvement; any increase in execution-image memory also needs measurement.
