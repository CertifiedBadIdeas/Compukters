# Phase 2b — План реализации отвязки Runtime Device

> **Для агентов:** ОБЯЗАТЕЛЬНЫЙ САБ-СКИЛЛ: superpowers:subagent-driven-development (рекомендуется) или superpowers:executing-plans для пошагового исполнения. Шаги — чекбоксы (`- [ ]`).

**Цель:** Ввести platform-neutral семейство ролей `RuntimeDevice` в `:core`, отвязать `ServerComputer` от `BlockEntity`/`ServerLevel` через узкие host-порты, перенести имплементацию в `:core` и переименовать остаточные `Computer*` идентификаторы (VM/manager/properties/events) в `Device*`.

**Архитектура:** Композиция ролевых интерфейсов (`RuntimeDeviceLifecycle`, `…Input`, `…Screen`, `…TerminalSessions`, `…Metadata`) и зонтичный `RuntimeDevice`. Мировые зависимости подаются через три узких порта: `GameTimeSource`, `TerminalNetworkBridge`, `DeviceStateSink`. Block-side carrier (`BlockEntityRuntimeDeviceHost`) собирает порты для `AbstractComputerBlockEntity`. Block / item / menu / screen / network-message слой сохраняет `Computer*` имена — он моделирует именно in-game *Computer-блок*.

**Tech Stack:** Kotlin/Gradle multi-module, Architectury Loom, Minecraft 1.21.1. Тестовая команда: `./gradlew test --no-daemon`.

**Спека:** [docs/superpowers/specs/2026-05-01-runtime-device-decoupling-design.ru.md](../specs/2026-05-01-runtime-device-decoupling-design.ru.md)

**Pre-flight (один раз перед Task 1):**

- [ ] Чистое дерево, ветка `phase2b-runtime-device-decoupling`, worktree `.worktrees/phase2b-runtime-device-decoupling`. Команда: `git status -s && git rev-parse --abbrev-ref HEAD`. Ожидание: пустой статус, ветка совпадает.
- [ ] Билд зелёный на старте. Команда: `./gradlew test --no-daemon`. Ожидание: `BUILD SUCCESSFUL`.

---

## Карта переименований (единый источник правды)

| Было | Стало | Модуль / Путь |
|---|---|---|
| `ComputerVmLogger` | `DeviceVmLogger` | `:core` `vm/BackgroundComputerVm.kt` (top-level fun interface) |
| `BackgroundComputerVm` | `BackgroundDeviceVm` | `:core` `vm/BackgroundComputerVm.kt` (class) + filename |
| `ComputerVmSupervisor` | `DeviceVmSupervisor` | `:core` `vm/ComputerVmSupervisor.kt` (class) + filename |
| `ComputerWorkspaceInitializer` | `DeviceWorkspaceInitializer` | `:core` `vm/ComputerWorkspaceInitializer.kt` + filename |
| `ComputerProgramSupport` | `DeviceProgramSupport` | `:core` `runtime/ComputerProgramSupport.kt` + filename |
| `ComputerEvents` | `DeviceEvents` | `:core` `computer/ComputerEvents.kt` + filename |
| `ComputerProperties` | `DeviceProperties` | `:core` `computer/ComputerProperties.kt` + filename |
| `ComputerManager` | `DeviceManager` | move `:v1_21_1-common` `context/ComputerManager.kt` → `:core` `runtime/DeviceManager.kt` |
| `ServerComputer` | `RuntimeDeviceImpl` | move `:v1_21_1-common` `context/ServerComputer.kt` → `:core` `runtime/RuntimeDeviceImpl.kt` |
| `ServerContext.computerManager` | `ServerContext.deviceManager` | `:v1_21_1-common` `context/ServerContext.kt` |
| `ServerContext.allocateComputerId()` | `ServerContext.allocateDeviceId()` | `:v1_21_1-common` `context/ServerContext.kt` |

**Out of scope (остаются `Computer*`):**
- `ComputerBlock`, `AbstractComputerBlockEntity`, `ComputerBlockEntity`, `NeoForgeComputerBlockEntity`, `ForgeComputerBlockEntity`
- `AbstractComputerItem`, `ComputerItem`
- `ComputerMenu`, `ComputerScreen`, `ComputerTerminalScreen`
- `ComputerState` (block-property enum)
- `ComputerActionServerMessage`, `KeyEventServerMessage`, `MouseEventServerMessage`, `PasteEventComputerMessage`
- `NetworkComputerInputGateway`, `ComputerInputDispatcher`
- Translation-keys (`gui.compukterkraft.tooltip.computer_id`) и сгенерированные методы (`Tooltip.computerId(...)`)
- `ComputerIdentitySavedData` NBT-ключ (`_computerID`); сам класс тоже сохраняет имя (хранит «computer ID» терминологию save-формата)
- Имя директории `core/computer/` (фс-перенос вне scope)

