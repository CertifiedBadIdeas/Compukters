# Phase 2a — Device Umbrella Rename (механический)

## Цель

Механически переименовать `Computer*`-префиксные типы shared substrate (используемые и Runtime Devices, и Authoring Stations) в `Device*`, чтобы слой shared substrate перестал подразумевать владение со стороны Computer-блока. Реализует пункт 4 Phase 2 из `docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md`, расширенный на полное семейство ко-локализованных типов в `compiler/lang/runtime`.

Эта спека — **механический рефакторинг**. Семантика не меняется, новые абстракции не вводятся, поведение тестов не меняется.

## Зачем

По доменной модели Device / Authoring Station:

> **Shared infrastructure types are named for their function, not their consumer.**

Сейчас контракты языка/рантайма в `compiler/lang/runtime` имеют префикс `Computer*` (`ComputerProfile`, `ComputerCapability`, `ComputerResources`). Эти типы описывают рантайм-контракт **любого** Runtime Device — Computer-блок сегодня, Laptop/Turtle/Pocket Computer завтра. Префикс `Computer*` ошибочно подразумевает, что Computer-блок — канонический потребитель.

Umbrella-имя, согласованное в спеке доменной модели — **`RuntimeDevice`** (с коротким префиксом `Device` для shared-типов). Phase 2a применяет этот префикс к механическому слою (data-классы, enum-ы, registry). Введение интерфейса `RuntimeDevice`, развязывание `ServerComputer` от `BlockEntity` и обобщение `TransientPairing` отложены в Phase 2b/2c — это уже design work, а не mechanical rename.

## Не-цели

- Не вводится интерфейс `RuntimeDevice` (Phase 2b).
- Не разделяется `ServerComputer` и `ServerLevel` / `BlockEntity` (Phase 2c).
- Не переименовываются block-специфичные типы: `ServerComputer`, `ComputerManager`, `BackgroundComputerVm`, `ComputerVmSupervisor`, `ComputerContext`, `ComputerInputDispatcher`, `ComputerProgramSupport`, `ComputerItem`, `ComputerMenu`, `ComputerScreen`, `ComputerTerminalScreen`, `ComputerState`, `ComputerFamilyExt`, `ComputerContainerData`, `NetworkComputerInputGateway`. Они называют *конкретный* Runtime Device (блок) и остаются `Computer*` до Phase 2b/2c.
- Никакой NBT-миграции. Мод в dev-стадии; существующие сейвы не поддерживаются.
- CKL surface naming не меняется (если язык и его документация уже говорят про "computer", это остаётся).

## Scope

### Переименования в `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/`

VM models (в `ComputerVmModels.kt`):

| Текущее имя | Новое имя |
|---|---|
| `ComputerCapability` (enum) | `DeviceCapability` |
| `ComputerCpuResources` (data class) | `DeviceCpuResources` |
| `ComputerMemoryResources` (data class) | `DeviceMemoryResources` |
| `ComputerStorageResources` (data class) | `DeviceStorageResources` |
| `ComputerQueueResources` (data class) | `DeviceQueueResources` |
| `ComputerResources` (data class) | `DeviceResources` |
| `ComputerProfile` (data class) | `DeviceProfile` |
| `ComputerVmHandle` (interface) | `DeviceVmHandle` |

Интерфейсы рантайм-контракта (в `ComputerRuntime.kt`):

| Текущее имя | Новое имя |
|---|---|
| `ComputerProgram` (interface) | `DeviceProgram` |
| `ComputerRuntime` (interface) | `DeviceRuntime` |
| `ComputerSystemApi` (interface) | `DeviceSystemApi` |
| `ComputerTerminalApi` (interface) | `DeviceTerminalApi` |
| `ComputerFileSystemApi` (interface) | `DeviceFileSystemApi` |
| `ComputerProcessApi` (interface) | `DeviceProcessApi` |
| `ComputerRedstoneApi` (interface) | `DeviceRedstoneApi` |
| `ComputerPeripheralApi` (interface) | `DevicePeripheralApi` |
| `ComputerProgramFiles` (object) | `DeviceProgramFiles` |

Stdio (в `ComputerStdioApi.kt`):

| Текущее имя | Новое имя |
|---|---|
| `ComputerStdioApi` (interface) | `DeviceStdioApi` |

rename файлов:

