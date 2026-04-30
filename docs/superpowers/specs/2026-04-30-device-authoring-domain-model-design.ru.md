# Доменная модель: Device / Authoring Station

## Цель

Зафиксировать единую каноническую ментальную модель внутриигровых сущностей мода Compukter Kraft, связанных с программированием, чтобы текущий код, запланированные фичи (Laptop, Turtle, Pocket Computer) и будущие инструменты разработчика укладывались в неё без концептуальных коллизий.

Этот спек — **только документация**. Он не меняет runtime-поведение. Его deliverables — только написанные документы. Все конкретные рефакторинги, вытекающие из модели, вынесены в отдельные фазы со своими планами.

## Зачем

Сейчас в моде есть два класса внутриигровых сущностей, связанных с программированием:

- **Computer** — блок с VM, исполняющий программы CKL.
- **Workbench** — блок с нативной (Kotlin) IDE, помогающей игроку писать программы CKL.

Текущая архитектура и кодбаза не называют, что у них общего, что нет, и как впишутся будущие устройства. В результате:

- Код Workbench в `modules/core` лежит под `compukterkraft.core.computer.workbench.*`, что сигнализирует, что Workbench — это подфича Computer. В `modules/v1_21_1/v1_21_1-common` тот же код лежит под `compukterkraft.common.workbench.*` как peer к computer. Эти два места противоречат друг другу.
- Несколько shared bridge-интерфейсов названы `Computer*` (`ComputerControlGateway`, `ComputerInputGateway`), хотя используются и Workbench, и Computer. Имена подразумевают, что Computer — главный владелец.
- В `docs/TODOs.md` Laptop описан как будущая фича без чёткого архитектурного места. Текущий код жёстко завязан на block-entity (`ServerComputer(level: ServerLevel)`, `TransientPairing` по `BlockPos`), что заблокирует портативные runtime-устройства.
- На вопрос «является ли IDE разновидностью компьютера?» в доках нет канонического ответа, хотя ответ влияет на каждое решение по будущим фичам.

Этот спек устраняет концептуальный долг, явно называя модель.

## Что не делается

- В рамках этого спека код не рефакторится.
- Новые фичи не добавляются.
- API CKL не меняется.
- Renames на umbrella-уровне (`ComputerProfile` → `DeviceProfile` и т.п.) НЕ входят в этот спек; они выделены в Фазу 2 ниже.

## Доменная модель

В моде **две ортогональные категории** внутриигровых сущностей, связанных с программированием. Они не являются подтипами друг друга.

### Категория 1: Runtime Devices

**Runtime Device** — всё в мире, что **исполняет** программы CKL.

У Runtime Device по определению есть:

- VM (`BackgroundComputerVm`), работающая на корутине и исполняющая скомпилированный байткод CKL.
- `DeviceProfile` (сегодня называется `ComputerProfile`), описывающий CPU budget, размеры терминала, поддержку цвета, содержимое ROM.
- `DeviceFamily` (сегодня называется `ComputerFamily`), идентифицирующий API surface, который устройство предоставляет программам CKL.
- Runtime workspace — развёрнутое файловое дерево, из которого VM читает и в которое пишет.
- Абстракция терминала — `ScreenBuffer` плюс приём ввода.
- Опциональные peripherals (модем, инвентарь у Turtle, топливо и т.д.).

**Текущие члены:** Computer (блок).
**Запланированные члены:** Laptop (портативный предмет), Turtle (entity с инвентарём и топливом), Pocket Computer (handheld предмет).

Различаются члены формой, мобильностью, capabilities, peripherals. Внутренняя анатомия одна.

Runtime Device **не обязан** предоставлять встроенный редактор программ. Это сознательное геймдизайнерское решение (см. `docs/TODOs.md` пункт 8): авторинг программ происходит на Authoring Station, не на самом устройстве. Это аналогия с embedded-разработкой: прошивку пишут на рабочей станции, заливают на устройство.

### Категория 2: Authoring Stations

**Authoring Station** — всё в мире, что **помогает игроку писать** программы CKL и при этом само реализовано нативно (Kotlin), а не на CKL.

У Authoring Station по определению есть:

- Локальный development workspace — исходники, которые редактирует игрок, отдельные от workspace любого Runtime Device.
- IDE engine — парсер, тайпчекер, автокомплит, диагностики — берётся из модуля `compiler`.
- Target descriptor — ссылка на выбранный Runtime Device, под `DeviceProfile` / `DeviceFamily` которого подстраивается IDE.
- Sync actions — `pull`, `push`, `run`, `attach terminal` — явные операции против таргета.

У Authoring Station **нет** VM, она **не исполняет** CKL и **не является** Runtime Device.

