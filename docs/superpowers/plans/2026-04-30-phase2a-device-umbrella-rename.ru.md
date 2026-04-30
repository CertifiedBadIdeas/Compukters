# План реализации Phase 2a — Device Umbrella Rename

> **Для агентских воркеров:** ОБЯЗАТЕЛЬНЫЙ САБ-СКИЛЛ: используйте superpowers:subagent-driven-development (рекомендовано) или superpowers:executing-plans для пошагового исполнения. Шаги используют синтаксис чекбоксов (`- [ ]`).

**Цель:** Механически переименовать `Computer*`-префиксные типы shared substrate в `compiler/lang/runtime/`, `core/block/ComputerFamily` и `core/computer/vm/ComputerProfileRegistry` в `Device*`. Плюс NBT-ключ `FAMILY_ID` со значением `"ComputerFamilyId"` → `"DeviceFamilyId"` и Kotlin extension property `computerFamilyId` → `deviceFamilyId`.

**Архитектура:** Шесть последовательных коммитов, каждый — отдельный mechanical rename, верифицируемый через `./gradlew test --no-daemon`. На каждой задаче один и тот же рецепт: `git mv` → `sed` по символам с word boundaries `\b` → отлов `(Fake|Stub|Mock|Test)Computer*` обёрток (урок Phase 1) → grep на хвосты → тесты → коммит. Источник: [`docs/superpowers/specs/2026-04-30-device-umbrella-rename-design.ru.md`](../specs/2026-04-30-device-umbrella-rename-design.ru.md).

**Tech stack:** Kotlin/Gradle multi-module mod (Architectury Loom). Модули `:compiler`, `:core`, `:v1_21_1-common`, `:v1_21_1-fabric`, `:v1_21_1-forge`, `:v1_21_1-neoforge`, `:v1_21_1-create-neoforge`. Тесты: `./gradlew test --no-daemon`. Рабочая директория: `/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/phase2a-device-umbrella-rename` (worktree на ветке `phase2a-device-umbrella-rename`).

---

## Pre-flight (один раз перед Task 1)

- [ ] **Подтвердить чистое дерево на ветке worktree**

```bash
cd /home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/phase2a-device-umbrella-rename
git status --short   # ожидаемо: пусто
git log --oneline -1 # ожидаемо: 473c7a3 docs(spec): Phase 2a device umbrella rename design
```

