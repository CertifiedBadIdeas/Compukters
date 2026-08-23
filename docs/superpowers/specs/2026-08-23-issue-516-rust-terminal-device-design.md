# Rust-Owned Character Terminal Design

> Issue: [#516](https://github.com/CertifiedBadIdeas/Compukters/issues/516)

## Summary

Compukters will replace the current bounded transcript prototype with a
server-authoritative character terminal owned by the long-lived Rust virtual
computer. The terminal stores Unicode cells, a fixed 16-color palette, cursor
state, merged input events, revisions, and bounded dirty ranges. Minecraft
clients keep replicas of that state and render them with the built-in
`minecraft:uniform` font.

The terminal is a retained device, but it is not a framebuffer and does not
use a GPU-style draw list. Its scene topology is fixed: cell backgrounds,
cell glyphs, and a cursor. A new viewer receives the current cell state; later
updates contain only ordered patches and state changes.

The terminal lifetime is the VM lifetime. Program completion does not clear
it, while rebooting or destroying the VM does. A future VM snapshot preserves
the terminal as part of the same logical machine.

## Motivation and Prototype Status

The current Minecraft 26.1 prototype proves the basic path from a compiled
Kotlin program through JNI and the Rust VM to an in-game screen with input and
output. It intentionally uses a bounded UTF-16 transcript, proportional text
wrapping, periodic snapshots, and a single accepted input line. That model is
enough for the hello fixture but cannot support a shell, cursor addressing,
colors, text editors, or character-cell drawing.

The next terminal must support both ordinary stream-oriented programs and
interactive full-screen software without making terminal rendering part of
the Minecraft client authority.

## Chosen Architecture

```text
Minecraft clients
  <-> snapshots, deltas, KeyEvent/TextEvent packets
Minecraft server adapter
  <-> JDK 25 FFM / stable C ABI
Rust ComputerMachine
  `- TerminalDevice
       |- CellBuffer
       |- cursor and current colors
       |- merged input queue
       |- revision
       `- bounded dirty changes
             <-> raw terminal boot ABI
no-std Kotlin shell
  |- controls the raw terminal
  `- exports fixed stdin/stdout primitives
             <- hard-coded std ABI
Compukters stdlib
  |- print/println
  `- readln
             <-
ordinary Kotlin program
```

`TerminalDevice` belongs to `ComputerMachine`, not to a Minecraft block
entity, a client screen, or one execution session. An execution session may
halt and be replaced while the terminal remains. The current native session
boundary therefore needs to evolve so a computer-level Rust owner outlives
the currently executing artifact.

The Minecraft block entity owns the native machine handle and adapts its
terminal replication to Minecraft networking. It does not contain a second
authoritative terminal model.

## Native Boundary

The JDK 25 Foreign Function and Memory API replaces the current JNI-specific
bridge before terminal work extends the native contract. Rust exports a small,
versioned C ABI from a `cdylib`; Kotlin caches FFM downcall method handles and
passes caller-owned `MemorySegment` buffers.

The C ABI keeps opaque `u64` machine handles. It never returns pointers to
Rust-owned collections and never requires Kotlin to manage Rust allocation
lifetimes. Variable-size results use bounded caller-provided output buffers
and return an explicit status plus written or required length. Rust validates
all pointer/length pairs before constructing slices, catches panics at the ABI
boundary, and returns stable scalar error codes.

FFM bindings first reproduce the existing create, advance, resume, and close
fixture path. JNI is removed only after native-runtime, playground, Minecraft
dev, and packaged-native tests pass through FFM. Terminal full states, deltas,
and input then extend the proven C ABI.

FFM linking and native library loading are restricted operations in JDK 25.
Development, tests, and documented production launches enable native access
for the module containing Compukters. Verification also runs with
`--illegal-native-access=deny` so an accidental missing grant fails before a
future JDK makes denial the default.

## Cell Model

The device exposes one initial, device-defined grid size. Client window size
does not change the logical width or height. The first version uses one UI
scale and one fixed logical cell metric.

Each cell contains:

- one validated Unicode scalar value;
- a foreground index in a fixed 16-color palette; and
- a background index in the same palette.

One Unicode code point occupies exactly one cell. East Asian wide-cell rules,
combining sequences spanning cells, grapheme clusters, and two-cell glyphs are
not part of the first version. Invalid scalar input is rejected or projected
to `U+FFFD` at an explicitly validated boundary.

The internal representation may use a ring of rows so scrolling only advances
an internal first-row index and clears the reclaimed row. That index is an
implementation detail: program coordinates remain zero-based with `(0, 0)` at
the upper-left, and a full network state may serialize rows in logical order.

The raw device operations cover at least:

- querying grid dimensions;
- patching one cell or contiguous cell ranges;
- filling or clearing a rectangular region;
- scrolling a region or the full screen;
- setting cursor position and visibility; and
- reading input events.

Operations are bounded and batchable. Positional drawing does not implicitly
move the cursor or scroll. Stream semantics belong above this raw device.

## Rendering

The client stores a local cell replica and renders it every Minecraft render
frame. Rendering has a fixed algorithm rather than a transmitted draw list:

1. draw visible non-default background runs;
2. draw visible glyphs at fixed cell origins; and
3. draw the locally animated cursor when the authoritative cursor is visible.

The first version uses Minecraft 26.1's built-in `minecraft:uniform` font.
Minecraft remains responsible for glyph lookup, Unicode fallback, rasterizing
glyphs, and font atlases. Compukters owns cell placement and fixed advance.
Glyphs that exceed the one-cell contract are centered or clipped within their
cell instead of changing layout.

No custom bitmap font is required for the first version. A dedicated font,
additional integer UI scales, RGB colors, or wide glyph support require a
later design backed by visual or profiling evidence.

## Rust Ownership and VM Lifetime

The Rust owner is split conceptually into:

```text
ComputerMachine
|- current ExecutionSession
|- TerminalDevice
`- future machine devices and snapshot state
```

Terminal operations execute in Rust and mutate the retained cell state
directly. Mutations, dimension queries, and input polling are synchronous guest
operations because they never leave the Rust machine. Waiting for the next
input event is the only suspending terminal operation. A logically synchronous
large mutation may continue across bounded interpreter slices without exposing
`suspend` to Kotlin guest code. Minecraft-dependent work, viewer networking,
and client rendering remain outside Rust.

Lifecycle rules are:

- completing or replacing one program does not clear the terminal while the
  same VM remains alive;
- reboot destroys the old VM and therefore creates an empty terminal;
- destroying the computer's VM destroys the terminal;
- unloading and restoring the same logical VM through a future snapshot
  restores terminal cells, cursor, colors, and queued semantic device state;
  and
- replication revisions, viewer acknowledgements, and dirty journals are
  transport state and are recreated after restore.

The first implementation need not deliver general VM persistence, but it must
not choose ownership that makes these lifecycle semantics impossible.

## Replication Protocol

The protocol has full-state and delta messages over one versioned cell model.
It does not transmit rendered pixels or an ever-growing command history.

A full state contains:

- protocol version and machine/terminal identity;
- grid dimensions and palette identity;
- all cells in logical row order, with bounded run encoding where useful;
- cursor position and visibility; and
- the current terminal revision.

A delta contains a base revision, target revision, and an ordered bounded list
of changes such as:

- patch contiguous cells;
- fill a region;
- scroll by a bounded row count;
- update cursor position or visibility; and
- reset/clear the device.

Rust coalesces changes produced during a server tick into a bounded batch.
The Minecraft server sends one copy to all valid viewers. A viewer applies a
delta only when its base revision matches its replica. A new viewer or a viewer
with a revision mismatch receives the current full state.

The server may encode a full state using the same primitive patch vocabulary,
but it remains semantically a complete checkpoint. Rust still retains the
current cell buffer so it never needs to replay historical operations.

## Multi-Viewer Input

All valid viewers may type. There is no exclusive input lease.

Minecraft supplies physical/control key callbacks separately from produced
Unicode text. The terminal preserves that distinction:

- `KeyEvent` contains a stable Compukters key identifier, modifiers, and
  `PRESS` or `REPEAT` action;
- `TextEvent` contains already interpreted Unicode text, including layout,
  paste, dead-key, or IME output.

GLFW codes do not cross the public machine boundary. `RELEASE` events are not
part of the first version. Printable text is consumed from `TextEvent`, not
reconstructed from letter key codes.

The Minecraft server validates and orders accepted input on its server thread,
then appends it to one Rust terminal input queue. Events from different
players merge in server-arrival order. A text/paste packet is atomic, while
packets from different viewers may occur in either order. Per-player rate and
size limits prevent one viewer from monopolizing the queue. Clients do not
optimistically mutate the shared screen.

## Shell and Standard I/O Boundary

`write`, `println`, and `readln` are not raw `TerminalDevice` operations.

The boot shell is a Kotlin no-std program. It uses the raw terminal ABI,
creates the initial command-line experience, builds the std sysroot for normal
programs, and exports the minimum fixed input/output entry points expected by
that stdlib. The first std implementation may hard-code those stable shell
symbols rather than introduce general stream handles, descriptor tables,
pipes, or redirection.

Ordinary programs use stdlib functions. The stdlib implements `print`,
`println`, and `readln` on the fixed streams exported by the shell. The shell
remains loaded while a child program executes and resumes after it finishes.
Full-screen software may use terminal APIs exposed through the same sysroot
instead of stream-oriented helpers.

This document fixes the boundary but does not require issue #516 to implement
the complete shell, sysroot, process, pipe, or editor ecosystem. The terminal
slice must provide the raw semantics on which that work can be built.

## Quotas and Failure Handling

Every allocation and message is bounded before allocation. Limits cover at
least grid dimensions, total cells, code points per patch, changes per delta,
encoded delta/full-state size, input text length, queued input events, and
per-viewer input rate.

Invalid code points, palette indices, coordinates, dimensions, revisions, key
identifiers, or message lengths do not mutate terminal state. An invalid delta
on a client causes bounded resynchronization rather than partial application.
An allocation failure or corrupt internal state becomes a typed machine fault,
not an unchecked Minecraft server exception.

## Alternatives Considered

### Kotlin-owned terminal state

Keeping the authoritative grid in the block entity fits the current prototype
but splits machine behavior across languages, duplicates snapshot ownership,
and makes the terminal lifetime depend on Minecraft adapters. It was rejected
in favor of Rust machine ownership.

### GPU-style retained draw list

The removed `gpu0` implementation retained resources and a current draw list,
then patched resource ranges. It did not retain a pixel framebuffer. That
model was useful for arbitrary images, masks, clipping, and geometry.

A character terminal has fixed topology, so a transmitted draw list adds no
useful flexibility. The design keeps retained resources, bounded transactions,
dirty ranges, revisions, and full-state recovery from `gpu0`, but replaces the
draw list with a fixed client renderer over `CellBuffer`.

### Stateless full-frame submissions

Requiring the guest or shell to submit a complete frame continuously would
waste VM budget and network traffic, lose a halted program's screen, and still
need a recovery state for new viewers. It was rejected.

## Implementation Direction

Implementation should proceed in dependency order:

1. Replace the current JNI-specific bridge with a versioned Rust C ABI and JDK
   25 FFM bindings while preserving the existing fixture behavior.
2. Introduce a tested Rust `TerminalDevice` cell, cursor, input, revision, and
   bounded-delta model.
3. Establish a computer-level Rust owner whose terminal outlives one execution
   session and expose it through the proven FFM contract.
4. Replace the Kotlin transcript publication path with full-state and delta
   adapters sourced from Rust.
5. Replace the transcript screen with a fixed-grid `minecraft:uniform`
   renderer and multi-viewer raw input packets.
6. Adapt the existing hello fixture through a temporary bridge or the first
   shell/std boundary without preserving two permanent terminal models.
7. Remove transcript, lease, proportional wrapping, JNI, and line-box behavior
   once no active path depends on them.

The detailed implementation plan must define migration checkpoints so the
existing playable prototype remains testable until its replacement is wired
end to end.

## Verification

- Rust unit and property-style tests cover cell patches, clipping, scrolling,
  ring-row behavior, cursor state, Unicode validation, input ordering, quotas,
  delta coalescing, revision mismatch, and lifecycle reset.
- C ABI and FFM tests prove bounded full-state/delta transport, native-access
  configuration, and machine ownership across program completion.
- Kotlin codec tests reject malformed or oversized full states, deltas, and
  input packets before mutation.
- Minecraft tests cover viewer attach/resync, merged multi-viewer input,
  screen closure, computer removal, and reboot reset.
- Client-focused tests or extracted renderer tests cover fixed cell placement,
  palette mapping, clipping, and cursor projection.
- GameTest and manual client verification demonstrate a shared terminal with
  at least two viewers and visible cell-addressed drawing.
- Production packaging and `verifyLocalFull` continue to prove that no removed
  CC:Tweaked, K16, RISC-V, framebuffer, or UI DSL implementation returns.

## Out of Scope

- A custom font or multiple UI scale presets.
- RGB/true-color cells, grapheme shaping, combining-cell semantics, or
  double-width characters.
- Key-release state and platform-specific GLFW key codes in guest APIs.
- ANSI/VT compatibility unless separately designed above the raw device.
- General stream handles, pipes, redirection, background processes, or
  parallel process execution.
- The complete shell, ROM, std sysroot, editor, or in-game IDE implementation.
- Restoring the removed retained GPU, framebuffer, CC:Tweaked-derived UI, K16,
  or RISC-V code.
