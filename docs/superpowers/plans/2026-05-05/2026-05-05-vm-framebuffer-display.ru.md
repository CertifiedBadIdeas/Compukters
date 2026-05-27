# План реализации VM Framebuffer Display

> **Для agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) или superpowers:executing-plans для реализации этого плана task-by-task. Steps используют checkbox (`- [ ]`) syntax для tracking.

**Цель:** заменить terminal-first output компьютера на VM-rendered framebuffer display path, где разрешение задаёт client/display endpoint.

**Архитектура:** добавить pure display models в `:compiler`, framebuffer/dirty-tile state в `:core`, runtime API `display` в `BackgroundDeviceVm`, и Minecraft-facing endpoint/network/client buffers в `v1_21_1-common`. Использовать runtime host bridge, а не новые bytecode instructions, чтобы `Instruction` и ROM estimation не менялись в этой фазе.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, CKL compiler/runtime, Minecraft 1.21.1 common module networking, kotlin.test.

**Worktree:** `/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/vm-framebuffer-display` на branch `feature/vm-framebuffer-display`.

**Baseline verified:** `./gradlew :compiler:test` и `./gradlew test` прошли перед записью плана.

---

## Структура файлов

Создать pure cross-module models:

- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/display/DisplayModels.kt` — `DisplayPixelFormat`, `DisplayInfo`, `DisplayTile`, `DisplayFrameDelta`.

Создать core framebuffer runtime:

- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt` — RGB565 buffer mutation и tile extraction.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/TileDirtyTracker.kt` — tile coordinate bitset.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt` — per-display buffers, sequence, `present()`.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt` — attach/resize/detach/query/drain.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt` — runtime implementation для CKL `display::` builtins.

Изменить runtime interfaces и host wiring:

- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt` — добавить `DeviceDisplayApi` и `DeviceRuntime.display`.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceVmModels.kt` — добавить `DeviceCapability.DISPLAY`.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt` — добавить `display` module.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt` — dispatch `display` functions.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt` — хранить `DeviceDisplayApi`.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt` — владеть `DisplayRegistry`, expose attach/resize/detach/drain, включить `display` в runtime registry.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDevice.kt` — добавить display-session role.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt` — bridge display sessions и flush frame deltas.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/DisplayNetworkBridge.kt` — platform-neutral frame sender/session validator.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt` — реализовать `DisplayNetworkBridge`.

Создать common client/network files:

- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientDisplayBuffer.kt` — client staging/front buffers.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayAttachServerMessage.kt` — attach display endpoint.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayResizeServerMessage.kt` — resize display endpoint.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayDetachServerMessage.kt` — detach display endpoint.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/FrameDeltaClientMessage.kt` — deliver display frames.

Изменить common client integration:

- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt` — register display packets with IDs `22`, `23`, `24`, and `25`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt` — добавить `handleDisplayFrame(containerId, delta)`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt` — route display frames to current `ComputerMenu`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt` — store/apply `ClientDisplayBuffer` на client side.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlockEntity.kt` — передать host display network bridge в `RuntimeDeviceImpl`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt` — передать host display network bridge в `RuntimeDeviceImpl` для workbench target runtime devices.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt` — announce display attach/resize/detach и убрать старую terminal surface из active display path.

Добавить tests:

- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/TileDirtyTrackerTest.kt`
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayStateTest.kt`
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApiTest.kt`
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmDisplayTest.kt`
- `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientDisplayBufferTest.kt`
- `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/network/DisplayMessageCodecTest.kt`

---

### Task 1: Pure display models и dirty tiles

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/display/DisplayModels.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/TileDirtyTracker.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/TileDirtyTrackerTest.kt`

- [ ] **Step 1: Написать failing dirty tile test**

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm.display

import kotlin.test.Test
import kotlin.test.assertEquals

class TileDirtyTrackerTest {
    @Test
    fun marksEveryTileTouchedByRectangleOnceInStableOrder() {
        val tracker = TileDirtyTracker(width = 40, height = 24, tileSize = 16)

        tracker.markRectDirty(x = 15, y = 15, width = 20, height = 9)

        assertEquals(
            listOf(
                DirtyTile(tileX = 0, tileY = 0, x = 0, y = 0, width = 16, height = 16),
                DirtyTile(tileX = 1, tileY = 0, x = 16, y = 0, width = 16, height = 16),
                DirtyTile(tileX = 2, tileY = 0, x = 32, y = 0, width = 8, height = 16),
                DirtyTile(tileX = 0, tileY = 1, x = 0, y = 16, width = 16, height = 8),
                DirtyTile(tileX = 1, tileY = 1, x = 16, y = 16, width = 16, height = 8),
                DirtyTile(tileX = 2, tileY = 1, x = 32, y = 16, width = 8, height = 8),
            ),
            tracker.dirtyTiles(),
        )
    }

    @Test
    fun ignoresRectanglesOutsideTheDisplay() {
        val tracker = TileDirtyTracker(width = 32, height = 32, tileSize = 16)

        tracker.markRectDirty(x = 40, y = 40, width = 5, height = 5)
        assertEquals(emptyList(), tracker.dirtyTiles())
    }
}
```

