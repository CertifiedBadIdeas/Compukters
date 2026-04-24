# Эпик 1 — Stream I/O абстракция в рантайме (план реализации)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Цель:** Ввести байт-ориентированный стрим `stdout` в рантайм VM и заставить существующие host-функции `terminal.*` переизлучать VT-100 escape-последовательности в этот стрим, с серверным compat-слоем, который скармливает стрим обратно в существующий `ScreenBuffer`. Никаких изменений снаружи.

**Архитектура:** Новый модуль `VtParser` + `VtSink` в `compiler/runtime/vt`, новый host-интерфейс `ComputerStdioApi` как builtin-модуль `stdout`, новая серверная `VmStdioApi`, синк которой — существующий `ScreenBuffer`. `VmTerminalApi` переписывается поверх `stdout`. Новая ROM stdlib `term.ck`; все ROM-программы мигрируют на неё.

**Tech Stack:** Kotlin/JVM, `kotlin.test`, Gradle. Целевые модули: `compiler`, `core`. ROM в `v1_21_1-neoforge/src/main/resources/rom/`.

---

## Файловая структура

**Создать:**

- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtSink.kt` — интерфейс, который мутирует парсер (`printChar`, `moveCursor`, `clearScreen`, `eraseLine`, `setFg`, `setBg`, `saveCursor`, `restoreCursor`, `cursorRelative`).
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt` — state-machine, принимающий куски `String` (UTF-16, достаточно для Эпика 1), кормит `VtSink`.
- `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt` — тесты парсера.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerStdioApi.kt` — новый host-интерфейс.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmStdioApi.kt` — серверная импл, пропускает байты через `VtParser` в `ScreenBuffer`.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ScreenBufferVtSink.kt` — адаптер `VtSink → ScreenBuffer`.
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmStdioApiTest.kt` — интеграционный тест.
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/term.ck` — новая ROM stdlib.

**Модифицировать:**

- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt` — добавить `val stdio: ComputerStdioApi`.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt` — зарегистрировать builtin-модуль `stdout` с функцией `write(String)`.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt` — добавить ветку `invokeStdout`.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt` — сконструировать `VmStdioApi` в `createRuntime`, пробросить в `VmRuntime`.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmTerminalApi.kt` — переписать `write`, `printLine`, `clear`, `setCursor` на вызовы `stdio.writeString` с VT-последовательностями.
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/bios.ck`, `shell.ck`, `ls.ck`, `pwd.ck`, `mkdir.ck`, `rmdir.ck` — импортировать `term` там, где разумно.

Точные номера строк — из разведочного отчёта, представленного в ходе brainstorm'а этого плана. Каждый `invoke*` в [RuntimeHostBridge.kt#L130-L158](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt#L130-L158) — шаблон для нового `invokeStdout`.

---

## Задача 1: интерфейс `VtSink`

**Файлы:**
- Создать: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtSink.kt`

- [ ] **Шаг 1: Создать интерфейс**

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.vt

/**
 * Цель мутаций для VT-100 парсера.
 *
 * Реализации принимают высокоуровневые терминальные операции и применяют
 * их к своему backing-сторе (ScreenBuffer на сервере, клиентский буфер
 * в поздних эпиках).
 *
 * Все координаты 1-based (row, col) — совпадает с VT-100 wire-форматом.
 * Конвертеры в 0-based проектные координаты живут внутри реализаций синка.
 */
interface VtSink {
    fun printChar(ch: Char)

    /** CSI `H` / `f`. Передача null означает «текущее значение». */
    fun moveCursor(row: Int?, col: Int?)

    /** CSI `A`/`B`/`C`/`D`: относительные сдвиги. `delta` положительный. */
    fun cursorRelative(deltaRows: Int, deltaCols: Int)

    /** CSI `J`. 0=ниже, 1=выше, 2=всё. */
    fun eraseDisplay(mode: Int)

    /** CSI `K`. 0=справа, 1=слева, 2=вся строка. */
    fun eraseLine(mode: Int)

    /** CSI `m`. `0` сбрасывает цвета и атрибуты. */
    fun setForegroundColor(color: Int)

    fun setBackgroundColor(color: Int)

    fun resetAttributes()

    /** CSI `s` / `u`. */
    fun saveCursor()

    fun restoreCursor()