---

### Task 1: Ввести ролевые интерфейсы, зонт, порты, `PlayerHandle`

**Файлы:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/RuntimeDevice.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/PlayerHandle.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ports/GameTimeSource.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ports/TerminalNetworkBridge.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ports/DeviceStateSink.kt`

Чистые добавления — существующий код не трогается, билд остаётся зелёным.

- [ ] **Step 1: `PlayerHandle.kt`**

```kotlin
package ru.lazyhat.compukterkraft.core.computer.runtime

import java.util.UUID

/** Platform-neutral handle to a player, used by runtime devices for access checks
 *  without depending on net.minecraft.* types. */
interface PlayerHandle {
    val uuid: UUID
    val isStillValid: Boolean
}
```

- [ ] **Step 2: `ports/GameTimeSource.kt`**

```kotlin
package ru.lazyhat.compukterkraft.core.computer.runtime.ports

/** Supplies the current server game time (in ticks) to a runtime device. */
fun interface GameTimeSource {
    fun gameTime(): Long
}
```

- [ ] **Step 3: `ports/TerminalNetworkBridge.kt`**

```kotlin
package ru.lazyhat.compukterkraft.core.computer.runtime.ports

import java.util.UUID

/** Bridges per-player stdout byte streams from a runtime device to the network layer. */
interface TerminalNetworkBridge {
    fun isPlayerOnline(playerUuid: UUID): Boolean
    fun sendStdoutBytes(playerUuid: UUID, containerId: Int, bytes: ByteArray)
}
```

- [ ] **Step 4: `ports/DeviceStateSink.kt`**

```kotlin
package ru.lazyhat.compukterkraft.core.computer.runtime.ports

fun interface DeviceStateSink {
    fun onPowerStateChanged(isOn: Boolean)
}
```

- [ ] **Step 5: `RuntimeDevice.kt` — роли + зонт**

```kotlin
package ru.lazyhat.compukterkraft.core.computer.runtime

import java.util.UUID
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

interface RuntimeDeviceLifecycle {
    val deviceId: Int
    val isOn: Boolean
    fun turnOn()
    fun shutdown()
    fun reboot()
    fun serverTick()
    fun close()
}

interface RuntimeDeviceInput {
    fun queueEvent(event: String, arguments: Array<Any>)
}

interface RuntimeDeviceScreen {
    val lastScreenSnapshot: ScreenBufferSnapshot?
}

interface RuntimeDeviceTerminalSessions {
    fun attachTerminalSession(playerUuid: UUID, containerId: Int, cols: Int, rows: Int)
    fun resizeTerminalSession(playerUuid: UUID, cols: Int, rows: Int)
    fun detachTerminalSession(playerUuid: UUID)
}

interface RuntimeDeviceMetadata {
    val family: DeviceFamily
    var label: String?
    fun checkUsable(player: PlayerHandle): Boolean
}

interface RuntimeDevice :
    RuntimeDeviceLifecycle,
    RuntimeDeviceInput,
    RuntimeDeviceScreen,
    RuntimeDeviceTerminalSessions,
    RuntimeDeviceMetadata
```

> Если `import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot` не резолвится — найти тип: `grep -rn "class ScreenBufferSnapshot\|data class ScreenBufferSnapshot" modules/`. Использовать FQN своего пакета.

- [ ] **Step 6: Компиляция**

`./gradlew :core:compileKotlin --no-daemon` → `BUILD SUCCESSFUL`.

- [ ] **Step 7: Полные тесты**

`./gradlew test --no-daemon` → `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/
git commit -m "feat(core): introduce RuntimeDevice role interfaces and host ports"
```

---

### Task 2: Rename VM-машинерии в `:core/.../vm/`

Чистый rename: `BackgroundComputerVm` → `BackgroundDeviceVm`, `ComputerVmLogger` → `DeviceVmLogger`, `ComputerVmSupervisor` → `DeviceVmSupervisor`, `ComputerWorkspaceInitializer` → `DeviceWorkspaceInitializer`. Имена файлов следуют именам типов.

**Файлы (git mv):**
- `vm/BackgroundComputerVm.kt` → `BackgroundDeviceVm.kt`
- `vm/ComputerVmSupervisor.kt` → `DeviceVmSupervisor.kt`
- `vm/ComputerWorkspaceInitializer.kt` → `DeviceWorkspaceInitializer.kt`

**Тесты:**
- `:core` test + `:v1_21_1-neoforge` test: `BackgroundComputerVmTest.kt` → `BackgroundDeviceVmTest.kt`

- [ ] **Step 1: Переименовать файлы**

```bash
cd modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm
git mv BackgroundComputerVm.kt BackgroundDeviceVm.kt
git mv ComputerVmSupervisor.kt DeviceVmSupervisor.kt
git mv ComputerWorkspaceInitializer.kt DeviceWorkspaceInitializer.kt
cd -
git mv modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVmTest.kt \
       modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundDeviceVmTest.kt
