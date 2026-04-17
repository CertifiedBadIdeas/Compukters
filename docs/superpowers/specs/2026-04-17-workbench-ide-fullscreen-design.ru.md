# Дизайн полноэкранной IDE верстака

## Цель

Заменить текущий интерфейс верстака с переключением страниц на полноэкранную IDE, в которой редактор всегда является основной поверхностью, терминал живёт в скрываемом нижнем доке, слот целевого компьютера виден в chrome IDE, а lifecycle-действия снова доступны.

## Область изменений

Этот редизайн относится только к экрану IDE верстака.

Входит в задачу:

- полноэкранный layout IDE верстака
- скрываемый нижний dock с терминалом
- видимый слот целевого компьютера в поверхности IDE
- восстановление lifecycle/control surface для подключённого целевого компьютера
- отказ от старого разделения workbench на страницы editor и terminal
- точечные исправления багов со слотом и path для reboot/power controls

Не входит в задачу:

- изменения обычного экрана компьютера
- изменение размера terminal pane в этой итерации
- несколько target slots или более широкий redesign инвентаря

## Уже подтверждённые корневые причины

### Отсутствие lifecycle controls

Текущая control surface верстака сломана в коде, а не только скрыта в UI.

- `NetworkWorkbenchControlGateway.reboot()` пустой.
- Старая toolbar всё ещё отражает эпоху run/reboot surface, но привязана к модели маленького окна и переключения страниц.
- Текущее состояние workbench предполагает, что `TERMINAL` и `EDITOR` являются двумя отдельными top-level страницами.

### Отсутствие surface для слота компьютера

Target slot сейчас не просто не стилизован, а отключён на уровне menu contract.

- `WorkbenchMenuWithoutInventory` создаёт placeholder slots.
- Каждый такой слот возвращает `isActive() = false`.
- Текущий экран не выделяет и не рендерит видимую поверхность под target computer slot.

### IDE не соответствует целевому UX

Текущие `WorkbenchEditorScreen` и `WorkbenchLayoutModel` построены вокруг фиксированного окна с жёстко заданными отступами для:

- верхней toolbar
- левой колонки workspace
- основной editor area
- отдельной страницы terminal

Эта архитектура противоречит желаемой модели полноэкранной IDE с нижним docked terminal.

## Целевой UX

### Полноэкранная оболочка

IDE верстака становится полноэкранным экраном, привязанным к размерам окна Minecraft, а не к `WorkbenchTerminalMetrics`.

Оболочка содержит:

- верхний IDE header со статусом target, target slot и основными действиями
- левый sidebar для навигации по workspace
- центральный editor как основную поверхность
- нижний status bar для информации о документе и диагностике
- скрываемый нижний dock с terminal

### Terminal dock

Терминал больше не является отдельной страницей.

- Он появляется как нижняя панель внутри IDE.
- Его можно показать или скрыть.
- Редактор остаётся основной поверхностью и при скрытом, и при открытом доке.
- Поведение фокуса терминала продолжает следовать текущим правилам ввода.

### Target slot и controls

В header IDE верстака показывается один видимый slot целевого компьютера.

- Slot всегда присутствует в chrome верстака.
- Он сообщает, вставлен ли компьютер.
- Он расположен рядом с метаданными target и lifecycle controls.

Lifecycle controls переносятся в статусную/header область вместо старой фиксированной строки кнопок.

## Архитектурные изменения

### Модель состояния

`WorkbenchMode` должен перестать описывать `TERMINAL` и `EDITOR` как две отдельные страницы экрана верстака.

Вместо этого экран верстака должен моделироваться как одно полноэкранное IDE-состояние с под-состояниями UI, например:

- terminal visible или hidden
- target connected или disconnected
- active document/editor state

Если для постепенной миграции enum временно сохранится, он больше не должен управлять полноэкранным page switching.

### Layout model

`WorkbenchLayoutModel` должен быть переработан под расчёт bounds от размеров окна, а не от маленького framed window.

Он должен вычислять явные регионы для:

- header bounds
- sidebar bounds
- editor bounds
- status bar bounds
- terminal dock bounds при видимом terminal
- target slot bounds
- lifecycle action bounds

Текущие жёсткие отступы должны исчезнуть или быть сильно сокращены, чтобы layout масштабировался вместе с полноэкранной IDE.

### Menu и slot contract

Меню верстака должно отдавать один активный target slot, пригодный для рендера внутри IDE surface.

Для этого нужны оба слоя:

- активный slot contract на уровне menu
- screen-level layout с устойчивой видимой позицией для слота

Placeholder inactive slots не должны оставаться видимым путём для target computer surface.

### Control gateway

Lifecycle actions верстака должны иметь полный client-to-server path.

Как минимум:

- reboot больше не должен быть no-op
- новая control surface должна маппиться на реальные gateway actions
- surface должна корректно отражать connected/disconnected state target-компьютера

## Модель взаимодействия

### Действия в header

Верхний header отвечает за:

- показ target computer slot
- показ connection state и label целевого компьютера
- lifecycle controls
- workbench actions вроде save, pull, push, run и terminal toggle

### Переключение терминала

Старое переключение страниц `IDE/Console` превращается в действие видимости терминала.

- Когда terminal скрыт, editor использует освободившуюся высоту.
- Когда terminal показан, editor уменьшается по вертикали, освобождая место под нижний dock.

### Editor и workspace list

Существующие browser и editor-возможности сохраняются, но перекладываются в полноэкранную оболочку вместо текущего framed window.

## Тестирование и проверка

Implementation plan должен включать целевые проверки как на bug fixes, так и на новый layout.

Обязательные проверки:

- тест workbench control gateway на dispatch reboot
- тест menu или screen contract, подтверждающий, что target slot активен и должен быть видимым
- layout tests для fullscreen editor bounds и terminal dock bounds
- compile verification для `:core` и `:v1_21_1-common`
- ручная проверка в игре:
  - полноэкранный layout редактора
  - видимый slot целевого компьютера
  - видимые lifecycle controls
  - show/hide поведение terminal dock

## Не входит в задачу

- редизайн обычного computer UI
- multi-slot redesign инвентаря верстака
- resize handle для terminal pane в этой итерации
- несвязанные расширения editor feature surface