# Дизайн display graphics accelerator и text VRAM для ROM terminal

## Контекст

Этапы display/runtime profiling показали, что bundled ROM terminal дорогой из-за большого количества мелких display operations. Текущий glyph renderer рисует каждый включённый пиксель glyph через `display::fillRect(..., 1, 1, ...)`, а обычный terminal output может перерисовывать большие части экрана. Измеренный bundled terminal workload дал тысячи `fillRect` calls для маленького сценария boot плюс `help`.

Маленькая оптимизация row-runs уменьшила бы часть вызовов, но оставила бы ту же архитектурную проблему: terminal text rendering остаётся CKL loop, который превращает текст в множество мелких framebuffer writes. Нужное направление — чистая архитектура video device, которая также поддержит будущую не-текстовую графику.

## Цели

- Заменить per-pixel terminal glyph rendering generic accelerated framebuffer primitives.
- Добавить primitives, полезные для графики, а не terminal-specific shortcuts.
- Оставить terminal behavior в ROM code; не переносить terminal semantics в VM.
- Ввести ROM terminal text video memory, чтобы routine output/input обновляли dirty rows/cells вместо full-screen redraw.
- Сохранить существующую framebuffer, dirty-tile и frame-delta model.
- Сделать новый API совместимым с будущей command-buffer-backed display implementation.
- Измерить improvement через существующие display/runtime profiling tests.

## Не цели

- Не добавлять VM-side terminal или stdout renderer.
- Не добавлять `terminal` или `stdout` builtin namespace.
- Не добавлять `display::drawText` как terminal/text-specific host shortcut.
- Не реализовывать полный public command buffer в этом pass.
- Не вводить sprites, palettes, images или layers сейчас.
- Не добавлять строгие wall-clock performance budgets в CI tests.

## Новые публичные display API

### `display::copyRect`

Добавляем generic framebuffer copy primitive:

```ck
copyRect(displayId: Int, srcX: Int, srcY: Int, width: Int, height: Int, dstX: Int, dstY: Int): Unit
```

Семантика:

- Копирует pixels внутри display back buffer.
- Корректно обрабатывает overlapping source/destination regions.
- Clip к display bounds.
- Помечает destination rectangle dirty.
- Не вызывает `present` автоматически.

Основное terminal use: scroll framebuffer up by one text row без redraw всех visible rows.

Future graphics use: перемещение rectangular regions, например sprites, windows, cursor layers или viewport contents.

### `display::blitMono`

Добавляем generic monochrome bitmap mask primitive:

```ck
blitMono(displayId: Int, x: Int, y: Int, width: Int, height: Int, mask: String, foreground: Int, background: Int): Unit
```

Семантика:

- `mask` — row-major text из `0` и `1` bits.
- Bit `1` пишет `foreground` RGB565.
- Bit `0` пишет `background` RGB565, когда `background >= 0`.
- Bit `0` transparent, когда `background < 0`.
- Bits beyond `width * height` игнорируются; missing bits считаются `0`.
- Affected rectangle clip к display bounds.
- Affected rectangle помечается dirty.
- Не вызывает `present` автоматически.

Основное terminal use: рисовать один 5x7 glyph одной host-side framebuffer operation вместо многих 1x1 fills.

Future graphics use: icons, masks, bitmap fonts, monochrome sprites, selection/cursor masks и simple UI glyphs.

## Runtime implementation model

Новые primitives реализуются в существующем display stack:

- `DeviceDisplayApi` получает `copyRect` и `blitMono` methods.
- `NoopDeviceDisplayApi` реализует их как no-ops.
- `LanguageBuiltins` exposes them в `display` module.
- `RuntimeHostBridge` routes CKL calls to `DeviceDisplayApi`.
- `VmDisplayApi` delegates to `DisplayRegistry`.
- `DisplayRegistry` delegates to `DisplayState` и records profiling counters.
- `DisplayState` updates `PixelBuffer` и `TileDirtyTracker`.
- `PixelBuffer` performs clipped native Kotlin loops.

