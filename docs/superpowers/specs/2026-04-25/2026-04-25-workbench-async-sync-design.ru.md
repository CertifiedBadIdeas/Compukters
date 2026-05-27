# Дизайн асинхронной синхронизации Workbench

## Цель

Заменить ручную модель `Push` / `Pull` редактора workbench на непрерывную асинхронную CRDT-синхронизацию между
редактором игрока и файловой системой целевого компьютера. Сервер хранит авторитетный «живой» документ, клиент
зеркалит его, дельты операций летят в обе стороны с дебаунсом, чтобы не нагружать сеть. UI показывает индикатор
состояния синхронизации и подсветку «по букве», когда применяются удалённые операции.

Этот спек охватывает только **Phase 1**: один редактирующий игрок на файл, целевой компьютер — пассивный коллаборатор.
Phase 2 (multi-player presence, скрипт как живой коллаборатор) обозначен в конце и будет иметь отдельный дизайн.

## Текущий контекст

- `WorkbenchStore` (`modules/core/.../workbench/WorkbenchStore.kt`) держит клиентский editor state и предоставляет
  `pullFromTarget()`, `pushToTarget()`, `saveDocument()` как пользовательские действия.
- `EditorState` несёт флаг `dirty: Boolean` и координаты курсора `(line, column)`.
- `WorkbenchUiBuilder` рисует кнопки `Save` / `Pull` / `Push` и маркер `* ` в статусбаре.
- Сеть: `WorkbenchWorkspaceServerMessage` с action enum (`LIST` / `READ` / `WRITE` / `PULL` / `PUSH` / `RUN` /
  `REBOOT` / `ATTACH_TERMINAL`).
- Сервер: `ServerWorkbench` (`v1_21_1-common/.../workbench/context/ServerWorkbench.kt`) хранит документы в
  `ComputerWorkspace` поверх `ComputerWorkspaceHost`. Живого документа в памяти нет — каждый `READ` читает с диска,
  каждый `WRITE` пишет на диск.
- `RUN` на сервере читает файлы с диска, поэтому несохранённые правки теряются.
- Никаких фоновых таймеров — вся синхронизация триггерится пользователем.

## Дизайн

### Источник правды

Авторитетный документ живёт **в памяти на сервере** как CRDT (`ServerCrdtReplica`), пока открыта хотя бы одна
редакторская сессия по этому файлу. На диске остаётся тот же plain-text файл, что и сейчас; CRDT строится при
открытии и flatten'ится обратно при закрытии последнего редактора. Никакого CRDT-состояния на диске нет.

Это сознательный размен: мы избегаем миграций дискового формата, GC tombstones между перезапусками и сложной
recovery-логики. Цена — re-atomize при каждом открытии; для типичных скриптов (≤ десятки KB) это копейки.

### CRDT-модель: RGA-with-runs

Используем Replicated Growable Array (RGA) — известный list CRDT — с оптимизацией: подряд идущие символы одного
автора группируются в `TextRun`. Run'ы сплитятся, когда remote op вставляет/удаляет внутри них.

```kotlin
@JvmInline
value class SiteId(val raw: String)            // "s:i" | "p:<8charUuid>" | "t:<computerId>"

data class AtomId(val site: SiteId, val clock: Int)

data class TextRun(
    val id: AtomId,
    val leftId: AtomId?,        // null = вставлен в начало документа
    val text: String,
    val deleted: Boolean,        // tombstone
)

sealed interface Op {
    val author: SiteId
    val clock: Int

    data class Insert(
        override val author: SiteId,
        override val clock: Int,
        val leftId: AtomId?,
        val text: String,
    ) : Op

    data class Delete(
        override val author: SiteId,
        override val clock: Int,
        val targetId: AtomId,
        val length: Int,         // сколько символов удалить начиная с targetId
    ) : Op
}
```

Категории site:

- `s:i` — server-init replica для атомизации при первой загрузке файла с диска.
- `p:<uuid8>` — сессия игрока-редактора.
- `t:<computerId>` — целевой компьютер (Phase 2; зарезервировано).

**Tie-break при одинаковом `leftId`.** Если два insert'а имеют одинаковый `leftId`, тот, у которого
`(author.raw, clock)` лексикографически больше, размещается ближе к `leftId` (применяется первым при сканировании
слева направо). Тотальный, детерминированный, зависит только от identity опа.

**Инварианты сходимости** (проверяются property tests):

- Коммутативность неконфликтующих ops.
- Идемпотентность: повторное применение того же op'а — no-op.
- Eventual consistency: две replica с одинаковым набором применённых ops дают одинаковый `flatten()`.

### Client/Server Replicas

```kotlin
class CrdtDocument {
    val runs: PersistentList<TextRun>           // kotlinx.collections.immutable
    val clockBySite: Map<SiteId, Int>           // последний clock на site
    val versionVector: Map<SiteId, Int>         // для bootstrap snapshot
    private val runIndexById: Map<AtomId, Int>  // обновляется при каждом split

    fun apply(op: Op): CrdtDocument
    fun flatten(): String
}

class ClientCrdtReplica(siteId: SiteId, initial: CrdtDocument)
class ServerCrdtReplica(initial: CrdtDocument)
```

