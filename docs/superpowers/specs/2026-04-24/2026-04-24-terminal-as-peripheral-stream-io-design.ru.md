# Дизайн — Терминал как peripheral, stream I/O

**Статус:** Draft · **Дата:** 2026-04-24 · **Область:** runtime языка, блок компьютера, новый предмет-терминал, сеть, stdlib

## Проблема

Сегодня блок Computer имеет встроенный экран 77×27 — магическое число в `Config.DEFAULT_COMPUTER_TERM_WIDTH/HEIGHT`. VM на сервере пишет напрямую в один `ScreenBuffer` фиксированного размера; сервер каждый тик снимает с буфера снапшот и шлёт `ScreenBufferSnapshot` всем наблюдающим игрокам. У всех одинаковые размеры, и компьютер нельзя использовать без «вшитого» дисплея — экран является частью машины.

Цели:

1. Убрать магические размеры терминала на сервере.
2. Сделать терминал отдельным *peripheral* — устройством, физически отделённым от вычислительной машины.
3. Разрешить одновременный просмотр несколькими игроками, у каждого свой размер экрана, без конфликтов.

## Целевая архитектура

**Unix-метафора.** Computer — headless вычислительное устройство со стримами `stdout` / `stderr` / `stdin`. Terminal — VT-клиент со своим локальным экранным буфером своего размера. Подключение = подписка на потоки компьютера.

```
              ┌─ scrollback ring ─┐
 VM ─write(bytes)─→ stdout bus ──┼─→ Terminal-1 (60×20)   VT-парсер + локальный буфер
                                 ├─→ Terminal-2 (120×40)  VT-парсер + локальный буфер
 VM ←read(bytes)─── stdin bus ←──┼── Terminal-1 keys, resize, signals
                                 └── Terminal-2 keys, resize, signals
```

### Разделение компонентов

| Модуль | Ответственность |
|---|---|
| `compiler/runtime` | Интерфейс `TerminalIO` (stdout/stdin-байты). Удаляет `ComputerTerminalApi.screenBuffer`. Владеет `ComputerStdio` (broadcaster + scrollback). |
| `compiler/runtime/vt` | Чистый парсер подмножества VT-100. Покрывается юнит-тестами отдельно. |
| `core/ui/terminal` | Клиентский виджет: хранит `ScreenBuffer`, скармливает байты VT-парсеру, рендерит через UI DSL. Переиспользуется портативным терминалом и workbench preview. |
| `core/computer` | Жизненный цикл компьютера (boot, halt, reboot). Никакого экранного буфера, никакой логики дисплея. |
| `v1_21_1-common/terminal` | `TerminalItem`, `TerminalScreen`, сетевые пакеты attach/detach/stdio. |
| `v1_21_1-common/computer` | `ComputerControlMenu` (маленький UI ON/OFF/reboot, без экрана). |
| `rom/` | Переписанные `bios.ck` / `shell.ck` / stdlib `term.ck`. |

### Где живёт VT-парсер

**На клиенте.** Сервер — это только pipe байт + небольшой scrollback ring. Каждый клиент держит свой `ScreenBuffer` под свой размер viewport, прогоняет входящие байты через локальный парсер и рендерит. Это честная stream-модель: сервер не знает и не заботится о размере дисплея.

### Жизненный цикл сессии (эфемерная)

- Игрок держит Terminal item, Shift+ПКМ по блоку компьютера.
- Если компьютер в радиусе и чанк загружен — клиент открывает `TerminalScreen` и шлёт `AttachToComputer(computerId, cols, rows)` на сервер.
- Сервер создаёт сессию, отвечает `AttachAccepted(sessionId, scrollback)`. Клиентский VT-парсер сначала проигрывает scrollback, затем живые `StdoutChunk`-пакеты.
- Игрок закрывает UI / выходит из радиуса / чанк выгружается → `DetachFromComputer` / `ForceDetach`.
- **Никакого persistent NBT-биндинга.** Terminal item stateless; каждое подключение — свежая вставка кабеля.

### Семантика нескольких терминалов

Shared-сессия (модель `tmux attach -x`). Все подключённые терминалы подписаны на один `stdout` и пишут в один `stdin`. Размеры — per-terminal; каждый VT-парсер рендерит единый поток байт в свой экран. Программа видит одну сессию, а не N — модели per-client процессов нет.

### Headless-поведение

Без подключённых терминалов VM продолжает работать. `stdout.write()` дописывает в серверный scrollback ring (фиксированная байтовая ёмкость, из конфига). Когда терминал позже подключится — он получит текущий scrollback одним блобом; локальный VT-парсер прогонит его, восстановив текущее состояние экрана.

## Stream-протокол

### stdout (computer → clients)

Байты с подмножеством VT-100 / ANSI. Печатные символы, `\n`, `\r`, `\t`, `\b`. CSI-последовательности `\e[...`:

