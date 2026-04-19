# Дизайн alignment и weight для UI DSL

## Цель

Расширить последний screen-first DSL в worktree `screen-first-ui-program-merge`, чтобы автор мог описывать container-relative layout, а не только абсолютные смещения.

Первый поддерживаемый набор:

- `padding`
- `alignment`
- `weight`
- явные контейнеры `row` и `column`

Изменение должно оставаться достаточно маленьким, чтобы лечь в текущую compiled architecture `ScreenProgram` без второй runtime tree и без полноценного Compose-like measurement engine.

## Текущая проблема

Сейчас DSL поддерживает только:

- абсолютный `offset(x, y)`
- явный `size(width, height)`
- базовые modifiers для z-order и interaction
- `box`, `text`, `terminalSurface` и `if_`

Это упрощает compiler, но делает authoring неудобным для любого layout, который зависит от container-relative positioning. Центрирование контента, внутренние отступы и распределение остаточного пространства сейчас выразить напрямую нельзя.

## Scope

Этот дизайн добавляет маленькую container layout model, а не превращает проект в general-purpose retained UI framework.

Входит в scope:

- `padding` на контейнерах и leaf nodes
- `align` для container-relative child placement
- `weight` для axis-based space distribution
- контейнеры `row` и `column`
- compiler-side layout resolution перед render/hit/input lowering

Не входит в этот slice:

- spacing/gap между детьми
- min/max constraints
- intrinsic measurement
- wrap content по реальной ширине текста
- baseline alignment
- percentages и flex-grow/shrink variants

## Authoring model

### Новые контейнеры

Authoring layer должна поддерживать три structural container-а:

- `box { ... }`
- `row { ... }`
- `column { ... }`

Смысл:

- `box` — overlay container с одной общей content area для всех детей
- `row` — horizontal axis container
- `column` — vertical axis container

### Новые modifiers

`UiModifier` должен получить:

- `padding(all: Int)`
- `padding(horizontal: Int, vertical: Int)`
- `padding(left: Int, top: Int, right: Int, bottom: Int)`
- `align(value: UiAlignment)`
- `weight(value: Float)`

Modifier остаётся immutable и chainable, как и текущий API.

### Модель alignment

На первом шаге использовать один минимальный enum:

- `UiAlignment.Start`
- `UiAlignment.Center`
- `UiAlignment.End`
- `UiAlignment.Stretch`

Это сохраняет API маленьким. Если позже понадобятся axis-specific types, их можно split-нуть отдельно.

### Модель padding

Каждый container вычисляет:

`contentBounds = ownBounds - padding`

Дети layout-ятся только внутри `contentBounds`.

Значит padding — это layout concern, а не render-only decoration.

## Семантика по контейнерам

### Box

`box` остаётся самым простым контейнером.

Правила:

- все дети используют одну и ту же padded content area
- `align` позиционирует ребёнка внутри этой области
- `offset` применяется после aligned placement как локальная корректировка
- `weight` разрешён в API, но игнорируется при `box` layout

Обоснование:

Пользователь явно хочет иметь alignment и weight рядом с plain `box`, но у `weight` нет чистой семантики распределения в overlay container-е. Игнорировать его в `box` предсказуемее, чем придумывать неявные эвристики.

### Row

Правила:

- fixed-size children сначала занимают ширину
- оставшаяся ширина делится между weighted children пропорционально
- `align` управляет cross-axis placement по вертикали
- `Stretch` растягивает ребёнка по cross-axis высоте row
- `offset` применяется после row placement

### Column

Правила:

- fixed-size children сначала занимают высоту
- оставшаяся высота делится между weighted children пропорционально
- `align` управляет cross-axis placement по горизонтали
- `Stretch` растягивает ребёнка по cross-axis ширине column
- `offset` применяется после column placement

## Семантика weight

`weight` участвует в layout только внутри `row` и `column`.

Правила:

- `weight(value <= 0)` недопустим и должен падать через `require`
- weighted children делят только оставшееся место по primary axis после размещения fixed children
- в первом slice weighted child всегда получает весь выделенный ему primary-axis span
- любой non-fill вариант явно откладывается на следующий шаг

Это сохраняет семантику строгой и избегает частично пустого API.

## Архитектура compiler-а

Текущий compiler напрямую lower-ит authoring nodes в абсолютные `LayoutNode`, render ops, hit regions и input routes.

Как только геометрия ребёнка начинает зависеть от container semantics, этого уже недостаточно.

### Нужное изменение

Нужно ввести dedicated layout resolution pass до существующих lowering stages.

Предлагаемый shape:

- `UiLayoutResolver`
- input: authoring tree + root bounds
- output: resolved bounds per node id

После этого `ScreenProgramCompiler` становится двухфазным compiler-ом:

1. сначала вычисляет финальные bounds для всех nodes
2. потом lower-ит их в:
   - `LayoutProgram`
   - `RenderProgram`
   - `HitTestProgram`
   - `InputProgram`
   - `FocusProgram`

### Почему это правильная граница

- runtime executor не нужно учить правилам row/column
- hit-testing автоматически становится консистентным с rendered geometry
- render и input lowering используют один и тот же compiled layout data
- layout complexity изолируется в одном pure component

## Data flow

Новый flow должен быть таким:

`Authoring DSL -> UiLayoutResolver -> resolved bounds -> ScreenProgramCompiler lowering -> ScreenProgram -> ScreenRuntimeExecutor`

Более конкретно:

1. DSL строит authoring tree с `box`, `row`, `column` и modifiers.
2. `UiLayoutResolver` обходит tree и вычисляет финальные bounds для каждого node.
3. Compiler использует эти bounds для `LayoutProgram`, `RenderProgram`, `HitTestProgram` и `FocusProgram`.
4. Runtime executor потребляет только compiled geometry, а не layout rules.

## Error handling

В первой реализации лучше выбрать строгие ошибки вместо тихой магии.

Через `require` должны падать:

- отрицательные padding values
- `weight <= 0`
- `weight` на child, чей parent не умеет легально распределять axis space, если мы выберем validate вместо ignore

Рекомендованное поведение:

- invalid padding: fail
- invalid weight value: fail
- weight в `box`: ignore, без fail

Такая комбинация сохраняет желаемую форму API, но не вносит шумные runtime errors в overlay layouts.

## Стратегия тестирования

Добавить compiler-level tests для:

- `box` центрирует child внутри padded content bounds
- `box` игнорирует `weight`
- `row` распределяет оставшуюся ширину между weighted children
- `column` распределяет оставшуюся высоту между weighted children
- padding контейнера уменьшает distributable space
- aligned child bounds правильно попадают в hit regions

Добавить executor regression coverage для:

- hit-test по weighted child после resolved layout

После этого прогнать focused common compile check, чтобы существующий terminal screen по-прежнему собирался с расширенным DSL.

## Compatibility и migration

Существующий absolute-offset code должен остаться валидным.

Модель migration — additive:

- текущий `box`, `text` и `terminalSurface` продолжают работать
- `row`, `column`, `padding`, `align` и `weight` становятся доступны для новых layouts
- текущий terminal screen не обязан сразу мигрировать на новые layout primitives

## Критерии успеха

Slice считается успешным, когда:

- автор может центрировать контент внутри padded `box`
- автор может распределять оставшееся пространство через `weight` в `row` и `column`
- compiler по-прежнему выдаёт один консистентный набор bounds для render и hit-testing
- текущая compiled-screen architecture не ломается
- public API остаётся маленьким и понятным