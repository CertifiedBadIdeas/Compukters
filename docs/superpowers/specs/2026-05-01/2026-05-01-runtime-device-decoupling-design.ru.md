# Phase 2b — Дизайн отвязки Runtime Device

**Статус:** дизайн
**Фаза:** 2b (поглощает 2d) развёртывания Runtime Device / Authoring Station
**Предшественники:** Phase 2a (переименование подложки), Phase 2a-bis (Workspace/IDE rename)

## 1. Мотивация

Сегодня `ServerComputer` — единственное «то, что владеет VM и качает терминал» в коде. Он живёт в `:v1_21_1-common` и привязан в конструкторе к `ServerLevel`. `ComputerManager` — реестр; идентификатор — `Int`, выделяется через `ComputerIdentitySavedData`. Каждый block-entity / item / menu тянется к этому единственному конкретному классу.

Phase 3 введёт второй runtime device (Laptop), который не живёт как `BlockEntity` и не имеет `BlockPos`/`ServerLevel`. До этого VM-владеющая, терминал-качающая абстракция должна:

1. экспортировать стабильный интерфейс в `:core`, который не упоминает `ServerLevel`, `MinecraftServer`, `BlockPos` и иные платформенные типы;
2. получать мировые зависимости через узкие порты, а не через глобальный `ServerContext`;
3. носить имя, не привязанное к конкретному игровому артефакту («Computer» — блок).

Эта фаза делает это, плюс поглощает rename, который раньше был зарезервирован под Phase 2d.

## 2. Решения

Утверждены в брейншторме:

| Вопрос | Решение |
|---|---|
| Объём | Максимум: ввести роли `RuntimeDevice`, host-порты, и **перенести** имплементацию из `:v1_21_1-common` в `:core`. |
| Rename | Оба — `ServerComputer` и `ComputerManager` (Phase 2d поглощается). |
| Форма API | **Композиция** — несколько ролевых интерфейсов + зонтичный объединяющий. |
| Форма host | **Узкие порты**: `GameTimeSource`, `TerminalNetworkBridge`, `DeviceStateSink`. |
| Охват rename | Все `Computer*` внутри `:core/.../vm` и `:v1_21_1-common/.../computer/context`. |
| Out of scope | Block / item / menu / screen / network-message слой остаётся `Computer*`. Translation strings не трогаем. |

## 3. Глоссарий

- **Runtime device** — серверная сущность, владеющая VM, качающая терминал, принимающая input-события и отдающая screen output. Сегодня — единственный block-resident конкретный; завтра — также Laptop, Pocket, Turtle.
- **Host port** — узкий интерфейс, реализуемый world-side carrier'ом (BlockEntity, будущий ItemHost), отдающий device'у одну конкретную мировую способность.
- **Block-side carrier** — BlockEntity, удерживающий runtime device живым пока блок существует в мире; иначе называется host adapter.

## 4. Целевая архитектура

### 4.1 Раскладка модулей

```
modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/
  computer/                                  ← путь "computer/" сохраняется (фс-rename вне scope)
    vm/                                      ← VM-машинерия (rename)
      BackgroundDeviceVm.kt                  ← было BackgroundComputerVm
      DeviceVmSupervisor.kt                  ← было ComputerVmSupervisor
      DeviceVmHandle.kt                      ← было ComputerVmHandle
      DeviceVmLogger.kt                      ← было ComputerVmLogger
      DeviceProgramSupport.kt                ← было ComputerProgramSupport
      DeviceWorkspaceInitializer.kt          ← было ComputerWorkspaceInitializer
      DeviceWorkspaceHost.kt                 ← без изменений с Phase 2a-bis
      WorkspaceDeviceIdeHost.kt              ← без изменений с Phase 2a-bis
    runtime/                                 ← НОВЫЙ пакет
      RuntimeDevice.kt                       ← зонт + ролевые интерфейсы
      RuntimeDeviceImpl.kt                   ← единственная имплементация (было ServerComputer)
      DeviceManager.kt                       ← было ComputerManager
      DeviceProperties.kt                    ← было ComputerProperties
      DeviceEvents.kt                        ← было ComputerEvents
      ports/
        GameTimeSource.kt
        TerminalNetworkBridge.kt
        DeviceStateSink.kt

modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/
  computer/
    context/
      ServerContext.kt                       ← остаётся; теперь хранит DeviceManager
      BlockEntityRuntimeDeviceHost.kt        ← НОВЫЙ: собирает порты для BlockEntity
    block/
      AbstractComputerBlockEntity.kt         ← остаётся, остаётся "Computer" (block layer)
      ComputerBlockEntity.kt                 ← создаёт RuntimeDeviceImpl через host
      ...
    input/
      NetworkComputerInputGateway.kt         ← остаётся "Computer*" (network layer)
```