В этом pass operations могут применяться immediately к back buffer. Shape API всё равно должен быть command-buffer-compatible: каждая operation — полноценная display command, которую позже можно будет queue/apply на `present()` без изменения CKL call sites.

## Обновления profiling metrics

Расширяем display profiling metrics:

- `copyRectCalls`.
- `copyRectArea`.
- `blitMonoCalls`.
- `blitMonoArea`.

Bundled profiling workload должен проверять, что optimized terminal rendering использует `blitMono` и существенно меньше `fillRect` calls, чем текущий baseline. Он не должен проверять строгие wall-clock timings.

## ROM terminal text VRAM model

ROM terminal становится framebuffer text driver, а не full-screen text painter.

State:

- Display id.
- Columns and rows from display size.
- Fixed-size text cell buffer для visible terminal cells.
- Cursor row and column.
- Current input line.
- Dirty row tracking.

Output handling:

- Shell stdout/stderr chunks читаются как text streams.
- Printable characters записываются в cell buffer в cursor position.
- Newlines двигают cursor на next row.
- Когда output scrolls past bottom, terminal вызывает `display::copyRect` для перемещения framebuffer pixels up by one text row, clears last text row, updates text cell buffer и marks last row dirty.
- Redraw делается только для dirty rows.

Input handling:

- Typed characters обновляют только input line cells.
- Backspace обновляет только changed input line region.
- Prompt cells не очищаются при input changes.
- Enter writes line to shell input channel, commits it to text buffer, advances cursor и redraws only affected rows.

Rendering:

- Dirty row clear выполняется одним `display::fillRect` для row background.
- Non-space glyphs render через `display::blitMono` с существующими 5x7 glyph patterns.
- `display::present` вызывается после batch dirty row/cell updates, а не после каждого glyph.
- Full redraw остаётся допустимым для display attach, resize и terminal reset.

Так terminal semantics остаются в ROM, а display device становится generic accelerated framebuffer.

## Testing strategy

### Core display tests

Добавить или обновить tests для:

- `PixelBuffer.copyRect` clipping.
- `PixelBuffer.copyRect` overlapping copies во всех направлениях.
- `PixelBuffer.blitMono` foreground, background, transparent background, clipping, short masks и long masks.
- `DisplayState` и `DisplayRegistry` dirty tiles after `copyRect` and `blitMono`.
- `DisplayProfilingTest` counters for new operations.

### Compiler/runtime tests

Добавить или обновить tests для:

- `display::copyRect` и `display::blitMono` builtins присутствуют в runtime registry.
- `RuntimeHostBridge` dispatches new display functions.
- `VmDisplayApi` delegates them correctly.

### ROM terminal tests

Сохранить existing tests:

- Firmware status renders glyph shapes.
- ROM terminal renders shell output.
- Prompt remains visible while typing.
- Backspace does not cause a full framebuffer redraw per keypress.

Добавить profiling assertions:

- Bundled terminal workload uses `blitMonoCalls > 0`.
- `fillRectCalls` substantially below previous `2402` baseline for same boot plus `help` scenario.
- Scroll-heavy terminal workload uses `copyRectCalls > 0`.
- Frame payload and dirty tile counts remain internally consistent.

## Acceptance criteria

- `display::copyRect` and `display::blitMono` are documented CKL display APIs.
- Terminal no longer draws glyphs with per-pixel `fillRect` calls.
- Routine terminal output and input update dirty rows/cells instead of redrawing the whole screen.
- Existing terminal behavior remains unchanged from user perspective.
- Profiling output shows `blitMonoCalls > 0` and large reduction in `fillRectCalls` compared with current `2402` baseline.
- Scroll-heavy workload uses `copyRectCalls > 0`.
- Full `./gradlew test` passes.
- Forbidden terminal/stdout API audit remains clean.
