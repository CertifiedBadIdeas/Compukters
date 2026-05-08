# Terminal Scrollback Hotkeys Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add CKL-side terminal scrollback with `PageUp` / `PageDown` while preserving draft input overlay behavior.

**Architecture:** Keep committed history in CKL with a bottom-window cache for fast normal rendering. Add viewport helpers that redraw historical windows only when the user scrolls or snaps back to bottom.

**Tech Stack:** CKL ROM script, existing ROM compile regression tests, existing Rust/Kotlin image lowering path.

---

### Task 1: Source-Level Regression Tests

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`

- [ ] **Step 1: Write the failing scrollback test**
- [ ] **Step 2: Run the ROM compile test and verify RED**

### Task 2: CKL Scrollback State

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`

- [ ] **Step 1: Extend `TerminalBuffer` with history + viewport fields**
- [ ] **Step 2: Add helpers to read/write committed history rows**
- [ ] **Step 3: Add `renderViewport(...)` and page-scroll helpers**

### Task 3: Input/Event Integration

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`

- [ ] **Step 1: Update append/commit flow to keep history and bottom cache in sync**
- [ ] **Step 2: Handle `PageUp` / `PageDown` in the key-event loop**
- [ ] **Step 3: Snap back to bottom for local input edits and only draw overlay at bottom**

### Task 4: Verification

- [ ] **Step 1: Run `RomScriptCompileTest`**
- [ ] **Step 2: Run bundled image audit test**
- [ ] **Step 3: Run `git diff --check`**