`ClientCrdtReplica` владеет next-clock'ом своего site, генерирует ops для локальных правок, отслеживает acked-clock
для гейта `flushAndRun`. `ServerCrdtReplica` валидирует causality (`leftId` и `targetId` должны быть известны),
применяет ops, рассылает ack.

`PersistentList<TextRun>` для `runs` — нужна структура с дешёвым structural sharing под высокочастотные правки.
`pods4k` immutable arrays зарезервированы под вспомогательные структуры с примитивными ключами (например, version
vectors), где избавление от боксинга выгодно; для `runs` в Phase 1 не используем.

### Сетевой протокол

Три новых message, заменяющих `WRITE` / `PULL` / `PUSH`:

```kotlin
// C → S
class WorkbenchOpsServerMessage(
    val containerId: Int,
    val path: String,
    val ops: List<Op>,
)

// S → C, ack своих ops + relay чужих
class WorkbenchOpsClientMessage(
    val containerId: Int,
    val path: String,
    val ops: List<Op>,           // ops, которых клиент ещё не видел
    val ackedClock: Int,         // максимальный clock от этого клиента, применённый сервером
)

// S → C, отправляется при открытии сессии
class WorkbenchDocumentSnapshotClientMessage(
    val containerId: Int,
    val path: String,
    val initialRuns: List<TextRun>,
    val versionVector: Map<SiteId, Int>,
)
```

`Op` сериализуется через `FriendlyByteBuf` в ~30 байт на op (плюс длина `Insert.text`). Site ID — компактные строки
(`"s:i"`, `"p:abcd1234"`, `"t:42"`).

### OpOutbox: клиентский дебаунсер

```kotlin
class OpOutbox(
    private val send: (List<Op>) -> Unit,
    private val debounceMs: Long = 50,
    private val maxBatch: Int = 64,
) {
    fun enqueue(op: Op)
    fun flushNow()
    val pendingCount: Int
}
```

Поведение:

- Каждый `enqueue` шедулит flush через `debounceMs`; если `pendingCount` достиг `maxBatch` — flush синхронно.
- `flushNow()` вызывается из `flushAndRun` и `closeFile`.

### Sync Status

```kotlin
enum class SyncStatus { Idle, Pending, Syncing, Stale }
```

State machine:

- **Idle** — нет неподтверждённых ops.
- **Pending** — локальные правки в outbox, ещё не отправлены.
- **Syncing** — ops отправлены, ждём ack.
- **Stale** — ack не пришёл за 5 секунд после `Syncing`. Редактор продолжает работать; на следующем ack возвращаемся
  в `Idle`.

### Серверная обработка

На `WorkbenchOpsServerMessage`:

1. Найти `ServerCrdtReplica` для `(containerId, path)`. Если нет — игнор (сессия не открыта).
2. Для каждого op валидировать causality (`leftId` / `targetId` существуют, clock строго возрастает по site).
   При нарушении — drop сообщения и re-snapshot клиенту.
3. Применить ops, обновить version vector.
4. Посчитать `ackedClock` для отправителя и список ops, которых ещё не видели другие подключённые редакторы.
5. Отправить `WorkbenchOpsClientMessage` отправителю с `ackedClock` и остальным редакторам с relay (Phase 2 — в Phase
   1 редактор один).

При открытии сессии: построить `ServerCrdtReplica` с диска, если ещё не в памяти; отправить
`WorkbenchDocumentSnapshotClientMessage`.

При закрытии (последний редактор ушёл): `flatten()` на диск, drop `ServerCrdtReplica`.

### RUN

Кнопка `RUN` зовёт `store.flushAndRun(): Job`:

1. `outbox.flushNow()`.
2. Ждём `ackedClock >= lastEnqueuedClock` (или таймаут 3с → confirm-диалог).
3. Шлём обычный `WorkbenchWorkspaceServerMessage(action = RUN, path)`.
4. Сервер материализует CRDT в файл, запускает как сейчас.

### Store / EditorState / UI

`WorkbenchStore` — изменения API:

- **Удалено**: `pullFromTarget()`, `pushToTarget()`, публичный `saveDocument()`.
- **Добавлено**: `applyLocalEdit(LocalEdit)`, `applyRemoteOps(List<Op>)`, `applyAck(ackedClock: Int)`,
  `flushAndRun(): Job`.
- **Внутреннее**: держит `ClientCrdtReplica` и `OpOutbox`.

`EditorState` — изменения:

- **Удалено**: `dirty: Boolean`.
- **Добавлено**: `syncStatus: SyncStatus`, `pendingOpCount: Int`, `cursor: AtomId`, `cursorOffsetWithinRun: Int`.
- `(line, column)` становятся производным view над курсором + материализованным текстом.

`WorkbenchUiBuilder` — изменения:

