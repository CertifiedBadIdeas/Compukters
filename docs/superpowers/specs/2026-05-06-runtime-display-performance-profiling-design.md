# Runtime display performance profiling design

## Context

The VM no longer has `terminal`/`stdout` runtime output. Visible runtime UI is rendered by programs through `display::*`, and the bundled ROM terminal is now a CKL program that renders text into the display framebuffer.

The current display implementation already resembles a simplified video device:

- `DisplayRegistry` owns attached displays.
- `PixelBuffer` is persistent pixel memory for each display.
- `TileDirtyTracker` marks dirty framebuffer tiles.
- `present()` publishes `DisplayFrameDelta` objects for client display sessions.

The observed problem is poor performance, especially around runtime terminal/display rendering. Before changing architecture, this pass must measure where the cost actually comes from.

## Goals

- Add deterministic profiling hooks for the runtime display path.
- Measure the bundled ROM terminal workload with a reproducible test scenario.
- Capture counts and payload sizes that are stable enough for automated regression tests.
- Capture broad timing diagnostics for local analysis without making tests flaky.
- Produce evidence for a later optimization pass.

## Non-goals

- Do not add `display::drawText`, `drawBitmap`, `blit`, or other new user-facing graphics APIs in this pass.
- Do not introduce terminal/text video memory yet.
- Do not change ROM terminal rendering behavior except where instrumentation requires observation hooks.
- Do not reintroduce VM `terminal` or `stdout` built-ins.
- Do not add an internal diagnostics renderer.
- Do not make wall-clock timing the primary CI assertion.

## Profiling model

### Display operation metrics

Add an optional no-op-by-default display metrics collector around the VM display implementation. It should be enabled explicitly by tests or local profiling helpers.

Collect at least:

- `clear` call count.
- `setPixel` call count.
- `fillRect` call count.
- `present` call count.
- Total filled rectangle area in pixels.
- Optional clipped filled area if easy to collect without expensive work.

The collector should be attached near `DisplayRegistry` or `VmDisplayApi`, where all CKL display calls pass through a single boundary.

### Frame delta metrics

Collect metrics when display frames are drained or flushed:

- Number of emitted `DisplayFrameDelta` objects.
- Number of full-refresh frames.
- Number of dirty tiles.
- Approximate payload bytes (`tile.payload.size`).
- Frame dimensions and tile-size-derived totals where useful.

These metrics should explain the network/client pressure caused by server-side display updates.

### Tick/runtime diagnostics

For profiling runs, collect broad durations around:

- VM slice request / execution window as seen by the test or runtime tick loop.
- Host-call dispatch and result delivery.
- Display frame drain / display session flush.

Timing metrics are diagnostic output. Tests may assert very broad sanity thresholds only if they are stable. Counts and payload sizes are the preferred regression checks.

## Baseline workload

Create a reusable test/profiling fixture that runs the same terminal scenario:

1. Initialize a device workspace with bundled ROM.
2. Boot firmware with the bundled ROM terminal.
3. Attach a display of a fixed size.
4. Run ticks until shell prompt appears.
5. Type a short command through events.
6. Submit it with Enter.
7. Run ticks until shell output is rendered.
8. Drain display frames and record metrics.

The scenario should cover:

- Firmware status drawing.
- ROM terminal startup drawing.
- Input-line redraw.
- Shell output rendering.
- Frame emission during normal display session operation.

## Expected outputs

The profiling test/helper should produce a compact summary similar to:

```text
display: clear=..., setPixel=..., fillRect=..., fillArea=..., present=...
frames: count=..., fullRefresh=..., tiles=..., payloadBytes=...
timing: sliceNanos=..., hostCallNanos=..., displayFlushNanos=...
```

Automated tests should assert that metrics are collected and internally consistent, for example:

- `presentCount >= frameCount` where applicable.
- `payloadBytes == sum(tile.payload.size)`.
- terminal workload produces non-zero display operations and frame payload.
- no behavior regressions in existing terminal rendering tests.

Strict performance budgets should be introduced only after baseline numbers are known and stable.

## Future decisions enabled by this pass

The collected data will decide the next optimization design. Likely candidates include:

- ROM terminal text video memory with dirty cells or rows.
- Rendering glyph row runs instead of per-pixel `fillRect` calls.
- Display API additions such as `blit`, `drawBitmap`, or `copyRect` for graphics workloads.
- Coalescing multiple `present()` calls per server tick.
- Tuning tile size or dirty tracking.
- Reducing VM instruction overhead for display-heavy programs.

This pass intentionally stops before choosing one of these optimizations.

## Acceptance criteria

- A profiling-only display metrics path exists and is disabled by default.
- The bundled ROM terminal workload can be measured in an automated test or test helper.
- Metrics include display operation counts, frame/tile/payload counts, and diagnostic timing.
- Existing runtime display/terminal behavior tests still pass.
- Full verification passes with `./gradlew test`.
