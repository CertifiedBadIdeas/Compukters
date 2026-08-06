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
loads `BOOT`/KFS `/boot/loader.kb`, validates the `K16E` bootloader image,
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
9   mmu0          0x1000_0800   0x0000_0100   0x0000_0000
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

The CPU `wait` signal is non-terminal. It returns control to the host without
changing the durable control status, and execution resumes at the next guest
instruction when the host runs the VM again.

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

`gpu0` ABI v2 is a retained transaction device. It is the only active display
ABI. The VM stores guest-created resources and a draw list; it does not own a
pixel framebuffer. Minecraft clients receive snapshots/deltas and rasterize
the retained state through their normal renderer. Fonts, glyph lookup, text
layout, scrolling, colors, and resource IDs remain guest-owned.

The logical viewport is 320 x 200. Multi-byte values are little-endian.

```text
offset  size  access  name
0x00    4     R       device_abi_version: 2
0x04    4     R       width: 320
0x08    4     R       height: 200
0x0C    4     R       packet_version: 1
0x10    4     R       max_packet_bytes: 524288
0x14    4     R       max_transaction_operations: 2048
0x18    4     R       max_resources: 128
0x1C    4     R       max_resource_bytes: 131072
0x20    4     R       max_total_resource_bytes: 262144
0x24    4     R       max_draw_list_bytes: 65536
0x28    4     R       max_draw_commands: 2048
0x2C    4     R       max_clip_depth: 32
0x30    4     R/W     submission_address
0x34    4     R/W     submission_length
0x38    4     W       submit doorbell
0x3C    4     R       result_code
0x40    4     R       error_operation_index
0x44    4     R       error_byte_offset
0x48    4     R       committed_sequence_low
0x4C    4     R       committed_sequence_high
```

`submission_address` and `submission_length` must be 4-byte aligned. The
complete packet must fit guest RAM and `submission_length` must be between 24
and `max_packet_bytes`. Writing the `submit` doorbell copies, validates, and
commits one packet atomically. A rejected transaction changes no retained
resource, draw-list, identity counter, or committed sequence.

Packet header:

```text
offset  size  field
0x00    4     magic: "KGPU"
0x04    2     version: 1
0x06    2     reserved: 0
0x08    4     total byte length
0x0C    4     operation count
0x10    8     expected base committed sequence
0x18    ...   operations, each 4-byte aligned
```

Each operation begins with `u16 opcode`, `u16 reserved = 0`, and `u32 byte
length` including its 8-byte header. The active operations are:

```text
0x0001  create RGB565 image
0x0002  create MSB-first 1bpp mask
0x0003  create mask-instance buffer
0x0010  patch image rectangle
0x0011  patch mask rectangle
0x0012  patch mask-instance range
0x0020  drop resource
0x0030  replace draw list (must be the final operation)
```

Image payload colors are little-endian RGB565 values. A 1bpp mask row occupies
`ceil(width / 8)` bytes; unused low bits in the final byte must be zero. Each
mask-instance record is 24 bytes and contains source rectangle, destination
rectangle, foreground/background RGB565, flags, and a zero reserved field.
Flag bit 0 enables an opaque background; without it, background must be zero.

The draw list begins with a background RGB565 value, a zero reserved field,
and a command count. Its commands are `push_clip` (`0x0001`), `pop_clip`
(`0x0002`), `fill_rect` (`0x0010`), `draw_image` (`0x0020`), `draw_mask`
(`0x0021`), and `draw_mask_instances` (`0x0022`). Clip commands must balance,
all referenced resources and source ranges must exist in the transaction's
final state, and the installed draw list binds to the resource incarnations
resolved at commit time.

Result codes are `0 ok`, `1 unsupported_version`, `2 malformed_packet`,
`3 stale_base`, `4 invalid_argument`, `5 invalid_resource`,
`6 resource_in_use`, `7 out_of_bounds`, `8 quota_exceeded`, and
`9 invalid_draw_list`. On rejection, the error fields identify the first
offending operation and byte when available; `0xffffffff` means not
applicable.

Resource incarnation/revision values are host-assigned retained-state
identities. Guest packets contain only resource IDs and the expected global
base sequence; guests never predict or submit incarnation/revision values.

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
from Minecraft/server simulation time. Runtime implementations may coalesce CPU
execution permits while the VM worker is busy, but they must still apply every
elapsed host game tick to timer0 before the next guest CPU slice. Guest OS
sleep, scheduler ticks, firmware delays, and device cooldowns should use this
counter.

When `game_ticks` advances, the host sets the CPU pending interrupt source
advertised by the `timer0` hardware-table entry. The interrupt means that a
timer event occurred; it is not the authoritative transport for the current
time value. Guest code that needs the current time must read the timer0 MMIO
counter. Current hosts may still place `low32(game_ticks)` in `trap_value` as a
diagnostic compatibility payload, but firmware and kernels must not depend on
that payload for timekeeping. Firmware or kernel code that does not install
`trap_vector`, set `interrupt_mask`, and enable `interrupt_enable` continues to
observe timer0 purely by polling.

