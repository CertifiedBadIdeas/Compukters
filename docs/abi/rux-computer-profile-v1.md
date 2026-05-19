# Rux Computer Profile v1

## Status

Status: draft.

This document defines the concrete computer-class hardware IDs currently used by `ComputerMachine`.
It is a target machine profile layered on top of `RUXI` low image ABI v1 and Rux machine profile v2.

Use this target description for firmware that wants to run on the current mod computer VM:

```text
RUXI image ABI v1 + Rux machine profile v2 + Rux computer profile v1
```

## Base Machine Profile

Rux computer profile v1 uses:

- 32-bit guest physical addresses;
- little-endian RAM and MMIO accesses;
- profile v2 `BootInfo` at `0x0000_0000`;
- profile v2 `HardwareTable` entries;
- `page_size = 256`;
- `program_base = 0x0000_0100`.

The first RAM page is reserved for boot data. The host loads image memory sections at `program_base`.

## Hardware IDs

The profile defines these stable hardware IDs for the current boot:

```text
id  name          mmio_base     mmio_size
1   control       0x1000_0000   0x0000_0100
2   debug         0x1000_0100   0x0000_0100
3   serial-input  0x1000_0200   0x0000_0100
4   display0      0x1000_0300   0x0000_0100
```

Firmware should discover these ranges through `BootInfo.hardware_table_addr` and `BootInfo.hardware_count`.
The numeric addresses above are the current profile assignment, not an image ABI feature.

## Control MMIO

The control range is host-visible machine state. All registers are little-endian `i32`.

```text
offset  size  name
0x00    4     status
0x04    4     panic_code
0x08    4     exit_code
```

Status values:

```text
0  reset
1  booting
2  ready
3  halted
4  panic
```

The CPU `halt` signal still terminates execution from the host perspective. Writing control registers is a firmware convention for exposing state to the host and UI.

## Debug MMIO

The debug range provides a byte-oriented host-visible output stream.

```text
offset  size  name
0x00    1     write
```

A byte store to `debug + 0x00` appends the byte to host-visible debug output.

Multi-byte stores are accepted by the current VM through the same MMIO range, but portable firmware should use byte stores.

## Serial Input MMIO

The serial-input range provides a byte queue from host/UI into firmware.

```text
offset  size  name
0x00    4     ready
0x04    1     read
```

`ready` returns `1` when at least one byte is queued and `0` otherwise.

A byte load from `read` consumes and returns one queued byte. If the queue is empty, it returns `0`.

## Display0 MMIO

The display0 range provides a text-mode display surface for firmware.

Initial dimensions:

```text
80 columns x 25 rows
```

All multi-byte registers are little-endian.

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

Firmware writes `data` first, then writes `command`. A command write consumes the current data register value.

`put_byte_at_xy` uses packed data:

```text
bits 0..7    byte
bits 8..19   x
bits 20..31  y
```

The sequence registers expose a monotonic `u64` split into low/high `u32` words. It advances when visible display state changes through a display command.

## Missing Hardware

Rux machine profile v2 allows hardware entries to be absent. Current `ComputerMachine` always exposes the four entries above, but firmware should still handle missing entries:

- missing debug output should become a no-op;
- missing serial input should behave as not ready and return `0`;
- missing control should make firmware rely on CPU halt or host lifecycle controls.
- missing display should make display writes no-ops and display reads return `0`.

The Rux standard library follows this rule for `std::io`.
