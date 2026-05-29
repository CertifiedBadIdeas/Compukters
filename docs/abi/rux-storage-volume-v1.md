# RUX Storage Volume v1

## Status

Status: experimental.

`RUXVOL` is the current block-media container used by `storage0` test and
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
magic    "RUXVOL"
version  1
```

`payload_size` is the exact number of guest-visible media bytes after the host
file header. The file length must be `16 + payload_size`.

## Guest-Visible Layout

RUXVOL v1 uses the partitioned layout created by `rux volume init`:

```text
LBA 0        RUXPT partition table
LBA 1..32    BOOT partition
LBA 33..end  ROOT partition
```

In the partitioned layout, `rux volume put-boot` formats the `BOOT` partition
as RuxFS and writes the bootloader `RUXE` file to `/boot/loader.ruxe`.
`rux volume put-kernel` writes the kernel `RUXE` file to `/boot/kernel.ruxe`
inside the `ROOT` RuxFS partition. The partitioned layout does not use fixed
`RUXB` or `RUXK` records.

`rux volume create` creates an unpartitioned volume for non-boot data and tests.
Boot installation commands must reject unpartitioned volumes instead of writing
fixed boot records.

General filesystem operations are not part of `rux volume`. Tooling for RuxFS
uses `rux fs ruxfs ...`; future filesystems should use their own `rux fs
<filesystem>` namespace. `put-boot` and `put-kernel` are boot-chain
installation helpers for the current RuxFS-backed system volume layout.

`rux volume` may copy partition bytes without interpreting their filesystem:

```text
rux volume inspect <volume.ruxvol>
rux volume extract-partition <volume.ruxvol> <partition> <output>
rux volume replace-partition <volume.ruxvol> <partition> <input>
```

`inspect` prints the RUXVOL header summary and the decoded `RUXPT` partition
layout, including partition type, start LBA, block count, byte size, and name.

`<partition>` matches either the partition type tag, for example `BOOT` or
`ROOT`, or the partition name, for example `boot` or `root`. Replacement input
must match the partition size exactly. Tooling must reject wrong-sized input
rather than truncating or padding it.

Example ROOT filesystem image workflow:

```text
rux volume init storage0.ruxvol --size 65536
rux volume inspect storage0.ruxvol
rux fs ruxfs format root.ruxfs --blocks 95
rux fs ruxfs mkdir root.ruxfs /boot
rux fs ruxfs put root.ruxfs /boot/kernel.ruxe kernel.ruxe
rux volume replace-partition storage0.ruxvol ROOT root.ruxfs
rux volume extract-partition storage0.ruxvol ROOT check-root.ruxfs
rux fs ruxfs get check-root.ruxfs /boot/kernel.ruxe check-kernel.ruxe
```

This workflow keeps `rux volume` responsible for partition bytes and `rux fs`
responsible for filesystem contents.

## RUXPT Partition Table

`RUXPT` is guest-visible and starts at LBA 0 in the partitioned layout. It
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
magic        "RUXPT"
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

1. BIOS reads `RUXPT` from LBA 0.
2. BIOS reads `/boot/loader.ruxe` from the `BOOT` RuxFS partition.
3. BIOS validates the bootloader `RUXE`, copies its payload to `load_addr`,
   and jumps to `entry_pc`.
4. Bootloader reads `/boot/kernel.ruxe` from the `ROOT` RuxFS partition.
5. Bootloader validates the kernel `RUXE`, copies its payload to `load_addr`,
   and jumps to `entry_pc`.
6. Kernel reads `/bin/init.ruxe` from the `ROOT` RuxFS partition.
7. Kernel validates the program `RUXE`, copies its payload to `load_addr`, and
   jumps to `entry_pc`.

There is no fallback probing for fixed boot records, alternate paths, or raw
instruction bytes.

## Retired Fixed Records

Earlier development slices used fixed raw-media records named `RUXB` and
`RUXK`. They are retired from the active ABI. Current BIOS and volume tooling
must reject missing or malformed `RUXPT`/RuxFS structures instead of booting
from fixed LBA records.