> Имя директории `core/.../computer/vm/` сохраняем намеренно, чтобы фс-перенос не запутывал rename-диффы. Нормализация путей — будущая чистка.

### 4.2 Ролевые интерфейсы (`:core/.../runtime/RuntimeDevice.kt`)

```kotlin
package ru.lazyhat.compukterkraft.core.computer.runtime

import java.util.UUID
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

/** Жизненный цикл: вкл/выкл, тик, состояние. */
interface RuntimeDeviceLifecycle {
    val deviceId: Int
    val isOn: Boolean
    fun turnOn()
    fun shutdown()
    fun reboot()
    fun serverTick()
    fun close()
}

/** Вход: приём VM-событий. */
interface RuntimeDeviceInput {
    fun queueEvent(event: String, arguments: Array<Any>)
}

/** Экран: чтение последнего snapshot'а (для workbench / legacy-клиентов). */
interface RuntimeDeviceScreen {
    val lastScreenSnapshot: ScreenBufferSnapshot?
}

/** Терминал-сессии: per-player byte-stream attachments. */
interface RuntimeDeviceTerminalSessions {
    fun attachTerminalSession(playerUuid: UUID, containerId: Int, cols: Int, rows: Int)
    fun resizeTerminalSession(playerUuid: UUID, cols: Int, rows: Int)
    fun detachTerminalSession(playerUuid: UUID)
}

/** Метаданные: family/label, проверка доступа. */
interface RuntimeDeviceMetadata {
    val family: DeviceFamily
    var label: String?
    fun checkUsable(player: PlayerHandle): Boolean
}

/** Зонт: всякий сегодняшний runtime device реализует все роли. */
interface RuntimeDevice :
    RuntimeDeviceLifecycle,
    RuntimeDeviceInput,
    RuntimeDeviceScreen,
    RuntimeDeviceTerminalSessions,
    RuntimeDeviceMetadata
```

Будущие Pocket-подобные устройства, которым, например, не нужны терминал-сессии, реализуют только нужные роли; зонт тогда понизится до `typealias` соответствующего пересечения. Вне scope 2b.

### 4.3 Host-порты (`:core/.../runtime/ports/`)

```kotlin
fun interface GameTimeSource {
    fun gameTime(): Long
}

interface TerminalNetworkBridge {
    /** True, если игрок сейчас подключён к серверу. */
    fun isPlayerOnline(playerUuid: UUID): Boolean
    /** Шлёт сырые stdout-байты игроку; no-op если оффлайн. */
    fun sendStdoutBytes(playerUuid: UUID, containerId: Int, bytes: ByteArray)
}

fun interface DeviceStateSink {
    /** Уведомление о смене on/off у device; block-side carrier зеркалит это
     *  в blockstate-property. */
    fun onPowerStateChanged(isOn: Boolean)
}
```

> `DeviceStateSink` несёт абстрактный on/off, НЕ платформенный enum `ComputerState` (он в block-слое). Block-side carrier маппит `Boolean` в `ComputerState`.

### 4.4 `PlayerHandle` (мелкий хелпер)

`RuntimeDeviceMetadata.checkUsable(player)` сейчас принимает `net.minecraft.world.entity.player.Player`. Чтобы держать `:core` чистым от платформенных типов, в `:core` вводится:

```kotlin
interface PlayerHandle {
    val uuid: UUID
    val isStillValid: Boolean
}
```

`ServerContext` / BlockEntity заворачивает ванильный `Player` в это перед вызовом `checkUsable`. Тривиально, но граница чистая.

### 4.5 Конструктор `RuntimeDeviceImpl` (был `ServerComputer`)

```kotlin
class RuntimeDeviceImpl(
    override val deviceId: Int,
    properties: DeviceProperties,
    private val manager: DeviceManager,
    // узкие host-порты:
    private val gameTime: GameTimeSource,
    private val terminalNetwork: TerminalNetworkBridge,
    private val stateSink: DeviceStateSink,
) : RuntimeDevice { /* ... */ }
```

