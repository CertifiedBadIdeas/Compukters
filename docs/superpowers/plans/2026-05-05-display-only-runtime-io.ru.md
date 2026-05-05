# План реализации Display-only Runtime I/O

> **Для agentic workers:** ОБЯЗАТЕЛЬНЫЙ SUB-SKILL: используйте superpowers:subagent-driven-development (рекомендуется) или superpowers:executing-plans для выполнения плана по задачам. Шаги используют checkbox (`- [ ]`) для трекинга.

**Цель:** Удалить runtime terminal/stdout client-server broadcasting и сделать framebuffer display frames единственным server-to-client output для runtime UI.

**Архитектура:** Runtime UI становится display-only: client отправляет input events, server отправляет display frame deltas. Legacy terminal network path удаляется первым, ROM `terminal.ck` перестаёт использовать `stdout::write`, а VM-side stdout/terminal APIs остаются только как краткоживущая совместимость до follow-up cleanup language-level terminal builtins.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, Architectury/NeoForge common code, CKL ROM scripts, Kotlin test.

---

## Worktree и baseline

Implementation worktree уже создан и проверен:

- Worktree: `/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/display-only-runtime-io`
- Branch: `feature/display-only-runtime-io`
- Baseline command: `./gradlew test`
- Baseline result: `BUILD SUCCESSFUL` with `31 actionable tasks`.

Все команды ниже выполнять из `/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/display-only-runtime-io`.

## Scope boundary

Этот план реализует первый shippable staged cleanup:

1. убрать runtime terminal/stdout network transport;
2. перевести computer screen на `ClientDisplayBuffer` вместо `ClientTerminalBuffer`;
3. обновить ROM terminal, чтобы interactive input рендерился через display dirty rows и visible output не писался в `stdout`;
4. убрать runtime broadcaster consumers, которые существовали только для terminal network fanout.

Полное language/API удаление `terminal::*`, `stdout::*`, `DeviceStdioApi` и широкие compiler/runtime test rewrites делать отдельным follow-up планом после проверки display-only runtime UI. Это сохраняет утверждённое финальное направление, но оставляет branch reviewable.

## File map

### Core runtime

- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt` — убрать `TerminalNetworkBridge`, terminal sessions и flush.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDevice.kt` — убрать `RuntimeDeviceTerminalSessions`.
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/TerminalNetworkBridge.kt`.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ComputerStdioBroadcaster.kt` — убрать `Consumer`/fanout/replay.

### Common network/client UI

- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/StdoutBytesClientMessage.kt`.
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/AttachTerminalServerMessage.kt`.
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/ResizeTerminalServerMessage.kt`.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt` — убрать registrations `attach_terminal`, `resize_terminal`, `stdout_bytes`; IDs `7`, `8`, `14` оставить documented unused.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt` — убрать `handleStdoutBytes()`.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt` — убрать stdout routing.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt` — убрать `terminalNetwork` adapter.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt` — убрать `ClientTerminalBuffer`/stdout APIs.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/ComputerMenu.kt` — убрать `handleStdoutBytes()` и terminal docs.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt` — рендерить `ClientDisplayBuffer`, оставить input и display attach/resize/detach.

### ROM

- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck` — убрать `stdout::write`, добавить dirty-row rendering current input line.

### Tests/docs

- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImplDisplayTest.kt`.
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ComputerStdioBroadcasterTest.kt`.
- Modify: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/network/DisplayMessageCodecTest.kt`.
- Modify: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/MenuSideClientTest.kt`.
- Create: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreenArchitectureTest.kt`.
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`.
- Modify: `docs/ARCHITECTURE.md`.

---

### Task 1: Удалить terminal/stdout packet registration

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/network/DisplayMessageCodecTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt`
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/StdoutBytesClientMessage.kt`
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/AttachTerminalServerMessage.kt`
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/ResizeTerminalServerMessage.kt`

- [ ] **Step 1: Добавить failing registry test**

В `DisplayMessageCodecTest.kt` добавить imports:

```kotlin
import ru.lazyhat.compukterkraft.common.network.MessageTypeImpl
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import kotlin.test.assertFalse
```

Добавить test method:

```kotlin
    @Test
    fun registryDoesNotExposeTerminalStdoutMessages() {
        val serverboundIds =
            NetworkMessages.serverbound
                .map { (it as MessageTypeImpl<*>).id }
                .toSet()
        val clientboundIds =
            NetworkMessages.clientbound
                .map { (it as MessageTypeImpl<*>).id }
                .toSet()

        assertFalse(7 in serverboundIds, "attach_terminal id 7 must stay removed")
        assertFalse(8 in serverboundIds, "resize_terminal id 8 must stay removed")
        assertFalse(14 in clientboundIds, "stdout_bytes id 14 must stay removed")
    }
