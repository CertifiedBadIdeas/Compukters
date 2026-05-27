# План реализации Workbench Async Sync

> **Для агентов:** ОБЯЗАТЕЛЬНЫЙ SUB-SKILL: superpowers:subagent-driven-development (рекомендован) или
> superpowers:executing-plans для пошагового выполнения. Шаги используют чекбоксы (`- [ ]`).

**Цель:** Заменить ручную модель `Push` / `Pull` workbench'а на непрерывную CRDT-синхронизацию. Сервер держит живой
документ, клиент зеркалит через op-дельты, UI показывает sync-статус и letter-by-letter подсветку. Phase 1 — один
редактор на файл. См. [2026-04-25-workbench-async-sync-design.ru.md](../../specs/2026-04-25/2026-04-25-workbench-async-sync-design.ru.md).

**Архитектура:**

- Чистое CRDT-ядро в `modules/core/.../computer/workbench/crdt/`.
- Чистый дебаунсер в `modules/core/.../computer/workbench/sync/`.
- Три новых сетевых message в `v1_21_1-common/.../workbench/network/{client,server}/`.
- `ServerWorkbench` обзаводится `ConcurrentHashMap<String, ServerCrdtReplica>` по сессиям.
- `WorkbenchStore` переходит на op-based API; `EditorState` меняет `dirty` на `syncStatus` + cursor-as-`AtomId`.
- UI убирает Save / Pull / Push, добавляет sync-индикатор и подсветку sync-glow.

**Tech Stack:** Kotlin/JVM, junit5 для `:core`, `kotlin.test` для `:v1_21_1-common`,
`kotlinx.collections.immutable` для `PersistentList`, `FriendlyByteBuf` для wire encoding.

**Замечание по выполнению:** не делать коммиты, если пользователь явно не просит.

---

## Структура файлов

- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/SiteId.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/AtomId.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/TextRun.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/Op.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/CrdtDocument.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/ClientCrdtReplica.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/ServerCrdtReplica.kt`
- New: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/CrdtDocumentTest.kt`
- New: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/CrdtConvergenceFuzzTest.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/sync/SyncStatus.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/sync/OpOutbox.kt`
- New: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/sync/OpOutboxTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStore.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchEditorSupport.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchEditorViewModelTest.kt`
- New: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../workbench/network/server/WorkbenchOpsServerMessage.kt`
- New: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../workbench/network/client/WorkbenchOpsClientMessage.kt`
- New: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../workbench/network/client/WorkbenchDocumentSnapshotClientMessage.kt`
- New: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/.../workbench/network/WorkbenchOpsCodecTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../workbench/network/server/WorkbenchWorkspaceServerMessage.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../workbench/context/ServerWorkbench.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../workbench/WorkbenchGateways.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../workbench/ui/WorkbenchUiBuilder.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../ui/dsl/elements/CodeEditor.kt` (или где живут типы
  подсветки — уточнить в Task 9)
- Delete (после миграции): legacy WRITE / PULL / PUSH ветки в `WorkbenchWorkspaceServerMessage`

---

## Task 1: CRDT-примитивы — `SiteId`, `AtomId`, `TextRun`, `Op`

**Файлы:**

- New: `modules/core/.../crdt/SiteId.kt`
- New: `modules/core/.../crdt/AtomId.kt`
- New: `modules/core/.../crdt/TextRun.kt`
- New: `modules/core/.../crdt/Op.kt`

- [ ] **Шаг 1: Добавить `kotlinx.collections.immutable` в `:core`**

