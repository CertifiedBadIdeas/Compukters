# Epic 1 — Stream I/O Abstraction in Runtime (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a byte-oriented `stdout` stream in the VM runtime and make existing `terminal.*` host calls re-emit VT-100 escape sequences into that stream, with a server-side compat layer that feeds the stream back into the existing `ScreenBuffer`. No user-visible change.

**Architecture:** New `VtParser` + `VtSink` module in `compiler/runtime/vt`, new `ComputerStdioApi` host interface wired as a `stdout` builtin module, new server-side `VmStdioApi` whose sink is the existing `ScreenBuffer`. Existing `VmTerminalApi` is re-implemented on top of `stdout`. New ROM stdlib `term.ck`; all ROM programs migrate to it.

**Tech Stack:** Kotlin/JVM, `kotlin.test`, Gradle. Target modules: `compiler`, `core`. ROM files in `v1_21_1-neoforge/src/main/resources/rom/`.

---

## File Structure

**Create:**

- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtSink.kt` — interface the parser mutates (`printChar`, `moveCursor`, `clearScreen`, `eraseLine`, `setFg`, `setBg`, `saveCursor`, `restoreCursor`, `cursorRelative`).
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt` — state machine consuming `String` chunks (UTF-8 strings suffice for Epic 1) feeding a `VtSink`.
- `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt` — parser tests (printable, CSI cursor, erase, SGR, save/restore).
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerStdioApi.kt` — new host-facing interface.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmStdioApi.kt` — server-side impl routing through `VtParser` into `ScreenBuffer`.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ScreenBufferVtSink.kt` — adapter from `VtSink` to `ScreenBuffer`.
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmStdioApiTest.kt` — integration test.
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/term.ck` — new ROM stdlib.

**Modify:**

- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt` — add `val stdio: ComputerStdioApi` to `ComputerRuntime` interface.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt` — register `stdout` builtin module with `write(String)` function.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt` — add `invokeStdout` case and method.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt` — construct `VmStdioApi` in `createRuntime`, pass into `VmRuntime`.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmTerminalApi.kt` — re-implement `write`, `printLine`, `clear`, `setCursor` as calls to `stdio.writeString` with appropriate VT sequences.
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/bios.ck`, `shell.ck`, `ls.ck`, `pwd.ck`, `mkdir.ck`, `rmdir.ck` — import `term` where sensible; no functional change required.

**Cite:** exact line numbers come from the exploration report at the top of this plan's conversation. Every `invoke*` method in [RuntimeHostBridge.kt#L130-L158](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt#L130-L158) is the template for the new `invokeStdout`.

---

## Task 1: `VtSink` interface

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtSink.kt`

- [ ] **Step 1: Create the interface**

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.vt

/**
 * Mutation target for the VT-100 parser.
 *
 * Implementations accept high-level terminal operations and apply them to
 * whatever backing store they own (ScreenBuffer on server, a client-side
 * buffer in later epics).
 *
 * All coordinates are 1-based (row, col) to match the VT-100 wire protocol.
 * Converters to the project-internal 0-based coordinate system live inside
 * the sink implementations.
 */
interface VtSink {
    fun printChar(ch: Char)

    /** CSI `H` / `f`. Passing null for a component means "current value". */
    fun moveCursor(row: Int?, col: Int?)

    /** CSI `A`/`B`/`C`/`D`: relative cursor moves. `delta` positive. */
    fun cursorRelative(deltaRows: Int, deltaCols: Int)

    /** CSI `J`. 0=below, 1=above, 2=all. */
    fun eraseDisplay(mode: Int)

    /** CSI `K`. 0=right, 1=left, 2=whole line. */
    fun eraseLine(mode: Int)

    /** CSI `m`. `0` resets both colours and attributes. */
    fun setForegroundColor(color: Int)

    fun setBackgroundColor(color: Int)

    fun resetAttributes()

    /** CSI `s` / `u`. */
    fun saveCursor()

    fun restoreCursor()

    /** Raw `\n` (LF). */
    fun lineFeed()

    /** Raw `\r` (CR). */
    fun carriageReturn()

    /** Raw `\b`. Backspace = cursor left one, no erase. */
    fun backspace()
}
```

- [ ] **Step 2: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtSink.kt
git commit -m "feat(runtime): introduce VtSink interface"
```