- `H` cursor to `(row, col)`
- `J` erase display (`2J` = clear all)
- `K` erase line
- `A`/`B`/`C`/`D` cursor up/down/right/left
- `m` SGR (цвета: 30–37 / 40–47 / 90–97 / 100–107, `0` reset)
- `s` / `u` save / restore cursor

### stdin (client → computer)

```kotlin
sealed interface StdinMessage {
    data class Bytes(val data: ByteArray) : StdinMessage
    data class Resize(val cols: Int, val rows: Int) : StdinMessage
    data class Signal(val kind: SignalKind) : StdinMessage   // Ctrl-C, Ctrl-D (EOF), Ctrl-Z
}
```

Стрелки, Home/End/PgUp/PgDn отправляются как их канонические ANSI-последовательности (`\e[A` и т. д.) — ровно то, что отдал бы реальный терминал.

### Пакеты

| Пакет | Направление | Полезная нагрузка |
|---|---|---|
| `AttachToComputer` | C→S | `computerId`, `cols`, `rows` |
| `DetachFromComputer` | C→S | `sessionId` |
| `StdinChunk` | C→S | `sessionId`, `StdinMessage` |
| `StdoutChunk` | S→C | `sessionId`, `ByteArray` (агрегированный per-tick) |
| `AttachAccepted` | S→C | `sessionId`, scrollback `ByteArray` |
| `AttachRejected` | S→C | `reason` (OutOfRange, NotFound, NotPowered, TooManySessions) |
| `ForceDetach` | S→C | `sessionId`, `reason` |

## Runtime API и stdlib

### `compiler/runtime` — новый `TerminalIO`

```kotlin
interface TerminalIO {
    fun write(bytes: ByteArray)
    fun writeString(text: String)          // UTF-8
    suspend fun read(maxBytes: Int): ByteArray
    val attachedCount: Int                  // >0 если кто-то наблюдает
}
```

Старый `ComputerTerminalApi.setCursor/clear/write/readLine/screenBuffer` удалён. Host-вызовы минимальны: `stdout.write`, `stdin.read`. Всё остальное — на уровне `.ck`.

### `rom/term.ck`

```
fun cursor(row: Int, col: Int) = stdout.writeString("\e[\(row);\(col)H")
fun clear() = stdout.writeString("\e[2J\e[H")
fun eraseLine() = stdout.writeString("\e[K")
fun setFg(color: Int) = stdout.writeString("\e[\(30 + color)m")
fun setBg(color: Int) = stdout.writeString("\e[\(40 + color)m")
fun resetAttr() = stdout.writeString("\e[0m")
fun print(text: String) = stdout.writeString(text)
fun println(text: String) = stdout.writeString(text + "\n")
fun readLine(): String   // line editor с echo; парсит ESC-sequences, backspace
fun size(): (cols: Int, rows: Int)?   // последний Resize; null если терминалов нет
```

`readLine` с echo и редактированием реализован **на самом `.ck`**, а не в Kotlin. Парсит `\e[A`/`\b`/`\n` из `stdin` и шлёт эхо в `stdout`. Ядро остаётся минимальным.

### Отчёт о размере

`term.size()` отражает последнее событие `Resize`, увиденное VM. При нескольких терминалах с разными размерами VM получает одно событие на каждый attach/resize — *последний* побеждает. Программы, которым это важно, должны справляться с дрейфом размера (как реальные SIGWINCH-хендлеры). Один терминал — обычный случай; мульти-терминал с разными размерами — сценарий для продвинутых пользователей.

## Блоки, предметы, UI

### ComputerBlock (остался, но headless)

- ПКМ → `ComputerControlMenu`: статус (OFF / Booting / Running / Halted), кнопки (Turn On, Shutdown, Reboot), список подключённых сейчас сессий (ники и размеры), опционально последние N строк stderr для диагностики.
- Никакого экранного буфера, никакого key-routing, никакого терминального UI.

### TerminalItem (новый)

