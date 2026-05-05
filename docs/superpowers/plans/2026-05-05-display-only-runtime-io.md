# Display-only Runtime I/O Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove runtime terminal/stdout client-server broadcasting and make framebuffer display frames the only server-to-client runtime UI output.

**Architecture:** Runtime UI becomes display-only: clients send input events, servers send display frame deltas. The legacy terminal network path is removed first, ROM `terminal.ck` stops using `stdout::write`, and VM-side stdout/terminal APIs are left only as a short-lived compatibility surface until a follow-up cleanup removes language-level terminal builtins.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, Architectury/NeoForge common code, CKL ROM scripts, Kotlin test.

---

## Worktree and baseline

Implementation worktree already created and verified:

- Worktree: `/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/display-only-runtime-io`
- Branch: `feature/display-only-runtime-io`
- Baseline command: `./gradlew test`
- Baseline result: `BUILD SUCCESSFUL` with `31 actionable tasks`.

Run all commands below from `/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/display-only-runtime-io`.

## Scope boundary

This plan implements the first shippable staged cleanup:

1. remove runtime terminal/stdout network transport;
2. make the computer screen consume `ClientDisplayBuffer` instead of `ClientTerminalBuffer`;
3. update ROM terminal to render interactive input through display dirty rows and stop writing visible output to `stdout`;
4. remove runtime broadcaster consumers that only existed for terminal network fanout.

Full language/API deletion of `terminal::*`, `stdout::*`, `DeviceStdioApi`, and broad compiler/runtime test rewrites should be handled by a follow-up plan after this branch proves display-only runtime UI. This preserves the user-approved final direction while keeping this branch reviewable.

## File map

### Core runtime

- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt`
  - remove `TerminalNetworkBridge` constructor dependency;
  - remove `TerminalSession`, `terminalSessions`, `attachTerminalSession()`, `resizeTerminalSession()`, `detachTerminalSession()`, `flushTerminalSessions()`, `bindConsumer()`, `rebindTerminalConsumers()`;
  - remove `syncScreen()` call from `serverTick()` if no remaining caller needs per-tick snapshots.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDevice.kt`
  - remove `RuntimeDeviceTerminalSessions` from the runtime umbrella.
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/TerminalNetworkBridge.kt`.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ComputerStdioBroadcaster.kt`
  - remove `Consumer`, consumer list, replay fanout, `addConsumer()`, `removeConsumer()`, `consumerCount()` after runtime sessions are gone.

### Common network/client UI

- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/StdoutBytesClientMessage.kt`.
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/AttachTerminalServerMessage.kt`.
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/ResizeTerminalServerMessage.kt`.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt`
  - remove imports, docs, and registrations for `attach_terminal`, `resize_terminal`, `stdout_bytes`;
  - reserve IDs `7`, `8`, and `14` as unused in the protocol table.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt`
  - remove `handleStdoutBytes()`.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt`
  - remove `handleStdoutBytes()` routing.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt`
  - remove `terminalNetwork` adapter and `StdoutBytesClientMessage` import.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt`
  - remove `ClientTerminalBuffer`, `terminalBuffer`, `attachTerminalBuffer()`, `detachTerminalBuffer()`, `applyStdoutBytes()`, `handleStdoutBytes()`.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/ComputerMenu.kt`
  - remove `handleStdoutBytes()` and terminal-buffer docs.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt`
  - remove attach/resize terminal messages and terminal buffer usage;
  - render from `ClientDisplayBuffer`;
  - keep input forwarding and display attach/resize/detach.

### ROM

- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`
  - remove all `stdout::write` calls;
  - add dirty-row display rendering for current input line;
  - keep IPC shell channels.

### Tests/docs

- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImplDisplayTest.kt`.
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ComputerStdioBroadcasterTest.kt`.
- Modify: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/network/DisplayMessageCodecTest.kt`.
- Modify: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/MenuSideClientTest.kt`.
- Create: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreenArchitectureTest.kt`.
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`.
- Modify: `docs/ARCHITECTURE.md`.

---

### Task 1: Remove terminal/stdout packet registration

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/network/DisplayMessageCodecTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt`
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/StdoutBytesClientMessage.kt`
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/AttachTerminalServerMessage.kt`
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/ResizeTerminalServerMessage.kt`

- [ ] **Step 1: Add failing registry test**

Add these imports to `DisplayMessageCodecTest.kt`:

```kotlin
import ru.lazyhat.compukterkraft.common.network.MessageTypeImpl
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import kotlin.test.assertFalse
```

Add this test method inside `DisplayMessageCodecTest`:

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

- [ ] **Step 2: Run focused test and verify failure**

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.computer.network.DisplayMessageCodecTest`

