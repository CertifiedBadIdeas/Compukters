# Computer Control Menu Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add block-level computer power control: first RMB powers on, RMB while on opens a control menu, Shift+RMB always opens the control menu.

**Architecture:** Introduce a small `ComputerControlMenu`/`ComputerControlScreen` pair that reuses the existing `AbstractComputerMenu` state and `ComputerActionServerMessage` control packet. Keep display terminal and serial terminal item flows separate.

**Tech Stack:** Kotlin, Minecraft/NeoForge menu registration, existing common network layer.

---

### Task 1: Control Menu Core

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/ComputerControlMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/binding/ModObjects.kt`

- [ ] Add `ComputerControlMenu` as an `AbstractComputerMenu` subclass with client/server constructors and disabled inventory transfer.
- [ ] Add `computerControlMenuType` and `openComputerControlMenu` bindings.

### Task 2: Block Interaction

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/AbstractComputerBlock.kt`

- [ ] Empty-hand RMB on an off computer calls `turnOn()` and returns success.
- [ ] Empty-hand RMB on an on computer opens control menu.
- [ ] Shift+RMB always opens control menu, without auto-starting an off computer.

### Task 3: Screen And Registry

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerControlScreen.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ModRegistry.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ClientRegistry.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/en_us.json`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/ru_ru.json`

- [ ] Register the new menu type and client screen.
- [ ] Wire `ModObjects.openComputerControlMenu`.
- [ ] Render status and Turn on / Shutdown / Reboot buttons.

### Task 4: Verification

**Commands:**
- `./gradlew :v1_21_1-common:test --tests '*ComputerControl*'`
- `./gradlew :v1_21_1-common:compileKotlin :v1_21_1-neoforge:compileKotlin :v1_21_1-neoforge:processResources`
- `./gradlew :v1_21_1-neoforge:test --tests '*NetworkHandlerPayloadIdTest*'`

- [ ] Commit as `feat: add computer control menu`.