| Текущий файл | Новый файл |
|---|---|
| `ComputerVmModels.kt` | `DeviceVmModels.kt` |
| `ComputerRuntime.kt` | `DeviceRuntime.kt` |
| `ComputerStdioApi.kt` | `DeviceStdioApi.kt` |

### Переименования в `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/block/`

| Текущее имя | Новое имя |
|---|---|
| `ComputerFamily` (enum: `NORMAL`, `ADVANCED`, `COMMAND`) | `DeviceFamily` |
| Файл `ComputerFamily.kt` | `DeviceFamily.kt` |

### Переименования в `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/`

| Текущее имя | Новое имя |
|---|---|
| `ComputerProfileRegistry` (object) | `DeviceProfileRegistry` |
| Файл `ComputerProfileRegistry.kt` | `DeviceProfileRegistry.kt` |

### NBT-ключ в `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/utils/NBTUtls.kt`

| Текущее | Новое |
|---|---|
| `const val FAMILY_ID: String = "ComputerFamilyId"` | `const val FAMILY_ID: String = "DeviceFamilyId"` |
| `var CompoundTag.computerFamilyId` (extension property) | `var CompoundTag.deviceFamilyId` |

Изменение NBT-ключа **ломает существующие сейвы**. Принято, потому что мод в dev-стадии.

## Out of scope (явный список)

### Block-специфичные Runtime Device артефакты (отложены в Phase 2b/2c)

Эти имена содержат `Computer*`, но в Phase 2a НЕ переименовываются. Они называют конкретный Computer-блок Runtime Device или его block-side concrete artifacts:

- `BackgroundComputerVm`, `ComputerVmSupervisor`, `ComputerContext`, `ComputerProgramSupport`, `ComputerInputDispatcher`
- `ServerComputer`, `ComputerManager`, `ComputerIdentitySavedData`
- `ComputerItem`, `AbstractComputerItem`, `ComputerMenu`, `AbstractComputerMenu`
- `ComputerScreen`, `ComputerTerminalScreen`
- `ComputerState`, `ComputerFamilyExt`, `ComputerContainerData`
- `NetworkComputerInputGateway`
- Сами листья пакета `computer` (`core.computer.*`, `common.computer.*`, `impl.computer.*`)

К ним возвращаемся в Phase 2b (введение интерфейса `RuntimeDevice`) и Phase 2c (отвязка от `BlockEntity` и обобщение manager/registry).

### IDE/Workspace API типы (отложено — нужен дизайн-выбор)

Файл `compiler/lang/runtime/ComputerWorkspace.kt` объявляет IDE-side workspace и request/response типы:

- `ComputerWorkspace` (interface), `ComputerIdeHost` (interface)
- `ComputerWorkspaceEntry`, `ComputerWorkspaceDocument`, `ComputerIdeSnapshot`
- `ComputerCompletionRequest`/`Response`, `ComputerHoverRequest`/`Response`, `ComputerDefinitionRequest`/`Response`

Эти типы описывают **что потребляет IDE-engine на Authoring Station**, а не что работает на Runtime Device. Переименование в `Device*` ошибочно подразумевало бы, что они живут на device-стороне. Правильное имя — что-то вроде `Workspace*` / `Ide*` / `Authoring*`, но это дизайн-решение, а не mechanical rename. Phase 2a их не трогает и отмечает как тема для отдельного брейншторма перед Phase 2b.

## Подход

Шесть последовательных коммитов на feature-ветке `phase2a-device-umbrella-rename` (worktree `.worktrees/phase2a-device-umbrella-rename`). Каждый коммит — отдельный mechanical rename, верифицируемый через `./gradlew test --no-daemon`.

**Порядок коммитов:**

1. VM models — `ComputerCapability`, четыре data-класса `Computer*Resources`, `ComputerResources`, `ComputerProfile`, `ComputerVmHandle`. Rename файла `ComputerVmModels.kt` → `DeviceVmModels.kt`.
2. Интерфейсы рантайм-контракта — `ComputerProgram`, `ComputerRuntime`, `ComputerSystemApi`, `ComputerTerminalApi`, `ComputerFileSystemApi`, `ComputerProcessApi`, `ComputerRedstoneApi`, `ComputerPeripheralApi`, `ComputerProgramFiles`. Rename файла `ComputerRuntime.kt` → `DeviceRuntime.kt`.
3. Stdio — `ComputerStdioApi`. Rename файла `ComputerStdioApi.kt` → `DeviceStdioApi.kt`.
4. `ComputerFamily` → `DeviceFamily` (rename файла).
5. `ComputerProfileRegistry` → `DeviceProfileRegistry` (rename файла).
6. NBT-ключ + Kotlin extension property: `FAMILY_ID` value `"ComputerFamilyId"` → `"DeviceFamilyId"`, `computerFamilyId` extension → `deviceFamilyId`. Плюс правка секции Domain Model в `docs/ARCHITECTURE.md`, если там упоминаются новые имена.