    /** Сырой `\n` (LF). */
    fun lineFeed()

    /** Сырой `\r` (CR). */
    fun carriageReturn()

    /** Сырой `\b`. Backspace = курсор влево, без стирания. */
    fun backspace()
}
```

- [ ] **Шаг 2: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtSink.kt
git commit -m "feat(runtime): introduce VtSink interface"
```

---

## Задача 2: каркас `VtParser` + печатные символы

**Файлы:**
- Создать: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt`
- Создать: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt`

- [ ] **Шаг 1: Написать падающий тест**

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

- [ ] **Шаг 2: Убедиться, что тест падает**

Запустить: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.vt.VtParserTest.parsesPrintableCharsAndControlChars`
Ожидание: FAIL — `VtParser` unresolved.

- [ ] **Шаг 3: Минимальная реализация**

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.vt

/**
 * Стриминговый парсер подмножества VT-100. State machine; принимает любые
 * срезы произвольного размера; внутреннее состояние сохраняется между вызовами.
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
            else -> { state = State.GROUND } // неизвестный escape — молча дропаем
        }
    }

    private fun csi(ch: Char) {
        // Placeholder — будет реализован в следующих задачах.
        state = State.GROUND
    }
}
```

- [ ] **Шаг 4: Убедиться, что тест зелёный**

Запустить: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.vt.VtParserTest.parsesPrintableCharsAndControlChars`
Ожидание: PASS.

- [ ] **Шаг 5: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt
git commit -m "feat(runtime): VtParser handles printable and control chars"
```

---

## Задача 3: CSI позиционирование курсора (`\e[H`, `\e[r;cH`)

**Файлы:**
- Модифицировать: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt`
- Модифицировать: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt`

- [ ] **Шаг 1: Падающий тест**

Добавить в `VtParserTest`:

```kotlin
    @Test
    fun parsesCsiCursorPositioning() {
        val sink = RecordingSink()
        VtParser(sink).feed("\u001b[H\u001b[3;5H\u001b[12H")
        assertEquals(listOf("move(null,null)", "move(3,5)", "move(12,null)"), sink.events)
    }
```

- [ ] **Шаг 2: Убедиться в падении**

Запустить: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.vt.VtParserTest.parsesCsiCursorPositioning`
Ожидание: FAIL — события пустые (csi() всё дропает).

- [ ] **Шаг 3: Реализовать CSI-dispatch**

Заменить тело `csi` и добавить вспомогательную функцию:

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
     * Парсит `"3;5"` → `[3, 5]`. Пустые позиции → `null` (дефолт).
     * Пустая строка → пустой список (вызывающий использует `getOrNull(i)` → null).
     */
    private fun parseParams(raw: String): List<Int?> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(';').map { if (it.isEmpty()) null else it.toInt() }
    }
```

- [ ] **Шаг 4: Тесты зелёные**

Запустить: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.vt.VtParserTest.parsesCsiCursorPositioning`
Ожидание: PASS.

- [ ] **Шаг 5: Проверить отсутствие регрессии**

Запустить: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.vt.VtParserTest`
Ожидание: 2/2 PASS.

- [ ] **Шаг 6: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt
git commit -m "feat(runtime): VtParser handles CSI cursor positioning"
```

---

## Задача 4: CSI erase, relative move, save/restore

**Файлы:**
- Модифицировать: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt`
- Модифицировать: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt`

- [ ] **Шаг 1: Падающие тесты**

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

- [ ] **Шаг 2: Убедиться в падении**

Запустить: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.vt.VtParserTest`
Ожидание: 3 новых теста FAIL.

- [ ] **Шаг 3: Расширить CSI-dispatch**

Расширить `when (ch)` внутри `csi()`:

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

- [ ] **Шаг 4: Тесты зелёные**

Запустить: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.vt.VtParserTest`
Ожидание: 5/5 PASS.

- [ ] **Шаг 5: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt
git commit -m "feat(runtime): VtParser handles erase, relative cursor, save/restore"
```

---

## Задача 5: CSI SGR цвета

**Файлы:**
- Модифицировать: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt`
- Модифицировать: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt`

- [ ] **Шаг 1: Падающий тест**

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

Таблица: 30–37 → fg(0..7), 40–47 → bg(0..7), 90–97 → fg(8..15), 100–107 → bg(8..15), `0` → resetAttributes.

