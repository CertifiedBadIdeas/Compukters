# UI DSL Focus — минимальная реализация

> Дата: 2026-04-24
> Worktree: `ui/screen-first-ui-program-merge`
> Задача: закрыть текущий TODO вокруг фокуса и клавиатуры, не строя полноценную систему фокуса.

## Проблема

Декларативный UI DSL уже компилирует клики в `HitRegion` / `InputRoute` и корректно их диспатчит. Клавиатура — заглушка:

- `ScreenRuntimeExecutor.keyPressed` проверяет `focusedRegionId`, который всегда `null`, поэтому любое нажатие возвращает `false`.
- Компилятор создаёт `KeyPressed`-маршрут для `terminalSurface`, но runtime никогда не заполняет `keyHandlers`.
- Типы `FocusProgram` / `FocusTarget` объявлены, но никто их не пишет и не читает.
- `DslContainerScreen.buildExecutor()` передаёт аргумент `focusHandlers = emptyMap()`, которого в `ScreenRuntimeExecutor` нет — код сейчас не собирается.
- `DslContainerScreen` не переопределяет `mouseClicked` / `keyPressed`, поэтому Minecraft никогда не вызывает методы executor'а.

Единственный потребитель клавиатуры в обозримом будущем — `terminalSurface`. Полноценная система фокуса (Tab-порядок, визуальные индикаторы, hover, blur-колбэки, мультифокус) — преждевременна.

## Цель

Минимальный механизм фокуса, который:

1. Разблокирует ввод с клавиатуры для `terminalSurface`.
2. Имеет такую runtime-форму, в которую позже можно положить более богатую политику фокуса, не меняя DSL.
3. Добавляет минимум поверхности API.

Вне области: Tab-навигация, hover, визуальные индикаторы фокуса, blur-колбэки, focus-follows-mouse, несколько одновременно сфокусированных элементов.

## Дизайн: неявный одиночный фокус

### Правило

В каждой скомпилированной `ScreenProgram` **не более одного focusable-элемента**. Если он есть — он всегда сфокусирован на всё время жизни программы.

Если компилятор встречает второй focusable-элемент — бросает `IllegalStateException("UI DSL: multiple focusable elements are not supported")` во время построения программы. Это страхует от тихой поломки, когда позже политика фокуса станет богаче.

Обоснование: сейчас единственный focusable — `terminalSurface`, и реальные экраны содержат ровно один терминал. Дешёвая проверка, внятная ошибка.

### Поверхность DSL

Никакого нового публичного модификатора. `terminalSurface` — единственный элемент, который внутренне заявляет себя focusable. DSL-подпись сохраняется:

```kotlin
terminalSurface(
    snapshot = expr { state.snapshot },
    modifier = Modifier.size(128, 72),
    onKey = { keyCode -> vm.onKey(keyCode) },
)
```

Неиспользуемый параметр `onFocus` удаляется — в минимальной системе нет события blur / смены фокуса. `onKey` — единственный вход для клавиатуры.

### Типы ядра

- `FocusProgram` заменяется полем `val focusedNodeId: String?` на `ScreenProgram`. Типы `FocusProgram` / `FocusTarget` удаляются (неиспользуемый каркас).
- `InputEventType` теряет значение `KeyPressed`. Маршрутизация клавиатуры больше не идёт через `InputRoute`, потому что сфокусированный узел один — хендлер ключуется прямо по `nodeId`.
- `InputEventType` фактически остаётся `Click`-only. Сохраняется как enum ради будущих расширений (`MouseRelease`, `Scroll`).

### Runtime

```kotlin
class ScreenRuntimeExecutor(
    private val program: ScreenProgram,
    private val clickHandlers: Map<String, () -> Unit>, // по handlerId
    private val keyHandler: ((Int) -> Boolean)?,        // один хендлер, либо null
) {
    fun mouseClicked(x: Int, y: Int): Boolean { /* без изменений */ }

    fun keyPressed(keyCode: Int): Boolean =
        keyHandler?.invoke(keyCode) ?: false
}
```

Параметры `slotProvider` и `focusHandlers` убираются — оба сегодня не используются.

### Компилятор

При понижении `UiElement.TerminalSurface`:

1. Создаётся `RenderOp.DrawTerminalSurface` как и раньше.
2. `focusedNodeId = nodeId`. Если `focusedNodeId` уже установлен — `error(...)`.
3. Лямбда `onKey` регистрируется под этим `nodeId`.

Компилятор больше не создаёт `KeyPressed`-маршрут и hit-region для клавиатуры терминала.

Маршрутизация клика для `terminalSurface` — отдельный вопрос: у терминала сейчас нет `onClick`, значит hit-region ему не нужен. Если понадобится — модификатор `clickable` композируется штатно.

### Интеграция со Screen

В `DslContainerScreen` добавляются:

```kotlin
override fun mouseClicked(x: Double, y: Double, button: Int): Boolean =
    executor.mouseClicked(x.toInt(), y.toInt()) || super.mouseClicked(x, y, button)

override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
    executor.keyPressed(keyCode) || super.keyPressed(keyCode, scanCode, modifiers)
```

Executor сейчас пересоздаётся каждый кадр; побочная уборка: кэшируем его на поле, чтобы input-колбэки использовали тот же экземпляр, что и рендер (иначе плывут идентичности хендлеров). Минимально корректный фикс: лениво строить `executor` в `render` и хранить в поле; вызовы `mouseClicked`/`keyPressed` до первого рендера — no-op.

### Что сознательно не делаем

| Фича | Почему не делаем |
|---|---|
| Tab-навигация | Второго focusable не существует |
| Визуальный индикатор фокуса | Focusable один → неоднозначности нет |
| Hover | Нет потребителя |
| Focus-follows-mouse | Ноль потребителей, вернёмся когда будет IDE |
| Blur / focus-change колбэки | Нет потребителя; `onFocus` из DSL удалён |
| Мульти-фокус / группы | Прикрыто проверкой в компиляторе |

## Совместимость с будущим

Когда появится второй focusable-элемент (вероятно — `workbench` IDE с редактором и сайдбаром), заменяем `focusedNodeId: String?` на полноценное состояние с политикой (follows-mouse / click-to-focus / sticky / Tab-order). Сигнатура `ScreenRuntimeExecutor.keyPressed` не меняется. `DslContainerScreen` не меняется. Меняются только компилятор и внутренняя логика определения фокуса.

## Чек-лист миграции (ненормативный — полный план в плане)

1. Удалить файлы `FocusProgram` / `FocusTarget`.
2. Удалить `InputEventType.KeyPressed` и соответствующие маршруты.
3. Убрать `UiElement.TerminalSurface.onFocus` и посторонний импорт `jdk.internal.*` в `UiElement.kt`.
4. Добавить `focusedNodeId: String?` в `ScreenProgram`.
5. Переписать компилятор: заполнение `focusedNodeId` и отказ на мультифокусе.
6. Переписать конструктор и `keyPressed` у `ScreenRuntimeExecutor`.
7. Кэшировать executor в поле `DslContainerScreen`, переопределить `mouseClicked` / `keyPressed`.
8. Обновить `ScreenProgramCompilerTest`: один focusable → `focusedNodeId` проставлен; два focusable → бросается ошибка.