```

- [ ] **Step 2: Запустить focused test и увидеть failure**

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.computer.network.DisplayMessageCodecTest`

Expected: FAIL, потому что IDs `7`, `8`, `14` ещё зарегистрированы.

- [ ] **Step 3: Убрать terminal packet registrations**

В `NetworkMessages.kt` убрать imports/properties `ATTACH_TERMINAL`, `RESIZE_TERMINAL`, `STDOUT_BYTES`; protocol table обновить так, чтобы IDs `7`, `8`, `14` были unused/reserved removed terminal/stdout packet IDs.

- [ ] **Step 4: Удалить packet classes**

Удалить:

- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/StdoutBytesClientMessage.kt`
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/AttachTerminalServerMessage.kt`
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/ResizeTerminalServerMessage.kt`

- [ ] **Step 5: Запустить focused test и увидеть pass**

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.computer.network.DisplayMessageCodecTest`

Expected: PASS.

- [ ] **Step 6: Commit**

Run: `git add modules/v1_21_1/v1_21_1-common/src/main/kotlin modules/v1_21_1/v1_21_1-common/src/test/kotlin && git commit -m "refactor: remove terminal stdout packet registration"`

---

### Task 2: Удалить runtime terminal sessions из core

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImplDisplayTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDevice.kt`
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/TerminalNetworkBridge.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt`

- [ ] **Step 1: Обновить display test, чтобы terminal bridge не требовался**

В `RuntimeDeviceImplDisplayTest.kt` убрать import `TerminalNetworkBridge`, object `NoopTerminalNetworkBridge`, и аргумент `terminalNetwork = NoopTerminalNetworkBridge,` из `RuntimeDeviceImpl(...)`.

- [ ] **Step 2: Запустить focused test и увидеть compile failure**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceImplDisplayTest`

Expected: FAIL compile-time, потому что `RuntimeDeviceImpl` ещё требует `terminalNetwork`.

- [ ] **Step 3: Убрать terminal sessions из `RuntimeDevice.kt`**

Удалить весь `RuntimeDeviceTerminalSessions`. `RuntimeDevice` должен наследоваться так:

```kotlin
interface RuntimeDevice :
    RuntimeDeviceLifecycle,
    RuntimeDeviceInput,
    RuntimeDeviceScreen,
    RuntimeDeviceDisplaySessions,
    RuntimeDeviceMetadata
```

`RuntimeDeviceScreen` оставить для этой задачи; workbench snapshot cleanup отдельно.

- [ ] **Step 4: Убрать terminal bridge/session state из `RuntimeDeviceImpl.kt`**

В `RuntimeDeviceImpl.kt` удалить terminal imports/constructor parameter/session data/map, `rebindTerminalConsumers(handle)`, terminal cleanup in `close()`, `flushTerminalSessions()` calls, and methods `attachTerminalSession()`, `bindConsumer()`, `rebindTerminalConsumers()`, `resizeTerminalSession()`, `detachTerminalSession()`, `flushTerminalSessions()`.

Null handle branch in `serverTick()` заменить на:

```kotlin
        val handle = vmHandle ?: return
```

- [ ] **Step 5: Убрать common host terminal adapter**

В `BlockEntityRuntimeDeviceHost.kt` удалить imports `StdoutBytesClientMessage`, `TerminalNetworkBridge` и property `terminalNetwork`; `displayNetwork` оставить.

- [ ] **Step 6: Удалить terminal bridge port**

Удалить `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/TerminalNetworkBridge.kt`.

- [ ] **Step 7: Исправить constructor call sites**

Search: `rg "terminalNetwork =|RuntimeDeviceImpl\(" modules` и убрать terminal argument из всех call sites.

- [ ] **Step 8: Запустить core focused test**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceImplDisplayTest`