В [modules/core/build.gradle.kts](modules/core/build.gradle.kts) добавить зависимость:

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.7")
```

Проверить версию в [gradle/libs.versions.toml](gradle/libs.versions.toml); если нет — добавить и сослаться через
catalog.

- [ ] **Шаг 2: Реализовать примитивы**

```kotlin
// SiteId.kt
@JvmInline
value class SiteId(val raw: String) {
    init { require(raw.isNotEmpty() && raw.length <= 32) { "SiteId must be 1..32 chars" } }
    companion object {
        val ServerInit = SiteId("s:i")
        fun player(uuid: java.util.UUID): SiteId = SiteId("p:" + uuid.toString().replace("-", "").take(8))
        fun target(computerId: Int): SiteId = SiteId("t:$computerId")
    }
}

// AtomId.kt — data class(site, clock); Comparable<AtomId> по (site.raw, clock).

// TextRun.kt — data class(id: AtomId, leftId: AtomId?, text: String, deleted: Boolean).
//   text.length > 0, кроме случая deleted = true.

// Op.kt — sealed interface { Insert; Delete }, см. спек.
```

- [ ] **Шаг 3: Прогнать компиляцию `:core`**

Run: `./gradlew :core:compileKotlin`

Ожидание: PASS.

---

## Task 2: `CrdtDocument` — apply / flatten / индекс

**Файлы:**

- New: `modules/core/.../crdt/CrdtDocument.kt`
- New: `modules/core/.../crdt/CrdtDocumentTest.kt`

- [ ] **Шаг 1: Падающие тесты**

```kotlin
class CrdtDocumentTest {
    private val site = SiteId("p:test0001")

    @Test fun emptyDocumentFlattensToEmptyString() { assertEquals("", CrdtDocument.empty().flatten()) }
    @Test fun insertAtStartProducesText() { /* ... */ }
    @Test fun insertInMiddleSplitsRun() { /* ... */ }
    @Test fun deleteWholeRunMarksTombstoned() { /* ... */ }
    @Test fun deleteSpanningRunsTombstonesAll() { /* ... */ }
    @Test fun applyTwiceIsIdempotent() { /* ... */ }
    @Test fun tieBreakLargerSitewinsIfSameLeftId() { /* ... */ }
}
```

- [ ] **Шаг 2: Прогнать `:core` тесты — RED**

Run: `./gradlew :core:test --tests "*CrdtDocumentTest*"`

Ожидание: FAIL (класса нет).

- [ ] **Шаг 3: Реализовать `CrdtDocument`**

```kotlin
data class CrdtDocument(
    val runs: PersistentList<TextRun>,
    val clockBySite: PersistentMap<SiteId, Int>,
    val versionVector: PersistentMap<SiteId, Int>,
) {
    fun apply(op: Op): CrdtDocument
    fun flatten(): String
    companion object { fun empty(): CrdtDocument; fun fromText(text: String, site: SiteId): CrdtDocument }
}
```

Заметки по реализации:

- `apply(Insert)`: найти позицию вставки после `leftId` и concurrent insert'ов, проигравших tie-break; вставить новый
  run; если попадает внутрь существующего — split на левую/правую половины со стабильными атомами.
- `apply(Delete)`: пройтись по run'ам начиная с `targetId`, накопить `length` символов (пропуская deleted),
  пометить каждый затронутый run tombstoned. Если `length` попадает внутрь run'а — сначала split.
- `apply` — no-op, если `op.clock <= clockBySite[op.author]`.
- `flatten()`: конкатенация `text` non-deleted run'ов по порядку.

- [ ] **Шаг 4: Прогнать `:core` тесты — GREEN**

Run: `./gradlew :core:test --tests "*CrdtDocumentTest*"`

Ожидание: PASS.

---

## Task 3: Convergence fuzz test

**Файлы:**

- New: `modules/core/.../crdt/CrdtConvergenceFuzzTest.kt`

- [ ] **Шаг 1: Fuzz-тест**

```kotlin
class CrdtConvergenceFuzzTest {
    @Test fun twoReplicasConvergeRegardlessOfOpOrder() {
        // 500 ops от A + 500 от B, применённые в разном порядке -> одинаковый flatten()
    }
}
```

- [ ] **Шаг 2: Прогон, доводка `CrdtDocument` до сходимости**

Run: `./gradlew :core:test --tests "*CrdtConvergenceFuzzTest*"`

Ожидание: PASS. Если FAIL — выгружать diverging последовательности и фиксить tie-break / split.

---

## Task 4: `ClientCrdtReplica` и `ServerCrdtReplica`

**Файлы:**

- New: `modules/core/.../crdt/ClientCrdtReplica.kt`
- New: `modules/core/.../crdt/ServerCrdtReplica.kt`

- [ ] **Шаг 1: Юнит-тесты на client replica**

Покрыть: `produceInsert(localOffset, text)` отдаёт Op с правильным `leftId` и clock; `produceDelete` аналогично;
`applyAck(clock)` обновляет `lastAckedClock`; `relocateCursor(deletedAtomId)` снапает на правый соседний.

- [ ] **Шаг 2: Реализовать client replica**

```kotlin
class ClientCrdtReplica(val siteId: SiteId, initial: CrdtDocument) {
    var document: CrdtDocument = initial; private set
    var nextClock: Int = (document.clockBySite[siteId] ?: 0) + 1; private set
    var lastAckedClock: Int = document.clockBySite[siteId] ?: 0; private set