**Рецепт на коммит:**

```bash
# 1. чистое дерево
git status --short

# 2. rename файла (если применимо)
git mv path/to/Computer<X>.kt path/to/Device<X>.kt

# 3. замена символа
grep -rl --include='*.kt' '\bComputer<X>\b' modules \
  | xargs sed -i 's/\bComputer<X>\b/Device<X>/g'

# 4. ловим wrapper-prefixed test fakes (урок из Phase 1 Task 2)
grep -rE '(Fake|Stub|Mock|Test)Computer<X>' modules --include='*.kt' \
  | head -20
# rename если есть

# 5. убедиться, что не осталось хвостов
grep -rE '\bComputer<X>\b' modules --include='*.kt' || echo OK

# 6. тесты
./gradlew test --no-daemon

# 7. коммит
git commit -m "refactor: rename Computer<X> to Device<X>..."
```

**Финал:** `./gradlew clean test --no-daemon`, чтобы убедиться, что cold-сборка чистая. Затем merge в `dev`.

## Риски

- **Ловушка `\b` в sed.** В Phase 1 Task 2 уже всплыло, что `\bComputerFoo\b` НЕ матчит `FakeComputerFoo` (нет word boundary между `Fake` и `Computer`). Каждый рецепт коммита включает шаг 4 — сканирование `(Fake|Stub|Mock|Test)Computer<X>` и доп. sed, если найдено.
- **Каскад компиляции по модулям.** `compiler` — зависимость `core`, тот — зависимость `v1_21_1-common`/`v1_21_1-neoforge`/etc. После шага 3 если хоть один consumer-файл пропущен, билд падает. Митигация: шаг 5 — `grep -r` по всем `*.kt` под `modules/`.
- **Call sites вида `forFamily(ComputerFamily.ADVANCED)`.** Rename типа в параметре extension/method не меняет имя метода. Метод `forFamily` у registry остаётся — меняется только тип параметра.
- **Doc cross-references.** Спека доменной модели (`2026-04-30-device-authoring-domain-model-design.md`) явно называет СТАРЫЕ имена в таблице "Mapping to Current Code", потому что описывает состояние as-of-Phase-0. Phase 2a таблицу НЕ трогает — это инвалидировало бы исторический срез. После Phase 2a секцию Domain Model в `docs/ARCHITECTURE.md` можно обновить на новые имена — это единичная правка в коммите 6 рядом с NBT.

## Верификация

После всех шести коммитов:

```bash
./gradlew clean test --no-daemon

# In-scope renames — не должно быть хвостов.
grep -rE '\bComputer(Capability|CpuResources|MemoryResources|StorageResources|QueueResources|Resources|Profile|VmHandle|Program|Runtime|SystemApi|TerminalApi|FileSystemApi|ProcessApi|RedstoneApi|PeripheralApi|ProgramFiles|StdioApi|Family|ProfileRegistry)\b' modules --include='*.kt' \
  || echo "OK: в скоупе хвостов нет"

# Out-of-scope (block-specific + IDE/workspace) ОБЯЗАНЫ остаться нетронутыми.
grep -rE '\bComputer(Workspace|IdeHost|WorkspaceEntry|WorkspaceDocument|IdeSnapshot|CompletionRequest|CompletionResponse|HoverRequest|HoverResponse|DefinitionRequest|DefinitionResponse)\b' modules --include='*.kt' \
  | head -3 # ожидаем несколько попаданий — это ожидаемые нетронутые
```

## Передача Phase 2a → Phase 2b

После merge Phase 2a слой языка/рантайма больше не претендует, что Computer — канонический Runtime Device. Phase 2b может вводить интерфейс `RuntimeDevice` в `core`, ссылающийся напрямую на `DeviceProfile` / `DeviceFamily`, без коллизий имён и временных алиасов.

Phase 2c (отвязка `ServerComputer` от `BlockEntity`) идёт независимо после Phase 2b.
