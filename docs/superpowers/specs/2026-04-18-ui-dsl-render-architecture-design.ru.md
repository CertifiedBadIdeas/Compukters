# Дизайн архитектуры UI DSL для рендера

## Цель

Спроектировать новую декларативную UI-систему для игровых экранов, которая поддерживает относительный layout, компилирует статическую структуру один раз, позволяет узкие динамические bindings и исполняется как нативный render plan со стоимостью рантайма, близкой к вручную написанному imperative rendering.

Для автора система должна ощущаться декларативной, а для рантайма — скомпилированной.

## Область изменений

Этот дизайн покрывает:

- новый authoring DSL для UI
- компиляцию layout
- компиляцию render-плана
- модель invalidation и исполнения в рантайме
- escape hatch для кастомного нативного рендера
- стратегию миграции существующих экранов
- практические guidelines по написанию UI на новом DSL

Этот дизайн не покрывает:

- general-purpose reactive runtime в стиле Jetpack Compose
- общий constraint solver
- полную архитектуру input и focus в первой итерации
- compiler plugin, code generation или KSP-based AOT вне обычной runtime-инициализации

## Текущая проблема

У текущего рендеринга есть две отдельные проблемы.

Во-первых, большая часть UI до сих пор пишется вручную прямо в screen-классах через `graphics.fill(...)`, `graphics.drawString(...)` и ad hoc helper methods. Это делает relative layout, reuse и структурное понимание сложнее, чем нужно.

Во-вторых, текущий DSL-слой по сути не является настоящим UI DSL. Это маленький pure builder, который возвращает `List<UiNode>` для terminal rendering. Такая модель полезна как разделение ответственности, но она слишком узкая и слишком близка к generic scene-node representation.

Новая система должна решить обе проблемы и при этом не заменить их более медленной абстракцией.

## Цели дизайна

- декларативный authoring API для экранов и их поддеревьев
- relative layout primitives вместо в основном ручной абсолютной геометрии
- compile-once статическая структура при инициализации экрана
- узкий runtime invalidation вместо полной пересборки дерева
- first-class native render primitives для специальных поверхностей, таких как terminal
- custom render escape hatch для случаев, которые DSL не должен выражать напрямую
- runtime-поведение, близкое к handwritten imperative rendering
- путь миграции, который начинается с маленькой поверхности и не требует big-bang rewrite

## Не входит в цели

- node diffing
- recomposition по умолчанию
- неявное отслеживание зависимостей через произвольные state reads внутри DSL-блоков
- generic retained scene graph как основная runtime-модель
- layout engine сложности browser flexbox или general AutoLayout systems

## Архитектура верхнего уровня

Система представляет собой pipeline компиляции:

`DSL -> Layout IR -> Render IR -> Native Render Ops`

### DSL Layer

DSL — это author-facing слой. Он должен быть выразительным, читаемым и структурным, но не является runtime representation.

Его ответственность:

- объявлять layout containers и leaf primitives
- выражать relative sizing и alignment
- объявлять явные dynamic bindings
- предоставлять first-class special primitives, такие как terminal surfaces
- разрешать узкие custom render blocks там, где DSL должен останавливаться

### Layout IR

Layout IR — первая скомпилированная форма.

Он отвечает только за семантику геометрии и видимости:

- parent-child layout relationships
- вычисление bounds
- относительные размеры
- padding и alignment
- clip regions, связанные с layout
- visibility gates
- классификацию static и dynamic layout dependencies

Layout IR не должен содержать generic render logic.

### Render IR

Render IR — вторая скомпилированная форма.

Это низкоуровневая render program, построенная из специализированных примитивов:

- fill rectangle
- stroke rectangle
- draw text
- draw glyph run
- draw terminal surface
- push clip / pop clip
- push transform / pop transform
- custom native render op

Render IR не должен сохранять форму исходного authoring tree, если это не требуется для корректности.

### Runtime Execution

Во время кадра рантайм должен делать только три вида работы:

- обновлять dynamic binding slots
- пересчитывать только invalidated dynamic layout fragments
- исполнять скомпилированный render plan

Он не должен пересобирать DSL tree и не должен каждый кадр обходить generic node graph.

## Модель компиляции

Каждый экран один раз компилируется в `ScreenProgram`.

`ScreenProgram` содержит четыре концептуальных сегмента:

- `staticLayout`
- `dynamicLayoutFragments`
- `staticRenderOps`
- `dynamicRenderFragments`

### Static Layout

Static layout содержит все bounds и связи, которые можно вычислить в момент создания программы из известных screen bounds и parent-relative expressions.

Примеры:

- фиксированные padding и insets
- percentage-based regions, зависящие только от parent
- фиксированные rows и columns
- заранее вычисленные clip rectangles

### Dynamic Layout Fragments

Dynamic layout изолируется в fragments, чья геометрия зависит от runtime state.

Примеры:

- панель, меняющая высоту при раскрытии
- секции с управляемой видимостью
- bounds динамического viewport
- области, зависящие от активной вкладки