Expected: FAIL because IDs `7`, `8`, and `14` are still registered.

- [ ] **Step 3: Remove terminal packet registrations**

In `NetworkMessages.kt`:

- remove imports for `StdoutBytesClientMessage`, `AttachTerminalServerMessage`, and `ResizeTerminalServerMessage`;
- delete `ATTACH_TERMINAL`, `RESIZE_TERMINAL`, and `STDOUT_BYTES` properties;
- update the protocol table so IDs `7`, `8`, and `14` are documented as unused/reserved removed terminal/stdout packet IDs.

- [ ] **Step 4: Delete packet classes**

Delete these files:

- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/StdoutBytesClientMessage.kt`
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/AttachTerminalServerMessage.kt`
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/ResizeTerminalServerMessage.kt`

- [ ] **Step 5: Run focused test and verify pass**

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.computer.network.DisplayMessageCodecTest`

Expected: PASS.

- [ ] **Step 6: Commit**

Run: `git add modules/v1_21_1/v1_21_1-common/src/main/kotlin modules/v1_21_1/v1_21_1-common/src/test/kotlin && git commit -m "refactor: remove terminal stdout packet registration"`

---

### Task 2: Remove runtime terminal sessions from core

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImplDisplayTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDevice.kt`
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/TerminalNetworkBridge.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt`

- [ ] **Step 1: Update core display test to require no terminal bridge**

In `RuntimeDeviceImplDisplayTest.kt`, remove the import of `TerminalNetworkBridge`, remove the `NoopTerminalNetworkBridge` object, and remove the `terminalNetwork = NoopTerminalNetworkBridge,` constructor argument from `RuntimeDeviceImpl(...)`.

- [ ] **Step 2: Run focused test and verify compile failure**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceImplDisplayTest`

Expected: FAIL at compile time because `RuntimeDeviceImpl` still requires `terminalNetwork`.

- [ ] **Step 3: Remove terminal sessions from `RuntimeDevice.kt`**

Delete the whole `RuntimeDeviceTerminalSessions` interface. Remove it from the `RuntimeDevice` inheritance list so the umbrella becomes:

```kotlin
interface RuntimeDevice :
    RuntimeDeviceLifecycle,
    RuntimeDeviceInput,
    RuntimeDeviceScreen,
    RuntimeDeviceDisplaySessions,
    RuntimeDeviceMetadata
```

Keep `RuntimeDeviceScreen` for this task; workbench snapshot cleanup is separate.

- [ ] **Step 4: Remove terminal bridge and session state from `RuntimeDeviceImpl.kt`**

In `RuntimeDeviceImpl.kt`:

- remove `TerminalNetworkBridge`, `ComputerStdioBroadcaster`, and `ConcurrentLinkedQueue` imports;
- remove constructor parameter `private val terminalNetwork: TerminalNetworkBridge,`;
- remove `TerminalSession` data class;
- remove `terminalSessions` map;
- remove `rebindTerminalConsumers(handle)` call from `turnOn()`;
- in `close()`, remove `terminalSessions.keys.toList().forEach(::detachTerminalSession)`;
- in `serverTick()`, replace the null-handle branch with a plain return:

```kotlin
        val handle = vmHandle ?: return
