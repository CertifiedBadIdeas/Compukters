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
id  name          mmio_base     mmio_size     irq_source
1   control       0x1000_0000   0x0000_0100   0x0000_0000
2   debug         0x1000_0100   0x0000_0100   0x0000_0000
3   serial-input  0x1000_0200   0x0000_0100   0x0000_0000
5   storage0      0x1000_0400   0x0000_0100   0x0000_0000
6   gpu0          0x1000_0500   0x0000_0100   0x0000_0000
7   timer0        0x1000_0600   0x0000_0100   0x0000_0001
8   keyboard0     0x1000_0700   0x0000_0100   0x0000_0002
```

Firmware should discover these ranges through `BootInfo.hardware_table_addr` and
`BootInfo.hardware_count`. The numeric addresses above are the current profile
assignment, not a CPU feature.

Hardware id `4` and MMIO page `0x1000_0300..0x1000_0400` are retired and
unmapped in the active K16 computer profile. Firmware must not probe or depend
on a host-owned text display surface.

Firmware should also discover interrupt routing from each entry's `irq_source`.
`timer0` currently raises CPU interrupt source bit `0x00000001`; `keyboard0`
raises CPU interrupt source bit `0x00000002` when input becomes available.
Other devices in this profile do not raise interrupts and expose `0`.

## Control MMIO

The control range is host-visible machine state. All registers are little-endian
`i32`.

```text
offset  size  name
0x00    4     status
0x04    4     panic_code
0x08    4     exit_code
0x0c    4     yield
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

Writing a non-zero value to `yield` requests a host-visible pause after the
current instruction. The CPU remains runnable and resumes on the next host tick.
The register is edge-like VM state, not durable machine state.

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

## Gpu0 MMIO

The gpu0 range provides a simple 2D pixel graphics adapter for firmware and
kernel graphics. It is the only display-output device in the active K16
computer profile. Pixel output does not go through text cells or host text
rendering. The VM does not render fonts, glyphs, terminal state, windows,
shaders, or 3D; guest software owns those layers and writes pixels through
gpu0.

Initial dimensions:

```text
320 x 200 pixels
```

The only pixel format in profile v1 is RGB565. Guest RAM source buffers store
RGB565 pixels as little-endian `u16` values. Host display frames serialize
RGB565 payload bytes in the existing display-frame network format.

All multi-byte registers are little-endian.

```text
offset  size  access  name
0x00    4     R       width
0x04    4     R       height
0x08    4     R       stride_bytes
0x0C    4     R       pixel_format
0x10    4     W       command
0x14    4     R       status
0x18    4     R       error
0x1C    4     R/W     x
0x20    4     R/W     y
0x24    4     R/W     rect_width
0x28    4     R/W     rect_height
0x2C    4     R/W     buffer_addr
0x30    4     R/W     buffer_stride_bytes
0x34    4     R/W     color
0x38    4     R       sequence_low
0x3C    4     R       sequence_high
```

Pixel format values:

```text
1  rgb565
```

Status values:

```text
0  ready
1  done
2  error
```

Error values:

```text
0  none
1  invalid_command
2  buffer_out_of_bounds
3  invalid_rect
4  invalid_stride
```

Commands:

```text
0  nop
1  clear
2  blit_buffer
3  present
```

`clear` fills the gpu0 pixel surface with `color`. `color` uses the low 16 bits
as an RGB565 value.

`blit_buffer` copies a rectangle from guest RAM into the gpu0 pixel surface.
Firmware writes `x`, `y`, `rect_width`, `rect_height`, `buffer_addr`, and
`buffer_stride_bytes`, then writes `command = blit_buffer`. The source buffer
must contain at least `rect_height` rows, each with `buffer_stride_bytes`
bytes. `buffer_stride_bytes` must be at least `rect_width * 2`.

`present` emits dirty gpu0 tiles to the host display-frame path and increments
the gpu0 sequence if a frame is emitted. Dirty pixels are not
sent to the host until firmware writes `command = present`.