    fun produceInsert(charOffset: Int, text: String): Op.Insert
    fun produceDelete(charOffset: Int, length: Int): Op.Delete
    fun applyLocal(op: Op)
    fun applyRemote(op: Op)
    fun applyAck(clock: Int)
    fun cursorAtOffset(offset: Int): Pair<AtomId, Int>
    fun relocateCursor(cursor: Pair<AtomId, Int>): Pair<AtomId, Int>
}
```

- [ ] **Шаг 3: Реализовать server replica**

```kotlin
class ServerCrdtReplica(initial: CrdtDocument) {
    var document: CrdtDocument = initial; private set
    fun apply(ops: List<Op>): ApplyResult
    fun flatten(): String = document.flatten()
    fun versionVector(): Map<SiteId, Int> = document.versionVector
}
```

`ApplyResult(applied: List<Op>, rejected: List<Op>, ackedClockBySite: Map<SiteId, Int>)`. Server отвергает ops с
неизвестными `leftId` / `targetId` — caller (Task 7) отвечает свежим snapshot'ом.

- [ ] **Шаг 4: Прогон тестов**

Run: `./gradlew :core:test --tests "*Replica*"`

Ожидание: PASS.

---

## Task 5: `SyncStatus` и `OpOutbox`

**Файлы:**

- New: `modules/core/.../sync/SyncStatus.kt`
- New: `modules/core/.../sync/OpOutbox.kt`
- New: `modules/core/.../sync/OpOutboxTest.kt`

- [ ] **Шаг 1: Тесты**

```kotlin
class OpOutboxTest {
    @Test fun debouncesEnqueuesIntoSingleSend() { /* virtual time */ }
    @Test fun maxBatchTriggersSyncFlush() { /* enqueue 64 -> immediate */ }
    @Test fun flushNowEmitsAndResetsPending() { /* ... */ }
    @Test fun ackBeyondLastEnqueuedTransitionsToIdle() { /* ... */ }
    @Test fun stalesAfter5sWithoutAck() { /* virtual time */ }
}
```

Использовать `kotlinx.coroutines.test.runTest` + `TestScope.advanceTimeBy`.

- [ ] **Шаг 2: Реализация**

```kotlin
class OpOutbox(
    private val scope: CoroutineScope,
    private val send: suspend (List<Op>) -> Unit,
    private val debounceMs: Long = 50,
    private val maxBatch: Int = 64,
    private val staleAfterMs: Long = 5_000,
) {
    val status: StateFlow<SyncStatus>
    val pendingCount: StateFlow<Int>
    fun enqueue(op: Op)
    suspend fun flushNow()
    fun onAck(clockOfLastSent: Int, ackedClock: Int)
}
```

- [ ] **Шаг 3: Прогон тестов**

Run: `./gradlew :core:test --tests "*OpOutboxTest*"`

Ожидание: PASS.

---

## Task 6: Сетевые message и `FriendlyByteBuf`-кодеки

**Файлы:**

- New: `v1_21_1-common/.../workbench/network/server/WorkbenchOpsServerMessage.kt`
- New: `v1_21_1-common/.../workbench/network/client/WorkbenchOpsClientMessage.kt`
- New: `v1_21_1-common/.../workbench/network/client/WorkbenchDocumentSnapshotClientMessage.kt`
- New: `v1_21_1-common/.../workbench/network/WorkbenchOpsCodecTest.kt`

- [ ] **Шаг 1: Round-trip тесты кодеков**

```kotlin
class WorkbenchOpsCodecTest {
    @Test fun opsServerMessageRoundTrip() { /* encode -> decode -> equals */ }
    @Test fun opsClientMessageRoundTrip() { /* ... */ }
    @Test fun snapshotMessageRoundTrip() { /* run'ы разных авторов с tombstone'ами */ }
}
```

- [ ] **Шаг 2: Реализовать message**

Каждый message — `data class` + `encode(buf: FriendlyByteBuf)` и `companion object decode`. Кодировка:

- `SiteId`: `writeUtf(raw, max = 32)`.
- `AtomId`: `SiteId` + `writeVarInt(clock)`.
- Nullable `AtomId`: `writeBoolean(present)` + payload.
- `Op`: `writeByte(kind)` + поля.
- `List<Op>`: `writeVarInt(size)` + элементы.
- `Map<SiteId, Int>`: `writeVarInt(size)` + пары.
- `List<TextRun>`: `writeVarInt(size)` + элементы (`AtomId`, nullable `AtomId`, `writeUtf(text)`,
  `writeBoolean(deleted)`).

- [ ] **Шаг 3: Прогон тестов**

Run: `./gradlew :v1_21_1-common:test --tests "*WorkbenchOpsCodecTest*"`

Ожидание: PASS.

- [ ] **Шаг 4: Зарегистрировать message в network registry**

Найти регистрацию `WorkbenchWorkspaceServerMessage` и добавить три новых рядом. Серверные хэндлеры — заглушки
(`{ ctx, msg -> TODO("Task 7") }`); реальная логика в Task 7.

---

## Task 7: Серверная интеграция в `ServerWorkbench`

**Файлы:**

- Modify: `v1_21_1-common/.../workbench/context/ServerWorkbench.kt`
- Modify: `v1_21_1-common/.../workbench/network/server/WorkbenchWorkspaceServerMessage.kt`

- [ ] **Шаг 1: Реестр replica на сервере**

```kotlin
class ServerWorkbench(/* ... */) {
    private val replicas = ConcurrentHashMap<String, ServerCrdtReplica>()  // ключ = "$containerId|$path"

