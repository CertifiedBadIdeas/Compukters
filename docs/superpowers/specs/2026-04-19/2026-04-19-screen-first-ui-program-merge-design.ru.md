# Дизайн слияния screen-first UI DSL и compiled ScreenProgram

## Цель

Зафиксировать merged architecture, которая соединяет два уже исследованных направления:

- screen-first, Compose-like authoring DSL из дизайна от 19 апреля;
- staged IR и backend execution model из ветки и дизайна UI DSL render architecture от 18 апреля.

Результат должен сохранить новый authoring API, но вернуть compile-once, typed IR и backend-driven execution shape, которая нужна для предсказуемой производительности.

## Почему вообще нужно слияние

Оба существующих направления решили только половину задачи.

Направление screen-first DSL решило проблему authoring:

- один UI DSL вместо split render/input code;
- authoring semantics, которые ощущаются ближе к Compose;
- interaction concepts вроде click, focus и keyboard routing, выраженные на уровне DSL.

Но текущий prototype всё ещё держит слишком много работы в live runtime shape:

- rebuild UI tree каждый кадр;
- rebuild frame data каждый кадр;
- вычисление hit regions прямо из живого дерева;
- производительность всё ещё зависит от immediate runtime evaluation.

Направление UI render architecture решило проблему execution shape:

- typed layout IR;
- typed render IR;
- compile-once `ScreenProgram`;
- backend-based execution и selective invalidation.

Но оно недоопределило authoring surface:

- динамика на author-facing уровне завязана на bindings;
- surface получилась render-first;
- input и focus были отложены вместо того, чтобы быть first-class частью архитектуры.

Merged design сохраняет правильную половину от каждого направления:

- authoring DSL из screen-first направления;
- compiled staged execution из IR/backend направления.

## Связь с предыдущими дизайнами

Этот документ не выкидывает предыдущие дизайны, а приводит их к совместимой форме.

### Что сохраняется из дизайна render architecture от 18 апреля

- `ScreenProgram` как compiled artifact;
- разделение layout и render concerns;
- static versus dynamic classification;
- selective invalidation;
- narrow typed backends;
- typed specialized ops вместо generic retained scene graph.

### Что заменяется из дизайна render architecture от 18 апреля

- bindings как основная author-facing dynamic model;
- render-first DSL surface;
- откладывание input и focus на потом.

### Что сохраняется из дизайна screen-first DSL от 19 апреля

- один authoring DSL для layout, render intent и interaction intent;
- screen-first ergonomics;
- lambda-based expressions и handlers на authoring уровне;
- explicit structural nodes вроде `if_`, `when_` и `forEach`.

### Что заменяется из prototype screen-first DSL от 19 апреля

- rebuild live UI tree каждый кадр;
- rebuild interaction map с нуля каждый кадр;
- использование live runtime tree как главной execution shape;
- трактовка runtime как центральной архитектуры вместо executor-а compiled program.

## Архитектура верхнего уровня

Merged system должна иметь такую форму:

`Authoring DSL -> ScreenProgramCompiler -> ScreenProgram -> ScreenRuntimeExecutor + Backend`

Ключевой момент в том, что автор всё ещё пишет один screen-first DSL, но компилятор теперь производит phased `ScreenProgram`, а не render-only plan и не live runtime tree.

## Почему runtime всё равно нужен

В дизайне всё равно нужен runtime, но он должен быть маленьким и узким.

Runtime здесь не widget tree manager и не recomposition engine.

Он нужен только потому, что часть вещей по своей природе принадлежит execution time:

- текущие state values;
- текущие mouse и keyboard events;
- текущий focus owner;
- hit-testing по текущим bounds;
- dispatch event handler ids к живым handler functions;
- backend drawing с текущими Minecraft objects.

Поэтому runtime должен быть executor-ом compiled screen program, а не главной формой представления UI structure.

## Authoring model

### Unified authoring DSL

Автор пишет screen-first DSL, в котором есть:

- layout containers;
- visual primitives;
- semantic roles;
- interaction modifiers;
- explicit structural nodes;
- lambda-based expressions и handlers.

Примеры author-facing constructs:

- `box`, `row`, `column`, `stack`;
- `text`, `rect`, `terminalSurface`, `canvas`;
- `clickable`, `focusable`, `hoverable`, `scrollable`;
- `textExpr { ... }`, `boolExpr { ... }`, `colorExpr { ... }`;
- `if_`, `when_`, `forEach`.

### Лямбды разрешены, но не как структура

Лямбды уместны для:

- scalar dynamic values;
- event handlers;
- небольших dynamic text/color adapters.

Лямбды не должны быть структурной execution model.

