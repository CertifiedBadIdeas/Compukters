# Дизайн screen-first единого UI DSL

## Цель

Заменить текущий разрыв между pure render builder-ами, Minecraft-side renderer-ами и вручную написанным input-кодом в screen-классах на один screen-first UI foundation.

Новый foundation должен позволять описывать layout, drawing, hit testing, focus, keyboard input, mouse input и low-level custom rendering через единый DSL, который по ощущению ближе к Jetpack Compose, чем к pipeline-компилятору сцены, но без compiler plugin, скрытой рекомпозиции и магического dependency tracking.

## Почему предыдущее направление неверное

Предыдущий дизайн ставил в центр систему вида `DSL -> Layout IR -> Render IR -> ScreenProgram -> Backend`. Такое направление оптимизирует структуру компиляции и исполнения раньше, чем решает authoring.

Для этого проекта это неверный приоритет.

Реальная проблема не в том, что экранам не хватает compiler pipeline. Реальная проблема в том, что текущая UI-поверхность разрезана на слишком много мест:

- authoring в pure builder-ах вроде `buildTerminalUi(...)`
- drawing в Minecraft-side helper-ах вроде `UiRenderer`
- click, focus и keyboard logic внутри screen-классов
- special rendering paths вроде terminal rendering вне DSL-поверхности

Из-за этого текущий DSL неполон по своей природе. Декларативный UI-слой, который не умеет как first-class concepts выражать кнопки, обработку кликов, focus и keyboard routing, нельзя считать пригодным UI DSL.

Поэтому новый дизайн отвергает такие идеи как архитектурную основу:

- render-specific compiler pipeline как главную абстракцию
- отдельные renderer/backend layers как author-facing concepts
- binding-slot API для обычных динамических значений
- render-only DSL, в который input прикручивается потом

## Базовые принципы дизайна

### Один DSL, а не отдельные поверхности для render и input

Должна быть одна authoring-модель.

Элемент вроде `Button` или `TerminalSurface` должен сам владеть всем поведением, которое к нему относится:

- layout
- drawing
- hover и pressed states
- hit testing
- click handling
- focus behavior
- keyboard handling в focused state

Система не должна разрывать это на render DSL и отдельный input DSL. Такой разрез воссоздаёт текущую проблему под новыми именами.

### Screen-first authoring

Основная точка входа должна быть базовым классом вроде `DslScreen` или `DslContainerScreen`.

Автор должен писать UI прямо в screen-классе через один `content()` block или аналогичный screen-local DSL hook. Screen host сам обязан разложить это на обычный Minecraft screen lifecycle:

- `renderBg`
- `render`
- `mouseClicked`
- `mouseReleased`
- `mouseMoved`
- `mouseDragged`
- `mouseScrolled`
- `keyPressed`
- `keyReleased`
- `charTyped`

Автор не должен вручную синхронизировать render и input paths.

### Динамика через лямбды, а не через bindings

Обычные динамические значения должны выражаться через типизированные лямбды и DSL-expression, а не через declarations binding-slot-ов.

Примеры:

- `textExpr { state.statusLine }`
- `enabledWhen { state.connected }`
- `visibleWhen { state.showTerminal }`
- `modifier.clickable(enabled = { state.canActivate }) { onActivate() }`

Такой подход сохраняет authoring-модель читаемой и не вводит искусственный binding API для значений, которые естественно вычисляются из screen state.

### Явные DSL-expression для динамической структуры

Обычные Kotlin `if` и `for` не должны быть основой структурной динамики, потому что без поддержки компилятора runtime не сможет надёжно анализировать структуру и отличать stable subtree от dynamic.

Вместо этого структурная динамика должна выражаться явными DSL-узлами и expression, такими как:

- `if_(condition) { ... } else_ { ... }`
- `when_(expr) { ... }`
- `forEach(itemsExpr, key = { ... }) { ... }`

Это сохраняет dynamic surface анализируемой без compiler plugin.

### Low-level rendering должен оставаться доступным

DSL должен уметь выражать как можно большую часть обычного Minecraft screen UI, но также обязан предоставлять прямой escape hatch для специального рендеринга.