    fun openSession(containerId: Int, path: String): WorkbenchDocumentSnapshotClientMessage { /* ... */ }
    fun handleOps(containerId: Int, path: String, ops: List<Op>, sender: SiteId): WorkbenchOpsClientMessage { /* ... */ }
    fun closeSession(containerId: Int, path: String) { /* flatten -> диск -> remove */ }
}
```

- [ ] **Шаг 2: Подключить packet handlers**

Где регистрируются workbench-сообщения, подключить:

- `WorkbenchOpsServerMessage` → `serverWorkbench.handleOps(...)` + ответ.
- Расширить существующий `READ` (если он = «open file»), чтобы он также вызывал `openSession` и присылал
  `WorkbenchDocumentSnapshotClientMessage`. Если переиспользование выходит грязным — добавить отдельный
  `WorkbenchOpenSessionServerMessage`. Решение принять в этом шаге.

- [ ] **Шаг 3: Удалить ветки `WRITE` / `PULL` / `PUSH`**

Удалить enum-варианты и их хэндлеры из
[WorkbenchWorkspaceServerMessage.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/network/server/WorkbenchWorkspaceServerMessage.kt).
Оставить `LIST` / `READ` (или ребейджнутый open) / `RUN` / `REBOOT` / `ATTACH_TERMINAL`.

`RUN`-хэндлер: `closeSession` **не** зовётся (редактор остаётся открыт), но перед запуском — `replica.flatten()` и
запись на диск.

- [ ] **Шаг 4: Компиляция**

Run: `./gradlew :v1_21_1-common:compileKotlin`

Ожидание: PASS.

---

## Task 8: Клиентская интеграция — переподключение `WorkbenchStore`

**Файлы:**

- Modify: `modules/core/.../workbench/WorkbenchStore.kt`
- Modify: `modules/core/.../workbench/WorkbenchEditorSupport.kt`
- Modify: `modules/core/.../workbench/WorkbenchStoreTest.kt`
- Modify: `modules/core/.../workbench/WorkbenchEditorViewModelTest.kt`
- Modify: `v1_21_1-common/.../workbench/WorkbenchGateways.kt`

- [ ] **Шаг 1: Обновить `EditorState`**

```kotlin
data class EditorState(
    val text: String,                        // derived view
    val cursor: AtomId,
    val cursorOffsetWithinRun: Int,
    val syncStatus: SyncStatus,
    val pendingOpCount: Int,
    // ...остальные поля кроме `dirty`
)
```

`(line, column)` — derived property от `text` + `flatCursorOffset`.

- [ ] **Шаг 2: Переписать `WorkbenchStoreTest`**

Удалить:

- `saveClearsDirtyFlag`
- `pullFromTarget*`
- `pushToTarget*`

Добавить:

```kotlin
@Test fun applyLocalEditAddsToOutbox()
@Test fun applyAckClearsPendingCount()
@Test fun applyRemoteOpsUpdatesEditorText()
@Test fun flushAndRunWaitsForSync()
@Test fun cursorMovesWhenRemoteInsertHappensLeft()
```

Использовать fake gateway с `sendOps`.

- [ ] **Шаг 3: Обновить `WorkbenchEditorViewModelTest`**

`onCharTyped` теперь зовёт `store.applyLocalEdit(LocalEdit.Insert(offset, text))`, а не мутирует `text`.

- [ ] **Шаг 4: `:core` тесты — RED**

Run: `./gradlew :core:test`

Ожидание: FAIL (compile + missing API).

- [ ] **Шаг 5: Реализовать новый API `WorkbenchStore`**

```kotlin
class WorkbenchStore(/* deps */) {
    private val replica: ClientCrdtReplica = /* инициализируется на snapshot */
    private val outbox: OpOutbox

