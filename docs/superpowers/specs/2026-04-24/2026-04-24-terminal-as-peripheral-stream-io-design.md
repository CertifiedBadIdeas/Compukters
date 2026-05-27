# Design — Terminal as Peripheral, Stream I/O

**Status:** Draft · **Date:** 2026-04-24 · **Scope:** language runtime, computer block, new terminal item, networking, stdlib

## Problem

Today the Computer block has a baked-in screen (77×27 — a magic number in `Config.DEFAULT_COMPUTER_TERM_WIDTH/HEIGHT`). The VM on the server writes directly into one `ScreenBuffer` of that fixed size; the server snapshots the buffer each tick and broadcasts `ScreenBufferSnapshot` to every watching player. All viewers get the same dimensions regardless of their Minecraft window size, and the computer cannot be used without an attached display — the screen is part of the machine.

Goals:

1. Remove the server-side "magic number" terminal dimensions.
2. Make the terminal a first-class *peripheral* — a separate device, physically distinct from the computing machine.
3. Let multiple players view the same computer simultaneously, each with their own display size, without conflict.

## Target Architecture

**Unix metaphor.** A Computer becomes a headless computing device with `stdout`, `stderr`, `stdin` byte streams. A Terminal becomes a VT-style client device with its own local screen buffer of its own size. Attachment is the act of subscribing a terminal to a computer's streams.

```
              ┌─ scrollback ring ─┐
 VM ─write(bytes)─→ stdout bus ──┼─→ Terminal-1 (60×20)   VT-parser + local buffer
                                 ├─→ Terminal-2 (120×40)  VT-parser + local buffer
 VM ←read(bytes)─── stdin bus ←──┼── Terminal-1 keys, resize, signals
                                 └── Terminal-2 keys, resize, signals
```

### Component split

| Module | Responsibility |
|---|---|
| `compiler/runtime` | `TerminalIO` interface (stdout/stdin bytes). Removes `ComputerTerminalApi.screenBuffer`. Owns `ComputerStdio` (broadcaster + scrollback). |
| `compiler/runtime/vt` | Pure VT-100 subset parser. Unit-tested independently. |
| `core/ui/terminal` | Client-side widget: holds a `ScreenBuffer`, feeds bytes into VT-parser, renders via the UI DSL. Reused by both portable terminal and workbench preview. |
| `core/computer` | Computer lifecycle (boot, halt, reboot). No screen buffer, no display logic. |
| `v1_21_1-common/terminal` | `TerminalItem`, `TerminalScreen`, networking packets for attach/detach/stdio. |
| `v1_21_1-common/computer` | `ComputerControlMenu` (small ON/OFF/reboot UI, no screen). |
| `rom/` | Rewritten `bios.ck` / `shell.ck` / `term.ck` stdlib. |

### Where the VT parser lives

**On the client.** The server is only a byte pipe + short scrollback ring. Each client holds its own `ScreenBuffer` sized to its own viewport, processes incoming bytes through a local parser, and renders. This is the honest stream model: the server does not know or care about display size.

### Session lifecycle (ephemeral)

- Player holds a Terminal item, Shift-right-clicks a Computer block.
- If the computer is in range and its chunk loaded, the client opens `TerminalScreen` and sends `AttachToComputer(computerId, cols, rows)` to the server.
- Server creates a session, returns `AttachAccepted(sessionId, scrollback)`. Client's VT-parser consumes scrollback first, then live `StdoutChunk` packets.
- Player closes UI / walks out of range / chunk unloads → `DetachFromComputer` / `ForceDetach`.
- **No persistent NBT binding.** The Terminal item is stateless; each attach is a fresh cable plug-in.

### Multi-terminal semantics

Shared session (tmux `attach -x` model). All attached terminals subscribe to the same `stdout` stream and feed into the same `stdin`. Dimensions are per-terminal; each VT-parser renders the one byte stream into its own screen. Programs see one session, not N — there is no per-client process model.

### Headless behavior

When no terminals are attached, the VM keeps running. `stdout.write()` appends to the server-side scrollback ring buffer (fixed byte capacity, config-driven). When a terminal attaches later, it receives the current scrollback as a single blob; the local VT-parser replays it, reconstructing the current on-screen state.

## Stream Protocol

### stdout (computer → clients)

