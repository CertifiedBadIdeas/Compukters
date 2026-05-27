# Hybrid Profiler Design

**Goal:** Build a profiling system that shows where Compukter Kraft spends CPU time and allocations in runtime, display, compiler, and profiling workloads, so native JNI/Rust decisions are based on measured bottlenecks rather than guesses.

**Scope:** This design covers profiler instrumentation, reproducible workloads, external profiler integration, and baseline comparison. It does not introduce Rust, JNI, FFM, hard CI performance budgets, or production-facing behavior changes.

## Background

The codebase already has no-op-by-default runtime and display profiling hooks. `RuntimeMetricsCollector` tracks server tick phases, host-call phases, display frame drain/flush, VM scheduling points, and execution window time. `DisplayMetricsCollector` tracks display operation counts, areas, frames, tiles, and payload bytes. `RuntimeDisplayProfilingTest` exercises a bundled firmware/ROM terminal workload.

Those hooks are useful but not enough to decide whether native code would help. They do not provide per-operation timings, compiler phase timings, sustained workloads, allocation profiles, or baseline comparison reports.

## Design Principles

1. **No-op by default:** Normal runtime should keep using no-op collectors and should not pay meaningful profiling overhead.
2. **Measure domain events and real CPU separately:** In-code metrics explain domain behavior; JFR/async-profiler provide CPU and allocation truth.
3. **Batch-oriented interpretation:** Any JNI/Rust candidate must be evaluated as a coarse batch target, not as per-pixel, per-glyph, or per-opcode native calls.
4. **Portable first, external tools optional:** Gradle tests and summaries should work everywhere; JFR/async-profiler workflows can be optional diagnostics.
5. **Diagnostics before budgets:** Summaries and comparisons should reveal regressions, but no hard pass/fail thresholds should be added until enough baseline history exists.

## Architecture

### Metrics collectors

Extend the existing collector model instead of adding a separate profiler subsystem.

- Runtime metrics remain under core runtime profiling.
- Display metrics remain under core display profiling.
- Compiler metrics should be introduced near the compiler/frontend layer.
- Workloads should compose collectors and print or write combined summaries.

Collectors should expose simple immutable snapshots. Recording collectors may use atomic counters to support existing cross-thread collection patterns. Summaries should include counts, total nanos, max nanos where useful, averages, areas, tiles, and payload bytes.

### Runtime metrics

Runtime metrics should keep existing server tick, host-call, display-frame, slice, scheduling, and execution-window metrics. Add signal distribution and VM-focused derived values:

- pause/yield/sleep/wait-event/host-call/halt counts;
- average execution window nanos;
- optional instruction count per execution window if it can be recorded without distorting normal execution;
- host-call module/function counts only if cardinality remains controlled.

### Display metrics

Display metrics should preserve existing operation counts and area counters. Add timings for:

- `clear`, `setPixel`, `fillRect`, `copyRect`, `blitMono`, `blitMono5x7`;
- `present` total time;
- dirty tile scanning / frame build where practical;
- tile payload serialization (`copyTile`) time and bytes;
- front-buffer copy time where practical.

The summary should show values such as `nanosPerBlit`, `nanosPerTile`, and `nanosPerPayloadByte` to identify whether native framebuffer work is plausible.

### Compiler metrics

Compiler profiling should measure compile total and available phases without large refactors:

- source size and script name;
- analysis/frontend total;
- backend/codegen total when separable;
- bytecode/function/instruction counts when available.

If the current compiler structure cannot expose exact lexer/parser/analyzer/codegen phases cleanly, the first slice should record the phases that are already accessible and leave finer splits for a later refactor.

### Workloads

Keep the existing bundled terminal workload as the integration smoke workload. Add CPU-oriented variants that avoid artificial tick delays when measuring CPU work.

Initial workloads:

1. **Bundled terminal smoke:** boot, type `help`, press Enter, collect combined runtime/display metrics.
2. **Sustained terminal CPU workload:** same domain flow, but with no artificial per-tick delay and enough ticks/events to produce stable measurements.
3. **Multi-VM workload:** run several devices with displays to reveal scaling costs and decide whether native batching could amortize interop overhead.

Workloads may initially live as tests that print summaries. Later they can write JSON/text report files under Gradle reports.

### External profiler workflow

Document JFR and async-profiler workflows over the same workloads.

The guide should include:

- exact Gradle workload commands;
- how to run with JFR enabled;
- how to attach async-profiler when available;
- how to correlate profiler flamegraphs with in-code metrics;
- how to decide whether a hot path is a JNI/Rust candidate.

JFR should be the first external workflow because it is generally available with the JDK. async-profiler should remain optional because it is OS/tooling-dependent.

### Reports and comparison

Snapshots should be human-readable first. A later slice can add JSON snapshots and simple baseline comparison:

- before/after totals;
- ratios for main counters and timings;
- top deltas;
- no hard failure thresholds initially.

## First Implementation Slice

The first implementation slice should cover Phase 1 plus minimal Phase 2.

1. Extend display metrics with operation timings and tile serialization timings.
2. Extend runtime metrics with signal distribution and better derived summaries.
3. Add accessible compiler phase metrics without forcing a compiler architecture rewrite.
4. Add a sustained/no-delay terminal workload or variant.
5. Add a profiling guide with commands and interpretation notes.

## Out of Scope

- Rust implementation.
- JNI or FFM integration.
- Native library packaging.
- Hard CI performance budgets.
- Rewriting the VM or display architecture solely for profiling.
- Per-pixel/per-glyph native calls.

## Success Criteria

- Existing tests continue to pass with profiling disabled by default.
- The profiling workload prints combined runtime/display/compiler summaries with per-operation timings.
- The sustained workload can be run from Gradle without special local setup.
- Documentation explains how to collect JFR and optional async-profiler data.
- The resulting data can distinguish likely native candidates from paths where native interop would add overhead.

## Native Decision Heuristic

A path is a plausible JNI/Rust candidate only if all are true:

1. It is a measured CPU or allocation hotspot.
2. It can be invoked as a coarse batch.
3. Inputs and outputs can be represented as primitive buffers or compact handles.
4. It does not require frequent native-to-Java callbacks.
5. The expected savings exceed native packaging and crash-risk costs.

Examples likely to qualify after measurement: a full CKL VM slice runner, batched framebuffer operations, tile serialization/compression. Examples unlikely to qualify: individual `setPixel`, individual glyph calls, host filesystem calls, event queue bookkeeping, and UI glue.