    fun applyLocalEdit(edit: LocalEdit) { /* produce -> applyLocal -> outbox.enqueue -> recompute state */ }
    fun applyRemoteOps(ops: List<Op>) { /* applyRemote -> relocate cursor -> recompute */ }
    fun applyAck(ackedClock: Int) { /* replica.applyAck + outbox.onAck */ }
    suspend fun flushAndRun(timeoutMs: Long = 3_000L): RunResult
    fun onSnapshot(snapshot: WorkbenchDocumentSnapshot)
}

sealed interface LocalEdit {
    data class Insert(val offset: Int, val text: String) : LocalEdit
    data class Delete(val offset: Int, val length: Int) : LocalEdit
}
```

Удалить `pullFromTarget`, `pushToTarget`, публичный `saveDocument`.

- [ ] **Шаг 6: Обновить `WorkbenchGateways`**

Заменить `write/pull/push` на `sendOps(containerId, path, ops)` и `sessionOpen(containerId, path)`.

- [ ] **Шаг 7: `:core` + `:v1_21_1-common` тесты — GREEN**

Run: `./gradlew :core:test :v1_21_1-common:test`

Ожидание: PASS.

---

## Task 9: UI — sync-индикатор, удалённые кнопки, sync-glow подсветка

**Файлы:**

- Modify: `v1_21_1-common/.../workbench/ui/WorkbenchUiBuilder.kt`
- Modify: `v1_21_1-common/.../ui/dsl/elements/CodeEditor.kt` (или родственные — уточнить в шаге 1)

- [ ] **Шаг 1: Найти типы подсветки CodeEditor**

```bash
grep -rn "CodeEditorHighlight\|HighlightType\|class CodeEditor" modules/v1_21_1/v1_21_1-common/src/main/kotlin/
```

Найти, где объявляются и рисуются range-подсветки. Добавить вариант `SyncingRun(start: Int, end: Int, alpha: Float)`.

- [ ] **Шаг 2: Рисовать sync glow**

В рендере — мягкий цветной фон под run'ами в `SyncingRun` диапазонах. Анимация alpha 1.0 → 0.0 за 300ms после ack.
Store отдаёт `Flow<List<SyncingRun>>`, выведенный из in-flight ops.

- [ ] **Шаг 3: Удалить Save / Pull / Push и dirty-маркер**

В [WorkbenchUiBuilder.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/ui/WorkbenchUiBuilder.kt):

- Удалить три тулбар-кнопки.
- Заменить `* `-префикс в статусбаре на виджет `syncStatusIndicator(syncStatus, pendingCount)` —
  `✓` (Idle) / `⋯` (Pending) / `↻` (Syncing) / `⚠` (Stale), рядом — `pendingCount`, если > 0.

- [ ] **Шаг 4: Smoke-тест в dev runtime**

Run: `./gradlew :v1_21_1-neoforge:runClient`

Открыть workbench, печатать. Убедиться: индикатор Idle → Pending → Syncing → Idle за ~150ms; sync glow появляется
под напечатанными буквами и затухает на ack. Симулировать обрыв сети (убить серверный поток) — увидеть Stale.

---

## Task 10: Интеграционный тест и финальная зачистка

**Файлы:**

- New: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/.../workbench/WorkbenchSyncIntegrationTest.kt`

