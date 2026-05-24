# Rux Storage MMIO Contract Design

> Issue: [#52](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/52)

## Status

Draft for review.

## Context

Rux machine profile v2 already separates the CPU/image ABI from target machine
hardware. `RUXI` image ABI v1 remains unchanged, while concrete machine targets
describe guest-visible MMIO ranges through `BootInfo` and the hardware table.

Current Rux computer profile v1 exposes control, debug serial, serial input, and
text display devices. It has no persistent storage contract yet. Storage is the
next foundation layer for world-backed volume blobs, partitions, a real
filesystem, `std::fs`, boot from a persistent system volume, and notebook storage
media.

This design defines the guest-visible storage MMIO contract only. It does not
define host blob persistence, partitions, or filesystem layout.

## Goals

- Define a small block storage port that guest firmware can discover through
  the existing hardware table.
- Keep storage optional: firmware must work when no storage entry exists.
- Keep media optional: a storage port can exist with no disk/flash media
  attached.
- Keep the image ABI stable: no `RUXI` instruction encoding or instruction
  semantics change.
- Use guest RAM buffers for block I/O so larger reads and writes do not require
  per-byte MMIO loops.
- Leave room for future asynchronous execution without requiring it now.

## Non-Goals

- No filesystem, VFS, partition table, `std::fs`, or bootloader format.
- No hotplug. The hardware table and media attachment state are static for a
  boot.
- No generic device class, flags, descriptors, USB, PCI, or ACPI layer.
- No host world-save blob layout.
- No journaling or crash recovery semantics.

## Alternatives Considered

### Device-Local Data Window

The storage MMIO range could expose a byte window. Firmware would copy bytes
between RAM and the device window manually.

Pros:

- Easy to implement with the current `MmioDevice` trait.
- No DMA-like access from a device into RAM.

Cons:

- Firmware must loop over every byte or word for every sector.
- Filesystem and bootloader code becomes unnecessarily noisy and slow.
- It does not resemble the eventual volume/filesystem model.

### Guest RAM DMA Buffer

The storage port uses `buffer_addr`, `lba`, and `block_count` registers. A
command copies blocks between the backing volume and guest RAM.

Pros:

- Better firmware ergonomics.
- More realistic block device model.
- Efficient enough for boot, filesystem, and future `std::fs`.
- Keeps the MMIO register surface small.

Cons:

- The VM needs a controlled way for the storage port to read/write guest RAM.
- Implementation must carefully validate RAM bounds and integer overflow.

Decision: use guest RAM DMA buffer semantics. The implementation can either let
the bus execute storage commands with memory access, or let the storage port
return an internal request that the bus resolves. The guest-visible contract is
the same either way.

## Port And Media Model

The hardware table describes storage ports, not guaranteed attached disks.

A port is a stable machine capability:

- it has a stable hardware id for the current target profile;
- it has a stable MMIO range for the current boot;
- it may have media attached or may be empty;
- media attachment changes become guest-visible only after reboot.

Media is the thing inserted into a port:

- internal disk volume owned by a computer or notebook block entity;
- removable media volume owned by an item stack;
- future external storage module.

The VM core does not need to know whether media is internal, removable, disk, or
flash. It only exposes block I/O through the port.

## Hardware Table Entry

Rux computer profile v1 should define one initial storage port:

```text
id  name      mmio_base     mmio_size
5   storage0  0x1000_0400   0x0000_0100
```

The address is a target-profile assignment, not an image ABI feature. Firmware
must discover the entry through `BootInfo.hardware_table_addr` and
`BootInfo.hardware_count`.

If the entry is absent, portable firmware treats storage as unavailable.

If the entry exists but no media/volume is attached, the port reports
`media_status = ABSENT`, zero capacity, and returns `MEDIA_ABSENT` for I/O
commands.

## Register Layout

All registers are little-endian. Multi-byte values are `u32` bit patterns
accessed through normal VM loads/stores.

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

`version` is `1`.

`capacity_blocks` is a `u64` split into low/high `u32` words.

`block_size` is the port block size in bytes. The first implementation should
use `512`, but firmware must read the register instead of hardcoding it.

`block_size` remains defined even when no media is attached. Empty ports report
zero capacity rather than changing block size.

`bytes_done` reports the number of bytes completed by the last command. It is
zero after reset and after a failed command.

`sequence` is a monotonic `u64` split into low/high words. It increments after a
command reaches `DONE` or `ERROR`.

## Media Status Values

```text
0  ABSENT
1  PRESENT
2  READ_ONLY
3  ERROR
```

`ABSENT` means the port is real but no media is attached for this boot.

`PRESENT` means the port has readable and writable media.

`READ_ONLY` means reads are allowed and writes fail with `WRITE_PROTECTED`.

`ERROR` means media exists from the host perspective but is not currently usable.
I/O commands fail with `IO_ERROR`.

## Status Values

```text
0  READY
1  BUSY
2  DONE
3  ERROR
```

The first implementation may execute commands synchronously, so firmware may
observe `DONE` or `ERROR` immediately after writing `command`. `BUSY` is still
reserved in v1 so a later implementation can perform asynchronous work without
changing the register layout.

Writing a new command clears `error` and `bytes_done` before command validation.

## Error Values

```text
0  NONE
1  INVALID_COMMAND
2  MEDIA_ABSENT
3  BUFFER_OUT_OF_BOUNDS
4  LBA_OUT_OF_BOUNDS
5  BYTE_COUNT_OVERFLOW
6  WRITE_PROTECTED
7  IO_ERROR
```

All errors are deterministic. A command that fails must not partially modify
guest RAM or backing storage unless the error is `IO_ERROR` from a lower host
layer that cannot guarantee atomicity. Crash-safety for host persistence is
handled by a separate issue.

## Commands

```text
0  NOP
1  READ_BLOCKS
2  WRITE_BLOCKS
3  FLUSH
```

### NOP

Completes successfully with `bytes_done = 0`.

### READ_BLOCKS

Reads `block_count` blocks starting at `lba` from the backing volume into guest
RAM starting at `buffer_addr`.

Validation:

- `media_status != ABSENT`, otherwise `MEDIA_ABSENT`;
- `media_status != ERROR`, otherwise `IO_ERROR`;
- `lba + block_count <= capacity_blocks`, otherwise `LBA_OUT_OF_BOUNDS`;
- `block_count * block_size` must fit in `u32`, otherwise
  `BYTE_COUNT_OVERFLOW`;
- `buffer_addr .. buffer_addr + byte_count` must be fully inside RAM, otherwise
  `BUFFER_OUT_OF_BOUNDS`.

`block_count = 0` is valid and completes with `bytes_done = 0`.

### WRITE_BLOCKS

Writes `block_count` blocks from guest RAM starting at `buffer_addr` into the
backing volume starting at `lba`.

It uses the same validation as `READ_BLOCKS`, plus `WRITE_PROTECTED` if
`media_status = READ_ONLY`.

`block_count = 0` is valid and completes with `bytes_done = 0`.

### FLUSH

Requests that the host flush any buffered writes for this storage port.

The first implementation may be a no-op if writes are already synchronous. It
still completes with `DONE` and increments `sequence`.

## RAM Access Rules

`buffer_addr` is a guest physical RAM address.

The storage port must reject buffers that point into MMIO or outside RAM.

The storage port does not special-case the boot page. Firmware should avoid
overwriting boot data unless it intentionally gives up access to `BootInfo`, but
that policy is not enforced by storage.

## Missing Hardware Semantics

If no hardware table entry with storage id `5` exists, firmware treats storage as
unavailable.

If a storage entry exists but `media_status = ABSENT`, firmware treats the port
as empty, not as unsupported hardware.

The Rux standard library should map missing storage to deterministic "not
available" errors. It should not invent hidden host files or fallback to old VM
paths.

## BIOS And Boot Discovery

This storage contract intentionally stops at block I/O. Boot discovery is a
firmware/BIOS responsibility layered on top of storage.

The intended boot flow is:

- host starts the machine with a BIOS or firmware image;
- BIOS reads `BootInfo` and scans hardware table entries for known storage
  ports;
- for each present storage port, BIOS reads the first blocks and looks for a
  future Rux volume/partition/boot record;
- if exactly one bootable entry is found, BIOS loads it;
- if multiple bootable entries are found and display/input are available, BIOS
  may present a boot menu;
- if multiple bootable entries are found but no display/input is available, BIOS
  should follow a deterministic boot order;
- if no bootable entry is found, BIOS reports a no-bootable-device state or
  enters a setup/shell path.

Boot order is not part of the VM core. It belongs to the target machine profile,
firmware configuration, or host/block entity configuration.

The boot record, partition table, and filesystem formats are follow-up work.

## Implementation Boundary

The current `MmioDevice` trait receives only offset/value accesses and cannot
directly read or write guest RAM. Implementing this contract should add a narrow
bus/device boundary for command-time DMA, for example:

- bus executes storage command requests against `MachineMemory`; or
- storage port receives a limited memory accessor only while handling a
  command write.

The chosen Rust implementation detail must not leak into the guest-visible MMIO
contract.

## Test Plan

- Native VM test: hardware table includes `storage0` when configured.
- Native VM test: missing storage entry is allowed.
- Native VM test: present storage port with absent media reports `ABSENT`, zero
  capacity, and `MEDIA_ABSENT` for I/O.
- Native VM test: `READ_BLOCKS` copies bytes from an in-memory backing volume
  into guest RAM.
- Native VM test: `WRITE_BLOCKS` copies bytes from guest RAM into backing
  volume.
- Native VM test: out-of-bounds buffer returns `BUFFER_OUT_OF_BOUNDS`.
- Native VM test: out-of-bounds LBA returns `LBA_OUT_OF_BOUNDS`.
- Native VM test: read-only media allows reads and rejects writes with
  `WRITE_PROTECTED`.
- ABI/profile docs test or fixture: register constants match the documented
  layout.

## Open Follow-Up Work

- #53 will define host-backed world volume blobs.
- #54 will define partition metadata on top of block storage.
- #55 will define the filesystem format.
- #57 will expose file APIs in Rux stdlib.
- #62 will define boot from persistent system volume.
