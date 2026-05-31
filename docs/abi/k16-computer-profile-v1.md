# Rux Computer Profile v1

## Status

Status: active draft.

This document defines the concrete computer-class hardware IDs currently used by
`ComputerMachine`.

Use this target description for firmware that wants to run on the current mod
computer VM:

```text
K16 guest code + K16 machine profile v2 + K16 computer profile v1
```

## Boot Model

The current computer target starts a K16 CPU at:

```text
BIOS_FLASH_BASE = 0xFFF0_0000
```

The BIOS flash region is read-only and mapped outside RAM. Firmware fetches
instructions directly from that region. The host writes profile v2 boot info and
the hardware table into RAM before the CPU starts.

Storage boot is firmware policy. The current BIOS reads `K16PT` from storage0,
loads `BOOT`/K16FS `/boot/loader.kb`, validates the `K16E` bootloader image,
copies its payload into RAM, and jumps to the payload entry address. There is
no host-side executable decode step in this path.

## Base Machine Profile

K16 computer profile v1 uses:

- 32-bit guest physical addresses;
- little-endian RAM and MMIO accesses;
- profile v2 `BootInfo` at `0x0000_0000`;
- profile v2 `HardwareTable` entries;
- `page_size = 256`;
- `program_base = 0x0000_0100`;
- K16 BIOS flash mapped at `0xFFF0_0000`.

The first RAM page is reserved for boot data.

## Hardware IDs

The profile defines these stable hardware IDs for the current boot:

```text
id  name          mmio_base     mmio_size
1   control       0x1000_0000   0x0000_0100
2   debug         0x1000_0100   0x0000_0100
3   serial-input  0x1000_0200   0x0000_0100
4   display0      0x1000_0300   0x0000_0100
5   storage0      0x1000_0400   0x0000_0100
```

Firmware should discover these ranges through `BootInfo.hardware_table_addr` and
`BootInfo.hardware_count`. The numeric addresses above are the current profile
assignment, not a CPU feature.

## Control MMIO

The control range is host-visible machine state. All registers are little-endian
`i32`.

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

The CPU `halt` signal still terminates execution from the host perspective.
Writing control registers is a firmware convention for exposing state to the
host and UI.

## Debug MMIO

The debug range provides a byte-oriented host-visible output stream.

```text
offset  size  name
0x00    1     write
```

A byte store to `debug + 0x00` appends the byte to host-visible debug output.

Multi-byte stores are accepted by the current VM through the same MMIO range,
but portable firmware should use byte stores.

## Serial Input MMIO

The serial-input range provides a byte queue from host/UI into firmware.

```text
offset  size  name
0x00    4     ready
0x04    1     read
```

`ready` returns `1` when at least one byte is queued and `0` otherwise.

A byte load from `read` consumes and returns one queued byte. If the queue is
empty, it returns `0`.

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

Firmware writes `data` first, then writes `command`. A command write consumes
the current data register value.

`put_byte_at_xy` uses packed data:

```text
bits 0..7    byte
bits 8..19   x
bits 20..31  y
```

The sequence registers expose a monotonic `u64` split into low/high `u32`
words. It advances when visible display state changes through a display command.

## Storage0 MMIO

The storage0 range exposes a block storage port. The port is stable hardware,
but media is optional: firmware must expect the port to exist with no disk
attached.

All multi-byte registers are little-endian.

```text
offset  size  access  name
0x00    4     R       version
0x04    4     R       status
0x08    4     R       error
0x0C    4     W       command
0x10    4     R       block_size
0x14    4     R       capacity_blocks_low
0x18    4     R       capacity_blocks_high
0x1C    4     R/W     lba_low
0x20    4     R/W     lba_high
0x24    4     R/W     block_count
0x28    4     R/W     buffer_addr
0x2C    4     R       bytes_done
0x30    4     R       sequence_low
0x34    4     R       sequence_high
0x38    4     R       media_status
```

Version:

```text
1  storage MMIO v1
```

Status values:

```text
0  ready
1  busy
2  done
3  error
```

Error values:

```text
0  none
1  invalid_command
2  media_absent
3  buffer_out_of_bounds
4  lba_out_of_bounds
5  byte_count_overflow
6  write_protected
7  io_error
```

Commands:

```text
0  nop
1  read_blocks
2  write_blocks
3  flush
```

Media status values:

```text
0  absent
1  present
2  read_only
3  error
```

When media is absent, `capacity_blocks_*` return zero, `media_status` returns
`absent`, and block I/O commands complete with `status = error` and
`error = media_absent`.

`read_blocks` and `write_blocks` use guest RAM as the transfer buffer. Firmware
writes `lba_low/high`, `block_count`, and `buffer_addr`, then writes `command`.
The host copies `block_count * block_size` bytes between the attached media and
guest RAM.
