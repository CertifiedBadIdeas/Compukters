# Numeric Glyph Display API Design

Date: 2026-05-06

## Context

`rom/terminal.ck` currently stores every 5x7 glyph as a 35-character string of `0` and `1`. The terminal passes that string to `display::blitMono(...)`, and the host display path checks each character while writing pixels.

This representation is easy to read, but it is wasteful for terminal rendering:

- each glyph mask is text, not compact data;
- the ROM program repeatedly passes string masks across the VM/runtime boundary;
- the host path parses characters instead of checking integer bits;
- empty/background semantics are already handled by display primitives and do not need string masks.

CKL does not currently expose bitwise operators or binary integer literals. This design improves the glyph/render path without turning the change into a full language-feature project.

## Goals

- Represent ROM terminal glyphs as numeric row masks instead of 35-character strings.
- Add a numeric display builtin for fixed 5x7 monochrome glyph blits.
- Preserve the existing `display::blitMono(...)` string-mask API for compatibility.
- Keep terminal row-batched rendering and dirty-region behavior unchanged.
- Avoid adding CKL bitwise operators in this stage.

## Non-goals

- Do not remove `display::blitMono(...)`.
- Do not add generic arbitrary-size packed bitmap APIs yet.
- Do not add CKL bitwise operators (`&`, `|`, `^`, `~`, `<<`, `>>`).
- Do not add binary integer literals such as `0b01110`.
- Do not change stdin/stdout/stderr stream architecture.

## API Design

Add a display builtin:

`display::blitMono5x7(displayId, x, y, row0, row1, row2, row3, row4, row5, row6, foreground, background): Unit`

Each row argument is an `Int` in the logical range `0..31`. The five low bits represent pixels from left to right. For example:

- `01110` is stored as `14`.
- `10001` is stored as `17`.
- `11111` is stored as `31`.

The runtime masks rows with `31` before drawing, so higher bits cannot draw outside the 5-pixel glyph width.

`background < 0` keeps the current transparent-background behavior from `display::blitMono(...)`. Otherwise zero bits are filled with `background`.

Invalid or missing display ids remain no-op, matching the current display API behavior.

## ROM Terminal Design

Add a CKL struct in `terminal.ck`:

`Glyph5x7 { row0: Int, row1: Int, row2: Int, row3: Int, row4: Int, row5: Int, row6: Int }`

Replace `glyphPattern(ch): String` with `glyphRows(ch): Glyph5x7`.

`drawGlyph(...)` keeps the current space fast path with `display::fillRect(...)`. For non-space characters it calls `display::blitMono5x7(...)` with the seven numeric rows and the existing foreground/background colors.

All higher-level terminal rendering functions remain unchanged:

- `renderTextRow(...)` still redraws one text row.
- `commitDirtySegment(...)` still batches contiguous dirty glyphs by row.
- `appendText(...)` still handles `\n`, `\r`, and `\b`.
- The shell-owned Enter echo and newline-delimited stdin behavior remain unchanged.

## Runtime Design

Add `blitMono5x7(...)` beside the existing `blitMono(...)` path:

- compiler builtin registry exposes the CKL function signature;
- runtime host bridge dispatches the builtin to the device display API;
- device display API and no-op display API gain a typed method;
- core display registry records one blit operation with area `5 * 7`;
- display state forwards to the pixel buffer;
- pixel buffer draws the 5x7 rows by checking integer bits.

The existing string-mask `blitMono(...)` implementation stays intact for BIOS splash, user programs, and future generic bitmap use.

## Testing

Add or update tests to verify:

- `blitMono5x7(...)` draws the same pixels as the equivalent `blitMono(...)` string mask.
- Transparent background (`background < 0`) still leaves zero bits unchanged.
- Display profiling counts the numeric glyph blit as one `blitMono` call with area `35`.
- Runtime host bridge routes `display::blitMono5x7(...)` to the display API with correct arguments.
- ROM terminal source uses `display::blitMono5x7(...)` and no longer returns 35-character glyph strings from `glyphPattern(...)`.
- All bundled ROM scripts compile cleanly.

Full verification should run:

- `./gradlew :compiler:test`
- `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`
- `./gradlew test`
- `git diff --check`

## Future Work

After this API exists, CKL bitwise operators can be designed as a separate language feature. That later work can improve authoring and manipulation of numeric masks, but it is not required for the first performance-focused glyph migration.