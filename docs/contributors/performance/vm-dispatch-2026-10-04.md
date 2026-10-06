---
layout: default
title: VM virtual dispatch optimization (2026-10-04)
section: contributors
permalink: /VM-DISPATCH-2026-10-04/
---

# VM virtual dispatch optimization

This follows the [managed type lookup optimization]({{ '/VM-OPTIMIZATION-2026-10-04/' | relative_url }}) in the same isolated worktree. The baseline already includes that optimization; the gains below are additional, not comparisons with the original VM.

At admission, the VM builds an offset table identifying the contiguous virtual/interface method entries for each global receiver type. A call searches only that receiver’s entries by declaration ID instead of searching the entire table by a type/declaration pair. Resolution still uses the original admitted implementations, including inherited overrides and interface defaults. No artifact, public API or native ABI changes.

## Paired measurements

Ryzen 9 9950X3D, Linux 7.2.8-zen1-2-zen, rustc 1.99.0 / LLVM 23.1.1. Release with thin LTO, one codegen unit and CARGO_PROFILE_RELEASE_DEBUG=1. Governor powersave; no fixed frequency or affinity. Desktop and other agent activity were uncontrolled. Our compilation and profiling did not overlap timing.

Three paired rounds of seven fresh sessions per case/version yield 21 samples each and 462 total timed samples. Version order and suite order reverse in round two; case order alternates within each invocation. The untraced production-source harness, generated artifacts and heaps are unchanged: 16 KiB for loops, 256 KiB for transformations. Each invocation checks traced/untraced output and deterministic counters before timing. Verification, admission, start and teardown remain outside timers; final scans and output copying are included.

| Workload | Before, ms | After, ms | Less elapsed time |
| --- | ---: | ---: | ---: |
| `fold-256-300` | 76.45 | 71.16 | 6.92% |
| `fold-prebuilt-256-300` | 77.96 | 72.14 | 7.47% |
| `fold-read-var-256-300` | 75.81 | 70.95 | 6.41% |
| `fold-ref-256-300` | 77.32 | 71.99 | 6.88% |
| `fold-val-256-300` | 75.47 | 70.51 | 6.58% |
| `fold-write-var-256-300` | 80.94 | 74.66 | 7.76% |
| `for-list-256-300` | 73.01 | 68.37 | 6.37% |
| `while-indexed-256-300` | 40.20 | 37.87 | 5.80% |
| `map-not-null-1024-100-fresh` | 246.54 | 233.64 | 5.23% |
| `map-not-null-1024-100-reuse-list` | 258.63 | 246.30 | 4.76% |
| `map-not-null-1024-100-reuse-objects` | 272.76 | 258.36 | 5.28% |

All eleven cases improve in every paired round. Aggregate medians show 5.80–7.76% less time for loops and 4.76–5.28% for transformations. Eight deterministic hot counters (fixed, dynamic and maintenance units; blocks; executed and retired instructions; requests and responses) match between versions and rounds. Initial screening suggested larger transformation gains, but those are not used for the final claim.

The additional immutable payload is `(type_count + 1) * size_of::<usize>()`: eight bytes per declared type plus one sentinel on this 64-bit host, one boxed-slice field and allocator metadata. It is shared with the execution image, with no per-session cache or new hot-path allocations. Guest heap and deterministic work quotas are unchanged. Admission performs an additional bounded linear pass and fallible allocation; admission latency was not measured separately.

[Comparison]({{ '/benchmarks/dispatch-2026-10-04/comparison.tsv' | relative_url }}), [environment, commands, revisions and binary hashes]({{ '/benchmarks/dispatch-2026-10-04/environment.json' | relative_url }}), [loop samples]({{ '/benchmarks/dispatch-2026-10-04/transient-allocations-samples.tsv' | relative_url }}), [transformation samples]({{ '/benchmarks/dispatch-2026-10-04/collection-reuse-samples.tsv' | relative_url }}).

## Profiles and verification

Before and after profiles separately run the fold case for 31 samples with `perf record -e cycles:u -F 499 --call-graph dwarf`. Dispatch lookup accounts for 8.10% of sampled cycles before and 4.15% after. These are whole-process sampling percentages with about 1,400 samples each, including setup, warmup and traced controls; they support the hotspot diagnosis rather than an exact hot-phase cost claim. Timed comparisons exclude profiled runs.

[Before profile]({{ '/benchmarks/dispatch-2026-10-04/perf-before.txt' | relative_url }}), [after profile]({{ '/benchmarks/dispatch-2026-10-04/perf-after.txt' | relative_url }}). Frozen binaries and perf.data remain under ignored `build/reports/benchmarks/dispatch-2026-10-04/`.

521 native crate tests passed (432 unit tests plus integrations/doc tests), with clippy warnings denied and formatting checked. Six Kotlin-to-VM conformance scenarios executed successfully: function values, generic interfaces, mapNotNull, mutable lists, tasks and exceptions. New tests compare every admitted dispatch target with its source entry and cover missing declarations/types, empty receiver ranges and table boundaries. [Verification evidence]({{ '/benchmarks/dispatch-2026-10-04/verification.txt' | relative_url }}).

## Reproduction and limits

Use the unchanged artifact generators and release build command in the previous report. Freeze a binary for each revision, then run:

```sh
before ARTIFACT_DIR REPORT_DIR 7 HEAP_BYTES untraced
after ARTIFACT_DIR REPORT_DIR 7 HEAP_BYTES untraced
```

Use the exact twelve invocations and alternating orders in environment.json. Baseline VM: `7fea2f27691bc21ffae3eadac787e039d00abdbc`; optimized VM: `973ec05743d1cc79649431030595a7b28cfe1a16`.

These are local native interpreter measurements, excluding Minecraft tick scheduling and JVM/native transport. No fresh Minecraft profile, full-checkout verification or release gate was run. Both optimizations were integrated locally on 2026-10-06; see the [integration verification]({{ '/VM-OPTIMIZATION-2026-10-04/' | relative_url }}#reproduction-and-verification). The timings above remain evidence for the recorded revisions, rather than a new measurement of the integrated runtime.