- [ ] **Step 2: Запустить failing test**

Run: `./gradlew :core:test --tests "*TileDirtyTrackerTest"`

Expected: `Compilation error` mentioning unresolved `TileDirtyTracker` and `DirtyTile`.

- [ ] **Step 3: Добавить display wire models**

Использовать код из English plan Task 1 Step 3 без изменения package/imports.

- [ ] **Step 4: Добавить dirty tile tracker**

Использовать код из English plan Task 1 Step 4 без изменения package/imports.

- [ ] **Step 5: Добавить RGB565 pixel buffer**

Использовать код из English plan Task 1 Step 5 без изменения package/imports.

- [ ] **Step 6: Запустить dirty tile tests**

Run: `./gradlew :core:test --tests "*TileDirtyTrackerTest"`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/display/DisplayModels.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/TileDirtyTracker.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/TileDirtyTrackerTest.kt
git commit -m "feat: add display framebuffer primitives"
```

---

### Task 2: Display state и frame deltas

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayStateTest.kt`

- [ ] **Step 1: Написать failing display state tests**

Использовать код из English plan Task 2 Step 1 без изменения package/imports.

- [ ] **Step 2: Запустить failing tests**

Run: `./gradlew :core:test --tests "*DisplayStateTest"`

Expected: `Compilation error` mentioning unresolved `DisplayState`.

- [ ] **Step 3: Добавить display state**

Использовать код из English plan Task 2 Step 3 без изменения package/imports.

- [ ] **Step 4: Добавить display registry**

Использовать код из English plan Task 2 Step 4 без изменения package/imports.

- [ ] **Step 5: Запустить display state tests**

Run: `./gradlew :core:test --tests "*DisplayStateTest"`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayStateTest.kt
git commit -m "feat: add display frame state"
```

---

### Task 3: Runtime display API и builtins

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceVmModels.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApiTest.kt`

- [ ] **Step 1: Написать failing API test**

Использовать код из English plan Task 3 Step 1 без изменения package/imports.

- [ ] **Step 2: Запустить failing API test**

Run: `./gradlew :core:test --tests "*VmDisplayApiTest"`

Expected: `Compilation error` mentioning unresolved `VmDisplayApi`.

- [ ] **Step 3: Добавить compiler runtime interface**

Использовать код из English plan Task 3 Step 3.

- [ ] **Step 4: Добавить display capability**

Использовать код из English plan Task 3 Step 4.

- [ ] **Step 5: Добавить `display` builtin module**

Использовать код из English plan Task 3 Step 5.

- [ ] **Step 6: Добавить VM display API implementation**

Использовать код из English plan Task 3 Step 6 без изменения package/imports.

- [ ] **Step 7: Wire `VmRuntime` and `RuntimeHostBridge`**

Использовать code snippets из English plan Task 3 Step 7.

- [ ] **Step 8: Запустить API tests**

Run: `./gradlew :core:test --tests "*VmDisplayApiTest"`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceVmModels.kt \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApiTest.kt
git commit -m "feat: expose display api to vm runtime"
```

---

### Task 4: VM-owned display registry and frame drain

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmDisplayTest.kt`

- [ ] **Step 1: Написать failing VM display test**

Использовать код из English plan Task 4 Step 1 без изменения package/imports.

- [ ] **Step 2: Запустить failing VM display test**

Run: `./gradlew :core:test --tests "*BackgroundDeviceVmDisplayTest"`

Expected: `Compilation error` mentioning unresolved `attachDisplay` and `drainDisplayFrames`.

- [ ] **Step 3: Добавить display registry в `BackgroundDeviceVm`**

Использовать code snippets из English plan Task 4 Step 3.

- [ ] **Step 4: Включить display module в runtime registry**

Использовать code snippet из English plan Task 4 Step 4.

- [ ] **Step 5: Передать `displayApi` в `VmRuntime`**

Использовать code snippet из English plan Task 4 Step 5.

- [ ] **Step 6: Запустить VM display test**

Run: `./gradlew :core:test --tests "*BackgroundDeviceVmDisplayTest"`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmDisplayTest.kt
git commit -m "feat: connect display registry to background vm"
```

---

### Task 5: Runtime device display sessions and server flush

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDevice.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/DisplayNetworkBridge.kt`

- [ ] **Step 1: Добавить display role в `RuntimeDevice.kt`**

Использовать code snippet из English plan Task 5 Step 1.

- [ ] **Step 2: Добавить display network bridge port**

Использовать code snippet из English plan Task 5 Step 2.

- [ ] **Step 3: Изменить constructor `RuntimeDeviceImpl`**

Использовать code snippet из English plan Task 5 Step 3 и обновить call sites.

- [ ] **Step 4: Добавить display sessions and flush code**

Использовать code snippets из English plan Task 5 Step 4.

- [ ] **Step 5: Запустить core compile**

Run: `./gradlew :core:compileKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDevice.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/DisplayNetworkBridge.kt
git commit -m "feat: add runtime display sessions"
```

---

