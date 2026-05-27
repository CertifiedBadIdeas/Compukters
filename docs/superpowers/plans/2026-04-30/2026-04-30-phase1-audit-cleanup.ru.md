# Фаза 1 — План реализации Audit-Driven Cleanup

> **Для агентов:** ОБЯЗАТЕЛЬНЫЙ SUB-SKILL: используйте superpowers:subagent-driven-development (рекомендуется) или superpowers:executing-plans для выполнения плана task-by-task. Шаги используют синтаксис чекбоксов (`- [ ]`) для трекинга.

**Цель:** Привести структуру кода и нейминг в соответствие с доменной моделью Device / Authoring Station из [docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.ru.md](../../specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.ru.md). Семантических изменений нет — только перенос пакетов, переименование gateway-типов и правки документации.

**Архитектура:** Три независимых рефакторинга, каждый изолирован в свой коммит и проверяется прогоном тестового набора проекта. Работа механическая: перенос `compukterkraft.core.computer.workbench.*` в `compukterkraft.core.workbench.*`, переименование двух cross-category bridge-типов и синхронизация двух doc-файлов. Модули `compiler` и `v1_21_1-common`/`fabric`/`neoforge`/`create-neoforge` затрагиваются только обновлением путей импортов.

**Tech stack:** Kotlin, Gradle (Kotlin DSL), Architectury Loom; тесты прогоняются через `./gradlew test`.

---

## Затрагиваемые файлы (инвентаризация)

### Задача 1 — Перенос пакетов

**Перенести (32 файла, через `git mv` для сохранения истории):**
- 22 main-файла под `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/**`
- 10 test-файлов под `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/**`

Места назначения зеркальны: `.../core/workbench/**` (без сегмента `computer/`).

**Обновить импорты / package decl в:**
- Всех 32 перенесённых файлах (их `package`)
- 22 дополнительных consumer-файлах в разных модулях (полный список — в Шаге 4 Задачи 1)

**Обновить boundary-тест, если он зависит от структуры пакетов:**
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt`

### Задача 2 — `ComputerControlGateway` → `TargetControlGateway`

**Место объявления:**
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchContracts.kt` (после Задачи 1: `core/workbench/WorkbenchContracts.kt`)

**Места использования (всего 8 файлов):**
- `modules/core/.../core/workbench/WorkbenchOpsGateway.kt`
- `modules/core/.../core/workbench/WorkbenchStore.kt`
- `modules/core/src/test/.../core/workbench/WorkbenchEditorViewModelTestSupport.kt`
- `modules/core/src/test/.../core/workbench/WorkbenchStoreTest.kt`
- `modules/v1_21_1/v1_21_1-common/.../common/infrastructure/workbench/WorkbenchGateways.kt`
- `modules/v1_21_1/v1_21_1-common/src/test/.../common/workbench/WorkbenchSyncIntegrationTest.kt`
- `modules/v1_21_1/v1_21_1-neoforge/src/test/.../impl/computer/workbench/WorkbenchStoreTest.kt`

### Задача 3 — `ComputerInputGateway` → `TargetInputGateway`

