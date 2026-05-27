# Дизайн numeric glyph display API

Дата: 2026-05-06

## Контекст

`rom/terminal.ck` сейчас хранит каждый 5x7 glyph как 35-символьную строку из `0` и `1`. Terminal передаёт эту строку в `display::blitMono(...)`, а host display path проверяет каждый символ при записи pixels.

Такой формат читаемый, но неэффективный для terminal rendering:

- glyph mask хранится как text, а не compact data;
- ROM program постоянно передаёт string masks через VM/runtime boundary;
- host path парсит characters вместо проверки integer bits;
- empty/background semantics уже покрываются display primitives и не требуют string masks.

CKL пока не имеет bitwise operators и binary integer literals. Этот дизайн улучшает glyph/render path, не превращая задачу в полноценный language-feature проект.

## Цели

- Представить ROM terminal glyphs как numeric row masks вместо 35-character strings.
- Добавить numeric display builtin для fixed 5x7 monochrome glyph blits.
- Сохранить существующий string-mask API `display::blitMono(...)` для совместимости.
- Не менять terminal row-batched rendering и dirty-region behavior.
- Не добавлять CKL bitwise operators на этом этапе.

## Не цели

- Не удалять `display::blitMono(...)`.
- Не добавлять generic arbitrary-size packed bitmap APIs сейчас.
- Не добавлять CKL bitwise operators (`&`, `|`, `^`, `~`, `<<`, `>>`).
- Не добавлять binary integer literals вроде `0b01110`.
- Не менять stdin/stdout/stderr stream architecture.

## API design

Добавить display builtin:

`display::blitMono5x7(displayId, x, y, row0, row1, row2, row3, row4, row5, row6, foreground, background): Unit`

Каждый row argument — `Int` в логическом диапазоне `0..31`. Пять младших bits описывают pixels слева направо. Например:

- `01110` хранится как `14`.
- `10001` хранится как `17`.
- `11111` хранится как `31`.

Runtime применяет mask `31` к каждому row перед drawing, поэтому старшие bits не могут рисовать за пределами 5-pixel glyph width.

`background < 0` сохраняет текущее transparent-background behavior из `display::blitMono(...)`. Иначе zero bits заполняются `background`.

Invalid или missing display ids остаются no-op, как в текущем display API.

## ROM terminal design

Добавить CKL struct в `terminal.ck`:

`Glyph5x7 { row0: Int, row1: Int, row2: Int, row3: Int, row4: Int, row5: Int, row6: Int }`

Заменить `glyphPattern(ch): String` на `glyphRows(ch): Glyph5x7`.

`drawGlyph(...)` сохраняет текущий fast path для space через `display::fillRect(...)`. Для остальных символов он вызывает `display::blitMono5x7(...)` с семью numeric rows и текущими foreground/background colors.

Все higher-level terminal rendering functions остаются без архитектурных изменений:

- `renderTextRow(...)` всё ещё redraws one text row.
- `commitDirtySegment(...)` всё ещё batches contiguous dirty glyphs by row.
- `appendText(...)` всё ещё обрабатывает `\n`, `\r` и `\b`.
- Shell-owned Enter echo и newline-delimited stdin behavior остаются без изменений.

## Runtime design

Добавить `blitMono5x7(...)` рядом с текущим `blitMono(...)` path:

- compiler builtin registry публикует CKL function signature;
- runtime host bridge dispatches builtin в device display API;
- device display API и no-op display API получают typed method;
- core display registry records one blit operation with area `5 * 7`;
- display state forwards to pixel buffer;
- pixel buffer draws 5x7 rows через проверку integer bits.

Существующая string-mask реализация `blitMono(...)` остаётся для BIOS splash, user programs и будущего generic bitmap use.

## Testing

Добавить или обновить tests, чтобы проверить:

- `blitMono5x7(...)` рисует те же pixels, что equivalent string mask через `blitMono(...)`.
- Transparent background (`background < 0`) всё ещё оставляет zero bits без изменений.
- Display profiling считает numeric glyph blit как one `blitMono` call with area `35`.
- Runtime host bridge routes `display::blitMono5x7(...)` в display API с correct arguments.
- ROM terminal source использует `display::blitMono5x7(...)` и больше не возвращает 35-character glyph strings из `glyphPattern(...)`.
- Все bundled ROM scripts compile cleanly.

Full verification:

- `./gradlew :compiler:test`
- `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`
- `./gradlew test`
- `git diff --check`

## Future work

После появления этого API CKL bitwise operators можно спроектировать как отдельную language feature. Этот будущий этап улучшит authoring и manipulation of numeric masks, но не нужен для первой performance-focused glyph migration.