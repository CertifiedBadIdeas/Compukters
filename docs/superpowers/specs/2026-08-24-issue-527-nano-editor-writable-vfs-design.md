# Nano-Like Guest Editor and Writable Text API Design

> Issue: [#527](https://github.com/CertifiedBadIdeas/Compukters/issues/527)

## Context

Compukters already boots `/rom/boot`, runs the ordinary Kotlin shell and child
programs, persists an isolated Rust-owned `/home`, and compiles one Kotlin
source through `/rom/kotlinc` and the server-global compilation cache. The
remaining break in the first playable programming loop is source creation: a
player cannot yet edit a file from the computer itself.

The Rust machine already implements bounded synchronous filesystem operations
for text reads and writes, directories, removal, and rename. It enforces mount
immutability, subtree capabilities, quotas, persistence journaling, and atomic
publication. `ComputerMachine` already publishes the complete filesystem ABI,
but `guest-api-core` and the trusted K2 registry expose only `stat` and `list`.
Likewise, the Rust terminal already supports positional cursor, patch, fill,
palette, and visibility operations, while the Guest Kotlin terminal facade
exposes only stream-oriented calls.

This design opens the minimum reusable API surface and adds `/rom/edit` as an
ordinary no-std Kotlin program. It must complete the in-game flow:

```text
edit hello.kt -> kotlinc hello.kt -> hello
```

## Goals

- Let an authorised guest read and atomically replace bounded UTF-8 text under
  its delegated writable filesystem subtree.
- Build a usable full-screen, single-buffer editor in ordinary Guest Kotlin.
- Keep all authoritative terminal and filesystem state in the Rust machine.
- Avoid rebuilding the complete document string for every inserted character.
- Add reusable primitive `CharArray` lowering and positional terminal calls,
  not editor-specific VM or Minecraft behavior.
- Preserve source and compiled output across computer reboot and world reload.

## Non-Goals

- Syntax highlighting, completion, compiler diagnostics UI, projects, tabs,
  multiple open buffers, search, replace, undo, selection, mouse input, or an
  IDE/LSP protocol.
- Background processes, concurrent editors, file locking, collaborative cursor
  state, pipes, redirection, or general stream handles.
- Binary or hex editing, arbitrary host filesystem access, mutable `/rom`, or
  direct guest access to the compilation cache.
- A complete Kotlin collections or standard-library implementation. Only the
  primitive array semantics required by the accepted editor are added here.

## Ownership and Boundaries

`/rom/edit` owns only transient editor state: its gap buffer, logical cursor,
viewport, dirty flag, and status message. It consumes the same merged raw input
queue as shell and writes through trusted capabilities already delegated by the
foreground process stack.

`ComputerMachine` continues to own the terminal grid, input queue, filesystem,
capabilities, persistence generation, and quota accounting. Terminal operations
mutate the Rust grid synchronously and are replicated through the existing
full/delta protocol. Filesystem operations mutate the Rust VFS synchronously;
durable host I/O remains queued on the existing store worker and never blocks a
Minecraft server tick.

Minecraft remains a renderer and input adapter. It receives no editor buffer,
file content, or second terminal model.

## Guest Filesystem API

The existing `compukter.filesystem-api@1` bundle exposes these additional
declarations:

```kotlin
object FileSystem {
    fun readText(path: String): String
    fun writeText(path: String, value: String): Int
}
```

They lower to existing filesystem capability operations 2 and 3. `readText`
retains the existing bounded host-failure behavior for inaccessible, oversized,
invalid UTF-8, or storage-faulted files. The editor uses `stat` to handle the
ordinary missing-file and directory cases before reading; other read failures
terminate the child through the existing bounded process result until Guest
Kotlin has catchable failures or typed result objects. `writeText` returns the
existing stable integer status: zero on success and a negative filesystem code
on failure. The editor never receives a host path or handle.

`writeText` replaces or creates one non-executable file through the existing
`ComputerFileSystem.write_file` transaction. `/rom` remains read-only, paths
remain exact absolute virtual paths, and failed validation, reservation,
journaling, or publication leaves the previous visible file unchanged.

## Primitive `CharArray` Lowering

The editor holds its document in a managed primitive `CharArray` gap buffer.
The compiler adds the minimum standard Kotlin surface:

- `CharArray(size)` -> verified `NewArray` for the canonical primitive char
  array type;
- `array.size` -> `ArrayLength` (opcode `0x32`, already decoded and executed by
  the Rust VM but missing from the Kotlin artifact model/writer);
- `array[index]` -> `ArrayLoad`;
- `array[index] = value` -> `ArrayStore`;
- `array.concatToString(startIndex, endIndex)` -> a new bounded
  `StringFromCharArray` instruction in the unused string opcode space.

All normal Kotlin bounds and negative-size behavior is preserved by verified VM
array operations. `StringFromCharArray` validates `0 <= start <= end <= size`,
charges work proportional to the requested UTF-16 length, allocates through the
managed heap, participates in the existing one-retry GC path, and copies exact
UTF-16 code units without Unicode normalization. It is not a trusted host
capability and does not cross FFM.

On load, the editor allocates one fixed-capacity array and copies the bounded
`String` through existing `String.length` and `String.get`. Before saving it
moves the suffix beside the prefix, leaving the gap at the end, then calls
`concatToString(0, documentLength)`. Thus ordinary editing moves only characters
crossing the gap; the unavoidable complete materialization happens once per
save.

The first editor limit equals the machine's current maximum inbound/outbound
text size: 4096 UTF-16 code units. Opening a larger file or inserting beyond
capacity produces a bounded status message and does not partially edit or save
the document.

## Positional Terminal API

The trusted terminal bundle grows reusable synchronous operations over methods
already present on `TerminalDevice`:

```kotlin
object Terminal {
    fun setCursor(x: Int, y: Int)
    fun setCursorVisible(visible: Boolean)
    fun setColors(foreground: Int, background: Int)
    fun writeAt(x: Int, y: Int, text: String)
    fun fill(x: Int, y: Int, width: Int, height: Int, character: Char)
}
```

`writeAt` patches scalars into one logical row, clips at its right edge, never
wraps or scrolls, and does not move the visible cursor. `fill` uses the current
palette colors. Invalid coordinates, rectangles, palette indices, or oversized
payloads fail through the bounded terminal capability boundary. Rendering a
frame clears only affected rectangles/rows, writes their visible slices, and
places the Rust-owned cursor last. The existing once-per-tick commit coalesces
changes into the normal terminal delta stream.

These operations are general TUI primitives. Neither their names nor payloads
refer to an editor.

## Editor Buffer and Unicode Semantics

The gap is represented by `gapStart` and `gapEnd`. Logical indices before the
gap map directly; indices at or after `gapStart` map after `gapEnd`. Moving the
cursor transfers code units across the gap. Insertion consumes gap capacity;
Backspace and Delete enlarge it.

The file and Kotlin `String` semantics remain UTF-16. Navigation and deletion
are scalar-aware: a valid surrogate pair moves and deletes as one displayed
cell, and editor operations never create a split pair from valid input. Text
events already arrive as valid Unicode scalars. Existing malformed UTF-16, if
encountered through a future source, is displayed with the terminal replacement
glyph while exact code units remain bounded by VM semantics.

Line endings are LF. CRLF input is normalized to LF when admitted into the
editor buffer; that normalization marks the buffer dirty. No soft wrapping is
performed. Logical lines remain exact file lines, and the horizontal viewport
follows the cursor by scalar display columns.

## Screen and Input Model

The fixed 51x19 terminal is divided as follows:

- row 0: title, resolved path, and `*` when dirty;
- rows 1-16: document viewport;
- row 17: transient status or one-based line/column position;
- row 18: stable `^S Save  ^X Exit` shortcuts.

Supported editing controls are Text/paste, arrows, Home, End, PageUp, PageDown,
Backspace, Delete, Enter, and Tab. Tab inserts four spaces. Enter inserts LF and
copies the current line's leading spaces. Key repeats follow the existing raw
event action. Unsupported control combinations are ignored after the event is
finished.

`Ctrl+S` compacts and materializes the buffer, then calls `writeText`. Success
clears dirty state and displays `Saved`; failure retains the buffer and dirty
state and displays a bounded human-readable filesystem error.

`Ctrl+X` exits immediately when clean. When dirty it enters a modal
`Save modified buffer? Y/N` prompt: Y saves and exits only on successful save,
N discards and exits, and Escape returns to editing. The editor always finishes
the current raw terminal event before awaiting another.

## Path and ROM Integration

`edit` accepts exactly one path. A relative path resolves under `/home`; an
absolute path is passed to the virtual filesystem unchanged and remains subject
to the delegated capability. A missing target starts as an empty clean buffer
and is not created until an explicit successful save; inserting text marks it
dirty. Directories produce a bounded editor message without entering the edit
loop. Inaccessible, invalid UTF-8, oversized, and storage-faulted reads take the
existing bounded child-failure path and return control to shell.

The build compiles checked-in `system/programs/edit.kt` through the same K2
pipeline as boot, shell, and kotlinc. `SystemProgramImage` and `SystemRomImage`
package it as executable `/rom/edit`. Shell's existing `/home` then `/rom`
resolution already makes `edit hello.kt` executable; its help text gains
`edit`.

## Failure and Lifecycle Behavior

- Allocation or array bounds failure remains a normal VM trap/OOM outcome; no
  host or client buffer survives the process.
- Terminal capability failure terminates only the editor child through existing
  process-result mapping.
- Save failure never clears dirty state and never exits on a Y response.
- Reboot discards the editor process and terminal state, while the last
  successfully journaled `/home` generation persists.
- Two viewers still see one authoritative screen and contribute input in
  server-arrival order; there is one editor cursor because there is one active
  foreground process.

## Verification

Compiler and artifact tests cover exact deterministic lowering for primitive
char-array construction, length, load/store, range materialization, and the
checked-in editor artifact. Rust verifier/execution tests cover malformed
instructions, range checks, UTF-16 preservation, allocation/GC/quota failure,
and steady-state gap operations without whole-document allocation.

Rust filesystem and computer tests cover read/write capability filtering,
immutable ROM, missing/create/replace behavior, atomic failure, quotas,
persistence, isolation, invalid UTF-8, and 4096-unit boundaries. Terminal tests
cover clipped non-wrapping positional writes, fill, palette, cursor visibility,
and delta coalescing.

Guest integration tests drive the real editor artifact with raw Text/Key
events, verify navigation, horizontal/vertical scrolling, scalar-aware delete,
auto-indent, dirty prompts, save errors, and a successful return to shell. The
Minecraft GameTest performs `edit hello.kt`, saves a compiling program, runs
`kotlinc hello.kt`, executes `hello`, then reboots/reloads and proves the source
and executable remain isolated to the same computer.

Manual verification covers the same loop in `runClient`, including paste,
Ctrl+S, Ctrl+X Y/N/Escape, long lines, Cyrillic, a supplementary Unicode scalar,
and a visible filesystem error.
