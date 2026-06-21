# K16 Storage Volume v1

## Status

Status: experimental.

`K16VOL` is the current block-media container used by `storage0` test and
tooling flows. The VM exposes the payload bytes as the storage device media;
the 16-byte host file header is not visible to the guest.

All multi-byte fields are little-endian.

## Host File Header

```text
offset  size  name
0x00    6     magic
0x06    2     version
0x08    8     payload_size
```

Field values for v1:

```text
magic    "K16VOL"
version  1
```

`payload_size` is the exact number of guest-visible media bytes after the host
file header. The file length must be `16 + payload_size`.

## Guest-Visible Layout

K16VOL v1 uses the partitioned layout created by `k16 volume init`:

```text
LBA 0        K16PT partition table
LBA 1..256   BOOT partition
LBA 257..end ROOT partition
```

In the partitioned layout, `k16 volume put-boot` formats the `BOOT` partition
as K16FS and writes the bootloader `K16E` file to `/boot/loader.kb`.
`k16 volume put-kernel` writes the kernel `K16E` file to `/boot/kernel.kx`
inside the `ROOT` K16FS partition. The partitioned layout does not use fixed
`K16B` or `K16K` records.

`k16 volume create` creates an unpartitioned volume for non-boot data and tests.
Boot installation commands must reject unpartitioned volumes instead of writing
fixed boot records.

General filesystem operations are not part of `k16 volume`. Tooling for K16FS
uses `k16 fs kfs ...`; future filesystems should use their own `k16 fs
<filesystem>` namespace. `put-boot` and `put-kernel` are boot-chain
installation helpers for the current K16FS-backed system volume layout.

`k16 volume` may copy partition bytes without interpreting their filesystem:

```text
k16 volume inspect <volume.kv>
k16 volume extract-partition <volume.kv> <partition> <output>
k16 volume replace-partition <volume.kv> <partition> <input>
```

`inspect` prints the K16VOL header summary and the decoded `K16PT` partition
layout, including partition type, start LBA, block count, byte size, and name.

For format auto-detection across storage artifacts, use the read-only generic
inspector:

```text
k16 inspect <blob>
```

It identifies `K16VOL`, standalone `K16PT` media bytes, standalone `K16FS`
filesystem images, and `K16E` executables. Dynamic K16E v2 program images also
report their entry offset, memory size, relocation count, and relocation table
byte size. Snapshot blobs are intentionally not reported here until the native
ComputerMachine snapshot format is defined.

`<partition>` matches either the partition type tag, for example `BOOT` or
`ROOT`, or the partition name, for example `boot` or `root`. Replacement input
must match the partition size exactly. Tooling must reject wrong-sized input
rather than truncating or padding it.

Example ROOT filesystem image workflow:

```text
k16 volume init storage0.kv --size 1048576
k16 volume inspect storage0.kv
k16 fs kfs format root.kfs --blocks 1791
k16 fs kfs mkdir root.kfs /boot
k16 fs kfs put root.kfs /boot/kernel.kx kernel.kx
k16 volume replace-partition storage0.kv ROOT root.kfs
k16 volume extract-partition storage0.kv ROOT check-root.kfs
k16 fs kfs get check-root.kfs /boot/kernel.kx check-kernel.kx
```

This workflow keeps `k16 volume` responsible for partition bytes and `k16 fs`
responsible for filesystem contents.

## K16PT Partition Table

`K16PT` is guest-visible and starts at LBA 0 in the partitioned layout. It
occupies one 512-byte block.

Header:

```text
offset  size  name
0x00    5     magic
0x05    1     version
0x06    1     entry_count
0x07    1     reserved
0x08    4     table_lba
0x0C    4     table_blocks
```

Field values for v1:

```text
magic        "K16PT"
version      1
reserved     0
table_lba    0
table_blocks 1
```

Entries start at offset `0x10`. Each entry is 32 bytes:

```text
offset  size  name
0x00    4     type
0x04    4     flags
0x08    4     start_lba
0x0C    4     block_count
0x10    16    name
```

Accepted initial partition types:

```text
BOOT
ROOT
```

`name` is UTF-8, NUL-padded, and must be 1..16 bytes.

Validation must reject bad magic/version, non-zero reserved fields, too many
entries, zero-sized partitions, partitions that start inside the table area,
partitions outside the guest-visible media size, and overlapping partitions.

## Boot Chain

The current boot chain is:

1. BIOS reads `K16PT` from LBA 0.
2. BIOS reads `/boot/loader.kb` from the `BOOT` K16FS partition.
3. BIOS validates the bootloader `K16E`, copies its payload to `load_addr`,
   and jumps to `entry_pc`.
4. Bootloader reads `/boot/kernel.kx` from the `ROOT` K16FS partition.
5. Bootloader validates the kernel `K16E`, copies its payload to `load_addr`,
   and jumps to `entry_pc`.
6. Kernel reads `/bin/init.kx` from the `ROOT` K16FS partition.
7. Kernel validates the program `K16E`, resolves any declared K16E v5 shared
   object imports from `ROOT` K16FS `/lib/<needed-library>`, copies the program
   and shared object payloads into the child process, and jumps to `entry_pc`.
8. The bundled init program supervises `/bin/shell.kx` through the K16
   `SPAWN` and `WAIT` syscalls.
9. The shell launches foreground utilities by resolving command names to
   `/bin/*.kx` and issuing the K16 `RUN` syscall with a structured argv request.
   The kernel must open the requested program file from the `ROOT` K16FS
   partition, validate the dynamic `K16E` program image, and start the child
   process. A missing utility file is
   a hard `NOENT`-style launch failure, not a fallback to a bundled program.

There is no fallback probing for fixed boot records, alternate paths, or raw
instruction bytes.

## Retired Fixed Records

Earlier development slices used fixed raw-media records named `K16B` and
`K16K`. They are retired from the active ABI. Current BIOS and volume tooling
must reject missing or malformed `K16PT`/K16FS structures instead of booting
from fixed LBA records.