- **Удалено**: кнопки `Save`, `Pull`, `Push`; маркер `* ` в статусбаре.
- **Добавлено**: `syncStatusIndicator(syncStatus, pendingCount)` — иконки ✓ / ⋯ / ↻ / ⚠.
- В `CodeEditor` появляется тип подсветки `SyncingRun(start, end, alpha)` — letter-by-letter sync glow, alpha
  затухает на ack.

### Границы модулей

- `modules/core/.../computer/workbench/crdt/` — pure Kotlin, JVM-тестируемо: `CrdtDocument`, `Op`, `TextRun`,
  `AtomId`, `SiteId`, `ClientCrdtReplica`, `ServerCrdtReplica`.
- `modules/core/.../computer/workbench/sync/` — pure: `OpOutbox`, `SyncStatus`.
- `modules/core/.../computer/workbench/WorkbenchStore.kt` — оркестратор.
- `modules/v1_21_1/v1_21_1-common/.../workbench/network/{client,server}/Workbench{Ops,DocumentSnapshot}*Message.kt` —
  новые message.
- `modules/v1_21_1/v1_21_1-common/.../workbench/context/ServerWorkbench.kt` — добавляется
  `ConcurrentHashMap<String, ServerCrdtReplica>`, удаляются dirty-флаги.

## Тестирование

**`crdt/`** (JVM, junit5):

- `CrdtDocumentTest` — insert в начало/середину/конец, delete через несколько run'ов, идемпотентность, two-replica
  fuzz (1000 случайных правок в разном порядке → одинаковый `flatten()`), tombstone semantics.

**`sync/`** (JVM, junit5):

- `OpOutboxTest` — debounce flush, max-batch синхронный flush, ack очищает `pendingCount`, stale timeout.

**`WorkbenchStoreTest`** — расширяется:

- `applyLocalEditAddsToOutbox`
- `applyAckClearsPendingCount`
- `applyRemoteOpsUpdatesEditorText`
- `flushAndRunWaitsForSync`
- `cursorMovesWhenRemoteInsertHappensLeft`

**`WorkbenchEditorViewModelTest`** — `onCharTyped` больше не мутирует `EditorState.text` напрямую; тест проверяет, что
вызван `store.applyLocalEdit`.

**Integration (Phase 1):** один игрок + один target. Открыли файл → 100 правок → `RUN`. Проверки: финальный текст на
сервере = ожидаемому; `RUN` не стартует, пока последний op не получил ack.

## Миграция

- Дисковый формат не меняется.
- В сетевом протоколе появляются три новых message; actions `WRITE` / `PULL` / `PUSH` удаляются из
  `WorkbenchWorkspaceServerMessage`. Старые клиенты не подключатся к новому серверу — ок до 1.0.
- Старые `WorkbenchStoreTest` тесты на `saveClearsDirtyFlag`, `pullFrom*`, `pushTo*` переписываются под новую
  семантику.
- Миграция одним коммитом — без feature flag.

## Риски

1. **Производительность apply'я ops.** Plain `PersistentList<TextRun>` с линейным `findRun(leftId)` — O(N) на op.
   На 5000-строчном файле это уже ~ms на op. **Митигация:** держим `Map<AtomId, RunIndex>` внутри `CrdtDocument`,
   обновляемый на каждом split. Если профайлинг покажет — мигрировать на skiplist / finger-tree.

2. **Сетевой backpressure.** Очень быстрый набор может переполнить outbox. **Митигация:** `MAX_BATCH = 64` триггерит
   синхронный flush, минуя debounce.

3. **CRDT-сессия на сервере при крашe клиента.** В Phase 1 редактор один. На disconnect делаем `flatten()` на диск
   и сбрасываем replica — фиксируется состояние last-acked. Неподтверждённые ops теряются; в single-editor режиме
   это допустимо, в Phase 2 нужен retain policy.

4. **Стабильность курсора при удалении remote'ом.** Если remote op tombstone'ит run, на котором стоит курсор —
   переезжаем на ближайший правый non-deleted run через `ClientCrdtReplica.relocateCursor()`. Документировано и
   покрыто тестом.

5. **RUN deadlock на ack timeout.** В `flushAndRun` таймаут 3с. По истечении — confirm-диалог: «не удалось
   синхронизировать, всё равно запустить?».

6. **Суррогатные пары.** В атомах хранится `text: String`, не `Char`. Insert на границе суррогатной пары запрещён на
   уровне editor input. Тесты покрывают BMP и non-BMP roundtrip.

## Вне scope (Phase 2 — отдельный дизайн)

- Несколько игроков, редактирующих один файл (broadcast op'ов всем подписчикам).
- Presence / awareness — цветные курсоры удалённых игроков, Live Share style.
- Скрипт как живой коллаборатор: `fs.write` диффится через Myers и эмитится как ops под `t:<computerId>`.
- GC tombstones внутри активной сессии.
- Disk-side persistence CRDT-state (для recovery после краша сервера).
