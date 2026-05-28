# RUXPT Boot And Root Filesystem Layout Design

> Issue: [#54](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/54)

## Context

The current `storage0.ruxvol` boot path uses fixed guest-visible LBAs: BIOS reads a `RUXB` boot record at LBA 0, the bootloader reads a `RUXK` kernel record at LBA 16, and payloads live at fixed follow-up LBAs. That path proved the BIOS-first storage chain, but it should not become the long-term disk layout.

The next disk layer should make boot media look like a small real disk: firmware discovers a partition table, executable artifacts live as files in filesystems, and later OS code can use the same root filesystem model for kernel and user programs.

## Accepted Direction

Target the cleaner boot model where the only fixed storage anchor is the partition table at LBA 0.

```text
guest media:
  LBA 0    RUXPT partition table
  BOOT     boot filesystem
           /boot/loader.ruxe
           /boot/config
  ROOT     root filesystem
           /boot/kernel.ruxe
           /bin/init.ruxe
```

There is no required `KERN` partition. The kernel is a regular file, initially `/boot/kernel.ruxe`, stored in the `ROOT` filesystem and loaded by the bootloader.

There is no `DATA` partition in the first design. Extra data storage should be introduced only when there is a concrete OS/runtime need for it.

## Boot Flow

1. BIOS reads LBA 0 and validates `RUXPT`.
2. BIOS finds the `BOOT` partition entry.
3. BIOS reads the minimal filesystem inside `BOOT`.
4. BIOS loads `/boot/loader.ruxe`.
5. BIOS jumps to the loader entry point.
6. Bootloader reads LBA 0 and validates `RUXPT`.
7. Bootloader finds the `ROOT` partition entry.
8. Bootloader reads `/boot/kernel.ruxe` from `ROOT`.
9. Bootloader jumps to the kernel entry point.

The BIOS does not know about `ROOT`, the kernel file, userspace, or fixed kernel LBAs. The bootloader owns kernel discovery.

## Format Boundaries

`RUXVOL` remains a host file container. Its 16-byte header is not visible to the guest and is not part of the partition table.

`RUXPT` is guest-visible and starts at LBA 0. It describes partitions by type, start LBA, block count, flags, and a short name. The first accepted partition types are:

```text
BOOT
ROOT
```

`BOOT` and `ROOT` contain the same minimal filesystem format. The first implementation may support only the read/write operations needed by tooling, plus the read-only subset needed by BIOS and bootloader.

## Explicit Non-Goals

- Do not add `KERN` as a required partition type.
- Do not add `DATA` before there is a specific use case.
- Do not preserve `RUXB` or `RUXK` fixed LBA records as alternate discovery paths.
- Do not make BIOS parse the full future OS filesystem feature set.
- Do not add MBR/GPT compatibility.

## Implementation Slices

1. Define and test `RUXPT` encode/decode/validation.
2. Define the minimal `RUXFS` structures needed for file lookup by absolute path and extent-backed file reads.
3. Add `rux volume init` to create a deterministic `BOOT` + `ROOT` layout.
4. Change volume tooling so bootloader/kernel artifacts are written as files, not fixed LBA records.
5. In a follow-up, migrate BIOS to load `/boot/loader.ruxe` from `BOOT`.
6. In a follow-up, migrate the bootloader to load `/boot/kernel.ruxe` from `ROOT`.

## Validation Rules

Tooling and guest readers must reject invalid layouts deterministically:

- bad `RUXPT` magic or version;
- partition count beyond the supported maximum;
- zero-sized partitions;
- partitions outside the guest-visible media size;
- overlapping partitions;
- partition start before the reserved table area;
- unsupported filesystem magic or version inside `BOOT`/`ROOT`;
- missing `/boot/loader.ruxe` in `BOOT`;
- missing `/boot/kernel.ruxe` in `ROOT` when the bootloader reaches kernel load.

There should be one intended path. Missing or malformed structures produce explicit errors, not fallback guesses.
