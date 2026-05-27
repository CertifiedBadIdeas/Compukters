# Rux Computer Display MMIO Design

## Goal

Add the first guest-visible display device to `ComputerMachine` through Rux machine profile v2 `HardwareTable`, so Rux firmware can draw to a notebook/laptop screen without using the legacy CKL display path.

The first slice is intentionally text-mode. It should make a Rux laptop visibly useful quickly, while keeping room for a later pixel framebuffer device.

## Context

The current Rux computer stack has:

- `RUXI` image ABI v1, now frozen;
- Rux machine profile v2 with `BootInfo` and a static `HardwareTable`;
- Rux computer profile v1 with `control`, `debug`, and `serial-input` MMIO devices;
- `std::computer` helpers for discovering common computer MMIO bases;
- notebook UI that can open a dedicated laptop screen, but no Rux-native display device yet.

This design does not change the image ABI. It only extends the target machine profile used by `ComputerMachine`.

## Non-Goals

- Do not add a GPU, command queue, sprites, acceleration, or a pixel framebuffer in this slice.
- Do not add USB, ports, storage, hotplug, or device class descriptors.
- Do not route through old CKL display builtins.
- Do not expose Minecraft block positions or world information to the guest.
- Do not make display mandatory in core machine profile v2.

## Hardware Entry

Extend Rux computer profile v1 with one additional hardware entry:

```text
id  name      mmio_base     mmio_size
4   display0  0x1000_0300   0x0000_0100
```

Firmware discovers this through `BootInfo.hardware_table_addr` and `BootInfo.hardware_count`, exactly like the existing computer devices.

The hardware id is target-profile-defined. The core machine profile still does not know what a display is.

## Display Model

The first display device is a text terminal surface:

- fixed columns and rows;
- byte-oriented cells;
- one host-defined foreground/background style for the first slice;
- host-side rendering converts cells into the existing Minecraft UI pixels.

Recommended initial size:

```text
80 columns x 25 rows
```

The VM stores display state inside the display MMIO device, not inside guest RAM. Guest software writes characters and control registers. The host exposes snapshots or deltas to the notebook UI.

This is deliberately not a framebuffer. A text device is enough for boot banners, shell-like interaction, diagnostics, and early OS work. A later computer profile revision can add `display-fb0` or extend the display device with framebuffer registers if needed.

## MMIO Register Layout

All multi-byte registers are little-endian. Offsets are relative to `display0` base.

```text
offset  size  access  name
0x00    4     R       columns
0x04    4     R       rows
0x08    4     R/W     cursor_x
0x0C    4     R/W     cursor_y
0x10    4     W       command
0x14    4     W       data
0x18    4     R       sequence_low
0x1C    4     R       sequence_high
```

Commands:

```text
1  clear
2  put_byte_at_cursor
3  put_byte_at_xy
4  newline
```

Command behavior:

- Firmware writes `data` first, then writes `command`. A command write consumes the current `data` register value.
- `clear`: clears all cells to `0`, moves cursor to `(0, 0)`, and advances sequence.
- `put_byte_at_cursor`: writes `data & 0xFF` at cursor, advances cursor, scrolls if needed, and advances sequence.
- `put_byte_at_xy`: uses packed data: bits `0..7 = byte`, bits `8..19 = x`, bits `20..31 = y`. Out-of-range writes are ignored and do not advance sequence.
- `newline`: moves to the next row, scrolls if needed, and advances sequence.

Cursor behavior:

- Writing `cursor_x` or `cursor_y` clamps to the valid range.
- A cursor write does not advance sequence unless it changes visible cells.
- `put_byte_at_cursor` wraps to the next line after the last column.
- Scrolling drops the first row and clears the last row.

Sequence is a monotonic `u64` exposed as low/high `u32`. It lets the host/UI tell whether display contents changed without polling or copying the whole text buffer blindly.

## Host API

`ComputerMachine` should expose a host-side display read API:

```rust
fn display0_snapshot(&self) -> Option<ComputerTextDisplaySnapshot>
fn display0_sequence(&self) -> Option<u64>
```

The snapshot should contain:

```rust
struct ComputerTextDisplaySnapshot {
    columns: u32,
    rows: u32,
    cursor_x: u32,
    cursor_y: u32,
    sequence: u64,
    cells: Vec<u8>,
}
```

This keeps Minecraft UI code out of VM/device internals. Notebook rendering can convert cells to pixels or text glyphs on the Kotlin/client side.

## Rux Standard Library

Add `std::display` as a thin profile-specific convenience layer:

```rust
pub fn base() -> u32
pub fn columns() -> u32
pub fn rows() -> u32
pub fn clear()
pub fn put_byte(byte: u8)
pub fn newline()
pub fn write_bytes(ptr: ptr<u8>, len: u32)
```

Behavior when display is missing:

- `base()` returns `0`;
- reads return `0`;
- writes are no-ops.

This follows the existing optional hardware rule and keeps firmware portable across headless computers.

## Firmware MVP

Add a minimal firmware example:

```text
examples/firmware/display_hello.rx
```

It should:

- set machine status to booting;
- clear the display;
- write a boot line such as `RUX DISPLAY READY`;
- set machine status to ready;
- return `0`.

This example becomes the first real target for the notebook screen.

## Notebook Integration

The first UI integration should not invent a new rendering stack. The notebook screen should:

- obtain the native Rux computer handle/state through the existing block entity path;
- read display snapshots from the native VM/JNI bridge;
- render text cells inside the notebook screen panel;
- keep reboot/shutdown buttons as host controls.

Input can remain serial or be deferred until the display path is working.

## Error Handling

- Unknown display commands are ignored.
- Out-of-range `put_byte_at_xy` is ignored.
- Invalid MMIO sizes use the same `MemoryFault` behavior as other MMIO devices.
- Missing display hardware is not an error for firmware.
- Host snapshot calls return `None` only if the machine has no display device due to a future configurable hardware set.

## Tests

Native VM tests:

- `ComputerMachine` hardware table includes `display0` with id `4`.
- Memory map includes `display0`.
- Display MMIO reports columns and rows.
- Clear command clears cells and advances sequence.
- Put-byte command writes visible cells and advances sequence.
- Newline and scrolling behave deterministically.
- Missing or invalid command does not panic.

Compiler tests:

- `std::display` imports compile.
- `std::display::write_bytes` lowers to display MMIO writes.
- Example firmware runs on `ComputerMachine` and produces expected display cells.

Mod/JNI tests:

- Native bindings can read display snapshot from a Rux computer handle.
- Notebook screen path can consume a snapshot without touching CKL display fallbacks.

## Compatibility

This is compatible with frozen `RUXI` image ABI v1 because:

- no instruction encoding changes;
- no image section changes;
- no validation rule changes;
- all new semantics are target machine profile and stdlib additions.

Existing Rux programs continue to run. Firmware that wants the display must target:

```text
RUXI image ABI v1 + Rux machine profile v2 + Rux computer profile v1 with display0
```

## Rollout

1. Update docs and constants for `display0`.
2. Add the native display MMIO device and host snapshot API.
3. Add `std::display`.
4. Add display firmware example and tests.
5. Expose display snapshot through JNI/native bindings.
6. Render the snapshot in `NotebookScreen`.
7. Add input after visible display works.