- [ ] **Подтвердить, что baseline-тесты зелёные** (чтобы любой последующий fail атрибутировался к rename'у, а не к pre-existing breakage)

```bash
./gradlew test --no-daemon
```
Ожидаемо: `BUILD SUCCESSFUL`. Если всплыл флэйк `BackgroundComputerVmTest` (timeout 5000ms), запустить ещё раз с `--rerun-tasks`.

---

## Задача 1: переименование VM models (`ComputerVmModels.kt`)

**Файлы:**
- Rename: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerVmModels.kt` → `DeviceVmModels.kt`
- Modify (ссылки на символы): каждый `*.kt` под `modules/`, который импортирует или использует один из восьми типов ниже.

**Переименовываемые символы:** `ComputerCapability`, `ComputerCpuResources`, `ComputerMemoryResources`, `ComputerStorageResources`, `ComputerQueueResources`, `ComputerResources`, `ComputerProfile`, `ComputerVmHandle`.

- [ ] **Шаг 1: rename файла**

```bash
git mv modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerVmModels.kt \
       modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceVmModels.kt
```

- [ ] **Шаг 2: замена символов по всему коду**

```bash
for sym in ComputerCapability ComputerCpuResources ComputerMemoryResources ComputerStorageResources ComputerQueueResources ComputerResources ComputerProfile ComputerVmHandle; do
  new=${sym/Computer/Device}
  grep -rl --include='*.kt' "\\b$sym\\b" modules \
    | xargs --no-run-if-empty sed -i "s/\\b$sym\\b/$new/g"
done
```

- [ ] **Шаг 3: ловим wrapper-prefixed test fakes**

```bash
grep -rE '\b(Fake|Stub|Mock|Test)Computer(Capability|CpuResources|MemoryResources|StorageResources|QueueResources|Resources|Profile|VmHandle)' modules --include='*.kt'
```
Ожидаемо: пусто. (Инвентарь Phase 1 показал отсутствие таких обёрток для этих символов — но всегда перепроверять.) Если что-то всплыло, прогнать дополнительный sed.

- [ ] **Шаг 4: убедиться, что хвостов нет**

```bash
grep -rE '\bComputer(Capability|CpuResources|MemoryResources|StorageResources|QueueResources|Resources|Profile|VmHandle)\b' modules --include='*.kt' || echo OK
```
Ожидаемо: `OK`.

- [ ] **Шаг 5: тесты**

```bash
./gradlew test --no-daemon
```
Ожидаемо: `BUILD SUCCESSFUL`. Если флэйкнул `BackgroundComputerVmTest`, прогнать с `--rerun-tasks`.

- [ ] **Шаг 6: коммит**

```bash
git add -A
git commit -m "refactor: rename VM models to Device prefix

Mechanical rename of the shared-substrate VM model types:

- ComputerCapability     -> DeviceCapability
- ComputerCpuResources   -> DeviceCpuResources
- ComputerMemoryResources -> DeviceMemoryResources
- ComputerStorageResources -> DeviceStorageResources
- ComputerQueueResources -> DeviceQueueResources
- ComputerResources      -> DeviceResources
- ComputerProfile        -> DeviceProfile
- ComputerVmHandle       -> DeviceVmHandle

File ComputerVmModels.kt -> DeviceVmModels.kt.

These types describe the runtime contract of any Runtime Device, not
specifically the Computer block, per the Device / Authoring Station
domain model.

Per docs/superpowers/specs/2026-04-30-device-umbrella-rename-design.md
Task 1."
```

---

## Задача 2: переименование интерфейсов рантайм-контракта (`ComputerRuntime.kt`)

**Файлы:**
- Rename: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt` → `DeviceRuntime.kt`
- Modify: каждый `*.kt`, использующий один из девяти типов ниже.

**Переименовываемые символы:** `ComputerProgram`, `ComputerRuntime`, `ComputerSystemApi`, `ComputerTerminalApi`, `ComputerFileSystemApi`, `ComputerProcessApi`, `ComputerRedstoneApi`, `ComputerPeripheralApi`, `ComputerProgramFiles`.

- [ ] **Шаг 1: rename файла**

```bash
git mv modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt \
       modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt
```

- [ ] **Шаг 2: замена символов**

```bash
for sym in ComputerProgram ComputerRuntime ComputerSystemApi ComputerTerminalApi ComputerFileSystemApi ComputerProcessApi ComputerRedstoneApi ComputerPeripheralApi ComputerProgramFiles; do
  new=${sym/Computer/Device}
  grep -rl --include='*.kt' "\\b$sym\\b" modules \
    | xargs --no-run-if-empty sed -i "s/\\b$sym\\b/$new/g"
done
```

ВНИМАНИЕ: `\bComputerRuntime\b` НЕ матчит `BackgroundComputerVm` или другие compound-имена — `Runtime` это полное слово. Проверить, прочитав пару изменённых файлов после sed.

- [ ] **Шаг 3: ловим обёртки**

```bash
grep -rE '\b(Fake|Stub|Mock|Test)Computer(Program|Runtime|SystemApi|TerminalApi|FileSystemApi|ProcessApi|RedstoneApi|PeripheralApi|ProgramFiles)' modules --include='*.kt'
```
Ожидаемо: пусто.

- [ ] **Шаг 4: проверка хвостов**

```bash
grep -rE '\bComputer(Program|Runtime|SystemApi|TerminalApi|FileSystemApi|ProcessApi|RedstoneApi|PeripheralApi|ProgramFiles)\b' modules --include='*.kt' || echo OK
```
Ожидаемо: `OK`.

- [ ] **Шаг 5: тесты**

```bash
./gradlew test --no-daemon
```

- [ ] **Шаг 6: коммит**

```bash
git add -A
git commit -m "refactor: rename language-runtime interfaces to Device prefix

Mechanical rename of shared-substrate runtime contract interfaces:

- ComputerProgram        -> DeviceProgram
- ComputerRuntime        -> DeviceRuntime
- ComputerSystemApi      -> DeviceSystemApi
- ComputerTerminalApi    -> DeviceTerminalApi
- ComputerFileSystemApi  -> DeviceFileSystemApi
- ComputerProcessApi     -> DeviceProcessApi
- ComputerRedstoneApi    -> DeviceRedstoneApi
- ComputerPeripheralApi  -> DevicePeripheralApi
- ComputerProgramFiles   -> DeviceProgramFiles

File ComputerRuntime.kt -> DeviceRuntime.kt.

Per docs/superpowers/specs/2026-04-30-device-umbrella-rename-design.md
Task 2."
```

---

## Задача 3: переименование `ComputerStdioApi`

**Файлы:**
- Rename: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerStdioApi.kt` → `DeviceStdioApi.kt`
- Modify: каждый `*.kt`, ссылающийся на `ComputerStdioApi`.

- [ ] **Шаг 1: rename файла**

```bash
git mv modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerStdioApi.kt \
       modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceStdioApi.kt
```

- [ ] **Шаг 2: замена символа**

```bash
grep -rl --include='*.kt' '\bComputerStdioApi\b' modules \
  | xargs --no-run-if-empty sed -i 's/\bComputerStdioApi\b/DeviceStdioApi/g'
```

- [ ] **Шаг 3: ловим обёртки**

```bash
grep -rE '\b(Fake|Stub|Mock|Test)ComputerStdioApi' modules --include='*.kt'
```

- [ ] **Шаг 4: проверка хвостов**

```bash
grep -rE '\bComputerStdioApi\b' modules --include='*.kt' || echo OK
```

- [ ] **Шаг 5: тесты**

```bash
./gradlew test --no-daemon
```

- [ ] **Шаг 6: коммит**

```bash
git add -A
git commit -m "refactor: rename ComputerStdioApi to DeviceStdioApi

Per docs/superpowers/specs/2026-04-30-device-umbrella-rename-design.md
Task 3."
```

---

## Задача 4: переименование enum-а `ComputerFamily`

**Файлы:**
- Rename: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/block/ComputerFamily.kt` → `DeviceFamily.kt`
- Modify: каждый `*.kt` и NBT-utils.

**Замечание о риске:** `ComputerFamily` имеет 50 ссылок — самое большое количество в этом rename. Большой blast radius. Шаг 4 (grep на хвосты) обязателен.

- [ ] **Шаг 1: rename файла**

```bash
git mv modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/block/ComputerFamily.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/block/DeviceFamily.kt
```

- [ ] **Шаг 2: замена символа**

```bash
grep -rl --include='*.kt' '\bComputerFamily\b' modules \
  | xargs --no-run-if-empty sed -i 's/\bComputerFamily\b/DeviceFamily/g'
```

- [ ] **Шаг 3: ловим обёртки и проверяем `ComputerFamilyExt`**

```bash
grep -rE '\b(Fake|Stub|Mock|Test)ComputerFamily' modules --include='*.kt'
grep -rE '\bComputerFamilyExt\b' modules --include='*.kt'   # это ДОЛЖНО остаться (block-side, out of scope)
```
- `Fake/Stub/Mock/Test`: ожидаемо пусто.
- `ComputerFamilyExt`: ожидаемо есть попадания — это block-side extension-тип, намеренно вне scope. Проверить, что sed не задел его (`\b`-граница не должна пропустить, но проверить).

- [ ] **Шаг 4: проверка хвостов**

```bash
grep -rE '\bComputerFamily\b' modules --include='*.kt' || echo OK
```
Ожидаемо: `OK`. (`ComputerFamilyExt` НЕ матчит `\bComputerFamily\b`, потому что нет word-boundary между `y` и `E`.)

- [ ] **Шаг 5: тесты**

```bash
./gradlew test --no-daemon
```

- [ ] **Шаг 6: коммит**

```bash
git add -A
git commit -m "refactor: rename ComputerFamily enum to DeviceFamily

NORMAL/ADVANCED/COMMAND identifies the API surface a Runtime Device
exposes to CKL programs. Not Computer-specific.

ComputerFamilyExt (block-side extension) remains under its current name
and will be renamed in Phase 2b/2c alongside other block-specific
artifacts.

Per docs/superpowers/specs/2026-04-30-device-umbrella-rename-design.md
Task 4."
```

---

## Задача 5: переименование `ComputerProfileRegistry`

**Файлы:**
- Rename: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/ComputerProfileRegistry.kt` → `DeviceProfileRegistry.kt`
- Modify: каждый `*.kt`, ссылающийся на `ComputerProfileRegistry`.

**Заметка:** Файл ОСТАЁТСЯ в пакете `core/computer/vm/` — Phase 2a НЕ двигает пакеты. Меняется только имя файла и символ.

- [ ] **Шаг 1: rename файла**

```bash
git mv modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/ComputerProfileRegistry.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/DeviceProfileRegistry.kt
```

- [ ] **Шаг 2: замена символа**

```bash
grep -rl --include='*.kt' '\bComputerProfileRegistry\b' modules \
  | xargs --no-run-if-empty sed -i 's/\bComputerProfileRegistry\b/DeviceProfileRegistry/g'
```

- [ ] **Шаг 3: ловим обёртки**

```bash
grep -rE '\b(Fake|Stub|Mock|Test)ComputerProfileRegistry' modules --include='*.kt'
```

- [ ] **Шаг 4: проверка хвостов**

```bash
grep -rE '\bComputerProfileRegistry\b' modules --include='*.kt' || echo OK
```

- [ ] **Шаг 5: тесты**

```bash
./gradlew test --no-daemon
```

- [ ] **Шаг 6: коммит**

```bash
git add -A
git commit -m "refactor: rename ComputerProfileRegistry to DeviceProfileRegistry

Aligns the registry name with DeviceProfile/DeviceFamily it serves. The
file remains under core.computer.vm; the package move out of computer
is deferred to Phase 2c.

Per docs/superpowers/specs/2026-04-30-device-umbrella-rename-design.md
Task 5."
```

---

## Задача 6: NBT-ключ + extension property + финальная синхронизация документации

**Файлы:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/utils/NBTUtls.kt` (значение константы + имя extension property)
- Modify: каждый `*.kt`, вызывающий extension `computerFamilyId`
- Modify (если нужно): `docs/ARCHITECTURE.md`, секция Domain Model, если ссылается на переименованные типы

**Замечание о NBT:** Этот коммит меняет on-disk формат. По спеке принято — мод в dev-стадии. Migration-кода не добавляется.

- [ ] **Шаг 1: поменять значение NBT-ключа и переименовать extension property**

```bash
# Значение константы FAMILY_ID меняется со СТРОКИ "ComputerFamilyId" на "DeviceFamilyId".
sed -i 's|"ComputerFamilyId"|"DeviceFamilyId"|g' \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/utils/NBTUtls.kt

# Символ extension property computerFamilyId -> deviceFamilyId по всему modules/.
grep -rl --include='*.kt' '\bcomputerFamilyId\b' modules \
  | xargs --no-run-if-empty sed -i 's/\bcomputerFamilyId\b/deviceFamilyId/g'
```

- [ ] **Шаг 2: проверить значение константы и имя extension property**

```bash
grep -n 'FAMILY_ID' modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/utils/NBTUtls.kt
grep -n 'deviceFamilyId\|computerFamilyId' modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/utils/NBTUtls.kt
```
Ожидаемо:
- Строка `FAMILY_ID` показывает `"DeviceFamilyId"`.
- Строка extension property показывает `deviceFamilyId`, `computerFamilyId` не осталось.

- [ ] **Шаг 3: проверить хвосты по модулям**

```bash
grep -rE '"ComputerFamilyId"|\bcomputerFamilyId\b' modules --include='*.kt' || echo OK
```
Ожидаемо: `OK`.

- [ ] **Шаг 4: обновить секцию Domain Model в `docs/ARCHITECTURE.md`, если ссылается на переименованные типы**

```bash
grep -nE 'ComputerProfile|ComputerFamily|ComputerCapability|ComputerResources' docs/ARCHITECTURE.md
```

Если есть попадания — заменить каждое на соответствующий `Device*` in-place. Domain Model section — это forward-looking summary, обновлять её на текущие символы корректно. НЕ менять спеку `docs/superpowers/specs/2026-04-30-device-authoring-domain-model-design.md` — это исторический срез Phase-0 и должен остаться замороженным.

Если попаданий нет — шаг no-op, отметить в commit-сообщении.

- [ ] **Шаг 5: тесты**

```bash
./gradlew test --no-daemon
```

- [ ] **Шаг 6: коммит**

```bash
git add -A
git commit -m "refactor: rename FAMILY_ID NBT key and computerFamilyId extension to Device*

NBT key string value: \"ComputerFamilyId\" -> \"DeviceFamilyId\".
Kotlin extension property: computerFamilyId -> deviceFamilyId.

Breaking change for existing world saves; acceptable because the mod is
in development (no migration code is added).

Plus: sync the Domain Model section in docs/ARCHITECTURE.md to use
Device* names. The historical spec at
docs/superpowers/specs/2026-04-30-device-authoring-domain-model-design.md
is intentionally NOT updated — it is the Phase-0 frozen record.

Per docs/superpowers/specs/2026-04-30-device-umbrella-rename-design.md
Task 6."
```

---

## Финальная верификация

- [ ] **Шаг 1: cold build + полный прогон тестов**

```bash
./gradlew clean test --no-daemon
```
Ожидаемо: `BUILD SUCCESSFUL`. Перезапустить с `--rerun-tasks` если флэйкнул `BackgroundComputerVmTest`.

- [ ] **Шаг 2: in-scope хвосты — должно быть пусто**

```bash
grep -rE '\bComputer(Capability|CpuResources|MemoryResources|StorageResources|QueueResources|Resources|Profile|VmHandle|Program|Runtime|SystemApi|TerminalApi|FileSystemApi|ProcessApi|RedstoneApi|PeripheralApi|ProgramFiles|StdioApi|Family|ProfileRegistry)\b' modules --include='*.kt' \
  || echo "OK: in-scope хвостов нет"

grep -rE '"ComputerFamilyId"|\bcomputerFamilyId\b' modules --include='*.kt' \
  || echo "OK: NBT хвостов нет"
```
Ожидаемо: оба печатают `OK: ...`. Замечание: `ComputerFamilyExt` намеренно остаётся.

- [ ] **Шаг 3: out-of-scope ДОЛЖНЫ остаться нетронутыми**

```bash
grep -rE '\b(BackgroundComputerVm|ComputerVmSupervisor|ComputerContext|ServerComputer|ComputerManager|ComputerInputDispatcher|ComputerProgramSupport|ComputerItem|ComputerMenu|ComputerScreen|ComputerTerminalScreen|ComputerState|ComputerFamilyExt|ComputerContainerData|NetworkComputerInputGateway)\b' modules --include='*.kt' \
  | wc -l
```
Ожидаемо: положительное число (намеренно не тронуты). Если 0 — что-то пошло не так.

```bash
grep -rE '\bComputer(Workspace|IdeHost|WorkspaceEntry|WorkspaceDocument|IdeSnapshot|CompletionRequest|CompletionResponse|HoverRequest|HoverResponse|DefinitionRequest|DefinitionResponse)\b' modules --include='*.kt' \
  | head -3
```
Ожидаемо: попадания есть — это IDE/Workspace типы, намеренно отложены.

- [ ] **Шаг 4: посмотреть commit log ветки**

```bash
git log --oneline dev..HEAD
```
Ожидаемо: 6 коммитов, по одному на задачу. Это единственная дивергенция с `dev`. (Spec-коммит `473c7a3` лежит в самом `dev`.)

- [ ] **Шаг 5: handoff**

Применить skill `superpowers:finishing-a-development-branch`. Предложить пользователю: merge в `dev` локально, push + PR, оставить как есть, или discard. Рекомендация по умолчанию: merge локально с `--no-ff`, чтобы сохранить per-task историю.