Компилятор должен строить dependency graph так, чтобы invalidation затрагивал только нужные layout fragments.

### Static Render Ops

Static render ops составляют большую часть render plan и по возможности должны храниться в contiguous specialized buffers.

У этих ops уже должны быть разрешены:

- primitive kind
- ссылки на static geometry
- colors и resource handles
- clip и transform scopes
- static text shaping, где применимо

### Dynamic Render Fragments

Dynamic render fragments должны быть маленькими и типизированными.

Они должны ссылаться на binding slots, а не на произвольные closures.

Примеры:

- text content slot
- color slot
- visibility slot
- terminal snapshot slot
- selected-state slot

## Стратегия борьбы с overhead

Архитектура выбрана специально так, чтобы не скатиться в модель "сначала сделаем абстракции, потом будем их спасать оптимизациями".

Этот дизайн принимает чуть более богатый compiler pipeline в обмен на более низкий системный overhead в рантайме.

По сравнению с прямым render-program DSL у этого подхода немного выше теоретический минимум стоимости, но он заметно лучше удерживает производительность по мере роста системы, потому что оптимизация встроена в саму модель:

- compile once
- flatten once
- bind narrowly
- invalidate selectively
- execute contiguous specialized ops

Этот компромисс предпочтительнее, чем низкоуровневый scripting-style DSL, который обычно распадается на локальные micro-optimizations и ad hoc exceptions.

## Layout Model

Layout-система должна оставаться маленькой и предсказуемой.

### Bounds Space

Каждый layout node вычисляется внутри bounds своего parent.

Поддерживаемые типы измерений должны быть ограничены:

- absolute pixels
- percentage of parent
- fill remaining space
- weighted share
- intrinsic min-content и max-content там, где это поддерживает leaf primitive
- min и max constraints

### Базовые контейнеры

В первой версии должны поддерживаться только такие контейнеры:

- `box`
- `row`
- `column`
- `stack`
- `dock`

Этого достаточно для terminal screens, workbench shell, overlays, toolbars и split regions без введения большого layout language.

### Базовые modifiers

Поддерживаемые modifiers должны быть ограничены:

- padding
- margin
- alignment
- grow / shrink или эквивалентная weight-semantics
- min / max size
- visibility
- clip

Эти modifiers должны компилироваться в layout metadata, а не жить как богатые runtime objects.

### Relative Units

DSL должен предоставлять явные относительные единицы:

- `px(...)`
- `percent(...)`
- `fill()`
- `weight(...)`
- `minContent()`
- `maxContent()`

Также он должен поддерживать anchor-style placement там, где это нужно, через edge insets и centering, но не через fully general constraint system.

### Классификация static и dynamic

Каждое layout expression должно классифицироваться во время компиляции как одно из:

- static
- parent-relative static
- runtime-dynamic

Эта классификация обязательна, потому что она определяет, можно ли полностью pre-resolve expression или нужно сохранить runtime slot.

### Без общего constraint solver

Система должна явно избегать общего solver.

Целевая область — game UI, а не arbitrary document layout. Маленькая algebra of bounds даёт лучшую предсказуемость, более простую оптимизацию и лучший контроль над runtime cost.

## Render IR

Render IR — это flatten'ed или segmentable render program, собранная из специализированных примитивов.

### Набор примитивов

Начальный набор примитивов должен включать:

- `FillRectOp`
- `StrokeRectOp`
- `DrawTextOp`
- `DrawGlyphRunOp`
- `DrawTerminalSurfaceOp`
- `PushClipOp`
- `PopClipOp`
- `PushTransformOp`
- `PopTransformOp`
- `CustomRenderOp`

### Static Fusion

Компилятор должен иметь право:

- склеивать соседние совместимые ops
- удалять недостижимые или постоянно скрытые ops
- заранее разрешать resource references
- заранее shape'ить static text
- сворачивать tree-shaped authoring structure в linear execution buffers

### Typed Dynamic Slots

Dynamic data должны разрешаться через typed slots, а не через произвольные render closures.

Примеры:

- `TextSlot`
- `ColorSlot`
- `BooleanSlot`
- `SnapshotSlot`
- `GeometrySlotRef`

Цель — сделать frame-time dispatch маленьким и предсказуемым.

## Custom Render Escape Hatch

Custom render hook нужен, но он должен быть узким.

Его контракт должен быть таким:

- layout уже вычислен
- final bounds уже предоставлены
- clip state уже вычислен
- render context уже предоставлен
- нужные op bound-values уже разрешены
- custom op не получает доступ к исходному DSL tree

Это оставляет escape hatch достаточно мощным для special rendering, но слишком узким, чтобы он превратился во второй UI framework внутри первого.

### Layout-контракт для custom nodes

Любой custom-render leaf должен объявлять достаточно metadata для layout compilation:

- preferred size
- min / max size
- поддерживает ли intrinsic measurement
- может ли runtime state менять его геометрию

Это не позволит custom render nodes ломать layout compiler.

## Terminal как first-class primitive

