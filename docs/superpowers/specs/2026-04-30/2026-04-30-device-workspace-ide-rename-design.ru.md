# Phase 2a-bis — Переименование Device Workspace и IDE (механика)

## Цель

Механически переименовать `Computer*`-префиксованные типы Workspace и IDE-host (сегодня сложенные в один файл `compiler/lang/runtime/ComputerWorkspace.kt`) в `Device*` и разрезать этот файл на файл хранилища и файл IDE-хоста. Дополнительно — переименовать параметр `computerId: Int` в `deviceId: Int` по всему коду, потому что сегодня этот id однозначно идентифицирует Runtime Device.

Спека описывает **механический рефакторинг**. Семантика не меняется, новых абстракций не вводим, тесты не меняют поведение.

## Зачем

Phase 2a (`docs/superpowers/specs/2026-04-30/2026-04-30-device-umbrella-rename-design.md`) исключила IDE/Workspace типы с обоснованием «это Authoring-сторона, переименовывать в `Device*` неправильно». Перепроверка показала, что обоснование было ошибочным:

- `ComputerWorkspace` — это **per-device файловое хранилище**: его потребляет VM Runtime Device’а (`HostCallDispatcher`, `ComputerProgramSupport`, `ComputerVmSupervisor`) для filesystem-операций и загрузки boot-скрипта, и Authoring Station (`ServerWorkbench`), которая через `effectiveWorkspaceId` смотрит в файлы того же device’а.
- `ComputerIdeHost` **принадлежит Runtime Device’у**: создаётся в `ComputerVmSupervisor` (как `WorkspaceComputerIdeHost`) и отдаётся через `ComputerManager.ide`. Authoring Station — *потребитель*, а не совладелец.
- Параметр `computerId: Int` на каждом методе этих интерфейсов буквально означает «id того Runtime Device’а, чей workspace/IDE мы трогаем».

Это ровно то shared substrate, о котором говорит правило из domain-model спеки:

> Shared infrastructure types are named for their function, not their consumer.

`Device*` — префикс, который Phase 2a закрепила за этим substrate. Применение его здесь закрывает поверхность substrate-переименования.

## Не-цели

- Не вводим новых абстракций (никакого `RuntimeDevice` интерфейса — это Phase 2b).
- Не двигаем пакеты (Workspace/IDE типы остаются в `compiler/lang/runtime`; реализации остаются в `core/computer/vm`).
- IDE-примитивы (`Diagnostic`, `HighlightToken*`, `CompletionItem*`, `HoverInfo`, `DefinitionTarget`, `IdeDiagnosticSeverity`) семантически не привязаны к device’у — остаются без префикса.
- Никакой смены пользовательской терминологии CKL.
- Никакой NBT-миграции. Мод в dev-стадии.
- Никаких block-специфических `Computer*` (как и в 2a).

## Объём

### Переименования в `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/`

Текущий единственный файл `ComputerWorkspace.kt` разрезается на два по ответственности.

**Файл/расщепление:**

| Текущий файл | Новые файлы |
|---|---|
| `ComputerWorkspace.kt` | `DeviceWorkspace.kt` (хранилище) + `DeviceIdeHost.kt` (IDE-движок) |

**Типы хранилища (в `DeviceWorkspace.kt`):**

| Текущее имя | Новое имя |
|---|---|
| `ComputerWorkspaceEntry` (data class) | `DeviceWorkspaceEntry` |
| `ComputerWorkspaceDocument` (data class) | `DeviceWorkspaceDocument` |
| `ComputerWorkspace` (interface) | `DeviceWorkspace` |

**Типы IDE-хоста (в `DeviceIdeHost.kt`):**

| Текущее имя | Новое имя |
|---|---|
| `ComputerIdeSnapshot` (data class) | `DeviceIdeSnapshot` |
| `ComputerCompletionRequest` (data class) | `DeviceCompletionRequest` |
| `ComputerCompletionResponse` (data class) | `DeviceCompletionResponse` |
| `ComputerHoverRequest` (data class) | `DeviceHoverRequest` |
| `ComputerHoverResponse` (data class) | `DeviceHoverResponse` |
| `ComputerDefinitionRequest` (data class) | `DeviceDefinitionRequest` |
| `ComputerDefinitionResponse` (data class) | `DeviceDefinitionResponse` |
| `ComputerIdeHost` (interface) | `DeviceIdeHost` |