Bytes with a VT-100 / ANSI subset. Printable chars, `\n`, `\r`, `\t`, `\b`. CSI sequences `\e[...`:

- `H` cursor to `(row, col)`
- `J` erase display (`2J` = clear all)
- `K` erase line
- `A`/`B`/`C`/`D` cursor up/down/right/left
- `m` SGR (colors, 30–37 / 40–47 / 90–97 / 100–107, `0` reset)
- `s` / `u` save / restore cursor

### stdin (client → computer)

```kotlin
sealed interface StdinMessage {
    data class Bytes(val data: ByteArray) : StdinMessage
    data class Resize(val cols: Int, val rows: Int) : StdinMessage
    data class Signal(val kind: SignalKind) : StdinMessage   // Ctrl-C, Ctrl-D (EOF), Ctrl-Z
}
```

Arrow keys, Home/End/PgUp/PgDn are sent as their canonical ANSI input sequences (`\e[A`, etc.) — exactly what a real terminal would emit.

### Packets

| Packet | Direction | Payload |
|---|---|---|
| `AttachToComputer` | C→S | `computerId`, `cols`, `rows` |
| `DetachFromComputer` | C→S | `sessionId` |
| `StdinChunk` | C→S | `sessionId`, `StdinMessage` |
| `StdoutChunk` | S→C | `sessionId`, `ByteArray` (aggregated per tick) |
| `AttachAccepted` | S→C | `sessionId`, scrollback `ByteArray` |
| `AttachRejected` | S→C | `reason` (OutOfRange, NotFound, NotPowered, TooManySessions) |
| `ForceDetach` | S→C | `sessionId`, `reason` |

## Runtime API and stdlib

### `compiler/runtime` — new `TerminalIO`

```kotlin
interface TerminalIO {
    fun write(bytes: ByteArray)
    fun writeString(text: String)          // UTF-8 encoded
    suspend fun read(maxBytes: Int): ByteArray
    val attachedCount: Int                  // >0 means someone is watching
}
```