`monotonic_nanos` is a `u64` split into low/high `u32` words. It measures host
monotonic elapsed nanoseconds since the native machine/runtime instance was
created or restored. Guest diagnostics, profiling, and clocks may use it, but
it should not drive simulation sleeps.

Multi-word reads are not atomic. Firmware that requires a stable `u64` should
read high, then low, then high again and retry if the two high words differ.
Guest runtime libraries that cannot rely on native `u64` code generation or
calling conventions should expose these counters as explicit low/high `u32`
parts. The current `k16-rt` convention names that representation `U64Parts`
with `high` and `low` fields and provides part-based helpers for both
`game_ticks` and `monotonic_nanos`.

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

## Mmu0 MMIO

The `mmu0` range exposes the host-managed K16 MMU control surface to privileged
guest kernel code. User programs must not receive direct mappings to this
device. The device is command-based: guest code writes argument registers, then
writes `command`; the host applies the command before guest execution
continues.

All multi-byte registers are little-endian.

```text
offset  size  access  name
0x00    4     R       version
0x04    4     R       status
0x08    4     R       error
0x0C    4     W       command
0x10    4     R/W     address_space
0x14    4     R/W     virtual_start
0x18    4     R/W     physical_start
0x1C    4     R/W     page_count / byte_count
0x20    4     R/W     flags
0x24    4     R/W     entry_pc
0x28    4     R/W     stack_pointer
0x2C    4     R       result
```

Version:

```text
1  mmu MMIO v1
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
2  invalid_argument
3  invalid_address_space
4  translation_fault
5  physical_out_of_bounds
6  byte_count_overflow
```

Commands:

```text
0  nop
1  create_address_space
2  map_pages
3  protect_pages
4  activate_user_address_space
5  copy_from_user
6  copy_to_user
7  set_trap_return_physical
8  set_trap_return_address_space
9  destroy_address_space
```

Flag bits:

```text
bit 0  user_accessible
bit 1  writable
bit 2  executable
```

For user-accessible mappings, `writable` and `executable` are mutually
exclusive. `map_pages` and `protect_pages` report `invalid_argument` if both
bits are set together with `user_accessible`. Supervisor-only mappings are not
constrained by this user W^X rule in v1.

`create_address_space` returns the new address-space id in `result`.
`map_pages` and `protect_pages` use `address_space`, `virtual_start`,
`physical_start`, `page_count`, and `flags`. `activate_user_address_space`
uses `address_space`, `entry_pc`, and `stack_pointer`, then switches the current
K16 CPU to translated user execution at `entry_pc`. For this command,
`stack_pointer` is the user stack pointer and a non-zero `physical_start`
selects the physical kernel stack pointer used for later traps from that user
context. If `physical_start` is zero, the VM preserves the CPU's existing
trap-kernel-stack pointer for compatibility with older activation callers.

`copy_from_user` and `copy_to_user` use `address_space`, `virtual_start`,
`physical_start`, and `byte_count` (the register at offset `0x1C`). Both
commands are intended for physical/kernel syscall handlers. `copy_from_user`
loads bytes from translated user virtual memory and writes them into physical
kernel RAM. `copy_to_user` loads bytes from physical kernel RAM and stores them
into translated user virtual memory. The requested user pages must be
user-accessible; `copy_to_user` additionally requires writable mappings.
Successful copy commands return the copied byte count in `result`.

`set_trap_return_physical` takes no argument registers. It changes the current
CPU's saved trap-return mode to physical/kernel so the next `iret` resumes a
physical-mode parent frame. The command is intended for the transitional
process model where a physical parent can synchronously run a translated child.

`set_trap_return_address_space` uses `address_space` plus a non-zero
`physical_start`. It changes the current CPU's saved trap-return mode to
translated/user for the supplied address space, and installs `physical_start`
as the physical kernel stack pointer used by later traps from that resumed user
context. The command is intended for the transitional process model where a
translated shell can synchronously run a translated utility child.

`destroy_address_space` uses `address_space` and removes that host-managed
address space plus all of its mappings. Destroying a missing id produces
`invalid_address_space`. The current K16 kernel uses this during process
`EXIT` for translated children before returning to the blocked parent frame.

The VM rejects unknown flag bits, unaligned mappings, zero page counts,
overlapping virtual mappings, and other malformed map/protect arguments by
setting `status = error` and `error = invalid_argument`. Unknown address spaces
produce `invalid_address_space`. Failed user translation or permission checks
produce `translation_fault`. Physical kernel buffer ranges outside guest RAM
produce `physical_out_of_bounds`; overflowing byte ranges produce
`byte_count_overflow`.

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

`flush` asks the storage media backend to make previously accepted writes
durable. It does not transfer guest memory and leaves `bytes_done = 0`.

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
