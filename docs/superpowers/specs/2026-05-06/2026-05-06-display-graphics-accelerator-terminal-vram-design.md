# Display graphics accelerator and ROM terminal text VRAM design

## Context

The display and runtime profiling passes showed that the bundled ROM terminal is expensive because it renders text through many small display operations. The current glyph renderer draws every lit glyph pixel with `display::fillRect(..., 1, 1, ...)`, and normal terminal output can redraw large portions of the screen. The measured bundled terminal workload produced thousands of `fillRect` calls for a small boot plus `help` scenario.

A small row-run optimization would reduce some calls, but it would keep the same architectural problem: terminal text rendering remains a CKL loop that turns text into many tiny framebuffer writes. The desired direction is a clean video-device architecture that also supports future non-text graphics.

## Goals

- Replace per-pixel terminal glyph rendering with generic accelerated framebuffer primitives.
- Add primitives that are useful for graphics, not terminal-specific shortcuts.
- Keep terminal behavior in ROM code; do not move terminal semantics into the VM.
- Introduce ROM terminal text video memory so routine output/input updates dirty rows or cells instead of redrawing the full screen.
- Preserve the existing framebuffer, dirty-tile, and frame-delta model.
- Keep the new API compatible with a future command-buffer-backed display implementation.
- Measure the improvement through the existing display and runtime profiling tests.

## Non-goals

- Do not add a VM-side terminal or stdout renderer.
- Do not add a `terminal` or `stdout` builtin namespace.
- Do not add `display::drawText` as a terminal/text-specific host shortcut.
- Do not implement a full public command buffer in this pass.
- Do not introduce sprites, palettes, images, or layers yet.
- Do not add strict wall-clock performance budgets to CI tests.

## Public display API additions

### `display::copyRect`

Add a generic framebuffer copy primitive:

```ck
copyRect(displayId: Int, srcX: Int, srcY: Int, width: Int, height: Int, dstX: Int, dstY: Int): Unit
```

Semantics:

- Copies pixels inside the display back buffer.
- Handles overlapping source and destination regions correctly.
- Clips to display bounds.
- Marks the destination rectangle dirty.
- Does not present automatically.

Primary terminal use: scroll the framebuffer up by one text row without redrawing every visible row.

Future graphics use: moving rectangular regions such as sprites, windows, cursor layers, or viewport contents.

### `display::blitMono`

Add a generic monochrome bitmap mask primitive:

```ck
blitMono(displayId: Int, x: Int, y: Int, width: Int, height: Int, mask: String, foreground: Int, background: Int): Unit
```

Semantics:

- `mask` is row-major text containing `0` and `1` bits.
- A `1` bit writes `foreground` RGB565.
- A `0` bit writes `background` RGB565 when `background >= 0`.
- A `0` bit is transparent when `background < 0`.
- Bits past `width * height` are ignored; missing bits are treated as `0`.
- The affected rectangle is clipped to display bounds.
- The affected rectangle is marked dirty.
- Does not present automatically.

Primary terminal use: draw one 5x7 glyph with one host-side framebuffer operation instead of many 1x1 fills.

Future graphics use: icons, masks, bitmap fonts, monochrome sprites, selection/cursor masks, and simple UI glyphs.

## Runtime implementation model

The new primitives are implemented in the existing display stack:

- `DeviceDisplayApi` gains `copyRect` and `blitMono` methods.
- `NoopDeviceDisplayApi` implements them as no-ops.
- `LanguageBuiltins` exposes them in the `display` module.
- `RuntimeHostBridge` routes CKL calls to `DeviceDisplayApi`.
- `VmDisplayApi` delegates to `DisplayRegistry`.
- `DisplayRegistry` delegates to `DisplayState` and records profiling counters.
- `DisplayState` updates `PixelBuffer` and `TileDirtyTracker`.
- `PixelBuffer` performs clipped native Kotlin loops.

The implementation may apply operations immediately to the back buffer in this pass. The API shape should still be command-buffer-compatible: each operation is a complete display command that can later be queued and applied on `present()` without changing CKL call sites.

## Profiling metrics updates

Extend display profiling metrics with:

- `copyRectCalls`.
- `copyRectArea`.
- `blitMonoCalls`.
- `blitMonoArea`.

The bundled profiling workload should assert that optimized terminal rendering uses `blitMono` and substantially fewer `fillRect` calls than the current baseline. It should not assert strict wall-clock timings.

## ROM terminal text VRAM model

The ROM terminal becomes a framebuffer text driver rather than a full-screen text painter.

State:

- Display id.
- Columns and rows derived from display size.
- Fixed-size text cell buffer for visible terminal cells.
- Cursor row and column.
- Current input line.
- Dirty row tracking.

Output handling:

- Shell stdout and stderr chunks are consumed as text streams.
- Printable characters are written into the cell buffer at the cursor.
- Newlines move the cursor to the next row.
- When output scrolls past the bottom, the terminal calls `display::copyRect` to move framebuffer pixels up by one text row, clears the last text row, updates the text cell buffer, and marks the last row dirty.
- Only dirty rows are redrawn.

Input handling:

- Typed characters update only the input line cells.
- Backspace updates only the changed input line region.
- The prompt cells are not cleared when input changes.
- Pressing Enter writes the line to the shell input channel, commits it to the text buffer, advances the cursor, and redraws only affected rows.

Rendering:

- A dirty row clear is performed with one `display::fillRect` for the row background.
- Non-space glyphs are rendered with `display::blitMono` using the existing 5x7 glyph patterns.
- `display::present` is called after a batch of dirty row/cell updates, not after every glyph.
- Full redraw remains allowed for display attach, resize, and terminal reset.

This keeps terminal semantics in ROM while making the display device a generic accelerated framebuffer.

## Testing strategy

### Core display tests

Add or update tests for:

- `PixelBuffer.copyRect` clipping.
- `PixelBuffer.copyRect` overlapping copies in every direction.
- `PixelBuffer.blitMono` foreground, background, transparent background, clipping, short masks, and long masks.
- `DisplayState` and `DisplayRegistry` dirty tiles after `copyRect` and `blitMono`.
- `DisplayProfilingTest` counters for the new operations.

### Compiler/runtime tests

Add or update tests for:

- `display::copyRect` and `display::blitMono` builtins are present in the runtime registry.
- `RuntimeHostBridge` dispatches the new display functions.
- `VmDisplayApi` delegates them correctly.

### ROM terminal tests

Preserve existing tests:

- Firmware status renders glyph shapes.
- ROM terminal renders shell output.
- Prompt remains visible while typing.
- Backspace does not cause a full framebuffer redraw per keypress.

Add profiling assertions:

- Bundled terminal workload uses `blitMonoCalls > 0`.
- `fillRectCalls` is substantially below the previous `2402` baseline for the same boot plus `help` scenario.
- Scroll-heavy terminal workload uses `copyRectCalls > 0`.
- Frame payload and dirty tile counts remain internally consistent.

## Acceptance criteria

- `display::copyRect` and `display::blitMono` are documented CKL display APIs.
- The terminal no longer draws glyphs with per-pixel `fillRect` calls.
- Routine terminal output and input update dirty rows or cells instead of redrawing the whole screen.
- Existing terminal behavior remains unchanged from the user's perspective.
- Profiling output shows `blitMonoCalls > 0` and a large reduction in `fillRectCalls` compared with the current `2402` baseline.
- A scroll-heavy workload uses `copyRectCalls > 0`.
- Full `./gradlew test` passes.
- The forbidden terminal/stdout API audit remains clean.