---

## Task 2: `VtParser` skeleton + printable chars

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt`
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.vt

import kotlin.test.Test
import kotlin.test.assertEquals

private class RecordingSink : VtSink {
    val events = mutableListOf<String>()
    override fun printChar(ch: Char) { events += "print($ch)" }
    override fun moveCursor(row: Int?, col: Int?) { events += "move($row,$col)" }
    override fun cursorRelative(deltaRows: Int, deltaCols: Int) { events += "rel($deltaRows,$deltaCols)" }
    override fun eraseDisplay(mode: Int) { events += "eraseDisp($mode)" }
    override fun eraseLine(mode: Int) { events += "eraseLine($mode)" }
    override fun setForegroundColor(color: Int) { events += "fg($color)" }
    override fun setBackgroundColor(color: Int) { events += "bg($color)" }
    override fun resetAttributes() { events += "reset" }
    override fun saveCursor() { events += "save" }
    override fun restoreCursor() { events += "restore" }
    override fun lineFeed() { events += "lf" }
    override fun carriageReturn() { events += "cr" }
    override fun backspace() { events += "bs" }
}

class VtParserTest {
    @Test
    fun parsesPrintableCharsAndControlChars() {
        val sink = RecordingSink()
        VtParser(sink).feed("ab\r\n\b")
        assertEquals(listOf("print(a)", "print(b)", "cr", "lf", "bs"), sink.events)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.vt.VtParserTest.parsesPrintableCharsAndControlChars`
Expected: FAIL — `VtParser` unresolved reference.

- [ ] **Step 3: Implement minimal parser**

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.vt

/**
 * Streaming VT-100 subset parser. State machine; safe to feed any slice size.
 * Call [feed] with any chunk of text; internal state carries across calls.
 */