**Текущие члены:** Workbench (блок).
**Возможные будущие:** networked Workbench (хаб с несколькими таргетами), апгрейды Workbench, коллаборативные варианты. Они остаются нативными.

### Мост между категориями: target descriptor

Связь между двумя категориями — **target descriptor**, который держит Authoring Station. Дескриптор указывает на конкретный Runtime Device и предоставляет его `DeviceProfile` и `DeviceFamily`, чтобы IDE мог настроить capability-aware фичи.

В текущей реализации Workbench target descriptor — это предмет компьютера, вставленный в слот Workbench. Это обобщается: любой Runtime Device, представимый в виде дескриптора (item, world reference, network address), может служить таргетом.

Между Authoring Station и Runtime Device нет общей файловой системы. Перемещение между ними — явное, action-based, через перечисленные выше sync actions.

### Shared infrastructure (нейтральная, используется обеими категориями)

Эти артефакты не принадлежат ни одной категории. Это substrate, на котором обе категории построены. Они должны жить в модулях, не зависящих от конкретной категории:

- **Language tooling** (модуль `compiler`): парсер, тайпчекер, байткодная VM, data-классы `DeviceProfile`/`DeviceFamily`. Используется Authoring Station для IDE-фич и Runtime Device для компиляции и исполнения.
- **Workspace storage abstraction** (`core`): file CRUD над логической файловой системой. Каждая категория инстанцирует её со своим корнем и семантикой.
- **Терминальные text models и font rendering** (`v1_21_1-common/ui/render`): glyph layout, color tables, fixed-width rendering. Используются и terminal screen у Computer, и terminal preview panel у Workbench.
- **Input transport interfaces** (`core`): wire-level форма «событие клавиатуры/мыши доставлено серверному state holder». Отличается от *интерпретации* ввода, которая своя у каждой категории.

Если сомневаешься: если артефакт продолжал бы иметь смысл в гипотетическом моде, где есть только Runtime Devices ИЛИ только Authoring Stations, — это shared infrastructure, и она лежит вне пакетов категорий.

## Правила нейминга

- **Зонтик Категории 1: `RuntimeDevice`.** Сегодняшний код использует `Computer` как зонтик. Модель принимает `RuntimeDevice` как канонический зонтик. Историческое имя `Computer` оставляется за конкретным block-based вариантом; будущие варианты — `Laptop`, `Turtle`, `PocketComputer`.
- **Зонтик Категории 2: `AuthoringStation`.** Сегодня единственный член — `Workbench`. Будущие варианты переиспользуют зонтик.
- **Cross-category bridges используют нейтральные префиксы.** Тип, используемый обеими категориями, не должен иметь category-specific префикс. Конкретно:
  - `ComputerControlGateway` → `TargetControlGateway` (контролирует таргет Runtime Device с точки зрения Authoring Station).
  - `ComputerInputGateway` → `TargetInputGateway` (транспортирует input events к тому Runtime Device, к которому потребитель сейчас привязан).
- **Shared infrastructure типы названы по функции, не по потребителю.** `WorkbenchTerminalRenderer` — это shared terminal renderer, несмотря на имя; модель рекомендует function-based имя (например, `TerminalPanelRenderer`), когда следующий проход UI DSL коснётся этой области.
- **Вложенность пакетов должна отражать модель.** Код Workbench НЕ ДОЛЖЕН лежать под пакетом `computer.*`, и код Computer НЕ ДОЛЖЕН лежать под `workbench.*`. Они peer’ы.

## Соответствие текущему коду

Этот раздел привязывает модель к сегодняшней кодбазе. Это не список рефакторингов, а таблица перевода.

| Концепт | Сегодняшнее место |
|---|---|
| Runtime Device — абстрактная сущность | Неявно; нет umbrella-интерфейса. Концептуально представлено `ServerComputer` плюс `ComputerProfile`/`ComputerFamily`. |
| Computer (block-based Runtime Device) | `compukterkraft.common.computer.*`, `compukterkraft.core.computer.*`, `compukterkraft.impl.computer.*` |
| Authoring Station — абстрактная сущность | Неявно; нет umbrella-интерфейса. |
| Workbench (текущая Authoring Station) | `compukterkraft.common.workbench.*` (peer к computer — правильно), `compukterkraft.core.computer.workbench.*` (вложен в computer — НЕПРАВИЛЬНО, см. Фазу 1) |
| Target descriptor | Предмет компьютера в target-слоте Workbench плюс `ComputerControlGateway` (требует переименования). |
| `DeviceProfile`/`DeviceFamily` | Сейчас `ComputerProfile` (в `compiler`), `ComputerFamily` (в `core`). |
| Language tooling (shared) | Модуль `compiler` — уже на правильном месте. |
| Terminal rendering (shared) | `v1_21_1-common/ui/render` — на правильном месте; одно имя неудачное (`WorkbenchTerminalRenderer`). |
| Input transport (shared) | `compukterkraft.core.computer.input.*` — функция верная, имена пакета и типов с Computer-префиксом; должны стать нейтральными. |