**Без изменений** (тоже в `DeviceIdeHost.kt`):

`IdeDiagnosticSeverity`, `Diagnostic`, `HighlightTokenKind`, `HighlightToken`, `CompletionItemKind`, `CompletionItem`, `HoverInfo`, `DefinitionTarget`.

### Переименования в `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/`

| Текущий файл/класс | Новый файл/класс |
|---|---|
| `ComputerWorkspaceHost.kt` / `ComputerWorkspaceHost` | `DeviceWorkspaceHost.kt` / `DeviceWorkspaceHost` |
| `WorkspaceComputerIdeHost.kt` / `WorkspaceComputerIdeHost` | `WorkspaceDeviceIdeHost.kt` / `WorkspaceDeviceIdeHost` |

`WorkspaceDeviceIdeHost` читается как «IDE-хост, прибитый к workspace, на конкретном device’е» — сохраняет смысл исходного имени.

### Переименование параметра (по всему коду)

| Текущее | Новое |
|---|---|
| `computerId: Int` в методах Workspace/IDE-хоста и во всех транзитивных вызовах с тем же идентификатором | `deviceId: Int` |

Сегодня каждое `computerId` в коде идентифицирует Runtime Device, поэтому единый mass-sed по `*.kt` корректен. Единственные строковые упоминания — docstring’и/комментарии «computer id», они станут «device id».

`SiteId.target(computerId: Int)` (CRDT) — тот же концепт, переименовываем в `SiteId.target(deviceId: Int)`.

### Синхронизация документации

`docs/ARCHITECTURE.md`:
- Упоминания `ComputerWorkspace` обновить до `DeviceWorkspace`.
- В таблице пакетов `compiler` для `lang.runtime` упомянуть расщепление (`DeviceWorkspace`, `DeviceIdeHost`).
- Где `computerId` встречается прозой как идентификатор — заменить на «device id».

Phase 2a спеку (`2026-04-30-device-umbrella-rename-design.md`) не трогаем: исторический комментарий об исключении остаётся как запись.

## Вне объёма (остаётся `Computer*`)

- `ComputerVmSupervisor`, `ComputerManager`, `ComputerProgramSupport`, `ComputerWorkspaceInitializer`, `BackgroundComputerVm` — оркестрация на стороне Computer-блока. Идут в Phase 2b/2d отдельно.
- `ComputerItem`, `ComputerMenu`, `ComputerScreen`, `ComputerTerminalScreen`, `ComputerState`, `ComputerFamilyExt`, `ComputerContainerData`, `NetworkComputerInputGateway` — block-side.
- Любое `computerId` *внутри CKL-кода*, language docs, тултипов, user-facing строк — ортогонально.

## Критерии приёмки

- Файл `compiler/lang/runtime/ComputerWorkspace.kt` отсутствует; есть `DeviceWorkspace.kt` и `DeviceIdeHost.kt` с перечисленными типами.
- `core/computer/vm/ComputerWorkspaceHost.kt` переименован в `DeviceWorkspaceHost.kt` с переименованным классом.
- `core/computer/vm/WorkspaceComputerIdeHost.kt` переименован в `WorkspaceDeviceIdeHost.kt` с переименованным классом.
- В `*.kt` под `modules/` нет ни одного символа `Computer(Workspace|WorkspaceEntry|WorkspaceDocument|IdeHost|IdeSnapshot|CompletionRequest|CompletionResponse|HoverRequest|HoverResponse|DefinitionRequest|DefinitionResponse|WorkspaceHost)`.
- В `*.kt` под `modules/` нет токена `\bcomputerId\b` (параметр, переменная, JSON-like-литерал). Комментарии «computer» в нерелевантном контексте могут остаться.
- Block-side подстроки на месте (sanity): `ComputerVmSupervisor`, `ComputerManager`, `ComputerProgramSupport`, `BackgroundComputerVm`, `ComputerItem`, `ComputerMenu`, `ComputerScreen`, `ComputerFamilyExt`.
- `./gradlew clean test --no-daemon` зелёный.
