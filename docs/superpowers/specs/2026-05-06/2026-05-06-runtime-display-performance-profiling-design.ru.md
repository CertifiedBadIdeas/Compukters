# Дизайн профилирования производительности runtime display

## Контекст

В VM больше нет runtime output через `terminal`/`stdout`. Видимый runtime UI рендерится программами через `display::*`, а bundled ROM terminal теперь является CKL-программой, которая рисует текст в display framebuffer.

Текущая display-реализация уже похожа на упрощенное видеоустройство:

- `DisplayRegistry` владеет подключенными displays.
- `PixelBuffer` является persistent pixel memory для каждого display.
- `TileDirtyTracker` отмечает dirty framebuffer tiles.
- `present()` публикует `DisplayFrameDelta` для клиентских display sessions.

Наблюдаемая проблема — плохая производительность, особенно вокруг runtime terminal/display rendering. Перед архитектурными изменениями этот проход должен измерить, где именно появляется стоимость.

## Цели

- Добавить deterministic profiling hooks для runtime display path.
- Измерить bundled ROM terminal workload через воспроизводимый test scenario.
- Собирать counts и payload sizes, достаточно стабильные для automated regression tests.
- Собирать broad timing diagnostics для локального анализа без flaky CI tests.
- Получить evidence для следующего optimization pass.

## Не-цели

- Не добавлять `display::drawText`, `drawBitmap`, `blit` или другие новые user-facing graphics APIs в этом проходе.
- Не вводить terminal/text video memory пока.
- Не менять поведение ROM terminal renderer, кроме observation hooks для instrumentation.
- Не возвращать VM `terminal` или `stdout` built-ins.
- Не добавлять internal diagnostics renderer.
- Не делать wall-clock timing главным CI assertion.

## Модель профилирования

### Display operation metrics

Добавить optional no-op-by-default display metrics collector вокруг VM display implementation. Он должен включаться явно тестами или local profiling helpers.

Минимальный набор метрик:

- Количество `clear` calls.
- Количество `setPixel` calls.
- Количество `fillRect` calls.
- Количество `present` calls.
- Суммарная площадь fill rectangles в pixels.
- Optional clipped filled area, если это можно собрать без дорогой работы.

Collector лучше подключить около `DisplayRegistry` или `VmDisplayApi`, где все CKL display calls проходят через одну границу.

### Frame delta metrics

Собирать метрики при drain/flush display frames:

- Количество emitted `DisplayFrameDelta` objects.
- Количество full-refresh frames.
- Количество dirty tiles.
- Approximate payload bytes (`tile.payload.size`).
- Frame dimensions и tile-size-derived totals, если полезно.

Эти метрики должны объяснить network/client pressure от server-side display updates.

### Tick/runtime diagnostics

Для profiling runs собирать broad durations вокруг:

- VM slice request / execution window с точки зрения test или runtime tick loop.
- Host-call dispatch и result delivery.
- Display frame drain / display session flush.

Timing metrics являются diagnostic output. Tests могут проверять только очень широкие sanity thresholds, если они стабильны. Counts и payload sizes — предпочтительные regression checks.

## Baseline workload

Создать reusable test/profiling fixture, который запускает один и тот же terminal scenario:

1. Инициализировать device workspace с bundled ROM.
2. Загрузить firmware с bundled ROM terminal.
3. Подключить display фиксированного размера.
4. Выполнять ticks до появления shell prompt.
5. Напечатать короткую команду через events.
6. Отправить Enter.
7. Выполнять ticks до рендера shell output.
8. Drain display frames и записать metrics.

Scenario должен покрывать:

- Firmware status drawing.
- ROM terminal startup drawing.
- Input-line redraw.
- Shell output rendering.
- Frame emission в обычном display session flow.

## Ожидаемый вывод

Profiling test/helper должен выдавать компактный summary вроде:

```text
display: clear=..., setPixel=..., fillRect=..., fillArea=..., present=...
frames: count=..., fullRefresh=..., tiles=..., payloadBytes=...
timing: sliceNanos=..., hostCallNanos=..., displayFlushNanos=...
```

Automated tests должны проверять, что metrics собираются и внутренне консистентны, например:

- `presentCount >= frameCount`, где это применимо.
- `payloadBytes == sum(tile.payload.size)`.
- terminal workload генерирует ненулевые display operations и frame payload.
- нет behavior regressions в существующих terminal rendering tests.

Строгие performance budgets следует вводить только после получения baseline numbers и подтверждения их стабильности.

## Future decisions после этого pass

Собранные данные определят следующий optimization design. Вероятные кандидаты:

- ROM terminal text video memory с dirty cells или rows.
- Rendering glyph row runs вместо per-pixel `fillRect` calls.
- Display API additions вроде `blit`, `drawBitmap` или `copyRect` для graphics workloads.
- Coalescing нескольких `present()` calls в один server tick.
- Tuning tile size или dirty tracking.
- Снижение VM instruction overhead для display-heavy programs.

Этот pass намеренно останавливается до выбора конкретной оптимизации.

## Acceptance criteria

- Существует profiling-only display metrics path, выключенный по умолчанию.
- Bundled ROM terminal workload можно измерить в automated test или test helper.
- Metrics включают display operation counts, frame/tile/payload counts и diagnostic timing.
- Существующие runtime display/terminal behavior tests проходят.
- Full verification проходит через `./gradlew test`.