Компилятор обязан выводить структуру экрана из explicit DSL nodes, а не из произвольного runtime execution лямбд.

### Кнопка — это authoring sugar, а не engine primitive

`button` может существовать как authoring sugar, но core engine не должен требовать отдельную primitive-кнопку.

Компилятор должен lower-ить `button` в generic building blocks:

- layout nodes;
- visual modifiers или child primitives;
- interaction modifiers вроде `clickable` и `focusable`;
- semantic role metadata вроде `role = Button`.

Это сохраняет core IR маленьким и обобщённым.

## Фазовый `ScreenProgram`

Merged `ScreenProgram` должен вырасти из render-only формы в phased artifact.

Концептуально он должен содержать:

- `layoutProgram`
- `renderProgram`
- `hitTestProgram`
- `inputProgram`
- `focusProgram`

Это по-прежнему может быть один immutable object, но внутренне фазы должны оставаться специализированными.

### Layout program

Отвечает за:

- static bounds;
- dynamic layout fragments;
- parent-child geometry relationships;
- clip и visibility shape;
- metadata для dynamic recomputation.

### Render program

Отвечает за:

- static render ops;
- dynamic render fragments;
- terminal и native render primitives;
- typed payload slots;
- ссылки на layout bounds.

### Hit-test program

Отвечает за:

- compiled hit regions;
- z-order для input targeting;
- dynamic region enable/disable rules;
- mapping от pointer location к logical region id.

### Input program

Отвечает за:

- event routing rules;
- mapping event types к handler ids;
- dispatch rules для click, key, scroll и hover;
- key activation rules для semantic roles.

### Focus program

Отвечает за:

- focusable targets;
- tab order и directional traversal;
- focus transitions;
- capture rules для таких элементов, как terminal surface.

## Что компилируется, а что остаётся живым

### Что попадает в `ScreenProgram`

- structure экрана;
- topology layout;
- topology draw;
- hit regions и routing shape;
- topology focus;
- handler ids и event routing ids;
- semantic role metadata.

### Что остаётся в runtime host state

- текущие scalar values;
- текущие snapshot data;
- текущая таблица handler functions;
- текущие focused/hovered/pressed ids;
- текущие event payloads.

Это главный компромисс merged design: structure компилируется, а values и events остаются живыми.

## Dynamic model

Dynamic model не должен откатываться к author-facing bindings.

Вместо этого:

- автор пишет expressions и handlers как лямбды;
- компилятор назначает внутренние slot ids и handler ids;
- runtime host вычисляет expressions в slots;
- executor потребляет typed slots и маршрутизирует события по handler id.

Так сохраняется ergonomic screen-first DSL вместе с execution-shape преимуществами compiled IR model.

## Backend model

Backend model из направления 18 апреля должна сохраниться.

Backends должны оставаться:

- typed;
- narrow;
- testable;
- Minecraft-facing только на нижнем слое.

Merged executor должен сидеть над backend и исполнять phased `ScreenProgram`:

- применять layout updates;
- применять render updates;
- разрешать hit targets;
- обновлять focus state;
- dispatch input handlers;
- вызывать backend drawing.

Так backend остаётся маленьким, а screen class не скатывается обратно в ручной orchestrator.

## Cost model

Merged design мотивирован не только API cleanliness, но и cost control.

Специально нужно избежать таких overhead patterns из текущего prototype:

- rebuild live UI tree каждый кадр;
- rebuild hit regions с нуля каждый кадр;
- re-sort interaction regions каждый кадр;
- использование authoring lambdas как runtime program.

Целевой cost shape:

- compile once для structure;
- обновление только dynamic fragments;
- hit-test и focus tables как compiled data там, где это возможно;
- typed и contiguous backend execution.

## Стратегия миграции

Этот merged design означает новый execution target для screen-first DSL, а не откат к старому render-only surface.

Порядок миграции должен быть таким:

1. Сохранить screen-first authoring surface.
2. Заменить текущий live runtime tree на compiler, который выдаёт phased `ScreenProgram` artifacts.
3. Сохранить backend execution shape из IR/backend worktree.
4. Перевести текущий terminal screen slice на новый compiled executor.
5. Только после этого переносить более тяжёлый workbench screen.

## Критерии успеха

Merged design успешен, если одновременно выполняются все условия:

- автор по-прежнему пишет один screen-first DSL;
- interaction semantics для `button` и `terminalSurface` сохраняются без ручного screen glue;
- runtime больше не зависит от rebuild live tree каждый кадр;
- layout и render сохраняют compile-once typed IR model;
- input и focus компилируются в phased programs, а не прикручиваются к screen methods;
- backend interfaces остаются маленькими и testable.
