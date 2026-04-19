# Дизайн разделения color API для UI DSL

## Цель

Разделить цвет текста и цвет фона контейнера в screen-first UI DSL без поломки текущего worktree.

Этот slice должен сделать новый код явным и читаемым, сохранив работоспособность существующих вызовов `Modifier.color(...)` на время миграции.

## Текущая проблема

Сейчас DSL использует один `Modifier.color(...)` сразу для двух разных смыслов:

- foreground color текста
- fill color для `box` и `button`

Эта двусмысленность уже привела к реальной ошибке: цветной `box` компилировался иначе, чем цветной текст, и compiler был вынужден угадывать намерение по типу элемента.

## Scope

Входит в scope:

- отдельные modifier channels для цвета текста и фона
- явные DSL methods для обоих каналов
- compiler fallback для legacy `color(...)`
- focused regression tests для нового и legacy поведения

Не входит в scope:

- цвет рамки или stroke
- theme system или palette abstraction
- правила alpha blending сверх текущего поведения `Color` enum
- удаление legacy API `color(...)` в этом slice

## Public API

`UiModifier` должен предоставить:

- `textColor(value: Color)`
- `backgroundColor(value: Color)`
- существующий `color(value: Color)` оставить как legacy alias

Семантические правила:

- `textColor(...)` означает только foreground color текста
- `backgroundColor(...)` означает только fill color контейнера
- `color(...)` остаётся поддержанным во время миграции и используется как legacy fallback в зависимости от элемента

## Семантика элементов

### Text

`UiElement.Text` должен резолвить цвет в таком порядке:

1. `textColor`
2. legacy `color`
3. `Color.Transparent`

### Box и Button

`UiElement.Box` должен резолвить fill color в таком порядке:

1. `backgroundColor`
2. legacy `color`
3. no fill, если только элемент не button и существующий button styling не требует default transparent fill op

Так как `button` сейчас реализован как sugar над `box`, он автоматически наследует ту же семантику background color.

## Поведение compiler-а

Разделение остаётся локальным для compiler слоя.

Нужные изменения:

- `RenderOp.DrawText` должен получать только resolved text color
- `RenderOp.FillRect` должен получать только resolved background color
- compiler должен перестать трактовать одно modifier поле как универсальный источник цвета сразу для текста и fill

Правило совместимости:

- старый DSL code с `color(...)` должен продолжать корректно рендериться и для текста, и для box в рамках этого migration step

## Стратегия тестирования

Добавить focused tests для:

- `backgroundColor(...)` на `box` эмитит `FillRect` нужного цвета
- `textColor(...)` на `text` эмитит `DrawText` нужного цвета
- legacy `color(...)` на `box` всё ещё эмитит `FillRect`
- legacy `color(...)` на `text` всё ещё эмитит `DrawText`

Существующие compiler и runtime regression tests должны остаться зелёными.

## План миграции

Это additive step.

- существующий код продолжает работать
- новый код должен предпочитать `textColor(...)` и `backgroundColor(...)`
- отдельный cleanup slice позже сможет убрать legacy fallback `color(...)`, когда код worktree будет мигрирован

## Критерии успеха

Slice считается успешным, когда:

- новый DSL code может задавать цвет текста и fill color box без двусмысленности
- существующий код с `color(...)` продолжает работать
- compiler tests доказывают, что текст и fill lowering идут через разные semantic paths
- backend и runtime executor не требуют изменений