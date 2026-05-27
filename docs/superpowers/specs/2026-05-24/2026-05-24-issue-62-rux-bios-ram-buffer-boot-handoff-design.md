# Rux BIOS RAM-Buffer Boot Handoff Design

> Issue: [#62](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/62)

## Status

Accepted; first implementation slice covers the VM-side RAM-buffer handoff API.

## Context

Rux already has the pieces needed for a BIOS-first storage boot path:

- `BootInfo` and `HardwareTable` describe guest-visible hardware;
- `storage0` is a block device exposed through MMIO;
- `.ruxvol` provides persistent world-backed media;
- the bundled BIOS runs as the first RUXI image.

The missing piece is the transition from BIOS to a program stored on `storage0`.
The current VM executes host-decoded `RUXI` images, not arbitrary executable
bytes directly from guest RAM. Because of that, the first storage boot
implementation needs a narrow VM handoff service.

The handoff must preserve the machine model: BIOS reads storage and owns boot
policy. The host/VM should only decode and start executable bytes that BIOS has
already loaded into guest RAM.

## Goals

- Define a BIOS-first boot flow from persistent `storage0`.
- Keep boot media discovery and boot record parsing inside BIOS.
- Avoid host-side knowledge of storage layout, partition tables, or filesystem
  paths.
- Provide a temporary `RUXI` decode/start primitive that can later be replaced by
  guest-side executable loading.
- Keep normal post-boot program execution separate from BIOS handoff. See #68
  for the OS exec service.

## Non-Goals

- No filesystem, partition table, boot menu, installer, or shell in this issue.
- No host-side storage path or LBA based boot API.
- No change to frozen `RUXI` v1 instruction encoding.
- No guest-side executable relocation/loading in the first slice.
- No ordinary `/bin/*` program spawning from BIOS.

## Boot Flow

The first implementation should use this flow:

```text
Host
  -> creates ComputerMachine
  -> attaches RAM/MMIO/display/input/storage0
  -> loads BIOS RUXI as first-stage firmware
  -> starts BIOS CPU

BIOS
  -> validates BootInfo
  -> scans HardwareTable
  -> finds storage0
  -> checks media_status and capacity
  -> reads LBA 0 boot record through storage0 READ_BLOCKS
  -> validates boot record
  -> reads boot image blocks through storage0 into a RAM staging buffer
  -> calls boot handoff service with image_addr/image_len

VM handoff service
  -> validates image_addr/image_len guest RAM range
  -> copies RUXI bytes from guest RAM
  -> decodes and validates RUXI
  -> replaces BIOS CPU with the loaded boot image

Loaded boot image
  -> starts with normal BootInfo/HardwareTable
  -> becomes the first post-BIOS program
```

The key decision is that BIOS reads the disk. The VM handoff service does not
accept storage ids, LBAs, paths, or boot metadata. It accepts only a guest RAM
buffer.

## Boot Record

The first boot record can be a fixed binary structure in LBA 0:

```text
offset  size  name
0x00    7     magic = "RUXBOOT"
0x07    1     version = 1
0x08    8     image_lba
0x10    8     image_len
0x18    4     image_crc32
0x1C    4     flags
0x20    32    reserved
```

All multi-byte fields are little-endian. `image_lba` is a block address relative
to the same storage media. `image_len` is the exact byte length of the candidate
RUXI image. `image_crc32` covers the image bytes, not the boot record. A zero
CRC may be reserved for "unchecked" only if implementation needs that shortcut;
otherwise BIOS should require the checksum to match.

The boot record intentionally does not name files. Later filesystem or partition
formats can define how this record points to a richer bootloader. The first
version only needs a contiguous RUXI image.

## Boot Handoff Service

The exact transport can be a small `boot0` MMIO device, a syscall-like machine
service, or another profile-specific VM call surface. The guest-visible contract
should be equivalent to:

```text
boot_handoff_ruxi(
  image_addr: u32,
  image_len: u32,
  flags: u32,
) -> BootHandoffResult
```

`image_addr..image_addr + image_len` must be entirely inside guest RAM and must
not overflow the address space. The service reads the candidate `RUXI` image
from that range.

The service should not accept:

- storage device id;
- LBA;
- filesystem path;
- host file path;
- `.ruxvol` path.

This keeps boot policy in BIOS and prevents host-side shortcuts from becoming
the architecture.

## CPU And Machine State

On successful handoff, the BIOS CPU is replaced by the loaded image. BIOS does
not remain as a firmware process.

The loaded image receives the same machine profile:

- `BootInfo` at the profile-defined address;
- `HardwareTable`;
- `storage0`;
- display/input/control/debug devices when present.

The first slice does not need a separate `BootHandoffInfo` block. If the loaded
program needs to know its boot source later, a follow-up can add a small RAM
structure with boot storage id, boot flags, and boot record metadata.

## Error Handling

BIOS should stay alive and report a deterministic firmware state when boot fails.

BIOS-detected errors:

- no storage hardware entry;
- media absent;
- capacity too small for LBA 0;
- boot record read failure;
- invalid boot record magic/version;
- image range outside media capacity;
- image checksum mismatch;
- image too large for the RAM staging buffer.

Handoff-service errors:

- bad RAM buffer;
- empty image;
- image too large for configured decode limits;
- invalid RUXI bytes;
- unsupported image format;
- VM decode/validation failure;
- unable to replace BIOS CPU.

For handoff-service errors, the service returns an error code and leaves BIOS
running. BIOS then renders a clear display/debug message such as `Invalid boot
image` or `Boot handoff failed`.

## Relationship To OS Exec

Boot handoff and normal program execution use the same important boundary:
executable bytes come from guest RAM.

They differ in lifecycle:

```text
boot0:
  BIOS -> boot image
  replaces BIOS CPU
  used once during boot

exec service (#68):
  OS -> child process
  returns PID or error
  used repeatedly after the OS is running
```

BIOS should not launch `/bin/*` programs. Once the first post-BIOS image is
running, normal filesystem-backed program execution belongs to the OS exec
service.

## Migration Path

This design keeps the future guest-side loader path open:

```text
Phase 1:
  BIOS reads image into RAM -> boot0 asks VM to decode/start RUXI

Phase 2:
  BIOS or bootloader reads executable into RAM -> guest-side loader maps/starts it
```

The boot record and BIOS storage reads can remain useful after Phase 2. The
replaceable part is only the final "decode/start RUXI" service.

## Testing Strategy

Initial native tests:

- BIOS-visible storage can read a valid boot record from LBA 0.
- BIOS-visible storage can read a contiguous RUXI image into RAM.
- handoff accepts a valid in-RAM RUXI image and starts/replaces the boot CPU.
- handoff rejects a buffer outside guest RAM.
- handoff rejects invalid RUXI bytes and leaves BIOS running.
- invalid boot record magic/version produces a visible firmware error state.

Integration tests:

- prepared `storage0.ruxvol` with boot record + RUXI image boots without
  rebuilding the mod jar;
- missing media shows `No bootable device`;
- corrupt image shows `Invalid boot image`;
- existing bundled BIOS/dev boot remains available as an explicit bootstrap path.