Expected: PASS.

- [ ] **Step 9: Запустить common tests**

Run: `./gradlew :v1_21_1-common:test`

Expected: PASS.

- [ ] **Step 10: Commit**

Run: `git add modules/core modules/v1_21_1/v1_21_1-common && git commit -m "refactor: remove runtime terminal sessions"`

---

### Task 3: Удалить client terminal buffer из computer menus

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/MenuSideClientTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/ComputerMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt`
- Optional delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientTerminalBuffer.kt`

- [ ] **Step 1: Заменить terminal-buffer test на display-buffer test**

В `MenuSideClientTest.kt` удалить `ClientTerminalBuffer` test и добавить:

```kotlin
    @Test
    fun clientSideAttachAndDetachDisplayBuffer() {
        val client = MenuSide.Client()

        assertNull(client.displayBuffer)

        val buffer = ClientDisplayBuffer(displayId = 1, width = 64, height = 48)
        client.attachDisplayBuffer(buffer)

        assertSame(buffer, client.displayBuffer)

        client.detachDisplayBuffer()
        assertNull(client.displayBuffer)
    }
```

Imports: `ClientDisplayBuffer`, `assertSame`.

- [ ] **Step 2: Запустить focused test**

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.computer.menu.MenuSideClientTest`

Expected: PASS сейчас или compile failure после удаления old APIs; продолжить.

- [ ] **Step 3: Убрать menu stdout APIs**

В `AbstractComputerMenu.kt` убрать `ClientTerminalBuffer`, `terminalBuffer`, `attachTerminalBuffer()`, `detachTerminalBuffer()`, `applyStdoutBytes()`, `handleStdoutBytes()`. В `ComputerMenu.kt` убрать `handleStdoutBytes()` и terminal-buffer docs. В `ClientNetworkContext.kt`/`ClientNetworkContextImpl.kt` убрать `handleStdoutBytes(containerId, bytes)`.

- [ ] **Step 4: Удалить `ClientTerminalBuffer.kt`, если unused**

Run: `rg "ClientTerminalBuffer|handleStdoutBytes|applyStdoutBytes|terminalBuffer" modules/v1_21_1/v1_21_1-common/src/main/kotlin modules/v1_21_1/v1_21_1-common/src/test/kotlin`.

Если остались refs в `ComputerTerminalScreen.kt`, удалить file после Task 4.

- [ ] **Step 5: Run common tests**

Run: `./gradlew :v1_21_1-common:test`

Expected: PASS after Task 4 if screen still fails here; иначе PASS now.

- [ ] **Step 6: Commit**

Run: `git add modules/v1_21_1/v1_21_1-common && git commit -m "refactor: remove client stdout menu buffer"`

---

### Task 4: Сделать computer screen display-only

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreenArchitectureTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt`
- Delete when unused: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/TerminalSurfaceBridge.kt`

- [ ] **Step 1: Добавить source-level architecture test**

Create `ComputerTerminalScreenArchitectureTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.common.terminal.screen

import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerTerminalScreenArchitectureTest {
    private val source =
        Paths
            .get("src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt")
            .readText()

    @Test
    fun computerScreenUsesDisplayBufferNotTerminalBuffer() {
        assertFalse(source.contains("ClientTerminalBuffer"))
        assertFalse(source.contains("AttachTerminalServerMessage"))
        assertFalse(source.contains("ResizeTerminalServerMessage"))
        assertFalse(source.contains("terminalSurface("))
        assertTrue(source.contains("ClientDisplayBuffer"))
        assertTrue(source.contains("DisplayAttachServerMessage"))
        assertTrue(source.contains("DisplayResizeServerMessage"))
    }
}
```

- [ ] **Step 2: Run focused test and verify failure**

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.terminal.screen.ComputerTerminalScreenArchitectureTest`

Expected: FAIL.

- [ ] **Step 3: Убрать terminal session lifecycle из screen**

В `ComputerTerminalScreen.kt` убрать terminal imports/messages/buffer fields, terminal attach in `init`, detach in `removed`, terminal sync in `containerTick`, and functions `currentTerminalState()`, `syncTerminalWindowSize()`, `terminalDimensions()`.