## Timer0 MMIO

The timer0 range exposes time counters for guest firmware and kernel code.
Both counters are always readable by polling. In addition, each host game-tick
advance requests the K16 CPU `timer0` interrupt for the boot CPU; delivery
still depends on the CPU interrupt enable and mask CSRs documented in
`k16-cpu-v1.md`.

All multi-byte registers are little-endian and read-only for guest code.

```text
offset  size  access  name
0x00    4     R       version
0x04    4     R       game_ticks_low
0x08    4     R       game_ticks_high
0x0C    4     R       monotonic_nanos_low
0x10    4     R       monotonic_nanos_high
```

Version:

```text
1  timer MMIO v1
```

`game_ticks` is a `u64` split into low/high `u32` words. The host advances it
once for each high-level runtime tick before native CPU turns run. It follows
Minecraft/server simulation time, so guest OS sleep, scheduler ticks, firmware
delays, and device cooldowns should use this counter.

When `game_ticks` advances, the host sets the CPU pending interrupt source
advertised by the `timer0` hardware-table entry with
`trap_value = low32(game_ticks)`. Firmware or kernel code that does not install
`trap_vector`, set `interrupt_mask`, and enable `interrupt_enable` continues to
observe timer0 purely by polling.

`monotonic_nanos` is a `u64` split into low/high `u32` words. It measures host
monotonic elapsed nanoseconds since the native machine/runtime instance was
created or restored. Guest diagnostics, profiling, and clocks may use it, but
it should not drive simulation sleeps.

Multi-word reads are not atomic. Firmware that requires a stable `u64` should
read high, then low, then high again and retry if the two high words differ.

CPU execution progress is a separate concept from `timer0`. K16 currently
tracks VM steps internally, not physical CPU cycles. If guest-visible CPU work
counters become necessary, they should be exposed as future performance or
progress counters rather than as the OS sleep timer.

## Keyboard0 MMIO

The keyboard0 range exposes a PC-like keyboard event queue. It is independent
from `serial-input`, which remains a UART-style byte stream. Keyboard0 preserves
key up/down events, character bytes, paste bytes, repeat state, and modifier
bits for guest firmware and kernel code.

All multi-byte registers are little-endian.

```text
offset  size  access  name
0x00    4     R       version
0x04    4     R       queue_len
0x08    4     R       status
0x0C    4     R       event_kind
0x10    4     R       code
0x14    4     R       modifiers
0x18    4     R       flags
0x1C    4     R       sequence_low
0x20    4     R       sequence_high
0x24    4     W       command
0x28    4     R       dropped_count
```

Version:

```text
1  keyboard MMIO v1
```

Status values:

```text
0  empty
1  ready
2  overflow
```

Event kinds:

```text
0  none
1  key_down
2  key_up
3  char
4  paste_byte
```

Commands:

```text
0  nop
1  consume
2  clear
```

Flag bits:

```text
bit 0  repeat
```

Modifier bits:

```text
bit 0  shift
bit 1  control
bit 2  alt
bit 3  super
```

Named key codes:

```text
257  enter
259  backspace
335  keypad enter
```

`event_kind`, `code`, `modifiers`, and `flags` describe the front queued
event. When the queue is empty, these fields return `0`. For `key_down` and
`key_up`, `code` is the stable host key code. For `char` and `paste_byte`,
`code` contains the unsigned byte value in bits `0..7`.

Writing `consume` removes the front event if one is present. Writing `clear`
removes all pending events and advances `sequence`. The queue capacity is 256
events. When the queue is full, the host drops the newest event and increments
`dropped_count`; already queued input remains readable.

`sequence` increments when the host accepts an input event and when guest code
clears the queue. It does not increment when guest code consumes one event.

When keyboard0 transitions from empty to non-empty, the host requests the CPU
interrupt source advertised by the hardware-table entry. Guest code can still
observe the device purely by polling.

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
