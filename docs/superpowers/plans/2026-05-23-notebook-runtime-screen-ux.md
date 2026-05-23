# Notebook Runtime Screen UX Implementation Plan

> Issue: [#48](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/48)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve placed Notebook runtime screen UX without changing VM, ABI, firmware, or display lifecycle.

**Architecture:** Keep `NotebookScreen` on the shared `ComputerDisplayScreen` base from #47. Add local Notebook UI state helpers and state-aware controls inside `NotebookScreen` only.

**Tech Stack:** Kotlin, existing UI DSL, Minecraft/NeoForge screen APIs, existing Rux display/input lifecycle.

---

### Task 1: Notebook State And Controls

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/screen/NotebookScreen.kt`

- [x] Add local runtime state mapping for OFF, CONNECTING, and RUNNING.
- [x] Make status text clearer in the notebook header.
- [x] Make reboot inert and visually disabled while powered off.
- [x] Keep power button behavior unchanged: POWER when off, SHUTDOWN when on.

### Task 2: Notebook Future Slot Placeholder

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/screen/NotebookScreen.kt`

- [x] Add a simple non-interactive module bay placeholder in the status/footer area.
- [x] Keep it explicitly non-functional; storage/flash-drive slots are out of scope.

### Task 3: Verification

**Files:**
- No intended source changes.

- [x] Run `./gradlew --no-daemon :v1_21_1-common:compileKotlin`.
- [x] Run `./gradlew --no-daemon :v1_21_1-neoforge:compileKotlin`.
- [x] Commit with message `feat: polish notebook runtime screen ux`.