Это означает, что автор должен иметь возможность спуститься к low-level rendering, не выходя из DSL host model.

Примеры:

- элемент `canvas` или `customRender`
- modifier вроде `drawWithGuiGraphics`
- dedicated primitive вроде `terminalSurface`

Escape hatch здесь не запасной выход на случай плохого дизайна, а обязательная часть системы.

## Архитектура верхнего уровня

Новый foundation должен строиться вокруг маленького retained runtime, которым владеет screen host.

Ключевые runtime concepts:

- `DslScreen` или `DslContainerScreen`: screen host
- `UiElement`: author-facing element model
- `Modifier`: composable surface для поведения элемента
- `UiExpression<T>`: явная dynamic value surface
- `FrameModel`: вычисленное представление кадра
- `InteractionMap`: данные для hit testing, focus, hover и input routing

Это намеренно не архитектура в стиле compiler pipeline.

Система может flatten-ить, cache-ировать и precompute-ить внутренние структуры, но это детали реализации. Основная ментальная модель должна оставаться такой: screen host содержит UI tree, которое вместе описывает layout, rendering и interaction.

## Runtime model

Каждый кадр проходит через три концептуальные фазы.

### 1. Построение или обновление UI tree

Screen host вычисляет `content()` и получает дерево `UiElement` и `UiExpression` узлов.

Runtime может держать стабильный static skeleton и обновлять только dynamic pieces, но authoring API не должен напрямую выставлять это различие наружу.

### 2. Layout и сбор draw-команд

Runtime делает layout pass по дереву и строит `FrameModel`, который содержит:

- вычисленные bounds
- упорядоченные draw-команды
- clip и z-order информацию
- ссылки на low-level custom drawing callbacks там, где это нужно

Именно здесь static и dynamic work могут быть внутренне разделены ради производительности.

### 3. Построение interaction routing и dispatch input

Runtime строит `InteractionMap`, который содержит:

- hit regions
- identity элементов
- pointer capture state
- hovered target
- pressed target
- focused target
- владельца keyboard routing

Mouse events dispatch-ятся сверху вниз по z-order. Keyboard events сначала идут в focused target, а затем bubbling-ом в screen-level fallback, если это нужно.

## Performance contract

Дизайн обязан оставаться близким по стоимости к handwritten screen logic.

Это требует жёсткого performance contract с самого начала.

### Дешёвая работа, допустимая каждый кадр

- вычисление scalar expression вроде текста, цвета, bool и visibility flags
- перестроение interaction map по уже вычисленным bounds
- hit testing по плоскому ordered region list
- исполнение low-level draw-команд

### Работа, которую надо жёстко ограничить

- перестройка больших layout subtree
- реконструкция draw list для неизменившихся static regions
- повторное измерение stable text без необходимости
- обход больших generic graph, когда можно использовать плоский cache

### Правила первой итерации

- static subtree можно cache-ировать
- dynamic scalar expression можно пересчитывать каждый кадр
- structural dynamic nodes вроде `if_` и `forEach` могут перестраивать только затронутое subtree
- text measurement должен cache-ироваться, если исходная строка не изменилась
- terminal render data может cache-ироваться, если snapshot identity или revision не менялись

Система не должна пытаться делать full automatic dependency tracking или general-purpose diffing в первой итерации.

## Authoring surface

### Элементы в области первой итерации

- `box`
- `row`
- `column`
- `stack`
- `spacer`
- `text`
- `icon`
- `rect`
- `panel`
- `button`
- `terminalSurface`
- `canvas` или `custom`

Этих элементов достаточно, чтобы чисто переписать terminal screen и задать словарь дизайна для последующей миграции workbench.

### Modifiers в области первой итерации

- `padding`
- `offset`
- `size`
- `fillMaxWidth`
- `fillMaxHeight`
- `align`
- `zIndex`
- `background`
- `border`
- `clickable`
- `hoverable`
- `focusable`
- `scrollable`
- `keyInput`
- `visibleWhen`
- `enabledWhen`
- `tooltip`

### Expressions и структурные узлы