git mv modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/BackgroundComputerVmTest.kt \
       modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/BackgroundDeviceVmTest.kt
```

- [ ] **Step 2: Mass-replace идентификаторов**

Порядок важен — `BackgroundComputerVm` пересекается с `BackgroundComputerVmTest`; сначала длинный.

```bash
rg -l '\bBackgroundComputerVmTest\b' modules/ \
  | xargs -r sed -i 's/\bBackgroundComputerVmTest\b/BackgroundDeviceVmTest/g'
rg -l '\bBackgroundComputerVm\b' modules/ \
  | xargs -r sed -i 's/\bBackgroundComputerVm\b/BackgroundDeviceVm/g'
rg -l '\bComputerVmSupervisor\b' modules/ \
  | xargs -r sed -i 's/\bComputerVmSupervisor\b/DeviceVmSupervisor/g'
rg -l '\bComputerVmLogger\b' modules/ \
  | xargs -r sed -i 's/\bComputerVmLogger\b/DeviceVmLogger/g'
rg -l '\bComputerWorkspaceInitializer\b' modules/ \
  | xargs -r sed -i 's/\bComputerWorkspaceInitializer\b/DeviceWorkspaceInitializer/g'
```

- [ ] **Step 3: Проверить отсутствие остатков**

```bash
rg -n '\b(BackgroundComputerVm|ComputerVmSupervisor|ComputerVmLogger|ComputerWorkspaceInitializer)\b' modules/ docs/
```
Ожидание: ноль в `modules/`. Хиты в `docs/superpowers/` (исторические спеки/планы) — нормально.

- [ ] **Step 4: Компиляция + тесты**

`./gradlew test --no-daemon` → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add modules/
git commit -m "refactor: rename ComputerVm machinery to DeviceVm (BackgroundDeviceVm, DeviceVmSupervisor, DeviceVmLogger, DeviceWorkspaceInitializer)"
```

---

### Task 3: Rename `ComputerProgramSupport`/`ComputerEvents`/`ComputerProperties` → `Device*`

Все три уже живут в `:core`.

**Файлы (git mv):**
- `core/computer/runtime/ComputerProgramSupport.kt` → `DeviceProgramSupport.kt`
- `core/computer/ComputerEvents.kt` → `DeviceEvents.kt`
- `core/computer/ComputerProperties.kt` → `DeviceProperties.kt`
- `:core` test + `:v1_21_1-neoforge` test: `ComputerProgramSupportTest.kt` → `DeviceProgramSupportTest.kt`

- [ ] **Step 1: Переименовать файлы**

```bash
git mv modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupport.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/DeviceProgramSupport.kt
git mv modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/ComputerEvents.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/DeviceEvents.kt
git mv modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/ComputerProperties.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/DeviceProperties.kt
git mv modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupportTest.kt \
       modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/DeviceProgramSupportTest.kt
git mv modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/runtime/ComputerProgramSupportTest.kt \
       modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/runtime/DeviceProgramSupportTest.kt
```

- [ ] **Step 2: Mass-replace**

Порядок: `ComputerProgramSupportTest` (длинный) → дальше.

```bash
rg -l '\bComputerProgramSupportTest\b' modules/ \
  | xargs -r sed -i 's/\bComputerProgramSupportTest\b/DeviceProgramSupportTest/g'
rg -l '\bComputerProgramSupport\b' modules/ \
  | xargs -r sed -i 's/\bComputerProgramSupport\b/DeviceProgramSupport/g'
rg -l '\bComputerEvents\b' modules/ \
  | xargs -r sed -i 's/\bComputerEvents\b/DeviceEvents/g'
rg -l '\bComputerProperties\b' modules/ \
  | xargs -r sed -i 's/\bComputerProperties\b/DeviceProperties/g'
```

- [ ] **Step 3: Остатки**