Terminal surface не должен раскладываться на generic text и rectangle nodes.

Вместо этого DSL должен предоставлять terminal primitive, который компилируется напрямую в dedicated render op с такими вещами, как:

- ссылка на terminal bounds
- ссылка на terminal metrics
- snapshot slot
- palette reference
- clip reference
- cursor mode или status flags

Это первый proof point новой архитектуры, потому что terminal rendering — именно тот special surface, который должен стать дешевле, а не абстрактнее.

## Guidelines для авторов UI DSL

Системе нужны явные guidelines, чтобы авторы случайно не разрушали архитектуру.

### Сначала предпочитай декларативный layout

Для обычной структуры UI используй containers, relative units и built-in primitives.

Не уходи в custom render только для того, чтобы не писать маленькое layout-expression.

### Держи bindings узкими

Bindings должны отдавать уже разрешённые значения, а не произвольные domain objects, если только special primitive действительно не требует более богатое значение.

Предпочтительно:

- `binding(::titleText)`
- `binding(::statusColor)`
- `binding(::isTerminalVisible)`

Нежелательно:

- binding всего screen state в generic closure для одного node

### Воспринимай custom render как leaf

Custom render blocks должны использоваться для rendering behavior, а не для скрытых layout systems.

Если block хочет вычислять layout целого subtree самостоятельно, это обычно означает, что DSL не хватает примитива и его нужно расширять.

### Делай специальные поверхности first-class

Если UI-feature постоянно появляется как сложный custom render block, её нужно продвинуть в dedicated DSL primitive и Render IR op.

Канонический пример — terminal surface.

### Избегай неявных state reads

DSL не должен позволять скрытый доступ к состоянию во время рендера.

Всё динамическое поведение должно быть видно через explicit bindings или через custom render op contracts.

### Проектируй под fragment invalidation

Авторы должны структурировать UI так, чтобы локальные изменения состояния инвалидировали локальные fragments.

Большие catch-all bindings нежелательны, потому что они уничтожают выгоду selective invalidation.

## Пример формы authoring API

Точный синтаксис ещё может измениться, но целевая форма примерно такая:

```kotlin
ui {
    dock {
        top(height = px(24)) {
            row {
                text(binding(::titleText))
                spacer(fill())
                text(binding(::statusText))
            }
        }

        center {
            terminal(
                snapshot = binding(::screenSnapshot),
                focused = binding(::terminalFocused),
            )
        }

        bottom(
            height = percent(0.2f),
            visible = binding(::showDiagnostics),
        ) {
            customRender(id = "diagnostics") { bounds, context ->
                diagnosticsRenderer.render(bounds, context)
            }
        }
    }
}
```

Этот пример иллюстративный, а не нормативный. Ключевое требование — структурная ясность и явные dynamic inputs.

## Стратегия миграции

Первой production-миграцией должен стать только terminal renderer.

Это даёт проекту маленький и полезный proving ground:

- чётко определённый визуальный результат
- уже существующее specialized rendering behavior
- простое сравнение new и old output
- хороший сигнал о том, работает ли подход с first-class primitive

### Фазы миграции

1. Построить core compiler model: DSL surface, Layout IR, Render IR, `ScreenProgram`, slot model.
2. Добавить terminal primitive и компилировать его напрямую в dedicated render op.
3. Перевести terminal screen на новую систему.
4. Добавить generic containers и primitive leaf ops, нужные для более крупных экранов.
5. Перевести workbench shell и его non-terminal chrome.
6. Перевести advanced overlays и editor-specific renderers.
7. Удалить старый `UiNode`-based DSL и handwritten rendering islands, которые новая модель заменит.

## Критерии успеха

Новая UI-архитектура считается успешной, когда:

- static UI structure компилируется один раз на `ScreenProgram`
- dynamic updates не пересобирают authoring tree
- runtime invalidation остаётся fragment-local там, где локально меняется состояние
- terminal rendering становится first-class primitive, а не generic composition of scene nodes
- relative layout может выразить workbench и terminal surfaces без в основном ручной геометрии
- custom render blocks остаются редкими и leaf-like
- frame-time execution cost остаётся близкой к handwritten imperative rendering

## Риски

- DSL может стать слишком выразительным и вернуть общие runtime-семантики обратно в frame path.
- Escape hatch может начать использоваться слишком часто, если набор примитивов окажется слабым.
- Intrinsic sizing может стать скрытым источником сложности, если применять его слишком широко.
- Первый draft синтаксиса может выглядеть красиво, но плохо работать с compile-time classification.

Дизайн нужно оценивать прежде всего по его compiled representation и invalidation behavior, а не по тому, насколько внешне DSL похож на Compose.

## Итоговое решение

Проект должен принять новую UI-систему, основанную на:

- декларативном authoring
- компиляции `Layout IR -> Render IR`
- compile-once screen programs
- typed dynamic slots
- first-class native render primitives
- узких custom render escape hatches
- миграции, начинающейся с terminal renderer

Такой подход даёт проекту декларативный UI language без принятия тяжёлого generic UI runtime.