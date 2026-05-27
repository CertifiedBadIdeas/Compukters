# Shared Terminal Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every player terminal connected to the same computer observe and write to one shared display endpoint.

**Architecture:** Use one stable terminal display id on the client and keep RuntimeDevice display detaches ref-counted by display id. Opening another viewer refreshes the same VM display endpoint so the new client receives a full frame, while detach reaches the VM only after the last viewer leaves.

**Tech Stack:** Kotlin common/core modules, CK ROM terminal, Gradle tests.

---

### Task 1: Ref-count Runtime Display Sessions

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImplDisplayTest.kt`

- [ ] **Step 1: Write the failing test**

Add a unit-level regression that attaches two sessions to display id `1`, detaches one, and verifies the VM display remains attached until the second session is detached.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceImplDisplayTest`

- [ ] **Step 3: Implement display-id refcount behavior**

Update `detachDisplaySession` and `reattachDisplaySessions` so repeated sessions for the same display id do not detach or reattach the VM display repeatedly. Keep `attachDisplaySession` refreshing the endpoint for newly connected viewers.

- [ ] **Step 4: Re-run focused tests**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceImplDisplayTest`

### Task 2: Use A Shared Client Terminal Display Id

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt`

- [ ] **Step 1: Replace UUID-derived display id**

Use a stable shared terminal display id instead of `(player.player.uuid.hashCode() and 0x3FFFFFFF) + 1`.

- [ ] **Step 2: Run Kotlin compilation/tests**

Run: `./gradlew :v1_21_1-common:compileKotlin`
Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceImplDisplayTest`

- [ ] **Step 3: Commit**

Run: `git add docs/superpowers/plans/2026-05-11/2026-05-11-shared-terminal-display.md modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImplDisplayTest.kt modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt`
Run: `git commit -m "Share terminal display across players"`