- [ ] **Шаг 2: Убедиться в падении**

Ожидание: FAIL.

- [ ] **Шаг 3: Реализовать SGR**

В `when` внутри `csi()`:

```kotlin
            'm' -> handleSgr(params)
```

Добавить метод класса:

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
                // Неизвестные SGR-параметры молча игнорируем.
            }
        }
    }
```

- [ ] **Шаг 4: Тесты зелёные**

Ожидание: 6/6 PASS.

- [ ] **Шаг 5: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParserTest.kt
git commit -m "feat(runtime): VtParser handles SGR color sequences"
```

---

## Задача 6: адаптер `ScreenBufferVtSink`

**Файлы:**
- Создать: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ScreenBufferVtSink.kt`
- Создать: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ScreenBufferVtSinkTest.kt`

- [ ] **Шаг 1: Падающий тест**

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
        assertEquals('X', snap.charAt(3, 1))
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

- [ ] **Шаг 2: Убедиться в падении**

Запустить: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.computer.vm.api.ScreenBufferVtSinkTest`
Ожидание: FAIL — `ScreenBufferVtSink` unresolved.

- [ ] **Шаг 3: Реализация адаптера**

```kotlin
package ru.lazyhat.compukterkraft.core.computer.vm.api

import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import ru.lazyhat.compukterkraft.lang.runtime.vt.VtSink

/**
 * Адаптер [VtSink] (1-based VT-координаты) на проектный [ScreenBuffer]
 * (0-based, мутации через курсор).
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
        // режимы 0/1 не реализованы в Эпике 1 (YAGNI).
    }

    override fun eraseLine(mode: Int) {
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

- [ ] **Шаг 4: Проверить доступ к dimensions**

Перед запуском — посмотреть [ScreenBuffer.kt](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ScreenBuffer.kt) lines 20-50 и убедиться, что `width`/`height` — public `val`. Если private, заменить в коде выше на `buffer.forceSnapshot().width` / `.height`.

- [ ] **Шаг 5: Тесты**

Запустить: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.computer.vm.api.ScreenBufferVtSinkTest`
Ожидание: 3/3 PASS.

- [ ] **Шаг 6: Коммит**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ScreenBufferVtSink.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ScreenBufferVtSinkTest.kt
git commit -m "feat(runtime): ScreenBufferVtSink adapter from VtSink to ScreenBuffer"
```

---

## Задача 7: интерфейс `ComputerStdioApi` + импл `VmStdioApi`

**Файлы:**
- Создать: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerStdioApi.kt`
- Модифицировать: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt` — добавить `val stdio: ComputerStdioApi`.
- Создать: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmStdioApi.kt`
- Создать: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmStdioApiTest.kt`

- [ ] **Шаг 1: Создать `ComputerStdioApi`**

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime

/**
 * Байт-стрим I/O между VM и подключёнными терминалами.
 *
 * Эпик 1 выставляет только output (writeString). Input и будущий сигнал
 * количества подключений зарезервированы для Эпика 2.
 */
interface ComputerStdioApi {
    /**
     * Дописывает текст (UTF-16 char'ы, интерпретируемые как VT-100 байт-стрим)
     * в stdout компьютера. На сервере проходит через [VtParser] в существующий
     * ScreenBuffer; поздние эпики рассылают по сети.
     */
    fun writeString(text: String)
}
```

- [ ] **Шаг 2: Модифицировать `ComputerRuntime`**

В [ComputerRuntime.kt](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt), найти интерфейс `ComputerRuntime` (агрегирует `terminal`, `filesystem`, `process`…). Добавить:

```kotlin
    val stdio: ComputerStdioApi
```

рядом с существующим `val terminal: ComputerTerminalApi`.

- [ ] **Шаг 3: Падающий тест `VmStdioApi`**

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
        assertEquals('X', snap.charAt(2, 1))
    }
}
```

- [ ] **Шаг 4: Реализовать `VmStdioApi`**