- [ ] **Step 4: Render display buffer as active surface**

Оставить status bar/buttons. `content()` рассчитывает `cols = DEFAULT_COLS`, `rows = DEFAULT_ROWS`. Заменить `terminalSurface(...)` на focusable canvas/input surface, который рисует `menu.clientSide.displayBuffer?.frontArgb()`; if no frames, draw dark placeholder. Использовать nearest-neighbor pixel loop from English plan. Preserve callbacks:

```kotlin
onKey = { keyCode -> terminalInput.keyPressed(keyCode, 0, 0) }
onKeyReleased = { keyCode -> terminalInput.keyReleased(keyCode, 0) }
onCharTyped = { ch -> terminalInput.charTyped(ch) }
```

Если `canvas` не focusable, минимально расширить DSL или оставить transparent focusable input element so callbacks work.

- [ ] **Step 5: Stable display endpoint sizing**

Use:

```kotlin
    private fun currentDisplayWidth(): Int = (DEFAULT_COLS * TerminalFontConstants.FONT_WIDTH).coerceAtLeast(64)

    private fun currentDisplayHeight(): Int = (DEFAULT_ROWS * TerminalFontConstants.FONT_HEIGHT).coerceAtLeast(48)
```

- [ ] **Step 6: Удалить terminal surface bridge if unused**

Run: `rg "TerminalSurfaceBridge|drawTerminalSurface|terminalSurface\(" modules`.

Если terminal UI был единственным consumer, удалить `TerminalSurfaceBridge.kt` and corresponding backend/DSL ops. Если workbench still uses it, leave as workbench-only.

- [ ] **Step 7: Focused architecture test**

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.terminal.screen.ComputerTerminalScreenArchitectureTest`

Expected: PASS.

- [ ] **Step 8: Common tests**

Run: `./gradlew :v1_21_1-common:test`

Expected: PASS.

- [ ] **Step 9: Commit**

Run: `git add modules/v1_21_1/v1_21_1-common && git commit -m "refactor: render computer screen from display buffer"`

---

### Task 5: Убрать visible stdout из ROM terminal

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`
- Modify if needed: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] **Step 1: Add ROM source audit test**

In `RomScriptCompileTest.kt` add:

```kotlin
    @Test
    fun bundledRomTerminalDoesNotUseStdoutForVisibleUi() {
        val source = resourceText("rom/terminal.ck")
        assertFalse(source.contains("stdout::write"), "rom/terminal.ck must render via display, not stdout")
    }
```

If helper name differs, use existing resource helper in this file.

- [ ] **Step 2: Run focused test and verify failure**

Run: `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`

Expected: FAIL.

- [ ] **Step 3: Replace input stdout echo with dirty-row renderer**

In `terminal.ck` remove all `stdout::write(...)`. Keep full render for attach/resize/shell output/Enter. Add helper functions `columns`, `rows`, `lineRow`, `renderInputLine` as in English plan, then call `renderInputLine(displayId, screen, line)` after char/paste/Backspace. On Enter append `line + "\n"`, clear `line`, and call full `render(displayId, screen)`.

- [ ] **Step 4: ROM compile tests**

Run: `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`

Expected: PASS.

- [ ] **Step 5: VM ROM behavior tests**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest`

Expected: PASS after updating tests that expected stdout echo; tests should assert display frames or no per-key full redraw.

- [ ] **Step 6: Commit**

Run: `git add modules/v1_21_1/v1_21_1-neoforge modules/core/src/test && git commit -m "refactor: render rom terminal input through display"`

---

### Task 6: Удалить stdout broadcaster consumers

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ComputerStdioBroadcasterTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ComputerStdioBroadcaster.kt`
- Delete if unused: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ScrollbackRing.kt`
- Delete if unused: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/CursorTracker.kt`

- [ ] **Step 1: Replace consumer tests**

In `ComputerStdioBroadcasterTest.kt` remove `RecordingConsumer` and consumer delivery tests. Keep/add:

