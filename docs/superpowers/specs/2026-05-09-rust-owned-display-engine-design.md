# Rust-Owned Display Engine Design

## Goal

Move low-level display ownership from Kotlin into Rust so CKL programs keep using device-like display operations while
pixel rasterization, framebuffer mutation, dirty tracking, and frame-delta building stop paying JVM-side hot-path costs.

This is a display-device migration, not a terminal-host migration. `terminal.ck`, shell behavior, scrollback policy,
input handling, line wrapping, and userland drawing decisions remain CKL-owned. Rust owns the display hardware model.

## Motivation

The terminal is now functionally correct, but the hot path still crosses several costly boundaries:

- CKL emits many display operations while rendering text and UI.
- Kotlin handles the display host call, mutates the framebuffer, marks dirty tiles, and builds frame deltas.
- Client delivery still needs to copy and apply tile payloads.

The `display::blitMono5x7Text` experiment reduced per-glyph host-call count, but it did not make the terminal feel
dramatically faster. That result points at the wider display pipeline: the VM should issue device commands, and the
display device should do raster and frame production close to the native VM/runtime core.

## Scope

Included:

- Add a Rust-owned display engine for pixel displays.
- Store display framebuffer state in Rust for the native runtime path.
- Move low-level raster operations into Rust:
  - `clear`
  - `setPixel`
  - `fillRect`
  - `copyRect`
  - `blitMono`
  - `blitMono5x7Packed`
  - `blitMono5x7Text`
  - later `blitBitmap`/`blip`-style bitmap operations
- Move dirty-region or dirty-tile tracking into Rust.
- Build display frame deltas from Rust-owned dirty regions.
- Keep Kotlin as the integration layer that drains native frame deltas and sends them to the Minecraft/client layer.
- Keep the existing Kotlin display implementation as a fallback until the native path is verified.
- Extend profiling so display command time, native raster time, frame-build time, Rust-to-JVM copy time, and client apply
  time can be separated.

Excluded:

- Do not move terminal behavior into Kotlin or Rust host code.
- Do not introduce a semantic `drawTerminalLine`, `renderShell`, or terminal-specific host primitive.
- Do not replace the pixel UI model with a character-only terminal surface in this slice.
- Do not move Minecraft networking, screen sessions, client texture upload, or block/entity lifecycle into Rust.
- Do not require all non-native runtimes to use the Rust display engine.

## Ownership Boundary

Owned by Rust:

- display registry state for native devices
- framebuffer memory
- display dimensions and pixel format
- low-level raster primitives
- mono font glyph lookup used by display text primitives
- dirty tracking
- frame sequence numbers for native display frames
- frame-delta payload construction for dirty tiles or full refreshes

Owned by Kotlin:

- device and computer lifecycle
- attaching/detaching displays from game state
- screen sessions and player/container routing
- network packets to the client
- client-side frame application and texture updates
- fallback display implementation
- high-level runtime orchestration until the native device runtime core owns more of it

Owned by CKL/userland:

- shell and terminal behavior
- deciding when to clear, scroll, wrap, draw, and present
- choosing text, colors, bitmap content, and UI layout

## Architecture

The native path should look like this:

```text
CKL program
  -> display::* builtin
  -> native image runner fast path or narrow JNI call
  -> Rust DisplayEngine
       - framebuffer
       - raster primitives
       - dirty tracking
       - frame-delta builder
  -> Kotlin drainDisplayFrames()
  -> DisplayNetworkBridge
  -> client texture/frame apply
```

The important design rule is that the CKL API remains device-like. A program can still draw pixels, rectangles, glyphs,
text runs, and bitmaps. The implementation of those primitives moves down to the display device layer.

## Runtime Model

A native device should own a `DeviceDisplayRegistry` or equivalent Rust structure. Each attached display has a
`DisplayEngine`:

- immutable configuration: display id, width, height, pixel format, tile size
- mutable framebuffer storage
- dirty tracker
- pending frame sequence
- optional profiling counters

Image runners belonging to the same device reference the shared device display registry, the same way future event and
IPC work will reference the shared native device runtime kernel.

## API Shape

The public CKL builtins should stay close to the current `display::*` surface:

- `display::clear(displayId, color)`
- `display::setPixel(displayId, x, y, color)`
- `display::fillRect(displayId, x, y, width, height, color)`
- `display::copyRect(displayId, srcX, srcY, width, height, dstX, dstY)`
- `display::blitMono(displayId, x, y, width, height, bits, foreground, background)`
- `display::blitMono5x7Packed(displayId, x, y, glyph, foreground, background)`
- `display::blitMono5x7Text(displayId, x, y, text, foreground, background)`
- `display::present(displayId)`

Implementation may route these through either:

1. a native fast path inside the image runner for display host imports, or
2. a narrow JNI facade from Kotlin `DeviceDisplayApi` to Rust.

The preferred final shape is the native fast path for image VM execution, because it avoids the generic Kotlin
host-call bridge on display hot paths.

## Frame Delta Contract

Kotlin and the client already understand display frame deltas. The Rust engine should emit an equivalent model:

- display id
- sequence
- display dimensions
- full-refresh flag
- tile rectangles
- tile pixel payload bytes

The payload format should initially match the existing Kotlin `DisplayFrameDelta` contract to avoid client protocol
churn. Once Rust owns frame building, later optimizations can consider direct buffers or a more compact packet format.

## Terminal Interaction

`terminal.ck` should continue to be responsible for terminal behavior. The current text batching remains valid:

- committed terminal rows can call `display::blitMono5x7Text`
- input append can call `display::blitMono5x7Text`
- spaces and erases can remain `fillRect` or clear-cell operations
- scroll can remain `copyRect` plus redraw, later optimized as needed

The difference is that those calls mutate a Rust-owned framebuffer instead of a Kotlin-owned one.

## Profiling Requirements

The migration is only useful if profiling can identify where latency moved. Reports should capture at least:

- display command count by function
- native display active time by function
- native raster time
- dirty tracking time
- frame build time
- Rust-to-JVM frame copy time
- bytes copied per frame
- frames emitted and frames applied
- client apply/swap/snapshot time

The existing historical profiling report should show old Kotlin display runs next to Rust-owned display runs.

## Migration Strategy

1. Freeze the Rust/Kotlin display boundary and add tests for the new lifecycle/API names.
2. Implement a Rust `DisplayEngine` with framebuffer, primitive raster operations, and dirty tracking.
3. Expose native display lifecycle and frame drain through JNI.
4. Route Kotlin `DeviceDisplayApi` to the native engine behind an opt-in switch.
5. Route image-runner display host imports to the native engine fast path.
6. Keep Kotlin fallback until native parity tests and profiling runs are stable.
7. Remove or narrow duplicated Kotlin display hot-path code after confidence is high.

## Acceptance Criteria

- Native display lifecycle can create, attach, resize/free, and drain a display.
- Existing display primitives render the same pixels in Rust as the Kotlin fallback for focused parity tests.
- `terminal.ck` compiles without terminal-specific host primitives.
- Bundled terminal tests pass using the native display path.
- Profiling reports include native display metrics and Rust-to-JVM copy metrics.
- Historical comparison shows display primitive ownership moving from Kotlin display operations to Rust display metrics.
- The architecture keeps userland terminal logic out of host/display internals.