Никакого `ServerLevel`, никакого `MinecraftServer`, никаких `ServerContext`-обращений внутри. Все мировые факты — через три порта.

### 4.6 `DeviceManager` (был `ComputerManager`)

API семантически идентичен; имена параметров и типы возвратов обновлены:

```kotlin
class DeviceManager(private val vmSupervisor: DeviceVmSupervisor) {
    fun get(deviceId: Int): RuntimeDevice?
    fun add(device: RuntimeDevice)
    fun remove(deviceId: Int): RuntimeDevice?
    fun getOrCreateVm(deviceId, profile, labelProvider, logger): BackgroundDeviceVm
    fun removeVm(deviceId, reason)
    fun ensureWorkspaceInitialized(deviceId)
    val workspace: DeviceWorkspace
    val ide: DeviceIdeHost
}
```

Map ключуется по `Int` как сегодня. Никакого reverse-mapping по `BlockPos` не вводим; `TransientPairing` сохраняет свою workaround-логику до Phase 2c.

### 4.7 Block-side carrier (`:v1_21_1-common`)

`BlockEntityRuntimeDeviceHost` — маленький билдер, собирающий три порта для конкретного `AbstractComputerBlockEntity`:

```kotlin
class BlockEntityRuntimeDeviceHost(
    private val blockEntity: AbstractComputerBlockEntity,
) {
    val gameTime = GameTimeSource { (blockEntity.level as ServerLevel).gameTime }
    val terminalNetwork = object : TerminalNetworkBridge {
        override fun isPlayerOnline(uuid: UUID) =
            ServerContext.server.playerList.getPlayer(uuid) != null
        override fun sendStdoutBytes(uuid, containerId, bytes) {
            val p = ServerContext.server.playerList.getPlayer(uuid) ?: return
            ServerNetworking.sendToPlayer(StdoutBytesClientMessage(containerId, bytes), p)
        }
    }
    val stateSink = DeviceStateSink { isOn -> blockEntity.updateBlockState(isOn) }
}
```

`ComputerBlockEntity.createComputer(id)` обновляется на постройку `RuntimeDeviceImpl` через этот host:

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

`AbstractComputerBlockEntity.updateBlockState(isOn: Boolean)` — мелкий рефакторинг: block-слой маппит `Boolean` в существующий enum `ComputerState` и пишет blockstate как сегодня.

### 4.8 ServerContext

Переименования:
- `ServerContext.computerManager` → `ServerContext.deviceManager`
- `ServerContext.allocateComputerId()` → `ServerContext.allocateDeviceId()`
- backing `ComputerIdentitySavedData` сохраняет текущий NBT-ключ (`_computerID`), чтобы не миграть сейвы. Имя класса и методов переименуем; **NBT-ключ — стабильный data-format**.

> Та же осторожность что в Phase 2a-bis: любой идентификатор, разрешающийся в translation-generated метод или persisted save-key, не трогается без явного решения.

## 5. Out-of-scope (остаются "Computer*")

- `ComputerBlock`, `AbstractComputerBlockEntity`, `ComputerBlockEntity`, `NeoForgeComputerBlockEntity`, `ForgeComputerBlockEntity`
- `AbstractComputerItem`, `ComputerItem`
- `ComputerMenu`, `ComputerScreen`, `ComputerTerminalScreen`
- `ComputerState` (block-property enum)
- `ComputerActionServerMessage`, `KeyEventServerMessage`, `MouseEventServerMessage`, `PasteEventComputerMessage` и пр. сетевые сообщения, идентифицирующие "computer"
- `NetworkComputerInputGateway`, `ComputerInputDispatcher` (input-routing block-resident компьютера)
- Все translation-keys (`gui.compukterkraft.tooltip.computer_id` и т.п.) и любые сгенерированные методы (`Tooltip.computerId(...)`)
- `ComputerIdentitySavedData` NBT-ключ (`_computerID`)

Обоснование: эти типы моделируют **именно** in-game блок «Computer» (конкретный игровой артефакт), а не абстрактный runtime device. Будущий `Laptop` получит свои `LaptopBlockEntity`/`LaptopItem`/etc. — общим у них будет именно runtime-абстракция, не block-слой.

## 6. План миграции (обзор; полный план — отдельным документом)

