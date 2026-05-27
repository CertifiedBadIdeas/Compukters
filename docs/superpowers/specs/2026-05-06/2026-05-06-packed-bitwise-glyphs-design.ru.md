# Дизайн Packed Bitwise Glyphs

Дата: 2026-05-06

## Контекст

Stage 1 заменил terminal glyph string masks на numeric `Glyph5x7` rows и добавил `display::blitMono5x7(...)`. Stage 2 добавил CKL bitwise operators и binary integer literals.

ROM terminal всё ещё аллоцирует `Glyph5x7` record на каждый non-space glyph lookup. Каждый rendered glyph создаёт record с семью row fields, а render path сразу читает эти семь fields, чтобы вызвать `display::blitMono5x7(...)`. Это лишняя allocation на hot terminal rendering path.

## Цели

- Представлять каждый 5x7 glyph как один packed `Long` value в ROM code.
- Убрать `Glyph5x7` struct allocation из glyph lookup и rendering.
- Добавить packed display builtin, чтобы CKL code передавал host один `Long` glyph.
- Сохранить existing `display::blitMono5x7(...)` для compatibility.
- Сохранить glyph shapes, особенно balanced `<` и `>` glyphs.
- Сохранить runtime display pipeline и dirty/metrics behavior эквивалентными текущим 5x7 blits.

## Не цели

- Не удалять и не менять existing `display::blitMono5x7(...)`.
- Не менять framebuffer primitives или core `PixelBuffer.blitMono5x7(...)` implementation.
- Не добавлять general constant folding или struct scalar replacement в этом stage.
- Не добавлять unsigned integers или unsigned shifts.
- Не redesign terminal text buffering, scrolling, stdin или shell echo behavior.

## Packed glyph format

Glyph — это 35-bit payload, сохранённый в `Long`:

- 7 rows.
- 5 bits per row.
- `row0` занимает bits 34..30.
- `row1` занимает bits 29..25.
- `row2` занимает bits 24..20.
- `row3` занимает bits 19..15.
- `row4` занимает bits 14..10.
- `row5` занимает bits 9..5.
- `row6` занимает bits 4..0.

Пример для `A`:

```text
01110 10001 10001 11111 10001 10001 10001
```

В CKL это:

```ck
0b01110100011000111111100011000110001L
```

Любые upper bits выше bit 34 игнорируются host decoder.

## New display builtin

Добавить CKL builtin:

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

Core display layer остаётся без изменений. Packed builtin — это VM/host bridge convenience, который уменьшает CKL-side allocation и argument traffic.

## ROM terminal changes

Заменить:

```ck
pub struct Glyph5x7 { row0: Int, row1: Int, row2: Int, row3: Int, row4: Int, row5: Int, row6: Int }

fun glyphRows(ch: String): Glyph5x7 { ... }
```

На:

```ck
fun glyphBits(ch: String): Long { ... }
```

Каждая branch возвращает один packed binary `Long` literal. Например:

```ck
if (ch == "A" || ch == "a") { return 0b01110100011000111111100011000110001L }
```

Rendering меняется с:

```ck
val glyph: Glyph5x7 = glyphRows(ch)
display::blitMono5x7(displayId, x, y, glyph.row0, glyph.row1, glyph.row2, glyph.row3, glyph.row4, glyph.row5, glyph.row6, color, -1)
```

На:

```ck
val glyph: Long = glyphBits(ch)
display::blitMono5x7Packed(displayId, x, y, glyph, color, -1)
```

Space остаётся fast path через `display::fillRect(...)`, потому что он очищает всю 6x9 cell area.

## Compatibility

- Existing CKL programs with `display::blitMono5x7(...)` continue to compile and run.
- Packed builtin is additive.
- ROM terminal behavior and glyph shapes remain visually equivalent.
- Existing display dirty tracking and profiling still count the operation as a 5x7 monochrome blit, потому что bridge delegates to `runtime.display.blitMono5x7(...)`.

## Testing

Добавить или обновить tests для:

- Frontend builtin registration: program using `display::blitMono5x7Packed(...)` compiles cleanly.
- Runtime host bridge decoding: packed glyph reaches `DeviceDisplayApi.blitMono5x7(...)` as the same seven rows.
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

## Future work

Later work can add compiler constant folding, struct scalar replacement или more general bitmap API. Это отдельные оптимизации, они не required for this ROM terminal allocation fix.