- Ванильный `Item` с 3D-моделью планшета.
- ПКМ в воздух: no-op (или подсказка toast'ом).
- Shift+ПКМ по `ComputerBlock`: открывает `TerminalScreen` session-mode, шлёт `AttachToComputer`.
- **Никакого NBT-состояния.** Полностью stateless peripheral; каждое использование — новая сессия.

### TerminalScreen (новый)

- Minecraft `Screen` (без container menu — инвентарь не нужен).
- Клиентский `ScreenBuffer(cols, rows)`, где `cols` / `rows` = `floor((usableWidth - padding) / FONT_WIDTH)` clamp в `[40, 200]` и аналогично для rows в `[10, 80]`.
- Ресайз окна Minecraft → пересчитать dims, очистить буфер, сбросить парсер, отправить `StdinMessage.Resize`.
- Содержит state-machine `VtParser`. На `StdoutChunk` гонит байты через парсер, парсер мутирует `ScreenBuffer`.
- Рендер через существующий UI DSL (`ui { terminalSurface(...) }`).
- `onClose` → `DetachFromComputer`.

### Интеграция с Workbench

Workbench уже имеет in-process терминал-превью для запуска программ из IDE. Он будет использовать тот же виджет `TerminalView` (`core/ui/terminal`), но в обход сети: in-IDE VM пишет байты прямо в `TerminalView`. Один компонент, два пути подключения.

### Безопасность / квоты

- Сервер проверяет `AttachToComputer`: игрок в радиусе конфига, чанк загружен, компьютер включён.
- Каждые ~20 тиков — sweep: сессии, игрок которых вышел из радиуса → `ForceDetach`.
- Лимит на игрока: `K` одновременных сессий (по умолчанию 4).
- Per-session `StdinChunk` rate limit: `M` байт/тик (по умолчанию 4 КБ).
- Scrollback: фиксированный ring buffer, например 64 КБ (конфиг).

## Фазы миграции

Работа разбита на четыре независимо отгружаемых эпика. Каждый заканчивается зелёным тестом и рабочей игрой.

### Эпик 1 — Stream-абстракция в runtime (без визуальных изменений)

- Ввести `TerminalIO` и `ComputerStdio` в `compiler/runtime`.
- Сохранить **серверный compat-слой**: серверный VT-парсер пишет в существующий `ScreenBuffer`; сетевой протокол всё ещё шлёт `ScreenBufferSnapshot`. Снаружи ничего не меняется.
- Переписать host-функции `terminal.setCursor` и т. д. как `stdout.writeString("\e[...")`.
- Добавить `rom/term.ck`; переписать `bios.ck` / `shell.ck` поверх него.
- Новый модуль `compiler/runtime/vt` с исчерпывающими юнит-тестами парсера (это самая баг-опасная часть — покрываем сразу).
- **Готово когда:** игра выглядит идентично, `./gradlew test` зелёный.

### Эпик 2 — VT-парсер и ScreenBuffer на клиенте

- Перенести `ScreenBuffer` + `VtParser` из server/runtime в `modules/core/ui/terminal`.
- Новый сетевой протокол: `StdoutChunk` / `StdinChunk` вместо `ScreenBufferSnapshot`.
- Клиентский `ComputerTerminalScreen` (пока открывается из меню блока) использует локальный `ScreenBuffer` и парсер.
- Клиент вычисляет свой размер из метрик окна и шлёт `Resize`-события.
- Серверный scrollback ring; реплеится при attach.
- **Готово когда:** нигде нет магических 77×27. Два игрока с разными размерами окна видят один поток, каждый рендерит под свой размер.

### Эпик 3 — Отделение Terminal от Computer

- Новые `TerminalItem` + `TerminalScreen`.
- ПКМ по `ComputerBlock` теперь открывает `ComputerControlMenu` (без экрана).
- Пакеты attach/detach + проверки радиуса / чанка / квоты.
- Удалён старый `ComputerTerminalScreen`.
- Workbench встраивает `TerminalView` с in-process VM.
- **Готово когда:** скрафтил TerminalItem → Shift+ПКМ по компьютеру → сессия открыта. Второй игрок делает то же → оба видят один общий вывод.

### Эпик 4 — Полировка

- Удалить `Config.DEFAULT_COMPUTER_TERM_WIDTH/HEIGHT` и серверные compat-shim'ы ScreenBuffer.
- Квоты на сессии, rate-limit.
- Опциональный scrollback-вьюер в UI терминала (Shift+PgUp).
- (Stretch) OSC title escape (заголовок сессии на табе).

## Не цели

- Wireless / кроссмерный modem-linking.
- Multi-head output (несколько независимых stdout на компьютер).
- Полный VT-100 (scroll regions, alternate screen, mouse reporting).
- SSH-стиль: per-session процессы со своими stdio. Если понадобится — строится поверх этой архитектуры.
- Persistent NBT-биндинг терминала к конкретному компьютеру.

## Открытые вопросы

Блокирующих нет. Дефолты квот, размер scrollback и радиус настраиваем в ходе плейтеста Эпика 3.

## Риски

- **Корректность VT-парсера.** Баги здесь ломают все терминалы. Митигация: плотное юнит-тестирование в Эпике 1 до того, как парсер поедет на клиент.
- **Производительность реплея scrollback.** 64 КБ печатного текста парсятся быстро, но патологически escape-тяжёлый контент может зависнуть при attach. Митигация: кап размера блоба; если превышен, шлём только последний screen-worth состояния как `\e[2J\e[H` + cursor preamble.
- **Сетевой трафик.** Программы, спамящие вывод, дают большие объёмы. Митигация: per-tick агрегация + серверный rate-limit + ограниченная исходящая очередь per-session (дропаем старое с маркером "[…dropped N bytes]").
- **Переписывание всех ROM-программ.** Неизбежно при смене парадигмы; смягчается тем, что Эпик 1 сохраняет compat, и каждую программу можно мигрировать инкрементально.
