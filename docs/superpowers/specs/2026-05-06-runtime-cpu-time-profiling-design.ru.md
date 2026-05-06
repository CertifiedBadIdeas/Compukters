# Дизайн профилирования CPU-time runtime

## Контекст

Предыдущий этап profiling добавил стабильные счётчики для display operations и frame deltas. Первый baseline для bundled ROM terminal workload показал большую активность display calls и payload, но текущая timing-строка всё ещё включает искусственный `delay(10)` из test loop. Поэтому эта строка полезна как smoke signal, но не как baseline server CPU.

Следующий шаг — измерять runtime phases ближе к CPU-time, при этом сохраняя работу profiling-only. Цель — разделить costs server tick, host calls, display drain/flush и VM coroutine scheduling/execution diagnostics до выбора оптимизации.

## Цели

- Добавить optional no-op-by-default runtime timing metrics для host/server tick phases.
- Добавить optional no-op-by-default VM-side scheduling и execution diagnostics.
- Объединить runtime timing metrics с существующими display metrics в reproducible bundled ROM terminal workload.
- Не превращать wall-clock duration в строгий CI performance budget.
- Получить baseline numbers, которые помогут выбрать следующий optimization pass.

## Не цели

- Не оптимизировать ROM terminal в этом pass.
- Не менять CKL display APIs.
- Не добавлять `drawText`, `drawBitmap`, `blit`, `copyRect` или text video memory.
- Не возвращать VM `terminal` или `stdout` built-ins.
- Не добавлять постоянный production logging или user-facing profiler UI.
- Не заставлять tests падать из-за того, что одна машина медленнее другой.

## Модель профилирования

### Runtime metrics collector

Добавляем новый collector в `core`, отдельно от `DisplayMetricsCollector`, для runtime timing и scheduling diagnostics. Collector должен быть no-op по умолчанию и включаться явно tests или local diagnostics.

Модель включает:

- `RuntimeMetricsCollector` interface.
- `NoOpRuntimeMetricsCollector` default implementation.
- `RecordingRuntimeMetricsCollector` thread-safe implementation.
- `RuntimeProfilingSnapshot` data class.
- `summary()` text output для local analysis.

Recording collector должен использовать atomic counters, потому что server tick code и VM coroutine code могут писать в один collector.

### Host/server tick phases

Измеряем monotonic elapsed time вокруг phases, которые выполняются в server-side runtime tick path:

- Total `serverTick` duration.
- `requestSlice` duration.
- Host call drain duration и drained call count.
- Host call dispatch duration и dispatched call count.
- Host result delivery duration и delivered result count.
- Display frame drain duration и drained frame count.
- Display session flush duration, включая network-send loop work, видимую server-side code.

Эти measurements должны быть рядом с `RuntimeDeviceImpl`, потому что именно там real server tick path последовательно выполняет VM slice requests, host calls и display flushing.

### VM-side diagnostics

Измеряем coarse VM coroutine diagnostics в `BackgroundDeviceVm`:

- Slice permit request count.
- Slice permit sent count.
- Sleep-gated slice request count.
- Slice permit receive count.
- Scheduling point count.
- Yield scheduling point count.
- Wait-for-next-slice scheduling point count.
- Approximate VM execution window nanoseconds между receipt slice permit и следующим wait for permit или terminal stop.

Эти diagnostics должны показать, где находится время: host/server orchestration, VM execution или display/frame flushing. Они намеренно coarse и не требуют invasive interpreter instrumentation.

## Data flow

Profiling test создаёт оба collector:

- `RecordingDisplayMetricsCollector` для display operation/frame metrics.
- `RecordingRuntimeMetricsCollector` для runtime/tick/VM timing metrics.

VM получает оба collector через construction. Runtime device или test harness записывает host/server phases, пока выполняет тот же bundled firmware и ROM terminal workload, который используется в display profiling test.

Итоговый output должен включать обе summary:

```text
display: clear=..., setPixel=..., fillRect=..., fillArea=..., present=..., presentFrames=...
frames: count=..., fullRefresh=..., tiles=..., payloadBytes=...
runtime: serverTicks=..., serverTickNanos=..., requestSliceNanos=...
host: drainedCalls=..., dispatchedCalls=..., drainNanos=..., dispatchNanos=..., deliverNanos=...
display-runtime: drainFrames=..., drainNanos=..., flushNanos=...
vm: sliceRequests=..., slicePermits=..., sleepGated=..., schedulingPoints=..., executionNanos=...
```

## Testing strategy

Используем TDD для реализации:

1. Добавить failing unit test для `RecordingRuntimeMetricsCollector` counters и snapshot consistency.
2. Реализовать runtime profiling model.
3. Добавить failing test, который проверяет, что `BackgroundDeviceVm` записывает VM-side metrics при request/consume slices.
4. Подключить VM collector.
5. Добавить или расширить bundled ROM terminal profiling integration test, который печатает display и runtime summaries и проверяет non-zero stable counters.
6. Запустить full verification через `./gradlew test`.

Tests должны проверять стабильные факты:

- No-op collector snapshots остаются empty после record calls.
- Recording collector точно накапливает counts и durations в unit tests.
- Bundled workload создаёт non-zero display и runtime counters.
- Frame и operation metrics остаются internally consistent.
- Existing terminal behavior tests продолжают проходить.

Tests не должны проверять строгие performance thresholds вроде maximum milliseconds per tick.

## Acceptance criteria

- Runtime CPU-time profiling hooks существуют и выключены по умолчанию.
- Host/server tick phases измеряются достаточно независимо, чтобы различать request, host-call, display-drain, display-flush и total tick costs.
- VM-side scheduling и approximate execution diagnostics доступны в том же profiling run.
- Bundled ROM terminal workload может печатать combined display + runtime profiling summary.
- Текущий timing baseline больше не представляет artificial `delay(10)` time как CPU cost.
- Full `./gradlew test` проходит.
