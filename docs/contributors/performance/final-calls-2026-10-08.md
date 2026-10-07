---
layout: default
title: Direct final-class calls (2026-10-08)
section: contributors
permalink: /VM-FINAL-CALLS-2026-10-08/
---

# Direct final-class calls

The compiler now emits direct calls for statically known concrete methods declared in final classes, including
bound/unbound method references and imported declarations. Final collection implementations avoid redundant
virtual dispatch. Virtual declaration flags and method ranges remain intact, so calls through parent classes and
interfaces still select the runtime implementation. Inherited fake overrides retain the established resolution path.
No VM code, instruction encoding, artifact version or public API changes.

This implements the speed experiment identified by the [linked-method survey]({{ '/VM-LINKER-SURVEY-2026-10-07/' | relative_url }}).
Task starting parent: `0c73ef4c`; unchanged VM and benchmark harness: `bac6789`. Both variants use freshly generated
artifacts with compact debug paths and safepoint roots. All eleven generated source programs and both suite manifests
are identical across variants. Six selected artifacts replace 32–49 static virtual-call sites each with direct calls;
function counts, interface-call sites and artifact byte sizes are unchanged. Static call-site counts are not dynamic
invocation counts. This is an execution optimization, not a binary-size reduction.

## CPU results

Three rounds of seven samples per variant yield 21 samples per case, 252 timed samples in total. Each process is pinned
to logical CPU 6; the same frozen release benchmark binary runs both artifact variants. Order reverses in round two,
and case order alternates inside the harness. Loops use a 16 KiB heap; collection transformations use 256 KiB.
Our compilation and tests do not overlap these trials. Hot timers exclude artifact admission, construction and teardown.

| Workload | Before hot CPU, ms | Direct calls hot CPU, ms | CPU time change |
| --- | ---: | ---: | ---: |
| `fold-256-300` | 73.96 | 70.27 | -4.99% |
| `for-list-256-300` | 70.96 | 67.04 | -5.53% |
| `map-not-null-1024-100-fresh` | 247.67 | 231.90 | -6.37% |
| `map-not-null-1024-100-reuse-list` | 258.11 | 239.29 | -7.29% |
| `map-not-null-1024-100-reuse-objects` | 270.28 | 249.32 | -7.75% |
| `while-indexed-256-300` | 42.06 | 41.14 | -2.18% |

Negative values mean less CPU time. Collection transformations improve in every round; their pooled median reductions
are 6.37–7.75%. Loop pooled medians decrease by 2.18–5.53%, but round two increases by 11.89–43.15% while rounds one and
three decrease. These loop measurements do not establish a stable speedup. All samples and outliers remain archived;
no general VM or server-tick speedup is claimed.

The Ryzen 9 9950X3D workstation retains the earlier survey's powersave governor and enabled boost. The core is not
reserved, and concurrent desktop/other-agent activity remains uncontrolled. Thread CPU time excludes descheduling;
it still reflects CPU frequency and shared cache/memory interference. The workloads do not represent the unavailable
drone PID controller.

## Semantics and work counters

The dispatch fixture checks base-class and interface calls, concrete final implementations, inherited methods,
nullable safe calls, bound/unbound references, deterministic serialization and retained virtual declarations.
Two constructor fixtures read a non-null field through a method before initialization. They still throw Guest
`NullPointerException`: the VM checks the field read before dispatch. Non-null field/static/array loads, checked casts
and validated capability responses establish non-null references; removing dispatch does not remove these checks.

All seven non-fixed hot counters match across variants: dynamic units, maintenance units, blocks, executed/retired
instructions, requests and responses. Fixed units decrease by 1.41–3.31%, because direct-call charging is lower than
virtual-call charging. This deliberate quota difference is recorded separately from CPU timing. Each harness invocation
checks traced/untraced work agreement, expected checksum, ready marker and termination.

## Verification and reproduction

Compiler engine and worker module checks pass, as do dispatch, interface-default/super, nullable-reference,
generic-library, mapNotNull, mutable-list and List<Any> quota conformance scenarios. The final extended dispatch fixture
and affected Kotlin lint checks pass after the last test change. These are focused compiler/native-boundary checks;
no Minecraft, full-checkout or release readiness claim is made.

[Raw samples]({{ '/benchmarks/final-calls-2026-10-08/samples.tsv' | relative_url }}),
[round summaries]({{ '/benchmarks/final-calls-2026-10-08/measurements.tsv' | relative_url }}),
[CPU/wall comparisons and work counters]({{ '/benchmarks/final-calls-2026-10-08/comparison.json' | relative_url }}),
[exact commands, timestamps, binary/input hashes]({{ '/benchmarks/final-calls-2026-10-08/environment.json' | relative_url }}),
[provenance]({{ '/benchmarks/final-calls-2026-10-08/provenance.json' | relative_url }}),
[verification evidence]({{ '/benchmarks/final-calls-2026-10-08/verification.txt' | relative_url }}).

Validate the archive:

```sh
python3 docs/benchmarks/final-calls-2026-10-08/compare_trials.py
```

To repeat the compiler comparison, generate both suites at the starting revision and with this change, then use the
recorded commands with the same benchmark binary. Retain source/manifests, hashes, paired order and both clocks. More
precise final-executable method reachability remains the next size experiment; it requires coordinated declaration,
implementation and method-range pruning as described in the survey.
