# RUXE v1 Rux16 Executable

## Status

Status: experimental.

`RUXE` is the guest-loadable executable container for Rux16 bootloader,
kernel, and user-space program images. It is the format firmware, boot loaders,
and future OS exec services should read from storage before copying image bytes
into guest RAM and jumping to an entry address.

This first version intentionally supports one load section. It is enough to
separate "executable image file" from "raw instruction bytes" without
introducing relocation, dynamic linking, or a full process model yet.

## Relationship To Existing Targets

`rux compile` target meanings:

```text
boot     RUXE v1 fixed image, ABI kind bootloader, prepared for storage0 boot media
kernel   RUXE v1 fixed image, ABI kind kernel, loaded by a bootloader
program  RUXE v1 user-space executable, ABI kind program, loaded by an OS exec service
bios     raw Rux16 bytes mapped as BIOS flash
```

Loaders and volume tools must not treat raw Rux16 bytes as a valid `RUXE`
executable. Invalid magic is a hard decode error.

## Endianness

All multi-byte fields are little-endian.

## Header

The fixed header is 32 bytes.

```text
offset  size  name
0x00    4     magic
0x04    2     version
0x06    2     header_size
0x08    2     isa
0x0A    2     flags
0x0C    4     entry_pc
0x10    4     section_table_offset
0x14    4     section_count
0x18    4     abi_kind
0x1C    4     reserved1
```

Field values for v1:

```text
magic                 "RUXE"
version               1
header_size           32
isa                   1  (Rux16)
flags                 0
section_table_offset  32
section_count         1
abi_kind              1  (bootloader), 2  (kernel), or 3  (program)
reserved1             0
```

`entry_pc` is the guest physical address where execution starts after all load
sections have been copied. In v1 this is part of a trusted fixed-image ABI:
the image describes where it was linked to run, and the loader must reject it
if that range is not allowed by the current boot or exec policy.

ABI kind values:

```text
1  bootloader
2  kernel
3  program
```

The `program` kind is the first filesystem-backed user-space executable
profile. It is intended for OS-level `spawn(path, argv)` style services: the OS
resolves paths and reads file bytes from storage, then validates and loads a
`RUXE` program image. Host APIs must not accept guest filesystem paths.

The first `program` compiler profile links code at `0x00008000`. This address
is part of the target profile selected by `rux compile --target program`; Rux
source code does not choose an arbitrary load address. A kernel or exec service
must still validate that a program image uses the allowed user program range
before execution.

## Section Record

The v1 section table contains exactly one 20-byte load-section record.

```text
offset  size  name
0x00    4     kind
0x04    4     load_addr
0x08    4     file_offset
0x0C    4     file_size
0x10    4     memory_size
```

Field values and rules:

```text
kind         1  (load)
file_offset 52
file_size   non-zero Rux16 byte length
memory_size equal to file_size in v1
```

`load_addr` is the guest physical address where the payload bytes are copied.
`entry_pc` must point inside this loaded range and must be 2-byte aligned.

Zero-fill sections are deliberately not part of this first v1 slice. A decoder
must reject `memory_size != file_size`.

## Loader Algorithm

A loader should:

1. Read and validate the fixed header.
2. Validate the single section record.
3. Check that the payload byte range is present in the file.
4. Check that `load_addr + memory_size` does not overflow and fits in the
   target guest RAM range.
5. Copy `file_size` bytes from `file_offset` to guest RAM at `load_addr`.
6. Start or jump to `entry_pc`.

No loader should guess an entry address from file position, reinterpret a
different ABI kind, or fall back to raw instruction bytes.

For `program` images, the normal path is:

```text
OS filesystem -> read RUXE file bytes into RAM -> exec service validates ABI kind program -> load payload -> enter entry_pc
```

The exec service receives bytes or guest RAM ranges, never a host-side path.
The VM runtime provides the filesystem read and `program` ABI kind validation
boundary for already-read bytes. The first guest-side kernel loader now
exercises the same policy directly from storage: it reads `/bin/init.ruxe` from
the `ROOT` RuxFS partition, validates ABI kind `program`, copies the payload to
`load_addr`, and jumps to `entry_pc`.

## Storage Volume Tooling

`RUXE` is the artifact format produced by the compiler. The current `RUXVOL`
storage tooling decodes fixed-image `RUXE` artifacts and writes compact
guest-visible records:

- `put-boot` accepts ABI kind `bootloader` and writes a `RUXB` record.
- `put-kernel` accepts ABI kind `kernel` and writes a `RUXK` record.
- `program` artifacts are not installed by `rux volume put-boot` or
  `put-kernel`; they belong in a filesystem such as `RuxFS` and are selected by
  OS policy.

The fixed storage layout is defined in `rux-storage-volume-v1.md`.

## Validation Errors

A decoder must reject:

- invalid magic;
- unsupported version;
- unsupported header size;
- unsupported ISA;
- non-zero flags;
- unsupported section table offset;
- section count other than `1`;
- unsupported ABI kind;
- non-zero reserved header fields;
- unsupported section kind;
- payload offset other than `52`;
- empty payload;
- odd Rux16 payload length;
- `memory_size != file_size`;
- payload range outside the file;
- load range overflow;
- `entry_pc` outside the loaded range;
- unaligned `entry_pc`.