class VtParser(
    private val sink: VtSink,
) {
    private enum class State { GROUND, ESCAPE, CSI }
    private var state = State.GROUND
    private val csiBuffer = StringBuilder()

    fun feed(chunk: String) {
        for (ch in chunk) feedChar(ch)
    }

    private fun feedChar(ch: Char) {
        when (state) {
            State.GROUND -> ground(ch)
            State.ESCAPE -> escape(ch)
            State.CSI -> csi(ch)
        }
    }

    private fun ground(ch: Char) {
        when (ch) {
            '\u001b' -> state = State.ESCAPE
            '\n' -> sink.lineFeed()
            '\r' -> sink.carriageReturn()
            '\b' -> sink.backspace()
            else -> sink.printChar(ch)
        }
    }

    private fun escape(ch: Char) {
        when (ch) {
            '[' -> { state = State.CSI; csiBuffer.clear() }
            else -> { state = State.GROUND } // unknown escape; drop silently for now
        }
    }

    private fun csi(ch: Char) {
        // Placeholder — implemented in later tasks.
        state = State.GROUND
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.vt.VtParserTest.parsesPrintableCharsAndControlChars`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt
git commit -m "feat(runtime): VtParser handles printable and control chars"
```

---

## Task 3: CSI cursor positioning (`\e[H`, `\e[r;cH`)

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `VtParserTest`:

```kotlin
    @Test
    fun parsesCsiCursorPositioning() {
        val sink = RecordingSink()
        VtParser(sink).feed("\u001b[H\u001b[3;5H\u001b[12H")
        assertEquals(listOf("move(null,null)", "move(3,5)", "move(12,null)"), sink.events)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.vt.VtParserTest.parsesCsiCursorPositioning`
Expected: FAIL — sink.events empty (csi() drops everything).

- [ ] **Step 3: Implement CSI dispatch**

Replace the `csi` method body and add helpers:

```kotlin
    private fun csi(ch: Char) {
        if (ch in '0'..'9' || ch == ';') {
            csiBuffer.append(ch)
            return
        }
        val params = parseParams(csiBuffer.toString())
        state = State.GROUND
        when (ch) {
            'H', 'f' -> sink.moveCursor(params.getOrNull(0), params.getOrNull(1))
        }
    }

    /**
     * Parses `"3;5"` → `[3, 5]`. Empty positions become `null` (default).
     * Empty overall → empty list (caller uses `getOrNull(i)` → null).
     */
    private fun parseParams(raw: String): List<Int?> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(';').map { if (it.isEmpty()) null else it.toInt() }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.vt.VtParserTest.parsesCsiCursorPositioning`
Expected: PASS.

- [ ] **Step 5: Verify no regression on Task 2 test**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.vt.VtParserTest`
Expected: 2/2 PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt
git commit -m "feat(runtime): VtParser handles CSI cursor positioning"
```

---

## Task 4: CSI erase, relative move, save/restore

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `VtParserTest`:

```kotlin
    @Test
    fun parsesCsiEraseCommands() {
        val sink = RecordingSink()
        VtParser(sink).feed("\u001b[J\u001b[2J\u001b[K\u001b[1K")
        assertEquals(listOf("eraseDisp(0)", "eraseDisp(2)", "eraseLine(0)", "eraseLine(1)"), sink.events)
    }

    @Test
    fun parsesCsiRelativeCursor() {
        val sink = RecordingSink()
        VtParser(sink).feed("\u001b[A\u001b[3B\u001b[2C\u001b[D")
        assertEquals(listOf("rel(-1,0)", "rel(3,0)", "rel(0,2)", "rel(0,-1)"), sink.events)
    }

    @Test
    fun parsesCsiSaveRestore() {
        val sink = RecordingSink()
        VtParser(sink).feed("\u001b[s\u001b[u")
        assertEquals(listOf("save", "restore"), sink.events)
    }
```

- [ ] **Step 2: Run failing tests**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.vt.VtParserTest`
Expected: 3 new tests FAIL.

- [ ] **Step 3: Extend `csi` dispatch**

Extend the `when (ch)` inside `csi()`:

```kotlin
            'H', 'f' -> sink.moveCursor(params.getOrNull(0), params.getOrNull(1))
            'J' -> sink.eraseDisplay(params.getOrNull(0) ?: 0)
            'K' -> sink.eraseLine(params.getOrNull(0) ?: 0)
            'A' -> sink.cursorRelative(-(params.getOrNull(0) ?: 1), 0)
            'B' -> sink.cursorRelative(params.getOrNull(0) ?: 1, 0)
            'C' -> sink.cursorRelative(0, params.getOrNull(0) ?: 1)
            'D' -> sink.cursorRelative(0, -(params.getOrNull(0) ?: 1))
            's' -> sink.saveCursor()
            'u' -> sink.restoreCursor()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.vt.VtParserTest`
Expected: 5/5 PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt
git commit -m "feat(runtime): VtParser handles erase, relative cursor, save/restore"
```

---

## Task 5: CSI SGR colors

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt`

- [ ] **Step 1: Write failing test**

Append to `VtParserTest`:

```kotlin
    @Test
    fun parsesSgrColorsAndReset() {
        val sink = RecordingSink()
        VtParser(sink).feed("\u001b[31m\u001b[42m\u001b[0m\u001b[91m\u001b[102m")
        assertEquals(
            listOf("fg(1)", "bg(2)", "reset", "fg(9)", "bg(10)"),
            sink.events,
        )
    }
```

Note: 30–37 → fg(0..7), 40–47 → bg(0..7), 90–97 → fg(8..15), 100–107 → bg(8..15). `0` → resetAttributes.

- [ ] **Step 2: Run failing test**

Expected: FAIL.

- [ ] **Step 3: Implement SGR**

Add to `csi()` `when`:

```kotlin
            'm' -> handleSgr(params)
```

Add method to the class:

```kotlin
    private fun handleSgr(params: List<Int?>) {
        val effective = if (params.isEmpty()) listOf(0) else params.map { it ?: 0 }
        for (p in effective) {
            when (p) {
                0 -> sink.resetAttributes()
                in 30..37 -> sink.setForegroundColor(p - 30)
                in 40..47 -> sink.setBackgroundColor(p - 40)
                in 90..97 -> sink.setForegroundColor(p - 90 + 8)
                in 100..107 -> sink.setBackgroundColor(p - 100 + 8)
                // Unknown SGR params silently ignored for now.
            }
        }
    }
```

- [ ] **Step 4: Run to verify**

Expected: 6/6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt
git commit -m "feat(runtime): VtParser handles SGR color sequences"
```

---

## Task 6: `ScreenBufferVtSink` adapter

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ScreenBufferVtSink.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ScreenBufferVtSinkTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package ru.lazyhat.compukterkraft.core.computer.vm.api

import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenBufferVtSinkTest {
    @Test
    fun printingCharsWritesToBuffer() {
        val buffer = ScreenBuffer(width = 10, height = 3, colour = true)
        val sink = ScreenBufferVtSink(buffer)
        sink.printChar('H'); sink.printChar('i')
        val snap = buffer.forceSnapshot()
        assertEquals('H', snap.charAt(0, 0))
        assertEquals('i', snap.charAt(1, 0))
    }

    @Test
    fun moveCursorIsOneBasedFromVtToZeroBasedBuffer() {
        val buffer = ScreenBuffer(width = 10, height = 5, colour = true)
        val sink = ScreenBufferVtSink(buffer)
        sink.moveCursor(row = 2, col = 4)
        sink.printChar('X')
        val snap = buffer.forceSnapshot()
        assertEquals('X', snap.charAt(3, 1)) // col-1=3, row-1=1
    }

    @Test
    fun eraseDisplayModeTwoClears() {
        val buffer = ScreenBuffer(width = 4, height = 2, colour = true)
        val sink = ScreenBufferVtSink(buffer)
        sink.printChar('A'); sink.printChar('B')
        sink.eraseDisplay(2)
        val snap = buffer.forceSnapshot()
        assertEquals(' ', snap.charAt(0, 0))
        assertEquals(' ', snap.charAt(1, 0))
    }
}
```

- [ ] **Step 2: Run failing test**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.computer.vm.api.ScreenBufferVtSinkTest`
Expected: FAIL — `ScreenBufferVtSink` unresolved.

- [ ] **Step 3: Implement adapter**

```kotlin
package ru.lazyhat.compukterkraft.core.computer.vm.api

import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import ru.lazyhat.compukterkraft.lang.runtime.vt.VtSink

/**
 * Adapts [VtSink] operations (1-based VT coordinates) to the project-internal
 * [ScreenBuffer] API (0-based, cursor-based mutations).
 */
class ScreenBufferVtSink(
    private val buffer: ScreenBuffer,
) : VtSink {
    private var savedCursorX: Int = 0
    private var savedCursorY: Int = 0

    override fun printChar(ch: Char) = buffer.write(ch.toString())

    override fun moveCursor(row: Int?, col: Int?) {
        val targetRow = (row ?: 1).coerceAtLeast(1) - 1
        val targetCol = (col ?: 1).coerceAtLeast(1) - 1
        buffer.setCursor(targetCol, targetRow)
    }

    override fun cursorRelative(deltaRows: Int, deltaCols: Int) {
        val snap = buffer.forceSnapshot()
        val newX = (snap.cursorX + deltaCols).coerceIn(0, buffer.width - 1)
        val newY = (snap.cursorY + deltaRows).coerceIn(0, buffer.height - 1)
        buffer.setCursor(newX, newY)
    }

    override fun eraseDisplay(mode: Int) {
        if (mode == 2) buffer.clear()
        // modes 0/1 not yet implemented (Epic 1 YAGNI).
    }

    override fun eraseLine(mode: Int) {
        // Minimal: overwrite to end of line with spaces; advanced modes later.
        if (mode == 0 || mode == 2) {
            val snap = buffer.forceSnapshot()
            val savedX = snap.cursorX
            val y = snap.cursorY
            val spaces = " ".repeat(buffer.width - savedX)
            buffer.write(spaces)
            buffer.setCursor(savedX, y)
        }
    }

    override fun setForegroundColor(color: Int) = buffer.setForegroundColour(color)

    override fun setBackgroundColor(color: Int) = buffer.setBackgroundColour(color)

    override fun resetAttributes() {
        buffer.setForegroundColour(0)
        buffer.setBackgroundColour(15)
    }

    override fun saveCursor() {
        val snap = buffer.forceSnapshot()
        savedCursorX = snap.cursorX
        savedCursorY = snap.cursorY
    }

    override fun restoreCursor() = buffer.setCursor(savedCursorX, savedCursorY)

    override fun lineFeed() = buffer.write("\n")

    override fun carriageReturn() {
        val snap = buffer.forceSnapshot()
        buffer.setCursor(0, snap.cursorY)
    }

    override fun backspace() {
        val snap = buffer.forceSnapshot()
        if (snap.cursorX > 0) buffer.setCursor(snap.cursorX - 1, snap.cursorY)
    }
}
```

Note: the `width` / `height` fields on `ScreenBuffer` are public (see [ScreenBuffer.kt#L20-L50](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ScreenBuffer.kt#L20-L50)). If they are not, the implementation must read dimensions from `forceSnapshot()` instead.

- [ ] **Step 4: Verify dimensions access**

Before running, inspect [ScreenBuffer.kt](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ScreenBuffer.kt) lines 20-50 to confirm `width` and `height` are accessible as `val` on the class. If they are `private`, substitute `buffer.forceSnapshot().width` / `.height` in the code above.

- [ ] **Step 5: Run tests**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.computer.vm.api.ScreenBufferVtSinkTest`
Expected: 3/3 PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ScreenBufferVtSink.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ScreenBufferVtSinkTest.kt
git commit -m "feat(runtime): ScreenBufferVtSink adapter from VtSink to ScreenBuffer"
```

---

## Task 7: `ComputerStdioApi` interface + `VmStdioApi` impl

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerStdioApi.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt` — add `val stdio: ComputerStdioApi`.
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmStdioApi.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmStdioApiTest.kt`

- [ ] **Step 1: Create `ComputerStdioApi` interface**

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime

/**
 * Byte-stream I/O between the VM and attached terminals.
 *
 * Epic 1 exposes only output (writeString). Input and a future attachment-count
 * signal are reserved for Epic 2.
 */
interface ComputerStdioApi {
    /**
     * Append the given text (UTF-16 chars, interpreted as a VT-100 byte stream)
     * to the computer's stdout. On the server this is fed through [VtParser]
     * into the existing ScreenBuffer; later epics broadcast it over the network.
     */
    fun writeString(text: String)
}
```

- [ ] **Step 2: Modify `ComputerRuntime` interface**

In [ComputerRuntime.kt](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt), locate the `ComputerRuntime` interface (it aggregates `terminal`, `filesystem`, `process`, etc.) and add:

```kotlin
    val stdio: ComputerStdioApi
```

alongside the existing `val terminal: ComputerTerminalApi`.

- [ ] **Step 3: Write failing test for `VmStdioApi`**

```kotlin
package ru.lazyhat.compukterkraft.core.computer.vm.api

import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class VmStdioApiTest {
    @Test
    fun writeStringReachesBufferThroughVtParser() {
        val buffer = ScreenBuffer(width = 10, height = 3, colour = true)
        val stdio = VmStdioApi(buffer)
        stdio.writeString("Hi")
        val snap = buffer.forceSnapshot()
        assertEquals('H', snap.charAt(0, 0))
        assertEquals('i', snap.charAt(1, 0))
    }

    @Test
    fun escapeSequencesAreInterpreted() {
        val buffer = ScreenBuffer(width = 10, height = 3, colour = true)
        val stdio = VmStdioApi(buffer)
        stdio.writeString("\u001b[2J\u001b[2;3HX")
        val snap = buffer.forceSnapshot()
        assertEquals('X', snap.charAt(2, 1)) // col-1=2, row-1=1
    }
}
```

- [ ] **Step 4: Implement `VmStdioApi`**

```kotlin
package ru.lazyhat.compukterkraft.core.computer.vm.api

import ru.lazyhat.compukterkraft.lang.runtime.ComputerStdioApi
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import ru.lazyhat.compukterkraft.lang.runtime.vt.VtParser

/**
 * Server-side implementation of [ComputerStdioApi] used in Epic 1.
 * Every write is funneled through a [VtParser] whose sink is the
 * attached [ScreenBuffer] — preserving the pre-refactor behaviour exactly.
 *
 * Later epics replace this with a broadcaster feeding N network sessions.
 */
class VmStdioApi(
    buffer: ScreenBuffer,
) : ComputerStdioApi {
    private val parser = VtParser(ScreenBufferVtSink(buffer))

    override fun writeString(text: String) {
        parser.feed(text)
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.computer.vm.api.VmStdioApiTest`
Expected: 2/2 PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerStdioApi.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmStdioApi.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmStdioApiTest.kt
git commit -m "feat(runtime): introduce ComputerStdioApi backed by VtParser"
```

---

## Task 8: Wire `VmStdioApi` into `BackgroundComputerVm`

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt` — construct `VmStdioApi(screenBuffer)` and pass into the runtime.

- [ ] **Step 1: Read current `createRuntime` body**

Open [BackgroundComputerVm.kt#L264-L302](modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt#L264-L302). Locate the construction of `VmRuntime`. You will see:

```kotlin
val terminalApi = VmTerminalApi(screenBuffer = screenBuffer, ctx = this)
// … other apis …
val runtime = VmRuntime(
    terminal = terminalApi,
    // … other apis …
)
```

- [ ] **Step 2: Add stdio construction**

Immediately before the `VmRuntime(` call, add:

```kotlin
val stdioApi = VmStdioApi(buffer = screenBuffer)
```

And add a `stdio = stdioApi` argument to the `VmRuntime(…)` call. Note: `VmRuntime` (the concrete type implementing `ComputerRuntime`) must accept this new parameter. If the file defining `VmRuntime` is distinct (search for `class VmRuntime` — likely in same package), add the `stdio: ComputerStdioApi` constructor parameter there.

- [ ] **Step 3: Run build**

Run: `./gradlew :core:compileKotlin :compiler:compileKotlin`
Expected: SUCCESS. Any missing overrides will surface as errors; fix by propagating the new property.

- [ ] **Step 4: Run existing tests**

Run: `./gradlew :core:test :compiler:test`
Expected: all green — no behavioural changes yet.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt
# plus any other files touched (VmRuntime.kt likely)
git commit -m "feat(runtime): wire VmStdioApi into BackgroundComputerVm"
```

---

## Task 9: Register `stdout` builtin module

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`

- [ ] **Step 1: Read registration pattern**

Open [LanguageBuiltins.kt#L34-L69](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt#L34-L69). Study how the `"terminal"` module is registered (function names and arities).

- [ ] **Step 2: Add `stdout` module registration**

Immediately after the `terminal` module registration block, add an analogous block:

```kotlin
    registerModule("stdout") {
        function("write", listOf(BuiltinParam("text", BuiltinType.String)), BuiltinType.Unit)
    }
```

(Exact method names depend on the file's DSL — mirror what `terminal` does.)

- [ ] **Step 3: Open `RuntimeHostBridge.kt`**

Find the dispatch `when` block that routes by module name (around [line 31](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt#L31)). You'll see:

```kotlin
    fun invoke(module: String, function: String, args: List<VmValue>): VmValue =
        when (module) {
            "terminal" -> invokeTerminal(function, args)
            "filesystem" -> invokeFilesystem(function, args)
            …
        }
```

Add a branch:

```kotlin
            "stdout" -> invokeStdout(function, args)
```

- [ ] **Step 4: Implement `invokeStdout`**

Add a method mirroring `invokeTerminal` ([line 130](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt#L130)):

```kotlin
    private fun invokeStdout(function: String, args: List<VmValue>): VmValue {
        when (function) {
            "write" -> {
                require(args.size == 1) { "stdout.write expects exactly 1 argument" }
                runtime.stdio.writeString(args[0].asString())
                return VmValue.unit()
            }
            else -> throw IllegalArgumentException("Unknown stdout function: $function")
        }
    }
```

(Use whatever the actual `VmValue.unit()` / `.asString()` helpers are — confirm by looking at `invokeTerminal`.)

- [ ] **Step 5: Write integration test**

Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/StdoutHostCallTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class StdoutHostCallTest {
    @Test
    fun stdoutWriteReachesScreenBufferThroughBridge() {
        val fixture = RuntimeTestFixture()   // see existing LanguageRuntimeTest for pattern
        fixture.compileAndRun("""
            stdout.write("Hello")
        """.trimIndent())
        val snap = fixture.screenBuffer.forceSnapshot()
        assertEquals('H', snap.charAt(0, 0))
        assertEquals('e', snap.charAt(1, 0))
    }
}
```

Note: `RuntimeTestFixture` is referenced as a conceptual test harness. If no such fixture exists, inspect [LanguageRuntimeTest.kt#L39+](modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt) to see the actual setup pattern (it constructs a mock `ComputerRuntime` and runs bytecode directly); replicate that here inline.

- [ ] **Step 6: Run the test**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.StdoutHostCallTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/StdoutHostCallTest.kt
git commit -m "feat(runtime): register stdout.write host call"
```

---

## Task 10: Re-route `VmTerminalApi` through stdout

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmTerminalApi.kt`

Goal: `VmTerminalApi.write/printLine/clear/setCursor` now emit VT sequences into `stdio` instead of mutating `screenBuffer` directly. All pre-existing tests must still pass (because the server-side stdio routes back to the same `screenBuffer`).

- [ ] **Step 1: Read current implementation**

Open [VmTerminalApi.kt#L33-L74](modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmTerminalApi.kt#L33-L74). The current constructor is `VmTerminalApi(screenBuffer: ScreenBuffer, ctx: ...)`.

- [ ] **Step 2: Change constructor to take stdio instead of screenBuffer**

```kotlin
class VmTerminalApi(
    private val stdio: ComputerStdioApi,
    private val ctx: VmRuntimeContext,
    private val screenBufferForReadLineCompat: ScreenBuffer, // kept ONLY so readLine can still drive cursor blink for now
) : ComputerTerminalApi {
    override val screenBuffer: ScreenBuffer = screenBufferForReadLineCompat

    override fun write(text: String) {
        stdio.writeString(text)
    }

    override fun printLine(text: String) {
        stdio.writeString(text)
        stdio.writeString("\n")
    }

    override fun clear() {
        stdio.writeString("\u001b[2J\u001b[H")
    }

    override fun setCursor(x: Int, y: Int) {
        // VT is 1-based; our 0-based (x=col, y=row) → "\e[row;col H"
        stdio.writeString("\u001b[${y + 1};${x + 1}H")
    }

    // readLine left unchanged for Epic 1. Epic 2 migrates input to stdio.
    override suspend fun readLine(prompt: String): String =
        /* existing logic; still pokes screenBufferForReadLineCompat for cursor blink */
}
```

Important: `screenBuffer` property on `ComputerTerminalApi` is still read by VM code (some programs may call `terminal.screenBuffer`). Leave it exposed for now. Epic 2 removes it.

- [ ] **Step 3: Fix the construction site**

In [BackgroundComputerVm.kt createRuntime()](modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt#L264):

```kotlin
val stdioApi = VmStdioApi(buffer = screenBuffer)
val terminalApi = VmTerminalApi(
    stdio = stdioApi,
    ctx = this,
    screenBufferForReadLineCompat = screenBuffer,
)
```

- [ ] **Step 4: Run full test suite**

Run: `./gradlew :core:test :compiler:test`
Expected: all green. Pre-existing `LanguageRuntimeTest.executesHostCallsThroughRuntimeBridge()` proves `terminal.printLine()` still visibly writes through VT path.

- [ ] **Step 5: If any test fails, diagnose**

Likely failure: cursor-blink-during-readLine behaviour. If broken, a quick fix is to have readLine call `screenBufferForReadLineCompat.setCursorBlink(true)` directly — the parser doesn't handle cursor blink yet (out of scope for Epic 1). Document in a `// TODO(Epic 2):` comment.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmTerminalApi.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt
git commit -m "feat(runtime): VmTerminalApi routes through stdio + VT sequences"
```

---

## Task 11: Create `rom/term.ck` stdlib

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/term.ck`

- [ ] **Step 1: Write the stdlib**

```
// Terminal helpers built on top of the stdout byte stream.
// All functions produce VT-100 escape sequences that the terminal
// (server compat layer today, remote client in Epic 2+) interprets.

fun cursor(row, col) {
    stdout.write("\e[" + row + ";" + col + "H")
}

fun clear() {
    stdout.write("\e[2J\e[H")
}

fun eraseLine() {
    stdout.write("\e[K")
}

fun setFg(color) {
    if (color < 8) {
        stdout.write("\e[" + (30 + color) + "m")
    } else {
        stdout.write("\e[" + (90 + color - 8) + "m")
    }
}

fun setBg(color) {
    if (color < 8) {
        stdout.write("\e[" + (40 + color) + "m")
    } else {
        stdout.write("\e[" + (100 + color - 8) + "m")
    }
}

fun resetAttr() {
    stdout.write("\e[0m")
}

fun print(text) {
    stdout.write(text)
}

fun println(text) {
    stdout.write(text + "\n")
}
```

Note: confirm that the `.ck` language:
1. Supports `\e` as ESC (if not, use `chr(27)` or equivalent).
2. Has simple `fun` syntax and `if/else` as shown.
3. Can be used as a shared module via `use term` or similar — check how [ls.ck](modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/ls.ck) imports `strings`.

If `\e` is not a recognised escape in string literals, prepend a helper:

```
const ESC = chr(27)
// then: stdout.write(ESC + "[2J" + ESC + "[H")
```

- [ ] **Step 2: Manual smoke-test by editing bios.ck temporarily**

Temporarily add to [bios.ck](modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/bios.ck):

```
use term

term.clear()
term.setFg(2)
term.println("hello from term.ck")
term.resetAttr()
```

- [ ] **Step 3: Run the mod (one-off game test)**

Run: `./gradlew :v1_21_1-neoforge:runClient`
Expected: after placing a computer and turning it on, the screen shows green "hello from term.ck".

(This is a manual check — not part of the automated suite. Revert the temporary `bios.ck` edit afterwards.)

- [ ] **Step 4: Revert temporary bios.ck change**

- [ ] **Step 5: Commit**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/term.ck
git commit -m "feat(rom): add term.ck stdlib over the stdout byte stream"
```

---

## Task 12: Migrate ROM programs to use `term`

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/bios.ck`, `shell.ck`, `ls.ck`, `pwd.ck`, `mkdir.ck`, `rmdir.ck`

This is mostly mechanical: replace `terminal.printLine(x)` with `term.println(x)`, `terminal.write(x)` with `term.print(x)`, `terminal.setCursor(x, y)` with `term.cursor(y + 1, x + 1)`, `terminal.clear()` with `term.clear()`.

- [ ] **Step 1: For each ROM file, add `use term` at the top and replace calls**

Do them one at a time. After each file, run the mod (`./gradlew :v1_21_1-neoforge:runClient`) and place a computer to smoke-test.

- [ ] **Step 2: Commit after each file**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/bios.ck
git commit -m "refactor(rom): bios.ck uses term stdlib"
```

…and so on for shell.ck, ls.ck, pwd.ck, mkdir.ck, rmdir.ck — six commits total or one squash commit at the end, at the executor's discretion.

- [ ] **Step 3: Final full suite run**

Run: `./gradlew :core:test :compiler:test`
Expected: all green.

- [ ] **Step 4: Final manual smoke test**

Run: `./gradlew :v1_21_1-neoforge:runClient`
Expected: place computer, turn on, navigate shell (`ls`, `pwd`, `cd`, `mkdir foo`, `rmdir foo`). All commands print identically to before.

---

## Done Criteria for Epic 1

- All of the above tasks' tests pass.
- `./gradlew :core:test :compiler:test` fully green.
- Running the mod in-game looks identical to the state before the epic: same terminal, same colours, same behaviour.
- The VM now has a `stdout` host module. Any program can write raw VT bytes through it.
- All ROM programs route through `term.ck`.
- No network, no client UI change.

---

## Notes for the Executor

1. **Kotlin test harness for `.ck` tests:** inspect [LanguageRuntimeTest.kt](modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt) before Task 9 — it shows exactly how to compile + run a `.ck` snippet in tests. Replicate that pattern; do not invent new fixtures.
2. **`VmRuntime` constructor:** if `VmRuntime` is a concrete class (not an interface) defined alongside `ComputerRuntime`, adding a new property requires updating the class too. Use an IDE or `grep -n "class VmRuntime" modules/core modules/compiler` to find it.
3. **`ScreenBuffer` dimensions accessor:** Task 6 assumes `width`/`height` are public `val`. If they aren't, use `forceSnapshot().width` / `.height` — but note: `forceSnapshot` allocates arrays; prefer caching in the sink's constructor if it is called per-event. For Epic 1's low call volume this does not matter.
4. **Language escape syntax:** if the `.ck` compiler does not recognise `\e` in string literals, you may need to use `chr(27)` throughout `term.ck`. Confirm by looking at the language's lexer (search for `'\e'` or `escape` in `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/`).
5. **Stop and ask:** if `ComputerRuntime` turns out to be something other than a simple interface, or `RuntimeHostBridge.invoke` dispatches differently than described, halt and confirm with the user before guessing.