```

- remove the `flushTerminalSessions()` call;
- in `handleVmStopped()`, remove `flushTerminalSessions()`;
- delete methods `attachTerminalSession()`, `bindConsumer()`, `rebindTerminalConsumers()`, `resizeTerminalSession()`, `detachTerminalSession()`, and `flushTerminalSessions()`.

- [ ] **Step 5: Remove common host terminal adapter**

In `BlockEntityRuntimeDeviceHost.kt`:

- remove imports `StdoutBytesClientMessage`, `TerminalNetworkBridge`, and `UUID` if no longer needed by the terminal adapter;
- delete the `terminalNetwork` property;
- keep `displayNetwork` unchanged.

- [ ] **Step 6: Delete terminal bridge port**

Delete `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/TerminalNetworkBridge.kt`.

- [ ] **Step 7: Fix runtime device construction call sites**

Search for `terminalNetwork =` and `RuntimeDeviceImpl(`. Remove the terminal argument from every constructor call. Expected call sites include runtime device host/block entity creation and `RuntimeDeviceImplDisplayTest.kt`.

- [ ] **Step 8: Run core focused test**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceImplDisplayTest`

Expected: PASS.

- [ ] **Step 9: Run common compile/test to catch call-site fallout**

Run: `./gradlew :v1_21_1-common:test`

Expected: PASS after all terminal bridge references are removed.

- [ ] **Step 10: Commit**

Run: `git add modules/core modules/v1_21_1/v1_21_1-common && git commit -m "refactor: remove runtime terminal sessions"`

---

### Task 3: Remove client terminal buffer from computer menus

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/MenuSideClientTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/ComputerMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt`
- Optional delete after no references: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientTerminalBuffer.kt`

- [ ] **Step 1: Replace menu terminal-buffer test with display-buffer test**

In `MenuSideClientTest.kt`, remove imports `ClientTerminalBuffer`, `assertNotNull`, and `assertSame` if unused. Add imports:

```kotlin
import ru.lazyhat.compukterkraft.common.computer.client.ClientDisplayBuffer
import kotlin.test.assertSame
```

Replace `clientSideAttachAndDetachTerminalBuffer()` with:

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

- [ ] **Step 2: Run focused test and verify pass or compile failure**

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.computer.menu.MenuSideClientTest`

Expected: either PASS before implementation because display buffer already exists, or compile failure after deleting terminal APIs. Continue to Step 3.

- [ ] **Step 3: Remove menu stdout APIs**

In `AbstractComputerMenu.kt`:

- remove `ClientTerminalBuffer` import;
- remove `terminalBuffer` property;
- remove `attachTerminalBuffer()`, `detachTerminalBuffer()`, and `applyStdoutBytes()`;
- remove override `handleStdoutBytes(bytes: ByteArray)`;
- update comments to say client side owns `ClientDisplayBuffer`.

In `ComputerMenu.kt`:

- remove `handleStdoutBytes(bytes: ByteArray)`;
- update docs to mention `menu.clientSide.displayBuffer` instead of `terminalBuffer`.

In `ClientNetworkContext.kt` and `ClientNetworkContextImpl.kt`:

- remove `handleStdoutBytes(containerId, bytes)` declarations and implementation.

- [ ] **Step 4: Delete `ClientTerminalBuffer.kt` if unused**

Run: `rg "ClientTerminalBuffer|handleStdoutBytes|applyStdoutBytes|terminalBuffer" modules/v1_21_1/v1_21_1-common/src/main/kotlin modules/v1_21_1/v1_21_1-common/src/test/kotlin`.

If the only remaining reference is `ClientTerminalBuffer.kt` itself, delete it. If references remain in `ComputerTerminalScreen.kt`, leave the file until Task 4 and delete it there.

- [ ] **Step 5: Run common tests**

Run: `./gradlew :v1_21_1-common:test`

Expected: PASS after Task 4 if screen references still compile-fail here; otherwise PASS now.

- [ ] **Step 6: Commit**

Run: `git add modules/v1_21_1/v1_21_1-common && git commit -m "refactor: remove client stdout menu buffer"`

---

### Task 4: Make computer screen display-only

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreenArchitectureTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt`
- Delete when unused: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/TerminalSurfaceBridge.kt`

- [ ] **Step 1: Add source-level architecture test**

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

Expected: FAIL because the screen still imports terminal buffer/message classes and calls `terminalSurface(`.

- [ ] **Step 3: Remove terminal session attach/resize from screen lifecycle**

In `ComputerTerminalScreen.kt`:

- remove imports `ClientTerminalBuffer`, `AttachTerminalServerMessage`, `ResizeTerminalServerMessage`, `WorkbenchTerminalViewState`, and `ScreenBufferSnapshot`;
- remove fields `lastTerminalDimensions`, `announcedCols`, `announcedRows`;
- in `init { ... }`, do not attach a terminal buffer and do not send `AttachTerminalServerMessage`;
- in `removed()`, do not call `menu.clientSide.detachTerminalBuffer()`;
- in `containerTick()`, remove `currentTerminalState()`, `syncTerminalWindowSize(state)`, and terminal dimension invalidation;
- delete functions `currentTerminalState()`, `syncTerminalWindowSize()`, and `terminalDimensions()`.

- [ ] **Step 4: Render display buffer as the active surface**

Keep the status bar and power/reboot buttons. In `content()`, calculate layout using default display dimensions instead of terminal snapshot dimensions:

```kotlin
        val cols = DEFAULT_COLS
        val rows = DEFAULT_ROWS
```

Replace the `terminalSurface(...)` block with a focusable canvas or input surface that forwards the existing `terminalInput` callbacks and draws the latest `menu.clientSide.displayBuffer?.frontArgb()` into the surface bounds. Use a simple nearest-neighbor pixel loop first:

```kotlin
                canvas(
                    modifier =
                        Modifier
                            .offset(terminalRelX, terminalRelY)
                            .size(layout.terminalBounds.width, layout.terminalBounds.height),
                ) {
                    val buffer = menu.clientSide.displayBuffer
                    if (buffer == null || !buffer.hasReceivedFrames) {
                        fillRect(0, 0, layout.terminalBounds.width, layout.terminalBounds.height, Color.hex(0xFF05070AU))
                    } else {
                        val pixels = buffer.frontArgb()
                        val scaleX = layout.terminalBounds.width.toDouble() / buffer.width.toDouble()
                        val scaleY = layout.terminalBounds.height.toDouble() / buffer.height.toDouble()
                        var y = 0
                        while (y < buffer.height) {
                            var x = 0
                            while (x < buffer.width) {
                                val color = Color.hex(pixels[y * buffer.width + x].toUInt())
                                val px = (x * scaleX).toInt()
                                val py = (y * scaleY).toInt()
                                val pw = (((x + 1) * scaleX).toInt() - px).coerceAtLeast(1)
                                val ph = (((y + 1) * scaleY).toInt() - py).coerceAtLeast(1)
                                fillRect(px, py, pw, ph, color)
                                x = x + 1
                            }
                            y = y + 1
                        }
                    }
                }
```

If `canvas` is not focusable in the current DSL, keep a transparent focusable input element or extend the DSL minimally so the display surface receives `onKey`, `onKeyReleased`, and `onCharTyped` like `terminalSurface` did. Preserve these callbacks:

```kotlin
onKey = { keyCode -> terminalInput.keyPressed(keyCode, 0, 0) }
onKeyReleased = { keyCode -> terminalInput.keyReleased(keyCode, 0) }
onCharTyped = { ch -> terminalInput.charTyped(ch) }
```

- [ ] **Step 5: Keep display endpoint sizing stable**

Update `currentDisplayWidth()` and `currentDisplayHeight()` to avoid terminal snapshot reads:

```kotlin
    private fun currentDisplayWidth(): Int = (DEFAULT_COLS * TerminalFontConstants.FONT_WIDTH).coerceAtLeast(64)

    private fun currentDisplayHeight(): Int = (DEFAULT_ROWS * TerminalFontConstants.FONT_HEIGHT).coerceAtLeast(48)
```

- [ ] **Step 6: Delete terminal surface bridge if unused**

Run: `rg "TerminalSurfaceBridge|drawTerminalSurface|terminalSurface\(" modules`.

If only terminal UI code used it, delete `TerminalSurfaceBridge.kt` and remove `drawTerminalSurface` from `RenderBackend`, `GuiGraphicsRenderBackend`, test backends, compiler ops, and DSL nodes. If workbench still uses terminal surface, do not delete it in this task; leave it as workbench-only until the workbench follow-up.

- [ ] **Step 7: Run focused architecture test**

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.terminal.screen.ComputerTerminalScreenArchitectureTest`

Expected: PASS.

- [ ] **Step 8: Run common tests**

Run: `./gradlew :v1_21_1-common:test`

Expected: PASS.

- [ ] **Step 9: Commit**

Run: `git add modules/v1_21_1/v1_21_1-common && git commit -m "refactor: render computer screen from display buffer"`

---

### Task 5: Stop ROM terminal from writing visible output to stdout

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`
- Modify focused tests in `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt` only if they assert stdout echo behavior.

- [ ] **Step 1: Add ROM source audit test**

In `RomScriptCompileTest.kt`, add this test method:

```kotlin
    @Test
    fun bundledRomTerminalDoesNotUseStdoutForVisibleUi() {
        val source = resourceText("rom/terminal.ck")
        assertFalse(source.contains("stdout::write"), "rom/terminal.ck must render via display, not stdout")
    }
```

If `resourceText` is private or named differently, use the same helper already used by existing ROM source tests in this file.

- [ ] **Step 2: Run focused test and verify failure**

Run: `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`

Expected: FAIL because `terminal.ck` still contains `stdout::write`.

- [ ] **Step 3: Replace full redraw per input with dirty-row renderer**

In `terminal.ck`:

- remove every `stdout::write(...)` call;
- keep full `render(displayId, screen + line)` for attach/resize and shell output until a richer scrollback model exists;
- add a cheap current-line dirty renderer for char, paste, and Backspace:

```ck
fun columns(displayId: Int): Int {
    return display::width(displayId) / 6
}

fun rows(displayId: Int): Int {
    return display::height(displayId) / 9
}

fun lineRow(displayId: Int, screen: String): Int {
    var row: Int = 0
    var col: Int = 0
    var i: Int = 0
    val cols: Int = columns(displayId)
    while i < strings::length(screen) {
        val ch: String = strings::charAt(screen, i)
        if (ch == "\n") {
            row = row + 1
            col = 0
        } else {
            col = col + 1
            if (col >= cols) {
                col = 0
                row = row + 1
            }
        }
        i = i + 1
    }
    return row
}

fun renderInputLine(displayId: Int, screen: String, line: String) {
    val cols: Int = columns(displayId)
    val row: Int = lineRow(displayId, screen)
    if (row < 0 || row >= rows(displayId)) {
        return
    }
    display::fillRect(displayId, 0, row * 9, cols * 6, 9, 0)
    var x: Int = 0
    var i: Int = 0
    while i < strings::length(line) {
        if (x >= cols) {
            display::present(displayId)
            return
        }
        display::fillRect(displayId, x * 6, row * 9, 5, 8, 2016)
        x = x + 1
        i = i + 1
    }
    display::present(displayId)
}
```

Then call `renderInputLine(displayId, screen, line)` after char, paste, and Backspace mutations. On Enter, keep appending `line + "\n"` to `screen`, clear `line`, and call full `render(displayId, screen)`.

- [ ] **Step 4: Run ROM compile tests**

Run: `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`

Expected: PASS.

- [ ] **Step 5: Run VM ROM behavior tests**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest`

Expected: PASS after updating any tests that asserted stdout echo. Tests should assert display frames or absence of per-key full redraw instead.

- [ ] **Step 6: Commit**

Run: `git add modules/v1_21_1/v1_21_1-neoforge modules/core/src/test && git commit -m "refactor: render rom terminal input through display"`

---

### Task 6: Remove stdout broadcaster consumers

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ComputerStdioBroadcasterTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ComputerStdioBroadcaster.kt`
- Delete if unused: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ScrollbackRing.kt`
- Delete if unused: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/CursorTracker.kt`

- [ ] **Step 1: Replace consumer tests with no-fanout tests**

In `ComputerStdioBroadcasterTest.kt`, remove `RecordingConsumer` and tests `writeStringAppendsToScrollback`, `lateConsumerReceivesReplayThenNewWrites`, `removeConsumerStopsDelivery`, `twoConsumersShareSameStream`, and `emptyWriteIsNoOp` if they only test consumer delivery.

Keep or add:

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

- [ ] **Step 2: Run focused test and verify pass before implementation**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.api.ComputerStdioBroadcasterTest`

Expected: PASS because old code still satisfies cursor behavior.

- [ ] **Step 3: Remove consumer fanout implementation**

In `ComputerStdioBroadcaster.kt`:

- remove `CopyOnWriteArrayList` import;
- remove `ScrollbackRing` field;
- remove `Consumer` fun interface;
- remove `consumers` field;
- in `writeString`, only feed `cursorParser` inside the synchronized block;
- delete `addConsumer()`, `removeConsumer()`, and `consumerCount()`.

The class should still implement `DeviceStdioApi` until the follow-up language/API cleanup removes `DeviceStdioApi` from `DeviceRuntime`.

- [ ] **Step 4: Delete scrollback helper if unused**

Run: `rg "ScrollbackRing|Consumer|addConsumer|removeConsumer|consumerCount" modules/core/src/main modules/core/src/test`.

If `ScrollbackRing` is unused outside deleted tests, delete `ScrollbackRing.kt` and `ScrollbackRingTest.kt`. If `CursorTracker` is still used by `ComputerStdioBroadcaster`, keep it.

- [ ] **Step 5: Run core tests**

Run: `./gradlew :core:test`

Expected: PASS.

- [ ] **Step 6: Commit**

Run: `git add modules/core && git commit -m "refactor: remove stdout broadcaster fanout"`

---

### Task 7: Update architecture docs

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Optionally modify: `docs/MACHINE.md` if it still claims runtime UI is stdout/screen-buffer based.

- [ ] **Step 1: Replace stdout terminal session architecture text**

In `docs/ARCHITECTURE.md`, remove claims that `RuntimeDeviceImpl.serverTick()` flushes terminal sessions or sends `StdoutBytesClientMessage`. Replace with this architecture statement:

```markdown
Runtime computer UI uses display sessions for server-to-client output. The client sends discrete input events (`key`, `key_up`, `char`, `paste`, mouse events) to the VM event queue. The server sends framebuffer deltas through display sessions (`DisplayAttachServerMessage`, `DisplayResizeServerMessage`, `DisplayDetachServerMessage`, `FrameDeltaClientMessage`). There is no runtime stdout byte broadcast in the client-server protocol.
```

- [ ] **Step 2: Document temporary workbench limitation**

Add:

```markdown
Workbench live attach-terminal over stdout is temporarily removed. Workbench file sync, IDE, and run controls remain available. A future live viewer should attach to display sessions rather than reintroducing stdout transport.
```

- [ ] **Step 3: Scan docs for stale packet names**

Run: `rg "StdoutBytes|stdout_bytes|AttachTerminal|ResizeTerminal|TerminalNetworkBridge|ClientTerminalBuffer" docs modules/*/src/main/kotlin modules/*/*/src/main/kotlin`.

Expected: no stale references in runtime client-server docs or main code. References in historical plan/spec files under `docs/superpowers` are allowed.

- [ ] **Step 4: Commit**

Run: `git add docs/ARCHITECTURE.md docs/MACHINE.md && git commit -m "docs: describe display-only runtime io"`

---

### Task 8: Full verification

**Files:**
- No source edits expected.

- [ ] **Step 1: Run full tests**

Run: `./gradlew test`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run final source audit**

Run:

```bash
rg "StdoutBytesClientMessage|AttachTerminalServerMessage|ResizeTerminalServerMessage|TerminalNetworkBridge|RuntimeDeviceTerminalSessions|sendStdoutBytes|attachTerminalSession|resizeTerminalSession|flushTerminalSessions|ClientTerminalBuffer|stdout::write" modules/core/src/main modules/v1_21_1/v1_21_1-common/src/main modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom
```

Expected: no matches, except `stdout::write` may remain in non-terminal ROM utilities only if they are explicitly not visible runtime UI. `rom/terminal.ck` must have no matches.

- [ ] **Step 3: Check git status**

Run: `git status --short`

Expected: clean after commits.

- [ ] **Step 4: Report follow-up work**

Report that full VM-side language/API deletion remains as a follow-up plan covering `DeviceRuntime.stdio`, `DeviceStdioApi`, `LanguageBuiltins` terminal/stdout namespaces, compiler runtime tests, and firmware diagnostics migration to display/structured diagnostics.