```kotlin
package ru.lazyhat.compukterkraft.core.computer.vm.api

import ru.lazyhat.compukterkraft.lang.runtime.ComputerStdioApi
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import ru.lazyhat.compukterkraft.lang.runtime.vt.VtParser

/**
 * Серверная реализация [ComputerStdioApi] для Эпика 1.
 * Каждая запись проходит через [VtParser], синк которого — присоединённый
 * [ScreenBuffer]. Поведение до рефакторинга сохраняется точь-в-точь.
 *
 * Поздние эпики заменяют её на broadcaster, кормящий N сетевых сессий.
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

- [ ] **Шаг 5: Тесты**

Запустить: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.computer.vm.api.VmStdioApiTest`
Ожидание: 2/2 PASS.

- [ ] **Шаг 6: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerStdioApi.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmStdioApi.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmStdioApiTest.kt
git commit -m "feat(runtime): introduce ComputerStdioApi backed by VtParser"
```

---

## Задача 8: подключить `VmStdioApi` в `BackgroundComputerVm`

**Файлы:**
- Модифицировать: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt`

- [ ] **Шаг 1: Прочитать текущий `createRuntime`**

Открыть [BackgroundComputerVm.kt#L264-L302](modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt#L264-L302). Найти конструкцию `VmRuntime`:

```kotlin
val terminalApi = VmTerminalApi(screenBuffer = screenBuffer, ctx = this)
// … other apis …
val runtime = VmRuntime(
    terminal = terminalApi,
    // … other apis …
)
```

- [ ] **Шаг 2: Добавить конструирование stdio**

Сразу перед `VmRuntime(`:

```kotlin
val stdioApi = VmStdioApi(buffer = screenBuffer)
```

И добавить аргумент `stdio = stdioApi` в вызов `VmRuntime(…)`. Note: конкретный класс `VmRuntime` (реализующий `ComputerRuntime`) должен принять новый параметр. Если `VmRuntime` определён в отдельном файле (поиск `class VmRuntime`), добавить туда `stdio: ComputerStdioApi` в конструктор.

- [ ] **Шаг 3: Сборка**

Запустить: `./gradlew :core:compileKotlin :compiler:compileKotlin`
Ожидание: SUCCESS. Все missing override'ы всплывают как ошибки; прокидываем новое свойство.

- [ ] **Шаг 4: Прогнать существующие тесты**

Запустить: `./gradlew :core:test :compiler:test`
Ожидание: зелёные — поведение не изменилось.

- [ ] **Шаг 5: Коммит**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt
# плюс любые другие затронутые файлы (VmRuntime.kt вероятно)
git commit -m "feat(runtime): wire VmStdioApi into BackgroundComputerVm"
```

---

## Задача 9: зарегистрировать builtin-модуль `stdout`

**Файлы:**
- Модифицировать: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`
- Модифицировать: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`

- [ ] **Шаг 1: Прочитать паттерн регистрации**

Открыть [LanguageBuiltins.kt#L34-L69](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt#L34-L69). Посмотреть, как регистрируется модуль `"terminal"`.

- [ ] **Шаг 2: Добавить регистрацию `stdout`**

Сразу после блока регистрации `terminal`:

```kotlin
    registerModule("stdout") {
        function("write", listOf(BuiltinParam("text", BuiltinType.String)), BuiltinType.Unit)
    }
```

(Точные имена методов зависят от DSL файла — зеркалим `terminal`.)

- [ ] **Шаг 3: Открыть `RuntimeHostBridge.kt`**

Найти `when`-блок маршрутизации по имени модуля (около [line 31](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt#L31)):

```kotlin
    fun invoke(module: String, function: String, args: List<VmValue>): VmValue =
        when (module) {
            "terminal" -> invokeTerminal(function, args)
            …
        }
```

Добавить ветку:

```kotlin
            "stdout" -> invokeStdout(function, args)
```

- [ ] **Шаг 4: Реализовать `invokeStdout`**

Добавить метод, зеркальный `invokeTerminal` ([line 130](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt#L130)):

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

(Точные имена хелперов `VmValue.unit()` / `.asString()` — смотреть в `invokeTerminal`.)

- [ ] **Шаг 5: Интеграционный тест**

Создать `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/StdoutHostCallTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class StdoutHostCallTest {
    @Test
    fun stdoutWriteReachesScreenBufferThroughBridge() {
        val fixture = RuntimeTestFixture()
        fixture.compileAndRun("""
            stdout.write("Hello")
        """.trimIndent())
        val snap = fixture.screenBuffer.forceSnapshot()
        assertEquals('H', snap.charAt(0, 0))
        assertEquals('e', snap.charAt(1, 0))
    }
}
```

Note: `RuntimeTestFixture` упомянут концептуально. Если такого нет — смотреть [LanguageRuntimeTest.kt#L39+](modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt) чтобы увидеть реальный setup pattern (конструирует мок `ComputerRuntime` и запускает байт-код напрямую); повторить его inline.

- [ ] **Шаг 6: Запустить тест**

Запустить: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.StdoutHostCallTest`
Ожидание: PASS.

- [ ] **Шаг 7: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/StdoutHostCallTest.kt
git commit -m "feat(runtime): register stdout.write host call"
```

---

## Задача 10: переписать `VmTerminalApi` через stdout

**Файлы:**
- Модифицировать: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmTerminalApi.kt`

Цель: `VmTerminalApi.write/printLine/clear/setCursor` теперь излучают VT-последовательности в `stdio`, а не мутируют `screenBuffer` напрямую. Все предыдущие тесты должны остаться зелёными (т.к. серверный stdio возвращает в тот же `screenBuffer`).

- [ ] **Шаг 1: Прочитать текущую реализацию**

Открыть [VmTerminalApi.kt#L33-L74](modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmTerminalApi.kt#L33-L74). Текущий конструктор: `VmTerminalApi(screenBuffer: ScreenBuffer, ctx: ...)`.

- [ ] **Шаг 2: Поменять конструктор на stdio**

```kotlin
class VmTerminalApi(
    private val stdio: ComputerStdioApi,
    private val ctx: VmRuntimeContext,
    private val screenBufferForReadLineCompat: ScreenBuffer, // оставлено ТОЛЬКО чтобы readLine всё ещё управлял cursor blink
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
        // VT 1-based; наш 0-based (x=col, y=row) → "\e[row;col H"
        stdio.writeString("\u001b[${y + 1};${x + 1}H")
    }

    // readLine не трогаем в Эпике 1. Эпик 2 мигрирует input на stdio.
    override suspend fun readLine(prompt: String): String =
        /* существующая логика; всё ещё трогает screenBufferForReadLineCompat для cursor blink */
}
```

Важно: свойство `screenBuffer` на `ComputerTerminalApi` всё ещё читается VM-кодом (некоторые программы могут вызвать `terminal.screenBuffer`). Оставить выставленным пока. Эпик 2 удаляет.

- [ ] **Шаг 3: Починить место конструирования**

В [BackgroundComputerVm.kt createRuntime()](modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt#L264):

```kotlin
val stdioApi = VmStdioApi(buffer = screenBuffer)
val terminalApi = VmTerminalApi(
    stdio = stdioApi,
    ctx = this,
    screenBufferForReadLineCompat = screenBuffer,
)
```

- [ ] **Шаг 4: Прогнать полный тест-сьют**

Запустить: `./gradlew :core:test :compiler:test`
Ожидание: зелёный. Существующий `LanguageRuntimeTest.executesHostCallsThroughRuntimeBridge()` подтверждает, что `terminal.printLine()` по-прежнему пишет через VT-путь.

- [ ] **Шаг 5: Если что-то падает**

Вероятная регрессия: cursor-blink во время readLine. Если сломалось — быстрый фикс: readLine напрямую вызывает `screenBufferForReadLineCompat.setCursorBlink(true)` — парсер пока не поддерживает cursor blink (вне Эпика 1). Отметить `// TODO(Эпик 2):`.

- [ ] **Шаг 6: Коммит**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmTerminalApi.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt
git commit -m "feat(runtime): VmTerminalApi routes through stdio + VT sequences"
```

---

## Задача 11: создать ROM stdlib `term.ck`

**Файлы:**
- Создать: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/term.ck`

- [ ] **Шаг 1: Написать stdlib**

```
// Terminal helpers поверх stdout байт-стрима.
// Все функции продуцируют VT-100 escape-последовательности, которые
// терминал (compat-слой сервера сейчас, сетевой клиент в Эпике 2+) интерпретирует.

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

Note: подтвердить, что `.ck`:
1. Распознаёт `\e` как ESC в строках (если нет — использовать `chr(27)`).
2. Имеет простой `fun` синтаксис и `if/else` как выше.
3. Импортируется как `use term` — смотри, как [ls.ck](modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/ls.ck) импортирует `strings`.

Если `\e` не распознаётся — использовать константу:

```
const ESC = chr(27)
// затем: stdout.write(ESC + "[2J" + ESC + "[H")
```

- [ ] **Шаг 2: Ручной smoke-тест через временную правку bios.ck**

Временно добавить в [bios.ck](modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/bios.ck):

```
use term

term.clear()
term.setFg(2)
term.println("hello from term.ck")
term.resetAttr()
```

- [ ] **Шаг 3: Запустить мод (ручной тест)**

Запустить: `./gradlew :v1_21_1-neoforge:runClient`
Ожидание: после размещения компьютера и включения — зелёная строка "hello from term.ck".

(Ручная проверка — не часть автоматического сьюта. Откатить правку bios.ck после.)

- [ ] **Шаг 4: Откатить временную правку bios.ck**

- [ ] **Шаг 5: Коммит**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/term.ck
git commit -m "feat(rom): add term.ck stdlib over the stdout byte stream"
```

---

## Задача 12: мигрировать ROM-программы на `term`

**Файлы:**
- Модифицировать: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/bios.ck`, `shell.ck`, `ls.ck`, `pwd.ck`, `mkdir.ck`, `rmdir.ck`

Механическая замена: `terminal.printLine(x)` → `term.println(x)`, `terminal.write(x)` → `term.print(x)`, `terminal.setCursor(x, y)` → `term.cursor(y + 1, x + 1)`, `terminal.clear()` → `term.clear()`.

- [ ] **Шаг 1: Для каждого ROM-файла добавить `use term` и заменить вызовы**

По одному файлу. После каждого — запускать мод (`./gradlew :v1_21_1-neoforge:runClient`) и ставить компьютер для smoke-теста.

- [ ] **Шаг 2: Коммит после каждого файла**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/bios.ck
git commit -m "refactor(rom): bios.ck uses term stdlib"
```

…и так для shell.ck, ls.ck, pwd.ck, mkdir.ck, rmdir.ck — шесть коммитов суммарно (или squash в конце).

- [ ] **Шаг 3: Финальный полный запуск тестов**

Запустить: `./gradlew :core:test :compiler:test`
Ожидание: зелёный.

- [ ] **Шаг 4: Финальный ручной smoke-тест**

Запустить: `./gradlew :v1_21_1-neoforge:runClient`
Ожидание: поставить компьютер, включить, пролистать шелл (`ls`, `pwd`, `cd`, `mkdir foo`, `rmdir foo`). Все команды печатают идентично прежнему.

---

## Критерии завершения Эпика 1

- Все тесты выше проходят.
- `./gradlew :core:test :compiler:test` полностью зелёный.
- Запуск мода в игре выглядит идентично состоянию до эпика.
- У VM теперь есть host-модуль `stdout`. Любая программа может писать сырые VT-байты.
- Все ROM-программы идут через `term.ck`.
- Без сети, без изменения клиентского UI.

---

## Замечания исполнителю

1. **Kotlin-харнесс для `.ck` тестов:** до Задачи 9 осмотреть [LanguageRuntimeTest.kt](modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt) — показан паттерн компиляции + запуска `.ck` сниппета в тестах. Повторить паттерн, не изобретать новые fixture.
2. **Конструктор `VmRuntime`:** если `VmRuntime` — конкретный класс (а не только интерфейс) рядом с `ComputerRuntime`, добавление нового property требует правки класса. IDE или `grep -n "class VmRuntime" modules/core modules/compiler` найдут место.
3. **Доступ к dimensions `ScreenBuffer`:** Задача 6 предполагает, что `width`/`height` — public `val`. Если нет — `forceSnapshot().width` / `.height`, но `forceSnapshot` аллоцирует массивы; в таком случае кэшировать в конструкторе синка. Для Эпика 1 с его низким трафиком это неважно.
4. **Escape-синтаксис в языке:** если `.ck` не распознаёт `\e` в строках — использовать `chr(27)` в `term.ck`. Проверить в лексере языка (поиск `'\e'` или `escape` в `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/`).
5. **Стоп и спросить:** если `ComputerRuntime` оказался чем-то иным, нежели простым интерфейсом, либо `RuntimeHostBridge.invoke` диспатчит иначе — остановиться и уточнить у пользователя перед гаданиями.
