# Tick-Driven Scheduler Cleanup Design

## Goal

Уточнить архитектуру исполнения компьютера так, чтобы VM оставалась частью игровой симуляции и намеренно тормозила вместе с сервером, но внутренний scheduler был явным, предсказуемым и пригодным для дальнейшего развития ограничений ресурсов и диагностики.

## Problem Statement

Предыдущая идея полной отвязки VM от Minecraft ticks решала не ту задачу.

Реальная продуктовая цель состоит не в том, чтобы компьютер был максимально realtime-независимым, а в том, чтобы его поведение было интуитивно связано с миром:

- при падении TPS компьютер тоже должен замедляться;
- `sleep(ticks)` должен оставаться сном в тиках мира;
- CPU fairness должна измеряться в simulation steps, а не в wall-clock времени;
- при этом текущая реализация не должна оставаться неявной и спутанной.

Сейчас scheduler держится на комбинации:

- `requestSlice(serverTick)`;
- `slicePermits.receive()`;
- `sleepUntilTick`;
- `sliceDeadlineNanos`;
- косвенных проверок очередей и состояний.

Из-за этого архитектура труднее читается и хуже подходит для последующих изменений, хотя сама tick-bound семантика для продукта выглядит правильной.

## Design Principles

1. VM является частью симуляции мира, а не отдельным realtime-процессом.
2. Observable progress пользовательской программы определяется server ticks.
3. `wallTimeGuardNanosPerSlice` остается safety guard и не становится главным определением CPU.
4. `sleep(ticks)` остается tick-based API без подмены на wall-clock semantics.
5. Внутренняя реализация может быть упрощена и нормализована, но без разрешения VM жить быстрее мира.

## Scope

### In Scope

- Формализация tick-driven scheduler semantics.
- Явное представление причин, по которым VM сейчас не исполняется.
- Упрощение связей между `BackgroundComputerVm` и `VmStateManager`.
- Сохранение CPU budget как budget на tick/simulation step.
- Сохранение tick-based semantics для `sleep()`.
- Улучшение тестируемости и диагностируемости scheduler behavior.

### Out of Scope

- Полная отвязка VM от тиков.
- Переход `sleep()` на wall-clock semantics.
- Разрешение пользовательской программе продолжать observable execution между тиками мира.
- Большой coordinator/actor refactor, выходящий за рамки scheduler cleanup.

## Recommended Architecture

### 1. Tick-Bound Execution Remains the Source of Truth

Продвижение байткодной программы остается привязанным к server tick. Это означает:

- instruction budget выдается как budget на tick;
- если сервер тормозит, VM делает меньше работы в секунду;
- `yield()` и budget pauses возвращают управление scheduler в пределах simulation model;
- `sleep(ticks)` пробуждается только по достижении соответствующего world tick.

### 2. Scheduler Reasons Become Explicit

Вместо неявного смешения нескольких условий ожидания система должна различать причины остановки исполнения. Минимальный набор причин:

- sleeping until tick;
- waiting for event;
- waiting for host result;
- paused until next tick because CPU budget is exhausted.

Эти причины должны быть явно представлены в scheduler state, а не реконструироваться по нескольким полям и косвенным проверкам.

### 3. Tick-Driven Pacing, Cleaner Internal Mechanics

Даже если внутренняя механика очередей, snapshots или bookkeeping будет улучшена, это не должно менять observable semantics:

- появление host result между тиками не должно автоматически давать дополнительное пользовательское исполнение вне simulation step;
- появление event не должно превращать VM в realtime-driven loop;
- внутренние пробуждения могут подготавливать состояние, но не должны обходить tick pacing.

### 4. State Model Should Explain Why the VM Is Not Running

`VmStateManager` и `BackgroundComputerVm` должны вместе давать понятный ответ на вопрос: почему VM не исполняется прямо сейчас?

Этот ответ должен быть пригоден и для runtime-проверок, и для тестов, и для будущих диагностик. Текущая модель частично отражает lifecycle state, но не дает достаточно явного scheduler-level explanation.

## Component Impact

### BackgroundComputerVm

Основная точка рефакторинга.

Ожидаемые изменения:

- убрать размытость между “permit получен”, “можно исполняться”, и “появилась причина проснуться”;
- сделать влияние `requestSlice(serverTick)` на scheduler state явным;
- сохранить tick-driven entry point как внешний trigger simulation progress;
- отделить scheduler bookkeeping от очередей host calls и events.

### VmStateManager

Должен стать носителем более явной scheduler model.

Ожидаемые изменения:

- формализация scheduler-relevant state;
- сохранение текущих lifecycle transitions;
- более явная связь между current tick, sleep deadline и scheduler pause reasons.

### VmRuntime / VmSystemApi

Публичная семантика не меняется:

- `sleep(ticks)` остается tick-based;
- `currentTick` остается главным источником игрового времени для программ.

Возможны внутренние упрощения, но не смена контракта.

### Host Calls and Events

Они остаются подчинены tick-driven execution semantics.

Это означает:

- очереди и результаты могут храниться и диагностироваться лучше;
- но готовность host result или event сама по себе не обязана означать дополнительный execution slice вне server tick.

## Error Handling and Diagnostics

Дизайн должен подготовить почву для более явной диагностики scheduler behavior:

- почему VM находится в `Sleeping`, `WaitingEvent` или ином не-running состоянии;
- когда budget на текущем tick исчерпан;
- сколько событий и host calls ожидают обработки.

На этом этапе не требуется вводить полноценную новую публичную diagnostics API, но новая модель не должна скрывать причину остановки исполнения.

## Testing Strategy

Нужны тесты трех типов:

1. Scheduler semantics:
- VM не исполняется сверх budget одного tick;
- новый tick возобновляет исполнение;
- `sleep(ticks)` просыпается только на нужном tick.

2. Wait-state behavior:
- ожидание event корректно сохраняет paused state;
- ожидание host result не дает дополнительного пользовательского progress между тиками;
- переходы между scheduler reasons корректны.

3. Regression coverage:
- существующая семантика `yield()` не ломается;
- CPU wall-time guard остается safety guard;
- resource limits из предыдущих волн продолжают работать как раньше.

## Rejected Alternative

### Full VM Decoupling From Ticks

Отвергнуто, потому что:

- ломает связь компьютера с simulation pacing мира;
- делает поведение менее интуитивным при низком TPS;
- смещает CPU fairness из game semantics в realtime semantics;
- усложняет архитектуру сильнее, чем требуется текущей продуктовой цели.

## Success Criteria

- Архитектура явно выражает tick-driven nature исполнения.
- Scheduler state объясняет, почему VM сейчас не выполняется.
- Пользовательские программы замедляются вместе с сервером.
- `sleep(ticks)` и CPU budget остаются связанными с game time.
- Код становится проще расширять для будущих scheduler diagnostics и resource policies.