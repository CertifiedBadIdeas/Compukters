# BIOS Splash Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a short firmware-level pixel-art BIOS splash before `boot.ck` starts.

**Architecture:** Keep the splash entirely inside `firmware/bios.ck`. Use existing display primitives and a CKL `sleep(1)` + `events::tryPull()` loop so the VM waits for real ticks while display attach/resize can redraw the splash. Add source-level regression tests in `RomScriptCompileTest.kt` and keep existing firmware/ROM compilation tests green.

**Tech Stack:** CKL firmware scripts, Kotlin tests, Gradle `:v1_21_1-neoforge:test`.

---

## File Structure

- Modify `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck`: add splash helpers and call them before the existing `boot.ck` lookup.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`: add a regression test for splash ordering and display-only rendering.

### Task 1: Add BIOS Splash Regression

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`

- [ ] **Step 1: Write the failing test**

Add this test after `bundledFirmwareScriptCompilesCleanly()`:

```kotlin
    @Test
    fun bundledFirmwareShowsSplashBeforeBootLookup() {
        val source = resourceText("firmware/bios.ck")

        assertTrue(source.contains("fun draw_splash"), "bios.ck should have a dedicated splash renderer")
        assertTrue(source.contains("fun hold_splash"), "bios.ck should keep the splash visible before boot starts")
        assertTrue(source.contains("display::blitMono"), "bios.ck should render the splash through display primitives")
        assertTrue(source.contains("Compukter"), "bios.ck should include visible Compukter branding")
        assertTrue(source.contains("hold_splash(40)"), "bios.ck should hold the splash for roughly two seconds")
        assertTrue(
            source.indexOf("hold_splash(40)") < source.indexOf("filesystem::exists(\"boot.ck\")"),
            "bios.ck should show the splash before looking up boot.ck",
        )
        assertFalse(source.contains("stdout::write"), "bios.ck must not use stdout for visible splash UI")
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest.bundledFirmwareShowsSplashBeforeBootLookup`

Expected: FAIL because `draw_splash` and `hold_splash` are not present yet.

### Task 2: Implement Firmware Splash

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck`

- [ ] **Step 1: Add block sprite helpers**

Add helpers after `draw_text(...)` and before `draw_boot_frame(...)`:

```ck
fun draw_sprite(displayId: Int, x: Int, y: Int, width: Int, height: Int, pattern: String, color: Int) {
    display::blitMono(displayId, x, y, width, height, pattern, color, -1)
}

fun draw_splash(displayId: Int) {
    display::clear(displayId, 0)
    display::fillRect(displayId, 0, 0, display::width(displayId), 2, 2016)
    display::fillRect(displayId, 0, display::height(displayId) - 3, display::width(displayId), 3, 2016)
    draw_text(displayId, 1, "Compukter", 2016)
    draw_text(displayId, 3, "KRAFT BIOS", 65535)
    draw_sprite(displayId, 6, 52, 17, 9, "111111111111111111000000000000000110111100111100101101000001000001011011110011110010110000000000000001111111111111111110000001111100000000001111111110000", 63488)
    draw_text(displayId, 10, "Loading boot.ck...", 65535)
    display::present(displayId)
}

fun draw_splash_frame() {
    val id: Int = display::primary()
    if (id >= 0) {
        draw_splash(id)
    }
}

fun hold_splash(ticks: Int) {
    draw_splash_frame()
    var remaining: Int = ticks
    while remaining > 0 {
        val event: Event = events::tryPull()
        if (event.name == "display_attach" || event.name == "display_resize") {
            draw_splash_frame()
        }
        remaining = remaining - 1
        sleep(1)
    }
}
```

- [ ] **Step 2: Call splash before boot lookup**

In `pub fun main()`, insert `hold_splash(40)` before `var status: String = "Searching for boot.ck..."`:

```ck
pub fun main() {
    hold_splash(40)

    var status: String = "Searching for boot.ck..."
    draw_boot_frame(status)
```

### Task 3: Verify Firmware and Full Suite

**Files:**
- Verify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck`
- Verify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`

- [ ] **Step 1: Run ROM/firmware tests**

Run: `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run full suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Check diff hygiene**

Run: `git diff --check`

Expected: no output.