### Task 6: Display network packets and codec tests

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/FrameDeltaClientMessage.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayAttachServerMessage.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayResizeServerMessage.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayDetachServerMessage.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlockEntity.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/network/DisplayMessageCodecTest.kt`

- [ ] **Step 1: Написать failing frame codec test**

Использовать код из English plan Task 6 Step 1 без изменения package/imports.

- [ ] **Step 2: Запустить failing codec test**

Run: `./gradlew :v1_21_1:v1_21_1-common:test --tests "*DisplayMessageCodecTest"`

Expected: `Compilation error` mentioning unresolved `FrameDeltaClientMessage`.

- [ ] **Step 3: Добавить frame message**

Использовать код из English plan Task 6 Step 3 без изменения package/imports.

- [ ] **Step 4: Добавить serverbound display messages**

Использовать pattern из English plan Task 6 Step 4 for attach, resize, detach.

- [ ] **Step 5: Зарегистрировать message IDs**

Использовать code snippet из English plan Task 6 Step 5.

- [ ] **Step 6: Добавить client context handler**

Использовать code snippets из English plan Task 6 Step 6.

- [ ] **Step 7: Реализовать display network bridge в `BlockEntityRuntimeDeviceHost`**

Вставить Kotlin snippet из English plan Task 6 Step 7: `displayNetwork` проверяет текущий `ComputerMenu` и отправляет `FrameDeltaClientMessage(containerId, frame)` через `ServerNetworking.sendToPlayer`.

Затем обновить оба call sites `RuntimeDeviceImpl(...)`, передав `host.displayNetwork` после `host.terminalNetwork`.

- [ ] **Step 8: Запустить codec test**

Run: `./gradlew :v1_21_1:v1_21_1-common:test --tests "*DisplayMessageCodecTest"`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

Run:

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/FrameDeltaClientMessage.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayAttachServerMessage.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayResizeServerMessage.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayDetachServerMessage.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlockEntity.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt \
  modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/network/DisplayMessageCodecTest.kt
git commit -m "feat: add display frame network messages"
```

---

### Task 7: Client display double buffer and menu bridge

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientDisplayBuffer.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/ComputerMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientDisplayBufferTest.kt`

- [ ] **Step 1: Написать failing client buffer test**

Использовать код из English plan Task 7 Step 1 без изменения package/imports.

- [ ] **Step 2: Запустить failing client buffer test**

Run: `./gradlew :v1_21_1:v1_21_1-common:test --tests "*ClientDisplayBufferTest"`

Expected: `Compilation error` mentioning unresolved `ClientDisplayBuffer`.

- [ ] **Step 3: Добавить client display buffer**

Использовать код из English plan Task 7 Step 3 без изменения package/imports.

- [ ] **Step 4: Добавить menu bridge**

Использовать code snippets из English plan Task 7 Step 4.

- [ ] **Step 5: Запустить client buffer tests**

Run: `./gradlew :v1_21_1:v1_21_1-common:test --tests "*ClientDisplayBufferTest"`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientDisplayBuffer.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/ComputerMenu.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt \
  modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientDisplayBufferTest.kt
git commit -m "feat: add client display buffering"
```

---

### Task 8: Screen endpoint lifecycle and minimal display demo

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`

Этот task — Phase 1A. Он доказывает, что frames доходят до `ClientDisplayBuffer`. On-screen ARGB texture rendering намеренно остаётся для следующей UI-задачи.

- [ ] **Step 1: Attach display buffer from screen init**

Использовать code snippets из English plan Task 8 Step 1.

- [ ] **Step 2: Send resize and detach lifecycle messages**

Использовать code snippets из English plan Task 8 Step 2.

- [ ] **Step 3: Swap client buffer during render tick**

Использовать code snippet из English plan Task 8 Step 3.

- [ ] **Step 4: Добавить firmware display demo**

Использовать CKL code из English plan Task 8 Step 4.

- [ ] **Step 5: Запустить ROM compile and full tests**

Run:

```bash
./gradlew :v1_21_1:v1_21_1-neoforge:test --tests "*RomScriptCompileTest"
./gradlew test
```

Expected: both commands finish with `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt
git commit -m "feat: attach gui display endpoint"
```

---

## Final verification

- [ ] Run compiler tests: `./gradlew :compiler:test`
- [ ] Run core display tests: `./gradlew :core:test --tests "*Display*"`
- [ ] Run common display/network tests: `./gradlew :v1_21_1:v1_21_1-common:test --tests "*Display*"`
- [ ] Run full suite: `./gradlew test`
- [ ] Confirm every command reports `BUILD SUCCESSFUL`.
- [ ] Run `git status --short` and confirm only intentional changes are present.

## Notes for executors

- Не добавлять new `Instruction` variants в этой phase. Display API dispatch идёт через existing runtime host bridge.
- Если исполнитель всё же добавляет bytecode instructions, нужно обновить `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupport.kt` в том же commit.
- Не сохранять old terminal screen как fallback requirement. Он может временно оставаться dead code только до появления replacement UI renderer для display path.
- User-facing translation keys генерируют Kotlin methods по key name. Не делать bulk rename `computerId`/translation-derived method calls в этой branch.