- `textExpr { ... }`
- `colorExpr { ... }`
- `boolExpr { ... }`
- `if_(...) { ... } else_ { ... }`
- `when_(...) { ... }`
- `forEach(..., key = { ... }) { ... }`

## Модель ответственности элемента

Интерактивные элементы должны быть first-class, а не macro над более низкоуровневыми примитивами.

Например, `Button` должен владеть:

- default layout behavior
- default drawing behavior
- hover и pressed visuals
- click activation
- keyboard activation в focused state
- optional tooltip behavior

Аналогично, `TerminalSurface` должен владеть:

- отрисовкой terminal snapshot
- focus behavior
- click-to-focus behavior
- routing keyboard events
- handling scroll wheel там, где это применимо

Это устраняет текущую ситуацию, когда визуальное дерево живёт в одном месте, а interaction logic в другом.

## Ответственность screen host

`DslScreen` или `DslContainerScreen` должен централизовать логику, которая сейчас дублируется по screen-классам.

Ответственности:

- управлять UI runtime instance
- вычислять `content()` на текущем screen state
- запускать layout и draw phases
- хранить hover, press, focus и pointer capture state
- переводить Minecraft screen lifecycle callbacks в runtime events
- при необходимости отдавать low-level screen context в custom draw и custom input handlers

Host должен уменьшать объём screen-кода, а не увеличивать его.

## Стратегия тестирования

Архитектура должна оставаться тестируемой без необходимости поднимать полный Minecraft client для большинства случаев.

### Core-тесты

- тесты layout resolution
- тесты expression evaluation
- тесты structural dynamic nodes для `if_`, `when_` и `forEach`
- тесты hit testing и z-order
- тесты focus routing
- тесты click и keyboard dispatch

### Minecraft-facing smoke tests

- хотя бы один smoke test на rendering через новый screen host
- хотя бы один smoke test на terminal input focus и delivery key events

Если interaction behavior нельзя валидировать вне Minecraft-facing glue, foundation слишком сильно связан с MC-слоем.

## Стратегия переписывания

Это нужно рассматривать как rewrite-first foundation, а не как compatibility layer поверх текущего DSL.

Первой целью реализации должен быть `ComputerTerminalScreen`.

Этот экран подходит лучше всего, потому что уже содержит:

- static chrome
- terminal rendering
- focus management
- mouse clicks
- keyboard input
- screen-level control buttons

Порядок переписывания должен быть таким:

1. Ввести новый screen-first UI foundation.
2. Реализовать минимальный набор элементов и modifiers, нужных terminal screen.
3. Переписать `ComputerTerminalScreen` напрямую на новый foundation.
4. Провалидировать performance и interaction behavior.
5. Переписывать `WorkbenchEditorScreen` только после того, как terminal screen подтвердит правильность API.

Этот дизайн не требует сохранять текущие абстракции `UiNode`, `UiRenderer` или `WorkbenchTerminalRenderer`. Их нужно считать legacy и удаляемыми после того, как новый foundation будет поставлен на ноги.

## Что не входит в первую итерацию

- recomposition engine в стиле Compose
- compiler plugin
- AST-level dependency analysis произвольного Kotlin control flow
- general renderer/backend abstraction surface
- render-only DSL с отдельным input authoring
- universal widget toolkit, покрывающий любые будущие UI-задачи

## Критерии успеха

Первая итерация успешна, если одновременно выполняются все условия:

- хотя бы один экран можно описать через единый screen-first DSL
- кнопки и terminal surface выражаются без ручного screen-level hit testing glue
- dynamic text и visibility не требуют explicit binding-slot declarations
- структурная динамика использует явные DSL-узлы, а не скрытый Kotlin control flow
- low-level custom rendering остаётся возможным внутри DSL host
- runtime cost для terminal screen остаётся близким к предыдущему handwritten path

## Влияние на существующие планы

Этот дизайн заменяет предыдущее направление compiled render architecture, описанное в UI DSL design и implementation plan от 18 апреля.

Из того направления ещё можно взять полезные идеи по caching и specialization, но оно больше не должно определять основную архитектуру. Любой следующий implementation plan должен строиться уже поверх описанного здесь screen-first unified DSL.