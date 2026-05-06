# Runtime CPU-time profiling design

## Context

The runtime display profiling pass added stable counters for display operations and frame deltas. The first baseline for the bundled ROM terminal workload showed high display call and payload activity, but its timing output still includes artificial test-loop delay. That makes the timing line useful only as a smoke signal, not as a server CPU baseline.

The next step is to measure CPU-time-like runtime phases more directly while keeping the work profiling-only. The goal is to separate server tick costs, host call costs, display drain/flush costs, and VM coroutine scheduling/execution diagnostics before choosing any optimization.

## Goals

- Add optional no-op-by-default runtime timing metrics for host/server tick phases.
- Add optional no-op-by-default VM-side scheduling and execution diagnostics.
- Combine runtime timing metrics with the existing display metrics in a reproducible bundled ROM terminal workload.
- Avoid treating wall-clock duration as a strict CI performance budget.
- Produce baseline numbers that can guide a later optimization pass.

## Non-goals

- Do not optimize the ROM terminal in this pass.
- Do not change CKL display APIs.
- Do not add `drawText`, `drawBitmap`, `blit`, `copyRect`, or text video memory.
- Do not reintroduce VM `terminal` or `stdout` built-ins.
- Do not add continuous production logging or a user-facing profiler UI.
- Do not make tests fail because a machine is slower than another machine.

## Profiling model

### Runtime metrics collector

Add a new collector in `core`, separate from `DisplayMetricsCollector`, for runtime timing and scheduling diagnostics. The collector should be no-op by default and enabled explicitly by tests or local diagnostics.

The model should include:

- `RuntimeMetricsCollector` interface.
- `NoOpRuntimeMetricsCollector` default implementation.
- `RecordingRuntimeMetricsCollector` thread-safe implementation.
- `RuntimeProfilingSnapshot` data class.
- `summary()` text output for local analysis.

The recording collector should use atomic counters because server tick code and VM coroutine code may record into the same collector.

### Host/server tick phases

Measure monotonic elapsed time around the phases executed by the server-side runtime tick path:

- Total `serverTick` duration.
- `requestSlice` duration.
- Host call drain duration and drained call count.
- Host call dispatch duration and dispatched call count.
- Host result delivery duration and delivered result count.
- Display frame drain duration and drained frame count.
- Display session flush duration, including network-send loop work visible to the server-side code.

These measurements should be attached near `RuntimeDeviceImpl`, because that is where the real server tick path sequences VM slice requests, host calls, and display flushing.

### VM-side diagnostics

Measure broad VM coroutine diagnostics in `BackgroundDeviceVm`:

- Slice permit request count.
- Slice permit sent count.
- Sleep-gated slice request count.
- Slice permit receive count.
- Scheduling point count.
- Yield scheduling point count.
- Wait-for-next-slice scheduling point count.
- Approximate VM execution window nanoseconds between slice permit receipt and the next wait for a permit or terminal stop.

These diagnostics should explain whether time is being spent in host/server orchestration, VM execution, or display/frame flushing. They are intentionally coarse and should not require invasive interpreter instrumentation.

## Data flow

A profiling test creates both collectors:

- `RecordingDisplayMetricsCollector` for display operation/frame metrics.
- `RecordingRuntimeMetricsCollector` for runtime/tick/VM timing metrics.

The VM receives both collectors through construction. The runtime device or test harness records host/server phases while driving the same bundled firmware and ROM terminal workload used by the display profiling test.

The final output should include both summaries:

```text
display: clear=..., setPixel=..., fillRect=..., fillArea=..., present=..., presentFrames=...
frames: count=..., fullRefresh=..., tiles=..., payloadBytes=...
runtime: serverTicks=..., serverTickNanos=..., requestSliceNanos=...
host: drainedCalls=..., dispatchedCalls=..., drainNanos=..., dispatchNanos=..., deliverNanos=...
display-runtime: drainFrames=..., drainNanos=..., flushNanos=...
vm: sliceRequests=..., slicePermits=..., sleepGated=..., schedulingPoints=..., executionNanos=...
```

## Testing strategy

Use TDD for the implementation:

1. Add a failing unit test for `RecordingRuntimeMetricsCollector` counters and snapshot consistency.
2. Implement the runtime profiling model.
3. Add a failing test that verifies `BackgroundDeviceVm` records VM-side metrics when slices are requested and consumed.
4. Wire the VM collector.
5. Add or extend a bundled ROM terminal profiling integration test that prints display and runtime summaries and asserts non-zero stable counters.
6. Run full verification with `./gradlew test`.

Tests should assert stable facts:

- No-op collector snapshots remain empty after record calls.
- Recording collector accumulates counts and durations exactly in unit tests.
- Bundled workload produces non-zero display and runtime counters.
- Frame and operation metrics remain internally consistent.
- Existing terminal behavior tests still pass.

Tests should not assert strict performance thresholds such as maximum milliseconds per tick.

## Acceptance criteria

- Runtime CPU-time profiling hooks exist and are disabled by default.
- Host/server tick phases are measured independently enough to distinguish request, host-call, display-drain, display-flush, and total tick costs.
- VM-side scheduling and approximate execution diagnostics are available in the same profiling run.
- The bundled ROM terminal workload can print a combined display + runtime profiling summary.
- The current timing baseline no longer represents artificial `delay(10)` time as CPU cost.
- Full `./gradlew test` passes.
