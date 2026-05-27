# Packed Bitwise Glyphs Design

Date: 2026-05-06

## Context

Stage 1 replaced terminal glyph string masks with numeric `Glyph5x7` rows and added `display::blitMono5x7(...)`. Stage 2 added CKL bitwise operators and binary integer literals.

The ROM terminal still allocates a `Glyph5x7` record for every non-space glyph lookup. Each rendered glyph creates a record containing seven row fields, then the render path immediately reads those seven fields to call `display::blitMono5x7(...)`. This is unnecessary allocation on a hot terminal rendering path.

## Goals

- Represent each 5x7 glyph as one packed `Long` value in ROM code.
- Remove `Glyph5x7` struct allocation from glyph lookup and rendering.
- Add a packed display builtin so CKL code can pass a single `Long` glyph to the host.
- Preserve existing `display::blitMono5x7(...)` for compatibility.
- Preserve glyph shapes, especially the balanced `<` and `>` glyphs.
- Keep the runtime display pipeline and dirty/metrics behavior equivalent to current 5x7 blits.

## Non-goals

- Do not remove or change existing `display::blitMono5x7(...)`.
- Do not change framebuffer primitives or the core `PixelBuffer.blitMono5x7(...)` implementation.
- Do not add general constant folding or struct scalar replacement in this stage.
- Do not add unsigned integers or unsigned shifts.
- Do not redesign terminal text buffering, scrolling, stdin, or shell echo behavior.

## Packed Glyph Format

A glyph is a 35-bit payload stored in a `Long`:

- 7 rows.
- 5 bits per row.
- `row0` occupies bits 34..30.
- `row1` occupies bits 29..25.
- `row2` occupies bits 24..20.
- `row3` occupies bits 19..15.
- `row4` occupies bits 14..10.
- `row5` occupies bits 9..5.
- `row6` occupies bits 4..0.

Example for `A`:

```text
01110 10001 10001 11111 10001 10001 10001
```

In CKL this is:

```ck
0b01110100011000111111100011000110001L
```

Any upper bits above bit 34 are ignored by the host decoder.

## New Display Builtin

Add a CKL builtin:

```ck
display::blitMono5x7Packed(displayId: Int, x: Int, y: Int, glyph: Long, foreground: Int, background: Int): Unit
```

Runtime bridge behavior:

```kotlin
val glyph = arguments[3].asLong()
val row0 = ((glyph shr 30) and 0b11111).toInt()
val row1 = ((glyph shr 25) and 0b11111).toInt()
val row2 = ((glyph shr 20) and 0b11111).toInt()
val row3 = ((glyph shr 15) and 0b11111).toInt()
val row4 = ((glyph shr 10) and 0b11111).toInt()
val row5 = ((glyph shr 5) and 0b11111).toInt()
val row6 = (glyph and 0b11111).toInt()
runtime.display.blitMono5x7(displayId, x, y, row0, row1, row2, row3, row4, row5, row6, foreground, background)
```

This keeps the core display layer unchanged. The packed builtin is a VM/host bridge convenience that reduces CKL-side allocation and argument traffic.

## ROM Terminal Changes

Replace:

```ck
pub struct Glyph5x7 { row0: Int, row1: Int, row2: Int, row3: Int, row4: Int, row5: Int, row6: Int }

fun glyphRows(ch: String): Glyph5x7 { ... }
```

With:

```ck
fun glyphBits(ch: String): Long { ... }
```

Each branch returns one packed binary `Long` literal. For example:

```ck
if (ch == "A" || ch == "a") { return 0b01110100011000111111100011000110001L }
```

Rendering changes from:

```ck
val glyph: Glyph5x7 = glyphRows(ch)
display::blitMono5x7(displayId, x, y, glyph.row0, glyph.row1, glyph.row2, glyph.row3, glyph.row4, glyph.row5, glyph.row6, color, -1)
```

To:

```ck
val glyph: Long = glyphBits(ch)
display::blitMono5x7Packed(displayId, x, y, glyph, color, -1)
```

Space remains a fast path through `display::fillRect(...)` because it clears the whole 6x9 cell area.

## Compatibility

- Existing CKL programs using `display::blitMono5x7(...)` continue to compile and run.
- The packed builtin is additive.
- ROM terminal behavior and glyph shapes remain visually equivalent.
- Existing display dirty tracking and profiling still count the operation as a 5x7 monochrome blit because the bridge delegates to `runtime.display.blitMono5x7(...)`.

## Testing

Add or update tests for:

- Frontend builtin registration: a program using `display::blitMono5x7Packed(...)` compiles cleanly.
- Runtime host bridge decoding: a packed glyph reaches `DeviceDisplayApi.blitMono5x7(...)` as the same seven rows.
- ROM terminal source regression:
  - contains `fun glyphBits(ch: String): Long`;
  - contains `display::blitMono5x7Packed`;
  - does not contain `pub struct Glyph5x7`;
  - does not contain `fun glyphRows(ch: String): Glyph5x7`;
  - does not contain `Glyph5x7(`;
  - preserves expected packed `<` and `>` glyph values.
- ROM script compile regression still passes.
- Full compiler and full repository tests still pass.

Verification commands:

- `./gradlew :compiler:test`
- `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`
- `./gradlew test`
- `git diff --check`

## Future Work

Later work can add compiler constant folding, struct scalar replacement, or a more general bitmap API. Those are separate optimizations and should not be required for this ROM terminal allocation fix.