**Место объявления:**
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/input/ComputerInputGateway.kt` → переименовать файл в `TargetInputGateway.kt`

**Места использования (4 файла):**
- `modules/v1_21_1/v1_21_1-common/.../common/computer/input/ClientInputHandler.kt`
- `modules/v1_21_1/v1_21_1-common/.../common/computer/input/NetworkComputerInputGateway.kt`
- `modules/v1_21_1/v1_21_1-common/.../common/workbench/input/NetworkWorkbenchInputGateway.kt`
- `modules/v1_21_1/v1_21_1-common/.../common/workbench/input/WorkbenchClientInputHandler.kt`

### Задача 4 — Согласованность пути в loader-leaf neoforge тесте

**Перенести:**
- `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/workbench/WorkbenchStoreTest.kt` → `.../impl/workbench/WorkbenchStoreTest.kt`

### Задача 5 — Правки документации

- `docs/ARCHITECTURE.md` — таблица пакетов со ссылкой `compukterkraft.core.computer.workbench`
- `docs/TODOs.md` — пункт 8

---

## Заметки по выполнению

**Команда сборки/тестов для проверки (после каждой задачи):**

```bash
./gradlew :compukterkraft-core:test :compukterkraft-v1_21_1-common:test :compukterkraft-v1_21_1-neoforge:test --no-daemon
```

Если имена gradle-модулей отличаются от догадки выше, выполните `./gradlew projects` один раз, чтобы их узнать. Затем подкорректируйте.

**Политика коммитов:** один коммит на задачу. Используйте `git status` между задачами, чтобы убедиться в чистом дереве перед началом следующей.

**Рабочая директория:** все команды выполняются из корня worktree: `/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/phase1-audit-cleanup` (или там, где worktree был создан).

---

## Задача 1: перенос `compukterkraft.core.computer.workbench.*` → `compukterkraft.core.workbench.*`

**Файлы:**
- Перенос: 32 файла под `modules/core/src/{main,test}/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/**`
- Правка: 22 consumer-файла (см. Шаг 4)
- Проверка: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt`

- [ ] **Шаг 1: убедиться, что рабочее дерево чистое**

```bash
git status --short
```
Ожидание: пустой вывод. Если что-то незакоммичено — закоммитить или застэшить, потом начинать.

- [ ] **Шаг 2: перенести файлы с сохранением истории**

```bash
# main sources
mkdir -p modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench
git mv modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/* \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/

# test sources
mkdir -p modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench
git mv modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/* \
       modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/

# удалить опустевшие исходные директории
rmdir modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench || true
rmdir modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench || true
```
Ожидание: `git status` показывает 32 переименованных файла со статусом R (rename detected).

- [ ] **Шаг 3: обновить `package` в перенесённых файлах**

```bash
# Заменить package decl только в перенесённых исходниках (32 файла)
find modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench \
     modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench \
     -name '*.kt' -type f -print0 \
| xargs -0 sed -i 's|^package ru\.lazyhat\.compukterkraft\.core\.computer\.workbench|package ru.lazyhat.compukterkraft.core.workbench|'
```

Проверка:
```bash
grep -r '^package ru\.lazyhat\.compukterkraft\.core\.computer\.workbench' modules/core || echo OK
```
Ожидание: `OK` (старых package decl не осталось).

- [ ] **Шаг 4: обновить импорты во всех consumer-файлах**

```bash
grep -rl --include='*.kt' 'ru\.lazyhat\.compukterkraft\.core\.computer\.workbench' modules \
| xargs sed -i 's|ru\.lazyhat\.compukterkraft\.core\.computer\.workbench|ru.lazyhat.compukterkraft.core.workbench|g'
```

Убедиться, что упоминаний не осталось:
```bash
grep -r --include='*.kt' 'ru\.lazyhat\.compukterkraft\.core\.computer\.workbench' modules || echo OK
```
Ожидание: `OK`.

Также подтвердить, что число consumer-файлов соответствует аудиту (22 внешних + self-references перенесённых файлов уже обработаны Шагом 3):
```bash
grep -rl --include='*.kt' 'ru\.lazyhat\.compukterkraft\.core\.workbench' modules | wc -l
```
Ожидание: число ≥ 32 (перенесённые файлы) + несколько consumer-файлов — точное значение не критично, важно лишь, что строка `core.computer.workbench` нигде не осталась.

- [ ] **Шаг 5: проверить ArchitectureBoundaryTest на привязку к старым пакетам**

```bash
grep -n 'computer\.workbench\|workbench' modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt || echo "no workbench-specific assertions"
```

Если какая-то строка ссылается на `core.computer.workbench` буквально — обновить её через `sed -i` на `core.workbench`. Если тест перечисляет корни пакетов, которые не должны импортировать друг друга, добавить `core.workbench` как peer к `core.computer` если уместно. Если тест чисто структурный и не использует workbench-специфичные литералы — этот шаг пропустить.

- [ ] **Шаг 6: собрать и прогнать все тесты**

```bash
./gradlew test --no-daemon
```
Ожидание: BUILD SUCCESSFUL. Любая ошибка здесь означает пропущенный импорт или несоответствие package decl — исправить и перезапустить до коммита.

- [ ] **Шаг 7: закоммитить**

```bash
git add -A
git commit -m "refactor(core): move workbench out of computer package

Workbench is a peer to Computer in the domain model (Authoring Station
vs Runtime Device), not a sub-feature of Computer. Aligns core package
layout with v1_21_1-common, where workbench was already a top-level peer.

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md
Phase 1, item 1."
```

---

## Задача 2: переименование `ComputerControlGateway` → `TargetControlGateway`

**Файлы:**
- Правка (объявление): `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchContracts.kt`
- Правка (использования): 7 других файлов (см. инвентаризацию выше)

- [ ] **Шаг 1: убедиться, что рабочее дерево чистое**

```bash
git status --short
```
Ожидание: пустой вывод.

- [ ] **Шаг 2: заменить символ везде**

```bash
grep -rl --include='*.kt' 'ComputerControlGateway' modules \
| xargs sed -i 's/\bComputerControlGateway\b/TargetControlGateway/g'
```

Проверка:
```bash
grep -r --include='*.kt' 'ComputerControlGateway' modules || echo OK
grep -rn --include='*.kt' 'TargetControlGateway' modules | wc -l
```
Ожидание первой команды: `OK`. Второй: положительное число, соответствующее предыдущим 35 ссылкам.

- [ ] **Шаг 3: собрать и прогнать тесты**

```bash
./gradlew test --no-daemon
```
Ожидание: BUILD SUCCESSFUL.

- [ ] **Шаг 4: закоммитить**

```bash
git add -A
git commit -m "refactor: rename ComputerControlGateway to TargetControlGateway

This is a cross-category bridge owned by Authoring Station, used to
control the targeted Runtime Device. The Computer-prefix wrongly implied
Computer ownership.

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md
Phase 1, item 2."
```

---

## Задача 3: переименование `ComputerInputGateway` → `TargetInputGateway`

**Файлы:**
- Перенос + правка (объявление): `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/input/ComputerInputGateway.kt` → `.../core/computer/input/TargetInputGateway.kt`
- Правка (использования): 4 файла в `v1_21_1-common`

Заметка: файл *остаётся* под `core/computer/input/` пока — этот пакет содержит shared input transport и может быть переименован в Фазе 2 вместе с более широким umbrella-ренеймом. Фаза 1 переименовывает только тип.

- [ ] **Шаг 1: убедиться, что рабочее дерево чистое**

```bash
git status --short
```
Ожидание: пустой вывод.

- [ ] **Шаг 2: переименовать файл**

```bash
git mv modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/input/ComputerInputGateway.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/input/TargetInputGateway.kt
```

- [ ] **Шаг 3: заменить символ везде**

```bash
grep -rl --include='*.kt' 'ComputerInputGateway' modules \
| xargs sed -i 's/\bComputerInputGateway\b/TargetInputGateway/g'
```

Проверка:
```bash
grep -r --include='*.kt' 'ComputerInputGateway' modules || echo OK
```
Ожидание: `OK`.

- [ ] **Шаг 4: убедиться, что имя класса внутри файла соответствует имени файла**

```bash
grep -n 'interface TargetInputGateway\|class TargetInputGateway' \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/input/TargetInputGateway.kt
```
Ожидание: хотя бы одно совпадение. Если совпадений нет — тип объявлен под другим именем; открыть файл и переименовать декларацию вручную.

- [ ] **Шаг 5: собрать и прогнать тесты**

```bash
./gradlew test --no-daemon
```
Ожидание: BUILD SUCCESSFUL.

- [ ] **Шаг 6: закоммитить**

```bash
git add -A
git commit -m "refactor: rename ComputerInputGateway to TargetInputGateway

The gateway is a wire-level transport for input events to whichever
Runtime Device the consumer is bound to; it is shared by both Computer
and Workbench. The Computer-prefix wrongly implied Computer ownership.

File location is unchanged; the broader package move (input transport
out of core.computer.input) is deferred to Phase 2.

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md
Phase 1, item 3."
```

---

## Задача 4: перенос loader-leaf neoforge теста для согласованности

**Файлы:**
- Перенос: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/workbench/WorkbenchStoreTest.kt` → `.../impl/workbench/WorkbenchStoreTest.kt`

- [ ] **Шаг 1: убедиться, что рабочее дерево чистое**

```bash
git status --short
```
Ожидание: пустой вывод.

- [ ] **Шаг 2: перенести файл**

```bash
mkdir -p modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/workbench
git mv modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/workbench/WorkbenchStoreTest.kt \
       modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/workbench/WorkbenchStoreTest.kt
rmdir modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/workbench || true
# Удалить родительскую директорию impl/computer ТОЛЬКО если она опустела
ls modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer 2>/dev/null \
  && echo "impl/computer ещё содержит файлы (OK, оставляем)" \
  || rmdir modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer 2>/dev/null
```

- [ ] **Шаг 3: обновить package в перенесённом файле**

```bash
sed -i 's|^package ru\.lazyhat\.compukterkraft\.impl\.computer\.workbench|package ru.lazyhat.compukterkraft.impl.workbench|' \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/workbench/WorkbenchStoreTest.kt
```

Проверка:
```bash
head -3 modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/workbench/WorkbenchStoreTest.kt
```
Ожидание: `package ru.lazyhat.compukterkraft.impl.workbench`.

- [ ] **Шаг 4: поискать любые ссылки на старый путь**

```bash
grep -r --include='*.kt' 'impl\.computer\.workbench' modules || echo OK
```
Ожидание: `OK`. Если что-то нашлось — обновить тем же способом.

- [ ] **Шаг 5: собрать и прогнать тесты**

```bash
./gradlew :compukterkraft-v1_21_1-neoforge:test --no-daemon || ./gradlew test --no-daemon
```
Ожидание: BUILD SUCCESSFUL. (Первая команда быстро падает, если имя модуля угадано неверно; вторая — безопасный фолбэк.)

- [ ] **Шаг 6: закоммитить**

```bash
git add -A
git commit -m "refactor(v1_21_1-neoforge): move workbench test out of impl.computer

Mirrors the core package move from Task 1: workbench is a peer to
computer, not nested under it.

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md
Phase 1, item 1."
```

---

## Задача 5: обновление таблицы пакетов в `docs/ARCHITECTURE.md`

**Файлы:**
- Правка: `docs/ARCHITECTURE.md`

- [ ] **Шаг 1: найти строку таблицы для обновления**

```bash
grep -n 'ck\.core\.computer\.workbench' docs/ARCHITECTURE.md
```
Ожидание: один или несколько номеров строк (изначально строка 144 в неизменённом спеке, но коммит Фазы 0 добавил раздел Domain Model и сдвинул нумерацию).

- [ ] **Шаг 2: заменить запись таблицы**

Открыть `docs/ARCHITECTURE.md`. Найти строку:

```
| `compukterkraft.core.computer.workbench`       | IDE/workbench contracts and state                                  |
```

Заменить на запись, отражающую новую структуру (с peer-уточнением):

```
| `compukterkraft.core.workbench`                | Authoring Station contracts and state (peer to `compukterkraft.core.computer`) |
```

Если соседняя строка `compukterkraft.core.computer` тоже нуждается в peer-уточнении — обновить её описание, явно отметив, что это сторона Runtime Device. Использовать единое последовательное формулирование.

- [ ] **Шаг 3: убедиться, что больше нет ссылок на `compukterkraft.core.computer.workbench` в `docs/`**

```bash
grep -rn --include='*.md' 'ck\.core\.computer\.workbench' docs/
```
Ожидание: нет вывода. Если совпадения остались (например, в старых спеках) — оставить их; исторические спеки неизменяемы. Обновляется только архитектурная справка и активные TODO.

- [ ] **Шаг 4: закоммитить**

```bash
git add docs/ARCHITECTURE.md
git commit -m "docs(architecture): reflect workbench-as-peer package layout

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md
Phase 1, item 4."
```

---

## Задача 6: обновление пункта 8 в `docs/TODOs.md`

**Файлы:**
- Правка: `docs/TODOs.md`

- [ ] **Шаг 1: прочитать пункт 8 в контексте**

```bash
sed -n '12,20p' docs/TODOs.md
```

- [ ] **Шаг 2: заменить пункт 8 на формулировку текущего статуса**

Заменить существующий пункт 8 (блок `8. Сделать workbench(компьютерный стол)...` и его подпункты) на:

```
8. Workbench (компьютерный стол) — реализован как отдельная Authoring Station, см. [docs/superpowers/specs/2026-04-16/2026-04-16-workbench-separate-entity-design.md](../../specs/2026-04-16/2026-04-16-workbench-separate-entity-design.md) и доменную модель в [docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md](../../specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md). Дальнейшие итерации (multi-target, апгрейды, live-загрузка, запуск из IDE) — отдельные фичи, добавляются по мере необходимости.
```

Использовать редактор или аккуратный `sed` для замены блока. После правки убедиться:

```bash
grep -A1 '^8\. ' docs/TODOs.md | head -3
```
Ожидание: показывает новую формулировку.

- [ ] **Шаг 3: закоммитить**

```bash
git add docs/TODOs.md
git commit -m "docs(todos): mark workbench as implemented; link domain-model spec

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md
Phase 1, item 5."
```

---

## Финальная верификация

- [ ] **Шаг 1: полный прогон тестов**

```bash
./gradlew clean test --no-daemon
```
Ожидание: BUILD SUCCESSFUL.

- [ ] **Шаг 2: убедиться, что нет устаревших ссылок**

```bash
grep -rn --include='*.kt' 'ComputerControlGateway\|ComputerInputGateway' modules || echo "no stale gateway names"
grep -rn --include='*.kt' 'core\.computer\.workbench\|impl\.computer\.workbench' modules || echo "no stale package paths"
```
Обе должны вывести `no stale ...`.

- [ ] **Шаг 3: посмотреть лог коммитов**

```bash
git log --oneline dev..HEAD
```
Ожидание: 6 коммитов, по одному на задачу.

- [ ] **Шаг 4: запушить ветку (если нужно) и открыть PR**

```bash
git push -u origin phase1-audit-cleanup
```

(Шаг пропускается, если пользователь предпочитает merge локально или сделать push сам.)

---

## Вне scope (напоминание)

Следующее сознательно **отложено в Фазу 2 или позже** и НЕ ДОЛЖНО трогаться в этом плане:

- Переименование `Computer` → `RuntimeDevice` где-либо в коде или user-facing строках.
- Переименование `ComputerProfile` / `ComputerFamily` / `ComputerManager`.
- Введение интерфейса `RuntimeDevice`.
- Decoupling `ServerComputer` от `BlockEntity` / `ServerLevel`.
- Переименование `WorkbenchTerminalRenderer` (отложено до следующего прохода UI DSL).
- Перенос `core.computer.input.*` из пакета `computer` — пакет input transport переименовывается в Фазе 2 вместе с umbrella-ренеймом.

Если что-то из этих областей блокирует Фазу 1 — остановиться и вынести на обсуждение, не расширять scope.