## Фазы внедрения

Этот спек покрывает только Фазу 0. Каждая последующая фаза получает свой собственный цикл brainstorming → spec → plan → implementation.

### Фаза 0 — Канонизировать модель (этот спек)

**Deliverables:**
- Этот английский спек.
- Его русский аналог в `docs/superpowers/specs/2026-04-30-device-authoring-domain-model-design.ru.md`.
- Раздел «Domain Model» в начале `docs/ARCHITECTURE.md`, кратко описывающий две категории, мост и общий substrate, со ссылкой на этот спек как на канонический источник.

Без изменений в коде.

### Фаза 1 — Audit-driven cleanup (отдельный план)

**Scope:**
1. Перенос `modules/core/.../ck/core/computer/workbench/**` в `modules/core/.../ck/core/workbench/**`. Обновление всех импортов, включая модули v1_21_1 и тесты.
2. Переименование `ComputerControlGateway` → `TargetControlGateway` и обновление всех usages.
3. Переименование `ComputerInputGateway` → `TargetInputGateway` и обновление всех usages.
4. Обновление таблицы пакетов в `docs/ARCHITECTURE.md`.
5. Обновление пункта 8 в `docs/TODOs.md`: ссылка на этот спек, констатация, что Workbench-as-separate-entity реализован.
6. (Опционально) Переименование `WorkbenchTerminalRenderer` → `TerminalPanelRenderer`, если следующий проход UI DSL коснётся этой области; иначе отложить.

**Вне scope Фазы 1:**
- Переименование `Computer` → `RuntimeDevice` где-либо.
- Переименование `ComputerProfile`/`ComputerFamily`.
- Введение интерфейса `RuntimeDevice`.
- Decoupling `ServerComputer` от `BlockEntity`.

Фаза 1 — низкий риск: package move + точечные renames + правки доков. Без семантических изменений.

### Фаза 2 — Runtime Device umbrella (отдельный план, перед Laptop)

**Scope:**
1. Введение интерфейса `RuntimeDevice` в `core`, описывающего device-side контракт: создание/уничтожение VM, приём ввода, публикация screen output, выдача profile/family.
2. Decoupling `ServerComputer` от `ServerLevel` / `BlockEntity` через level/position-aware адаптер; core `ServerComputer` (или его наследник) принимает нейтральный host context.
3. Обобщение `TransientPairing` для поддержки не-блочных таргетов (item instances, entity instances), ключевание по стабильному идентификатору вместо `BlockPos`.
4. Механический rename: `ComputerProfile` → `DeviceProfile`, `ComputerFamily` → `DeviceFamily`. Затрагивает границу модуля `compiler`; rename должен сохранить CKL-only стойку модуля.
5. Опционально: `ComputerManager` → `DeviceManager` (или оставить `Computer`-имя внутри, если umbrella-интерфейса одного достаточно для разнесения).

**Вне scope Фазы 2:**
- Имплементация Laptop, Turtle, Pocket Computer.
- Изменение user-facing CKL терминологии (язык всё ещё может говорить «computer», если это привычное игроку слово — ортогональное решение).

### Фаза 3 — Laptop (отдельный план, после Фазы 2)

**Scope:**
- Реализовать Laptop как вторую имплементацию `RuntimeDevice`.
- Определить, как персистентное состояние живёт на предмете (NBT, server-side store по UUID предмета, гибрид).
- Определить, как игрок открывает терминал Laptop из инвентаря.
- Определить `DeviceProfile` и `DeviceFamily` Laptop (делит ли он профиль с Advanced Computer или у него свой).
- Определить, как target descriptor Workbench подстраивается под item-based Laptop.

Это уже фича, не рефакторинг; для неё свой brainstorm.

## Открытые вопросы

Эти вопросы сознательно отложены на будущие фазы. Перечислены здесь, чтобы не потерялись.

- Должна ли user-facing терминология CKL (в сообщениях об ошибках, в доках языка, в in-game tooltips) последовать за внутренним переименованием на `Device`, или остаться `Computer`? Решается в документации Фазы 2.
- Станет ли Workbench со временем Authoring Station с несколькими одновременными таргетами? Если да, target descriptor обобщается с «один вставленный item» до «выбранный таргет из известного набора». Решается, когда multi-target фича пойдёт в brainstorming.
- Делит ли Pocket Computer `DeviceFamily` с Laptop, или у него свой? Решается в Фазе 3+.
