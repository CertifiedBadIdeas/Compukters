# Notebook Display Screen Lifecycle Implementation Plan

> Issue: [#47](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/47)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract shared Rux display lifecycle from terminal-specific UI so Notebook can be a first-class laptop screen.

**Architecture:** Add `ComputerDisplayScreen` as a common base for attach/resize/detach, texture upload, keyboard focus, and display buffer sync. Keep terminal and notebook classes responsible only for their own chrome and controls.

**Tech Stack:** Kotlin, Minecraft/NeoForge screen APIs, existing UI DSL, existing Rux display network messages.

---

### Task 1: Shared Display Base

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerDisplayScreen.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/screen/NotebookScreen.kt`

- [x] Move display attach/resize/detach, texture upload, input controller, and focus lifecycle into `ComputerDisplayScreen`.
- [x] Make `ComputerTerminalScreen` extend `ComputerDisplayScreen` and keep only terminal chrome/buttons/layout.
- [x] Make `NotebookScreen` extend `ComputerDisplayScreen` and keep only notebook chrome/buttons/layout.
- [x] Verify with `./gradlew --no-daemon :v1_21_1-common:compileKotlin`.

### Task 2: NeoForge Compile Guard

**Files:**
- No intended source changes.

- [x] Run `./gradlew --no-daemon :v1_21_1-neoforge:compileKotlin`.
- [x] If compile fails because platform-specific screen registrations reference changed types, update those references only.

### Task 3: Commit

**Files:**
- Commit all files changed by Task 1 and this plan.

- [x] Run `git status --short`.
- [x] Commit with message `refactor: extract computer display screen lifecycle`.