- [ ] **Шаг 1: Интеграционный тест**

In-memory `ServerWorkbench` + `WorkbenchStore`, соединённые callback-gateway. Открыть файл, применить 100 случайных
правок, `flushAndRun()`. Проверить: серверный `replica.flatten()` == клиентский `editorState.text`; `flushAndRun`
завершается за ≤ 3с; `RUN` отправлен только после последнего ack.

- [ ] **Шаг 2: Зачистка мёртвого кода**

```bash
grep -rn "pullFromTarget\|pushToTarget\|saveDocument\|dirtyLocal\|dirtyRemote\|WorkbenchAction\.WRITE\|WorkbenchAction\.PULL\|WorkbenchAction\.PUSH" modules/
```

Удалить все недостижимые матчи. `./gradlew build` — убедиться, что ничего не отвалилось.

- [ ] **Шаг 3: Финальный прогон**

Run: `./gradlew build`

Ожидание: PASS.

- [ ] **Шаг 4: Ручная проверка**

Запустить dev-клиент, отредактировать файл, нажать RUN. Скрипт должен видеть последние правки без единого клика по
Save / Pull / Push (которых уже нет).

---

## Заметки

- **Phase 2 явно отложена:** multi-player presence, target-as-collaborator (Myers diff), GC tombstones и
  disk-persistence CRDT — отдельный план.
- **Performance hotspot:** если apply на больших файлах окажется медленным — заменить `runs: PersistentList` на
  skiplist или агрессивнее держать `Map<AtomId, Int>`.
- **Без feature flag:** миграция одним PR; старые клиенты не подключатся.
