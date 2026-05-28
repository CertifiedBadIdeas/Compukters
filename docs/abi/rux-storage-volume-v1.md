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

## Guest-Visible Layouts

RUXVOL v1 currently has two explicit tooling layouts.

`rux volume create` creates an empty volume. `rux volume put-boot` and
`rux volume put-kernel` write the legacy fixed boot layout used by the current
BIOS/bootloader chain. This layout reserves fixed media LBAs:

```text
LBA 0   RUXB bootloader record
LBA 1   bootloader payload bytes
LBA 16  RUXK kernel record
LBA 17  kernel payload bytes
```

Each LBA is 512 bytes. Tooling must reject artifacts that do not fit these
fixed regions. It must not relocate records or payloads implicitly.

`rux volume init` creates the partitioned layout targeted by the next boot
chain:

```text
LBA 0        RUXPT partition table
LBA 1..32    BOOT partition
LBA 33..end  ROOT partition
```

The partitioned layout does not contain `RUXB` or `RUXK` fixed records.
Current firmware does not boot it yet; future BIOS and bootloader work should
consume `RUXPT` directly rather than probing both layouts.

Filesystem-specific operations are not part of `rux volume`. Tooling for RuxFS
uses `rux fs ruxfs ...`; future filesystems should use their own `rux fs
<filesystem>` namespace.

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

## RUXB Bootloader Record

`rux volume put-boot` accepts only `RUXE` artifacts with ABI kind `bootloader`
and writes this record at LBA 0.

```text
offset  size  name
0x00    4     magic
0x04    4     entry_pc
0x08    4     load_addr
0x0C    4     block_count
0x10    4     start_lba
```

Field values:

```text
magic      "RUXB"
start_lba  1
```

The bootloader payload bytes are copied from the `RUXE` load section to LBA 1.

## RUXK Kernel Record

`rux volume put-kernel` accepts only `RUXE` artifacts with ABI kind `kernel`
and writes this record at LBA 16.

```text
offset  size  name
0x00    4     magic
0x04    4     entry_pc
0x08    4     load_addr
0x0C    4     block_count
0x10    4     start_lba
```

Field values:

```text
magic      "RUXK"
start_lba  17
```

The kernel payload bytes are copied from the `RUXE` load section to LBA 17.

## Boot Chain

The current boot chain is:

1. BIOS reads `RUXB` from LBA 0.
2. BIOS copies the bootloader payload from LBA 1 to `load_addr`.
3. BIOS jumps to `entry_pc`.
4. Bootloader reads `RUXK` from LBA 16.
5. Bootloader copies the kernel payload from LBA 17 to `load_addr`.
6. Bootloader jumps to `entry_pc`.

This is a fixed pre-filesystem layout. Filesystem-backed loading should replace
the fixed `RUXB`/`RUXK` locations rather than adding fallback guesses.