The old `ComputerTerminalApi.setCursor/clear/write/readLine/screenBuffer` is removed. Host calls are minimal: `stdout.write`, `stdin.read`. Everything else is done in `.ck`.

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
fun readLine(): String   // echoing line editor, handles ESC-sequences, backspace
fun size(): (cols: Int, rows: Int)?   // last observed resize; null if no terminal
```

`readLine` with echo and inline editing is implemented **in the `.ck` language itself**, not in Kotlin. It parses `\e[A`/`\b`/`\n` from `stdin` and emits echo writes to `stdout`. Kernel stays minimal.

### Size reporting

`term.size()` reflects the most recent `Resize` event seen by the VM. With multiple attached terminals of different sizes, the VM gets one size event per attach/resize — the *last* one wins. Programs that care must handle size drift (same as real SIGWINCH handlers). Single-terminal is the common case; multi-terminal with diverging sizes is a power-user scenario.

## Blocks, Items, UI

### ComputerBlock (retained, now headless)

- Right-click → `ComputerControlMenu`: status (OFF / Booting / Running / Halted), buttons (Turn On, Shutdown, Reboot), list of currently attached terminal sessions (player name + dimensions), optionally last N stderr lines for diagnostics.
- No screen buffer, no key routing, no terminal UI.

### TerminalItem (new)

- Vanilla `Item` with a 3D tablet model.
- Right-click in air: no-op (or hint toast).
- Shift-right-click on a `ComputerBlock`: open `TerminalScreen` session-mode, send `AttachToComputer`.
- **No NBT state.** Fully stateless peripheral; each use is a fresh session.

### TerminalScreen (new)

- A Minecraft `Screen` (no container menu — no inventory involved).
- Client-local `ScreenBuffer(cols, rows)` where `cols` / `rows` are derived from the available usable area: `floor((usableWidth - padding) / FONT_WIDTH)` clamped to `[40, 200]` and similar for rows in `[10, 80]`.
- On Minecraft window resize: recomputes dims, clears buffer, resets parser state, sends `StdinMessage.Resize`.
- Holds a `VtParser` state machine. On `StdoutChunk`, feeds bytes through parser, which mutates the `ScreenBuffer`.
- Rendered with the existing UI DSL (`ui { terminalSurface(...) }`).
- `onClose` → `DetachFromComputer`.

### Workbench integration

Workbench already has its own in-process terminal view for running programs in the IDE. It will reuse the new `TerminalView` widget (`core/ui/terminal`) but bypass the network: the in-IDE VM writes bytes to the same `TerminalView` directly. One component, two wiring paths.

### Security / quotas

- Server validates `AttachToComputer`: player must be within configured block radius, chunk loaded, computer powered on.
- Periodic (every ~20 ticks) sweep: any session whose player left the radius → `ForceDetach`.
- Per-player limit: `K` simultaneous attached sessions (default 4).
- Per-session `StdinChunk` rate limit: `M` bytes/tick (default 4 KB).
- Scrollback: fixed ring buffer, e.g. 64 KB (config).

## Migration Phases

Work is split into four independently shippable epics. Each ends with a green test suite and a working game.

### Epic 1 — Stream abstraction in the runtime (no user-facing change)

- Introduce `TerminalIO` and `ComputerStdio` in `compiler/runtime`.
- Retain a **server-side compat layer**: a server-side VT parser feeds the existing `ScreenBuffer`; the network protocol still sends `ScreenBufferSnapshot`. Externally nothing changes.
- Rewrite host-side `terminal.setCursor` / etc. as `stdout.writeString("\e[...")`.
- Add `rom/term.ck`; rewrite `bios.ck` / `shell.ck` against it.
- New module `compiler/runtime/vt` with exhaustive unit tests for the parser (this is the most bug-prone part — cover it up front).
- **Done when:** the game looks identical to users, `./gradlew test` green.

### Epic 2 — VT parser and ScreenBuffer on the client

- Move `ScreenBuffer` + `VtParser` out of server/runtime into `modules/core/ui/terminal`.
- New net protocol: `StdoutChunk` / `StdinChunk` replace `ScreenBufferSnapshot`.
- Client `ComputerTerminalScreen` (still opened by block menu) uses its local `ScreenBuffer` and parser.
- Client derives its size from window metrics and sends `Resize` events.
- Server-side scrollback ring buffer; replayed on attach.
- **Done when:** no magic 77×27 anywhere. Two players with different window sizes see one shared stream, each rendered to their size.

### Epic 3 — Detach Terminal from Computer

- New `TerminalItem` + `TerminalScreen`.
- `ComputerBlock` right-click now opens `ComputerControlMenu` (no screen).
- Attach/detach packets + radius / chunk / quota checks.
- Remove old `ComputerTerminalScreen`.
- Workbench embeds `TerminalView` with in-process VM.
- **Done when:** Craft TerminalItem → Shift-RMB a computer → session starts. Second player does the same → both see the same shared output.

### Epic 4 — Polish

- Remove `Config.DEFAULT_COMPUTER_TERM_WIDTH/HEIGHT` and server-side ScreenBuffer compat shim.
- Session quotas, rate limits.
- Optional scrollback viewer in terminal UI (Shift-PgUp).
- (Stretch) OSC title escape (show title on session tab).

## Non-Goals

- Wireless / cross-dimension modem linking.
- Multi-head output (several independent stdouts per computer).
- Full VT-100 (scroll regions, alternate screen, mouse reporting).
- SSH-style per-session processes with independent stdio. If ever desired, it builds on top of this architecture.
- Persistent NBT binding of a terminal to a specific computer.

## Open Questions

None blocking. Quota defaults, scrollback size, and radius default can be tuned during Epic 3 playtesting.

## Risks

- **VT parser correctness.** Bugs here corrupt every terminal display. Mitigation: heavy unit testing in Epic 1 before wiring it up on the client.
- **Scrollback replay performance.** A 64 KB blob of mostly printable text parses quickly, but pathological escape-heavy content could stall attach. Mitigation: cap blob size; if exceeded, send only the last screen-worth of state as an `\e[2J\e[H` + cursor positioning preamble.
- **Network bandwidth.** Programs that spam output could generate high byte volumes per tick. Mitigation: per-tick aggregation + server-side rate limiter + bounded outgoing queue per session (drop oldest with a "[…dropped N bytes]" marker).
- **Rewriting all ROM programs.** Unavoidable given the paradigm shift; guarded by Epic 1's compat approach letting each program be migrated incrementally.