```bash
rg -n '\b(ComputerProgramSupport|ComputerEvents|ComputerProperties)\b' modules/
```
Ожидание: 0.

- [ ] **Step 4: Тесты**

`./gradlew test --no-daemon` → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add modules/
git commit -m "refactor: rename ComputerEvents/ComputerProperties/ComputerProgramSupport to Device*"
```

---

### Task 4: Rename `ComputerManager` → `DeviceManager` и **перенести** в `:core/.../runtime/`

Один из двух тяжёлых переносов. `git mv` через границу модулей, смена `package`, mass-replace, починка импортов.

**Файлы:**
- Move + rename: `:v1_21_1-common` `context/ComputerManager.kt` → `:core` `runtime/DeviceManager.kt`

- [ ] **Step 1: Проверить, что у `:core` есть все транзитивные типы, нужные `ComputerManager`**

Прочитать `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ComputerManager.kt`. Подтвердить, что импорты ограничены `:core` типами (`DeviceVmSupervisor`, `BackgroundDeviceVm`, `DeviceProfile`, `VmStopReason`, `DeviceVmLogger`, `DeviceWorkspace`, `DeviceIdeHost`) и JDK. По историческому inventory так и есть; **подтвердить перед движением**.

> Если найдены неожиданные `net.minecraft.*` или `:v1_21_1-common` импорты — **остановиться** и поднять вопрос. Спека предполагала чистые зависимости.

- [ ] **Step 2: Переместить файл**

```bash
git mv modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ComputerManager.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/DeviceManager.kt
```

- [ ] **Step 3: Обновить package и имя класса**

В `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/DeviceManager.kt`:
- `package ru.lazyhat.compukterkraft.common.computer.context` → `package ru.lazyhat.compukterkraft.core.computer.runtime`
- `class ComputerManager(` → `class DeviceManager(`
- Внутренние self-ссылки на `ComputerManager` → `DeviceManager`
- Тип параметров/возвратов `ServerComputer` → `RuntimeDevice` (зонтичный интерфейс из Task 1). API публичных методов:
  - `fun get(deviceId: Int): RuntimeDevice?`
  - `fun add(device: RuntimeDevice)`
  - `fun remove(deviceId: Int): RuntimeDevice?`

- [ ] **Step 4: Mass-replace `ComputerManager` → `DeviceManager`**

```bash
rg -l '\bComputerManager\b' modules/ \
  | xargs -r sed -i 's/\bComputerManager\b/DeviceManager/g'
```

- [ ] **Step 5: Аксессоры в `ServerContext`**

В `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ServerContext.kt`:

`val computerManager = ComputerManager(vmSupervisor)` → `val deviceManager = DeviceManager(vmSupervisor)`.

Companion: `val computerManager: ComputerManager = context().computerManager` → `val deviceManager: DeviceManager = context().deviceManager`.

Распространить:
```bash
rg -l '\bcomputerManager\b' modules/ \
  | xargs -r sed -i 's/\bcomputerManager\b/deviceManager/g'
```

- [ ] **Step 6: Аудит call-sites под `RuntimeDevice` API**

`DeviceManager.get()` теперь возвращает `RuntimeDevice?`. Любой caller, делающий что-то ServerComputer-only, сломается.

```bash
rg -n '\b(ServerContext\.deviceManager|deviceManager)\b' modules/ --include='*.kt' \
  | rg -v '/test/'
```

Для каждого хита: убедиться, что используются методы зонта (turnOn/shutdown/reboot/close/serverTick/queueEvent/lastScreenSnapshot/attach…/detach…/resize…/family/label/checkUsable/isOn/deviceId). Если что-то ServerComputer-only — поднять вопрос (дизайн интерфейса должен это покрыть).

> Ожидаемые места:
> - `AbstractComputerBlockEntity.kt`: поле `serverComputer: ServerComputer?` — пока оставить тип `ServerComputer` (Task 5 переименует).
> - `WorkbenchBlockEntity.kt` / `ServerInputState.kt`: читают `lastScreenSnapshot` и `queueEvent` — есть в зонте ✓.

- [ ] **Step 7: Импорты**

`DeviceManager` ушёл за границу модуля. Старый путь `ru.lazyhat.compukterkraft.common.computer.context.ComputerManager` теперь сломан.

```bash
rg -n 'import ru\.lazyhat\.compukterkraft\.common\.computer\.context\.ComputerManager' modules/
```
Ожидание: 0 (sed уже заменил `ComputerManager` → `DeviceManager`, но путь пакета — старый). Чиним:
```bash
rg -l 'ru\.lazyhat\.compukterkraft\.common\.computer\.context\.DeviceManager' modules/ \
  | xargs -r sed -i 's|ru\.lazyhat\.compukterkraft\.common\.computer\.context\.DeviceManager|ru.lazyhat.compukterkraft.core.computer.runtime.DeviceManager|g'
```

- [ ] **Step 8: Тесты**

`./gradlew test --no-daemon` → `BUILD SUCCESSFUL`.

> Если падает на `:v1_21_1-common` — зайти в `ServerComputer.kt`, проверить, что внутренние ссылки на `ComputerManager`/`computerManager` отрезолвлены sed'ом. Если нет — починить вручную.

- [ ] **Step 9: Commit**

```bash
git add modules/
git commit -m "refactor: move and rename ComputerManager to :core DeviceManager"
```

---

### Task 5: Перенести и переименовать `ServerComputer` → `RuntimeDeviceImpl`, отвязать от `ServerLevel`/`ServerContext`

Сердце фазы. Перенос `ServerComputer` в `:core/.../runtime/`, замена world-зависимостей тремя портами, ренейм в `RuntimeDeviceImpl`. Создание `BlockEntityRuntimeDeviceHost` в `:v1_21_1-common`. Обновление `ComputerBlockEntity.createComputer(id)`.

**Файлы:**
- Move + rename: `:v1_21_1-common` `context/ServerComputer.kt` → `:core` `runtime/RuntimeDeviceImpl.kt`
- Modify: `:v1_21_1-common` `context/ServerContext.kt`
- Create: `:v1_21_1-common` `context/BlockEntityRuntimeDeviceHost.kt`
- Modify: `:v1_21_1-common` `block/ComputerBlockEntity.kt`
- Modify: `:v1_21_1-common` `block/AbstractComputerBlockEntity.kt`

Все правки делаются в одном working tree, инкрементальная компиляция между подшагами, коммит — в конце задачи.

- [ ] **Step 1: Полностью прочитать текущий `ServerComputer.kt`**

Зафиксировать каждое обращение к `level: ServerLevel`, `ServerContext.server`, `level.gameTime` и любую другую world-стороннюю операцию. Ожидаемые места:
1. `level.gameTime` в `serverTick` (request slice).
2. `ServerContext.server.playerList.getPlayer(uuid)` в `flushTerminalSessions`.
3. `ServerNetworking.sendToPlayer(message, player)` для stdout-байтов.
4. `ServerContext.computerManager.…` (теперь `deviceManager`) для VM lifecycle.
5. Путь уведомления о смене блок-стейта (что наблюдает за переходами `isOn`).

Если найдено что-то **ещё** — остановиться и обсудить.

- [ ] **Step 2: Перенести файл**

```bash
git mv modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ServerComputer.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/RuntimeDeviceImpl.kt
```

- [ ] **Step 3: Шапка файла**

- `package ru.lazyhat.compukterkraft.common.computer.context` → `package ru.lazyhat.compukterkraft.core.computer.runtime`
- `class ServerComputer(` → `class RuntimeDeviceImpl(`
- Добавить implementation: `class RuntimeDeviceImpl(...) : RuntimeDevice {`
- Удалить `: ComputerEvents.Receiver` если есть (Task 1 покрыл `RuntimeDeviceInput`). Если кто-то снаружи опирается на `DeviceEvents.Receiver` явно — оставить.

- [ ] **Step 4: Конструктор**

Заменить:
```kotlin
class ServerComputer(
    val instanceID: Int,
    val level: ServerLevel,
    val properties: ComputerProperties,
)
```
на:
```kotlin
class RuntimeDeviceImpl(
    override val deviceId: Int,
    private val properties: DeviceProperties,
    private val manager: DeviceManager,
    private val gameTime: GameTimeSource,
    private val terminalNetwork: TerminalNetworkBridge,
    private val stateSink: DeviceStateSink,
) : RuntimeDevice {
```

- [ ] **Step 5: Заменить world-вызовы вызовами портов**

| Было | Стало |
|---|---|
| `level.gameTime` | `gameTime.gameTime()` |
| `ServerContext.server.playerList.getPlayer(uuid) != null` | `terminalNetwork.isPlayerOnline(uuid)` |
| `val player = ServerContext.server.playerList.getPlayer(uuid) ?: return; ServerNetworking.sendToPlayer(StdoutBytesClientMessage(containerId, bytes), player)` | `terminalNetwork.sendStdoutBytes(uuid, containerId, bytes)` |
| `ServerContext.computerManager.…` / `…deviceManager.…` | `manager.…` |

Уведомление о смене стейта (что было `level.setBlock(...)` или требовало BlockEntity отреагировать) → `stateSink.onPowerStateChanged(isOn)` в той же точке lifecycle.

Добавить override'ы зонта:
- `override val deviceId: Int` — уже в primary constructor.
- `override val isOn: Boolean` — оставить существующее computed property.
- `override val family: DeviceFamily get() = properties.family`
- `override var label: String? get() = properties.label; set(v) { properties = properties.copy(label = v); /* persist */ }`
  > Если `DeviceProperties` — `class` с `var label`, оставляем. Если `data class` с `val` — старое тело `updateLabel` идёт в setter override.
- `override fun checkUsable(player: PlayerHandle): Boolean` — тело адаптировать под `player.uuid` / `player.isStillValid`.

- [ ] **Step 6: Сигнатура `DeviceFamily.checkUsable`**

```bash
rg -n 'checkUsable' modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/block/DeviceFamily.kt
```

Если принимает `Player` — сменить на `PlayerHandle`. Каллеры в `:v1_21_1-common` оборачивают ванильного `Player`:
```kotlin
internal fun net.minecraft.world.entity.player.Player.toRuntimeHandle(): PlayerHandle =
    object : PlayerHandle {
        override val uuid: UUID = this@toRuntimeHandle.uuid
        override val isStillValid: Boolean = !this@toRuntimeHandle.isRemoved
    }
```

> Если `DeviceFamily.checkUsable` отсутствует или не берёт `Player` — пропустить шаг.

- [ ] **Step 7: Mass-rename `ServerComputer` → `RuntimeDeviceImpl`**

```bash
rg -l '\bServerComputer\b' modules/ \
  | xargs -r sed -i 's/\bServerComputer\b/RuntimeDeviceImpl/g'
```

Затем поправить mismatched поля. Many call-sites: `var serverComputer: ServerComputer? = null`. После sed → `var serverComputer: RuntimeDeviceImpl? = null`. Имя поля устарело. Переименовать в `runtimeDevice` с типом интерфейса:

```bash
rg -l '\bserverComputer\b' modules/ \
  | xargs -r sed -i 's/\bserverComputer\b/runtimeDevice/g'
```

В `AbstractComputerBlockEntity.kt` руками: `var runtimeDevice: RuntimeDeviceImpl?` → `var runtimeDevice: RuntimeDevice?`.

- [ ] **Step 8: Обновить `ServerContext.kt`**

В `:v1_21_1-common` `context/ServerContext.kt`:
- `fun allocateComputerId(): Int` → `fun allocateDeviceId(): Int`. Внутренний вызов `ComputerIdentitySavedData.get(server).allocateComputerId()` НЕ переименовывать (persisted-формат).
- Распространить:
  ```bash
  rg -l '\ballocateComputerId\b' modules/ \
    | xargs -r sed -i 's/\ballocateComputerId(\(\))\?/allocateDeviceId()/g'
  ```
  Подтвердить, что метод **внутри** `ComputerIdentitySavedData.kt` остался:
  ```bash
  rg -n 'fun allocateComputerId' modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ComputerIdentitySavedData.kt
  ```
  Ожидание: один хит. Если sed зацепил — откатить руками.

- [ ] **Step 9: Создать `BlockEntityRuntimeDeviceHost.kt`**

```kotlin
// modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt
package ru.lazyhat.compukterkraft.common.computer.context

import java.util.UUID
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlockEntity
import ru.lazyhat.compukterkraft.common.network.ServerNetworking
import ru.lazyhat.compukterkraft.common.network.message.client.StdoutBytesClientMessage
import ru.lazyhat.compukterkraft.core.computer.runtime.ports.DeviceStateSink
import ru.lazyhat.compukterkraft.core.computer.runtime.ports.GameTimeSource
import ru.lazyhat.compukterkraft.core.computer.runtime.ports.TerminalNetworkBridge

class BlockEntityRuntimeDeviceHost(
    private val blockEntity: AbstractComputerBlockEntity,
) {
    val gameTime = GameTimeSource {
        (blockEntity.level as ServerLevel).gameTime
    }

    val terminalNetwork: TerminalNetworkBridge = object : TerminalNetworkBridge {
        override fun isPlayerOnline(playerUuid: UUID): Boolean =
            ServerContext.server.playerList.getPlayer(playerUuid) != null

        override fun sendStdoutBytes(playerUuid: UUID, containerId: Int, bytes: ByteArray) {
            val player = ServerContext.server.playerList.getPlayer(playerUuid) ?: return
            ServerNetworking.sendToPlayer(StdoutBytesClientMessage(containerId, bytes), player)
        }
    }

    val stateSink = DeviceStateSink { isOn ->
        blockEntity.updateBlockState(isOn)
    }
}
```

> Проверить, что `StdoutBytesClientMessage` и `ServerNetworking.sendToPlayer` существуют по этим FQN — точки вызова из старого `ServerComputer.flushTerminalSessions`. Если пути отличаются — взять из перенесённого `RuntimeDeviceImpl.kt` до правок.

- [ ] **Step 10: Обновить `AbstractComputerBlockEntity.kt`**

Добавить (или отрефакторить существующий) метод `updateBlockState(isOn: Boolean)`. Старая точка, где `RuntimeDeviceImpl` сам писал в `level`, заменяется на `stateSink.onPowerStateChanged(isOn)`. BlockEntity должен экспортировать `updateBlockState(isOn: Boolean)` достаточно открыто, чтобы `BlockEntityRuntimeDeviceHost` мог его звать (тот же пакет — `internal` ок, оба в `:v1_21_1-common`):

```kotlin
internal fun updateBlockState(isOn: Boolean) {
    val newState = if (isOn) ComputerState.ON else ComputerState.OFF
    val lvl = level ?: return
    lvl.setBlock(blockPos, blockState.setValue(ComputerBlock.state, newState), Block.UPDATE_CLIENTS)
}
```

> Тело — копия из старого `ComputerBlockEntity.updateBlockState()` (см. architecture report). Адаптировать.

- [ ] **Step 11: Обновить `ComputerBlockEntity.createComputer(id)`**

```kotlin
override fun createComputer(id: Int): RuntimeDevice {
    val host = BlockEntityRuntimeDeviceHost(this)
    return RuntimeDeviceImpl(
        deviceId = id,
        properties = DeviceProperties(family, label),
        manager = ServerContext.deviceManager,
        gameTime = host.gameTime,
        terminalNetwork = host.terminalNetwork,
        stateSink = host.stateSink,
    )
}
```

В `AbstractComputerBlockEntity` сменить `abstract fun createComputer(id: Int): ServerComputer` (после sed → `RuntimeDeviceImpl`) на:

```kotlin
abstract fun createComputer(id: Int): RuntimeDevice
```

— чтобы тип возврата совпадал с типом поля `runtimeDevice` (Step 7).

- [ ] **Step 12: Итеративная компиляция**

```bash
./gradlew :core:compileKotlin --no-daemon
```
Ожидание: BUILD SUCCESSFUL.

```bash
./gradlew :v1_21_1-common:compileKotlin --no-daemon
```
Ожидание: BUILD SUCCESSFUL. Чинить ошибки по очереди:
- импорт ещё указывает на старый пакет `ServerComputer`/`ComputerProperties` — починить;
- тестовый stub конструирует `ServerComputer(id, level, …)` — заменить на новый конструктор с port-стабами (`GameTimeSource { 0L }`, no-op `TerminalNetworkBridge`, `DeviceStateSink {}`).

```bash
./gradlew test --no-daemon
```
Ожидание: `BUILD SUCCESSFUL`.

- [ ] **Step 13: Commit**

```bash
git add modules/
git commit -m "refactor: move and rename ServerComputer to :core RuntimeDeviceImpl with host ports"
```

---

### Task 6: Architecture-test — `:core/.../runtime/` без `net.minecraft.*`

Регрессионная защита. Тест в test-source `:core`.

**Файлы:**
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/RuntimePackagePurityTest.kt`

- [ ] **Step 1: Тест**

```kotlin
package ru.lazyhat.compukterkraft.core.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimePackagePurityTest {

    @Test
    fun runtimePackageHasNoMinecraftImports() {
        val root = locateRuntimePackage()
        val violations = root.walk()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .withIndex()
                    .filter { (_, line) -> line.trimStart().startsWith("import net.minecraft.") }
                    .map { (idx, line) -> "${file.relativeTo(root)}:${idx + 1}: $line" }
            }
            .toList()

        assertTrue(
            violations.isEmpty(),
            "Found net.minecraft.* imports in :core/.../runtime/:\n${violations.joinToString("\n")}",
        )
    }

    private fun locateRuntimePackage(): File {
        var dir = File(".").canonicalFile
        repeat(6) {
            val candidate = File(
                dir,
                "modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime",
            )
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate :core runtime package from ${File(".").canonicalPath}")
    }
}
```

- [ ] **Step 2: Запустить тест**

```bash
./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.architecture.RuntimePackagePurityTest' --no-daemon
```
Ожидание: PASS.

- [ ] **Step 3: Negative-control grep**

```bash
rg -n '^\s*import net\.minecraft\.' modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/
```
Ожидание: 0.

- [ ] **Step 4: Полные тесты**

`./gradlew test --no-daemon` → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/RuntimePackagePurityTest.kt
git commit -m "test(core): guard :core runtime package against net.minecraft.* leaks"
```

---

### Task 7: Обновить `docs/ARCHITECTURE.md`

**Файлы:**
- Modify: `docs/ARCHITECTURE.md`

- [ ] **Step 1: Прочитать**

Найти секцию про обязанности `:core` и runtime-substrate row (Phase 2a-bis уже упоминала `DeviceWorkspace`, `DeviceIdeHost`).

- [ ] **Step 2: Дописать про `RuntimeDevice` зонт + порты**

```markdown
- **Runtime device abstraction (`core/computer/runtime/`).** `RuntimeDevice` —
  композиция ролей (`Lifecycle`, `Input`, `Screen`, `TerminalSessions`,
  `Metadata`), реализуется `RuntimeDeviceImpl`. World-зависимости приходят
  через три узких порта: `GameTimeSource`, `TerminalNetworkBridge`,
  `DeviceStateSink`. Block-side carrier (`v1_21_1-common/.../BlockEntityRuntimeDeviceHost`)
  собирает порты для `AbstractComputerBlockEntity`; будущие Laptop/Pocket-носители
  предоставят свой набор без изменений в `:core`.
- **Manager (`DeviceManager`)** живёт в `:core`, ключ — `Int deviceId`.
  Identity persistence — в `:v1_21_1-common`'s `ComputerIdentitySavedData`
  (NBT-ключ `_computerID` — часть save-формата, остаётся).
```

Обновить lang.runtime row/таблицу: добавить `RuntimeDevice`, `RuntimeDeviceImpl`, `DeviceManager`, три порта, `PlayerHandle`.

- [ ] **Step 3: Проверить markdown**

`git diff docs/ARCHITECTURE.md` — sanity.

- [ ] **Step 4: Commit**

```bash
git add docs/ARCHITECTURE.md
git commit -m "docs(architecture): document RuntimeDevice umbrella, host ports, and DeviceManager move"
```

---

## Финальная верификация

- [ ] **Final 1: Чистый билд**

`./gradlew clean test --no-daemon` → `BUILD SUCCESSFUL`.

- [ ] **Final 2: Sanity — out-of-scope артефакты остались `Computer*`**

```bash
rg -l 'class\s+(Abstract)?Computer(Block|BlockEntity|Item|Menu|Screen|TerminalScreen|State|ActionServerMessage|InputDispatcher)' modules/v1_21_1/
```
Ожидание: непустой список.

- [ ] **Final 3: Sanity — in-scope артефакты исчезли**

```bash
rg -n '\b(ServerComputer|ComputerManager|ComputerProperties|ComputerEvents|BackgroundComputerVm|ComputerVmSupervisor|ComputerVmLogger|ComputerProgramSupport|ComputerWorkspaceInitializer)\b' modules/
```
Ожидание: 0 в `modules/` (исторические в `docs/` ок).

- [ ] **Final 4: Translation-method audit**

```bash
rg -n '\bTooltip\.computerId\b|allocateComputerId\b' modules/
```
Ожидаемые хиты:
- `AbstractComputerItem.kt` — `Tooltip.computerId(...)` — out-of-scope, цел.
- `ComputerIdentitySavedData.kt` — `fun allocateComputerId()` — out-of-scope (persisted-формат), цел.
- `CompukterLangGenerationSmokeTest.kt` — `Tooltip.computerId("42")` — out-of-scope, цел.

Если что-то ещё — разобраться до merge.

- [ ] **Final 5: NBT-ключ цел**

```bash
rg -n '_computerID' modules/
```
Ожидание: минимум один хит в `ComputerIdentitySavedData.kt`.

- [ ] **Final 6: Architecture-test зелёный**

```bash
./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.architecture.RuntimePackagePurityTest' --no-daemon
```

- [ ] **Final 7: Сверить количество коммитов**

```bash
git log --oneline dev..HEAD
```
Ожидание: 7 коммитов на `phase2b-runtime-device-decoupling`.

---

## Handoff

После приземления всех тасков ветка готова к `git merge --no-ff phase2b-runtime-device-decoupling` в `dev`. Worktree удаляется через `git worktree remove .worktrees/phase2b-runtime-device-decoupling`.
