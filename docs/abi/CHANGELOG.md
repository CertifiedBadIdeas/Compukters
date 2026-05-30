# Rux ABI Changelog

## Unreleased

- `rux disasm` now validates Rux16 instruction encodings against the active VM
  decode rules and fails clearly on reserved bits, unknown opcodes, and
  truncated multi-word instructions instead of printing `.word` fallback lines.
  Rux16 assembler helpers now cover both zero and non-zero branch predicates.
- Added `rux runtime rux16-startup`, which emits the first freestanding Rux16
  startup object. It defines `_start`, calls application `main`, initializes the
  program stack, writes the low byte of `main`'s `r0` return value to
  `debug::WRITE`, and leaves reserved helper symbols such as `__rux16_memcpy`
  as explicit link-time requirements instead of fallback VM hooks.
- `rux runtime rux16-memory-helpers` now builds the helper object from bundled
  Rust `#![no_core]` source with `RUX16_RUSTC` and Rux16 `llc` from
  `RUX16_LLVM_BIN_DIR`. It defines `__rux16_memcpy`, `__rux16_memset`, and
  `__rux16_memmove`; programs still pass this object to `rux link`, and the
  linker still rejects missing helper symbols instead of synthesizing hidden
  bodies.
- Added `rux link`, a static object-to-`RUXE` linker for the experimental
  Rux16 ELF32 `ET_REL` object ABI. It emits bootloader, kernel, or program
  `RUXE` images and rejects unsupported allocated sections and relocation kinds
  without falling back to raw Rux16 bytes or VM-side relocation.
- Added `rux16-object-v1.md` as the experimental ELF32 `ET_REL` relocatable
  object ABI for LLVM-facing Rux16 tooling, including section names, symbol
  rules, relocation kinds, unsupported feature diagnostics, and the boundary
  that keeps ELF parsing/linking outside the VM.
- Defined the implementation-ready Rux16 calling convention for external
  LLVM-facing lowering, including scalar ABI slots, caller-saved registers,
  stack argument layout, frame-pointer offsets, caller cleanup, and the current
  Rux compiler boundary that still rejects helper calls needing stack-passed
  arguments.
- Replaced the experimental Rux16 integer ALU encoding with the canonical
  two-word `alu_rrr` format `0x2a0s 0x00bc`, covering `add`, `sub`, bitwise
  ops, shifts, equality, inequality, unsigned less-than, and signed less-than.
  Added `load16` and `store16` to the memory width encoding.
- Documented the initial LLVM-facing Rux16 target model, including the
  `rux16-unknown-ruxos` shape, register classification, caller-saved model,
  stack-passed argument boundary, required integer ISA families, and the
  object-to-`RUXE` executable pipeline. The boundary explicitly keeps LLVM
  backend/toolchain concerns outside the VM implementation.
- Added experimental `RUXSNAP` v1 as the host-side `ComputerMachine` snapshot
  container. It records a versioned header, full RAM bytes, and Rux16 CPU
  continuation records; `control`, `debug`, `display0`, and serial input
  device state plus `storage0` controller registers are now restored.
- Added `rux16-v1.md` and reserved `r15` as the Rux16 stack pointer. The stack
  lives in guest RAM, grows downward, and uses 4-byte slots in the first ABI
  slice.
- Added Rux16 `call rN` and `ret` instructions backed by the `r15` stack
  pointer convention.
- The Rux16 compiler now saves and restores live local registers around
  compiler-generated helper calls that use `call`/`ret`.
- Rux16 helper parameters now enter through `r1..r3` and are copied into stable
  callee-local storage before helper body lowering, so scratch register use does
  not clobber parameters.
- Added experimental `RUXE` v1 as the guest-loadable fixed-image container for
  Rux16 bootloader and kernel artifacts.
- `RUXE` now carries an ABI kind: `bootloader` or `kernel`.
- `rux compile` now emits `RUXE` for `boot`, `kernel`, and `program` targets.
  Explicit `bios` continues to emit raw Rux16 instruction bytes for BIOS flash.
- `RUXE` now carries ABI kind `program`, and the program target emits a
  filesystem-backed user-space executable profile linked at `0x8000`.
- The VM runtime now has a read-only `storage0 media -> RUXPT ROOT -> RuxFS`
  reader and validates already-read `RUXE` bytes as `program` images for the
  future OS exec path.
- The runtime `RuxComputerHandle` can transfer an already-read `RUXE` program
  into guest RAM and start Rux16 execution at the executable `entry_pc`.
- Added a guest-side kernel init loader that reads `/bin/init.ruxe` from
  `storage0` `ROOT`/RuxFS, validates `RUXE` ABI kind `program`, loads the
  payload, and enters the program `entry_pc`.
- The bundled BIOS now loads `/boot/loader.ruxe` from the `BOOT` RuxFS
  partition in the partitioned boot path, validates `RUXE` ABI kind
  `bootloader`, and enters the bootloader `entry_pc`.
- `rux volume put-boot` now accepts a `RUXE` boot artifact and writes the
  bootloader file to `BOOT`/RuxFS `/boot/loader.ruxe` for partitioned volumes.
  Kernel artifacts are rejected for boot media.
- Added `rux volume put-kernel`, which writes the kernel `RUXE` file to
  `ROOT`/RuxFS `/boot/kernel.ruxe` for the bootloader-to-kernel chain.
- Retired the legacy fixed `RUXB` raw boot path from active BIOS and
  `put-boot` behavior. Partitioned `RUXPT` plus RuxFS is now the only supported
  boot path.
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
- Added `rux-storage-volume-v1.md` for the earlier fixed-record `RUXVOL`,
  `RUXB`, and `RUXK` storage0 media layout.
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
- `rux16-object-v1.md`
- `ruxe-v1.md`
- `rux-storage-volume-v1.md`
- `ruxfs-v1.md`
- `rux-computer-snapshot-v1.md`