```kotlin
    @Test
    fun writeStringUpdatesCursorOnly() {
        val b = ComputerStdioBroadcaster()
        b.writeString("Hi")
        assertEquals(2 to 0, b.cursor())
        b.writeString("\r\n")
        assertEquals(0 to 1, b.cursor())
    }

    @Test
    fun emptyWriteIsNoOpForCursor() {
        val b = ComputerStdioBroadcaster()
        b.writeString("")
        assertEquals(0 to 0, b.cursor())
    }
```

- [ ] **Step 2: Run focused test**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.api.ComputerStdioBroadcasterTest`

Expected: PASS before implementation because old code still updates cursor.

- [ ] **Step 3: Remove fanout implementation**

In `ComputerStdioBroadcaster.kt` remove `CopyOnWriteArrayList`, `ScrollbackRing`, `Consumer`, `consumers`, fanout loop, `addConsumer()`, `removeConsumer()`, `consumerCount()`. `writeString` should only feed `cursorParser` inside synchronized block. Keep `DeviceStdioApi` until follow-up language/API cleanup.

- [ ] **Step 4: Delete scrollback helper if unused**

Run: `rg "ScrollbackRing|Consumer|addConsumer|removeConsumer|consumerCount" modules/core/src/main modules/core/src/test`.

If unused, delete `ScrollbackRing.kt` and `ScrollbackRingTest.kt`. Keep `CursorTracker` if broadcaster still uses it.

- [ ] **Step 5: Core tests**

Run: `./gradlew :core:test`

Expected: PASS.

- [ ] **Step 6: Commit**

Run: `git add modules/core && git commit -m "refactor: remove stdout broadcaster fanout"`

---

### Task 7: Обновить architecture docs

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Optional modify: `docs/MACHINE.md`

- [ ] **Step 1: Replace stdout terminal session architecture text**

In `docs/ARCHITECTURE.md` remove claims that `RuntimeDeviceImpl.serverTick()` flushes terminal sessions or sends `StdoutBytesClientMessage`. Add:

```markdown
Runtime computer UI uses display sessions for server-to-client output. The client sends discrete input events (`key`, `key_up`, `char`, `paste`, mouse events) to the VM event queue. The server sends framebuffer deltas through display sessions (`DisplayAttachServerMessage`, `DisplayResizeServerMessage`, `DisplayDetachServerMessage`, `FrameDeltaClientMessage`). There is no runtime stdout byte broadcast in the client-server protocol.
```

- [ ] **Step 2: Document temporary workbench limitation**

Add:

```markdown
Workbench live attach-terminal over stdout is temporarily removed. Workbench file sync, IDE, and run controls remain available. A future live viewer should attach to display sessions rather than reintroducing stdout transport.
```

- [ ] **Step 3: Scan stale packet names**

Run: `rg "StdoutBytes|stdout_bytes|AttachTerminal|ResizeTerminal|TerminalNetworkBridge|ClientTerminalBuffer" docs modules/*/src/main/kotlin modules/*/*/src/main/kotlin`.

Expected: no stale refs in runtime client-server docs/main code. Historical docs under `docs/superpowers` allowed.

- [ ] **Step 4: Commit**

Run: `git add docs/ARCHITECTURE.md docs/MACHINE.md && git commit -m "docs: describe display-only runtime io"`

---

### Task 8: Full verification

**Files:**
- No source edits expected.

- [ ] **Step 1: Full tests**

Run: `./gradlew test`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Final source audit**

Run:

```bash
rg "StdoutBytesClientMessage|AttachTerminalServerMessage|ResizeTerminalServerMessage|TerminalNetworkBridge|RuntimeDeviceTerminalSessions|sendStdoutBytes|attachTerminalSession|resizeTerminalSession|flushTerminalSessions|ClientTerminalBuffer|stdout::write" modules/core/src/main modules/v1_21_1/v1_21_1-common/src/main modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom
```

Expected: no matches, except `stdout::write` may remain in non-terminal ROM utilities only if explicitly not visible runtime UI. `rom/terminal.ck` must have no matches.

- [ ] **Step 3: Git status**

Run: `git status --short`

Expected: clean after commits.

- [ ] **Step 4: Report follow-up work**

Report follow-up: full VM-side language/API deletion covering `DeviceRuntime.stdio`, `DeviceStdioApi`, `LanguageBuiltins` terminal/stdout namespaces, compiler runtime tests, and firmware diagnostics migration to display/structured diagnostics.
