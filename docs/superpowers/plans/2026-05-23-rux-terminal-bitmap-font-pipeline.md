# Rux Terminal Bitmap Font Pipeline Implementation Plan

> Issue: [#49](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/49)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace duplicated Rux terminal glyph tables with generated Rust and Kotlin tables from one 5x7 bitmap font source.

**Architecture:** Keep the current `6x9` terminal cell and `5x7` glyph body. Store glyphs in a source font file, generate committed Rust/Kotlin lookup code, and update renderers to call the generated lookup.

**Tech Stack:** Rust, Kotlin, Gradle build-scripts, Cargo tests, Gradle tests.

---

### Task 1: Add Source Font And Generator

**Files:**
- Create: `assets/rux/fonts/rux-mono-5x7.font`
- Create: `tools/rux-font/generate-font-tables.py`
- Create: `native/rux-vm/src/generated/font_mono5x7.rs`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/gui/GeneratedTerminalFont.kt`

- [ ] Write a source font file with printable ASCII, required box drawing glyphs, and fallback glyph.
- [ ] Write a Gradle/Kotlin generator that validates every glyph is exactly 5 columns by 7 rows.
- [ ] Generate Rust and Kotlin packed `u64`/`Long` lookup tables.

### Task 2: Wire Renderers To Generated Font

**Files:**
- Modify: `native/rux-vm/src/display.rs`
- Modify: `native/rux-vm/src/lib.rs`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/SerialTextDisplayRenderer.kt`

- [ ] Replace duplicated hand-written glyph matches with generated lookup functions.
- [ ] Keep `TerminalFontConstants.FONT_WIDTH = 6` and `FONT_HEIGHT = 9`.
- [ ] Keep dirty rectangle and blit behavior unchanged.

### Task 3: Verify Font Coverage

**Files:**
- Modify: `native/rux-vm/src/display.rs`
- Modify: `native/rux-vm/tests/display_engine.rs`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/SerialTextDisplayRendererTest.kt`

- [ ] Add Rust tests for printable ASCII coverage, lowercase/uppercase differences, box drawing, and fallback glyph.
- [ ] Add Kotlin tests that render mixed-case text and verify foreground pixels are produced.
- [ ] Run focused Cargo and Gradle tests.