План будет разбит на ~7 коммитов, чтобы дифф читался. Шаги верхнего уровня:

1. Ввести пакет `:core/.../runtime/` с ролевыми интерфейсами, зонтичным `RuntimeDevice`, `PlayerHandle` и тремя портами. **Без перемещений.** Сборка зелёная.
2. Переименовать file-family VM-машинерии в `:core/.../vm/` (`BackgroundComputerVm` → `BackgroundDeviceVm` и т.д.). Чистый rename. Сборка зелёная.
3. Переименовать `ComputerManager` → `DeviceManager`, `ComputerProperties` → `DeviceProperties`, `ComputerEvents` → `DeviceEvents` *на месте* в `:v1_21_1-common`. Сборка зелёная.
4. Перенести и переименовать `ServerComputer` → `RuntimeDeviceImpl` в `:core/.../runtime/`. Заменить прямые `ServerLevel`/`ServerContext.server` обращения вызовами портов. Добавить `BlockEntityRuntimeDeviceHost` в `:v1_21_1-common`. Обновить `ComputerBlockEntity.createComputer`. Сборка + тесты зелёные.
5. Обновить аксессоры `ServerContext` (`computerManager` → `deviceManager`, `allocateComputerId` → `allocateDeviceId`) и распространить.
6. Удалить теперь-неиспользуемые `import net.minecraft.server.level.ServerLevel` из перенесённого файла. Добавить architecture-test, проверяющий, что в `:core/.../runtime/` ноль импортов `net.minecraft.*`.
7. Документация: обновить `docs/ARCHITECTURE.md` — упомянуть зонт `RuntimeDevice`, порты и границу `core ↔ host adapter`.

## 7. Риски и откат

- **Совместимость сейвов**: NBT-ключ `_computerID` сохраняется. Имя файла / dat-ключ `ComputerIdentitySavedData` сохраняется. Проверяется существующим save-load smoke test.
- **Translation-generated методы**: в этой окрестности один идентификатор (`Tooltip.computerId`); задокументирован как out-of-scope (урок Phase 2a-bis). Перед каждым коммитом — `./gradlew test` (включая lang-generation smoke).
- **NeoForge runtime visibility**: новых kotlinx-библиотек не добавляется, регистрационные хелперы (`neoForgeImplementation` / `fabricImplementation`) не трогаются.
- **Гранулярность отката**: каждый шаг — отдельный коммит; revert любого оставляет дерево рабочим.

## 8. Критерии приёмки

- `:core/.../runtime/RuntimeDevice.kt` определяет ролевые + зонтичный интерфейсы; в `:core` нет импортов `net.minecraft.*`.
- `RuntimeDeviceImpl` собирается в `:core` и зависит только от портов + `:core/.../vm/`.
- `BlockEntityRuntimeDeviceHost` существует в `:v1_21_1-common` и является **единственным** местом, где `ServerLevel.gameTime`, `MinecraftServer.playerList` и `ComputerState` enum трогаются от имени runtime device.
- `ServerComputer`, `ComputerManager`, `ComputerProperties`, `ComputerEvents`, `BackgroundComputerVm`, `ComputerVmSupervisor`, `ComputerVmHandle`, `ComputerVmLogger`, `ComputerProgramSupport`, `ComputerWorkspaceInitializer` больше не существуют как идентификаторы в коде.
- Block / item / menu / screen / network-message слой не тронут (sanity: `grep -r 'class .*Computer' modules/v1_21_1` всё ещё находит block-слой).
- `./gradlew clean test` зелёный на `dev` после merge.
- Architecture-test (новый или существующий): файлы в `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/` имеют ноль импортов `net.minecraft.*`.
- `docs/ARCHITECTURE.md` обновлён.

## 9. Что это даёт (preview Phase 3)

После приземления добавление `Laptop` runtime device требует:
- нового host adapter'а (например, `ItemStackRuntimeDeviceHost`), реализующего три порта по-другому (game time от server, stdout-bridge тот же, state-sink пишет в NBT itemstack'а вместо blockstate);
- нового `LaptopItem`, несущего `deviceId` в своём NBT и строящего `RuntimeDeviceImpl` при использовании;
- никаких изменений в `:core`.

Phase 2c (генерализация `TransientPairing`) — мелкий follow-up: меняем `BlockPos` на opaque «device locator» внутри одной map'ы.
