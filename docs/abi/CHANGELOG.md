# Rux ABI Changelog

## Unreleased

- Added `rux16-v1.md` and reserved `r15` as the Rux16 stack pointer. The stack
  lives in guest RAM, grows downward, and uses 4-byte slots in the first ABI
  slice.
- Added Rux16 `call rN` and `ret` instructions backed by the `r15` stack
  pointer convention.
- Added experimental `RUXE` v1 as the guest-loadable fixed-image container for
  Rux16 bootloader and kernel artifacts.
- `RUXE` now carries an ABI kind: `bootloader` or `kernel`.
- `rux compile` now emits `RUXE` for `boot` and `kernel` targets. Explicit
  `bios` continues to emit raw Rux16 instruction bytes for BIOS flash, while
  `program` is reserved for the future user-space executable ABI and is rejected.
- `rux volume put-boot` now accepts a `RUXE` boot artifact and writes the
  current storage0 boot record from its entry/load metadata and payload. Kernel
  artifacts are rejected for boot media.
- Added `rux volume put-kernel`, which writes a fixed `RUXK` kernel record and
  kernel payload for the bootloader-to-kernel chain.
- Added `rux volume init`, which creates a partitioned `RUXPT` volume with
  `BOOT` and `ROOT` partitions for the next filesystem-backed boot chain.
- Added byte-level `rux volume extract-partition` and `replace-partition`
  commands for moving partition images in and out of `RUXPT` volumes without
  filesystem-specific logic.
- Added `rux volume inspect`, which prints the `RUXVOL` header summary and
  decoded `RUXPT` partition layout.
- Added CLI workflow coverage for building a partitioned `storage0.ruxvol`
  with a RuxFS `ROOT` partition containing `/boot/kernel.ruxe`.
- Added a host-side RuxFS volume reader that models the future bootloader read
  path from `RUXPT` `ROOT` to `/boot/kernel.ruxe`.
- Added experimental `RuxFS` v1 as the extent-based filesystem contract for
  the partitioned `ROOT` partition, with empty formatting and structural
  validation in compiler tooling.
- `RuxFS` tooling now supports fixed-size directory entries, absolute-path
  directory creation, file creation, file reads, and directory listing over an
  in-memory filesystem image.
- Added `rux fs ruxfs`, keeping filesystem-specific commands separate from
  `rux volume` so additional filesystems can be introduced explicitly.
- Added `rux-storage-volume-v1.md` for the current `RUXVOL`, `RUXB`, and `RUXK`
  storage0 media layout.
- Retired the previous host-decoded executable ABI package from active
  documentation.
- Removed the obsolete decoder, runner, disassembler, conformance examples,
  and fixture tests from `native/rux-vm`.
- Moved the active runtime contract to Rux16 guest execution from mapped
  BIOS flash with optional storage0 boot media.
- Kept machine profile v2 and computer profile v1 as the active guest-visible
  hardware contracts.

## Current Active Contracts

- `rux-machine-profile-v2.md`
- `rux-computer-profile-v1.md`
- `rux16-v1.md`
- `ruxe-v1.md`
- `rux-storage-volume-v1.md`
- `ruxfs-v1.md`
