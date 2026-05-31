# K16 ABI Changelog

## Unreleased

- The LLVM-facing call ABI now supports up to four scalar `i32` return values
  in `r0..r3`, matching small Rust/LLVM multi-value returns without introducing
  hidden return pointers.
- Added K16 `mul` as canonical ALU subop `0xc`, with VM execution,
  disassembly, assembler helper, and LLVM lowering/emission support.
- `k16 disasm` now validates K16 instruction encodings against the active VM
  decode rules and fails clearly on reserved bits, unknown opcodes, and
  truncated multi-word instructions instead of printing `.word` fallback lines.
  K16 assembler helpers now cover both zero and non-zero branch predicates.
- Added `k16 runtime k16-startup`, which emits the first freestanding K16
  startup object. It defines `_start`, calls application `main`, initializes the
  program stack, writes the low byte of `main`'s `r0` return value to
  `debug::WRITE`, and leaves reserved helper symbols such as `__k16_memcpy`
  as explicit link-time requirements instead of fallback VM hooks.
- `k16 runtime k16-memory-helpers` now builds the helper object from bundled
  Rust `#![no_core]` source with `K16_RUSTC` and K16 `llc` from
  `K16_LLVM_BIN_DIR`. It defines `__k16_memcpy`, `__k16_memset`, and
  `__k16_memmove`; programs still pass this object to `k16 link`, and the
  linker still rejects missing helper symbols instead of synthesizing hidden
  bodies.
- Added `k16 link`, a static object-to-`K16E` linker for the experimental
  K16 ELF32 `ET_REL` object ABI. It emits bootloader, kernel, or program
  `K16E` images and rejects unsupported allocated sections and relocation kinds
  without falling back to raw K16 bytes or VM-side relocation.
- Added `k16-object-v1.md` as the experimental ELF32 `ET_REL` relocatable
  object ABI for LLVM-facing K16 tooling, including section names, symbol
  rules, relocation kinds, unsupported feature diagnostics, and the boundary
  that keeps ELF parsing/linking outside the VM.
- Defined the implementation-ready K16 calling convention for external
  LLVM-facing lowering, including scalar ABI slots, caller-saved registers,
  stack argument layout, frame-pointer offsets, caller cleanup, and the current
  Rux compiler boundary that still rejects helper calls needing stack-passed
  arguments.
- Replaced the experimental K16 integer ALU encoding with the canonical
  two-word `alu_rrr` format `0x2a0s 0x00bc`, covering `add`, `sub`, bitwise
  ops, shifts, equality, inequality, unsigned less-than, and signed less-than.
  Added `load16` and `store16` to the memory width encoding.
- Documented the initial LLVM-facing K16 target model, including the
  `k16-unknown-kraftos` shape, register classification, caller-saved model,
  stack-passed argument boundary, required integer ISA families, and the
  object-to-`K16E` executable pipeline. The boundary explicitly keeps LLVM
  backend/toolchain concerns outside the VM implementation.
- Added experimental `K16SNAP` v1 as the host-side `ComputerMachine` snapshot
  container. It records a versioned header, full RAM bytes, and K16 CPU
  continuation records; `control`, `debug`, `display0`, and serial input
  device state plus `storage0` controller registers are now restored.
- Added `k16-cpu-v1.md` and reserved `r15` as the K16 stack pointer. The stack
  lives in guest RAM, grows downward, and uses 4-byte slots in the first ABI
  slice.
- Added K16 `call rN` and `ret` instructions backed by the `r15` stack
  pointer convention.
- The K16 compiler now saves and restores live local registers around
  compiler-generated helper calls that use `call`/`ret`.
- K16 helper parameters now enter through `r1..r3` and are copied into stable
  callee-local storage before helper body lowering, so scratch register use does
  not clobber parameters.
- Added experimental `K16E` v1 as the guest-loadable fixed-image container for
  K16 bootloader and kernel artifacts.
- `K16E` now carries an ABI kind: `bootloader` or `kernel`.
- `rux compile` now emits `K16E` for `boot`, `kernel`, and `program` targets.
  Explicit `bios` continues to emit raw K16 instruction bytes for BIOS flash.
- `K16E` now carries ABI kind `program`, and the program target emits a
  filesystem-backed user-space executable profile linked at `0x8000`.
- The VM runtime now has a read-only `storage0 media -> K16PT ROOT -> K16FS`
  reader and validates already-read `K16E` bytes as `program` images for the
  future OS exec path.
- The runtime `K16ComputerHandle` can transfer an already-read `K16E` program
  into guest RAM and start K16 execution at the executable `entry_pc`.
- Added a guest-side kernel init loader that reads `/bin/init.kx` from
  `storage0` `ROOT`/K16FS, validates `K16E` ABI kind `program`, loads the
  payload, and enters the program `entry_pc`.
- The bundled BIOS now loads `/boot/loader.kb` from the `BOOT` K16FS
  partition in the partitioned boot path, validates `K16E` ABI kind
  `bootloader`, and enters the bootloader `entry_pc`.
- `k16 volume put-boot` now accepts a `K16E` boot artifact and writes the
  bootloader file to `BOOT`/K16FS `/boot/loader.kb` for partitioned volumes.
  Kernel artifacts are rejected for boot media.
- Added `k16 volume put-kernel`, which writes the kernel `K16E` file to
  `ROOT`/K16FS `/boot/kernel.kx` for the bootloader-to-kernel chain.
- Retired the legacy fixed `K16B` raw boot path from active BIOS and
  `put-boot` behavior. Partitioned `K16PT` plus K16FS is now the only supported
  boot path.
- Added `k16 volume init`, which creates a partitioned `K16PT` volume with
  `BOOT` and `ROOT` partitions for the next filesystem-backed boot chain.
- Added byte-level `k16 volume extract-partition` and `replace-partition`
  commands for moving partition images in and out of `K16PT` volumes without
  filesystem-specific logic.
- Added `k16 volume inspect`, which prints the `K16VOL` header summary and
  decoded `K16PT` partition layout.
- Added CLI workflow coverage for building a partitioned `storage0.kv`
  with a K16FS `ROOT` partition containing `/boot/kernel.kx`.
- Added a host-side K16FS volume reader that models the future bootloader read
  path from `K16PT` `ROOT` to `/boot/kernel.kx`.
- Added experimental `K16FS` v1 as the extent-based filesystem contract for
  the partitioned `ROOT` partition, with empty formatting and structural
  validation in compiler tooling.
- `K16FS` tooling now supports fixed-size directory entries, absolute-path
  directory creation, file creation, file reads, and directory listing over an
  in-memory filesystem image.
- Added `k16 fs kfs`, keeping filesystem-specific commands separate from
  `k16 volume` so additional filesystems can be introduced explicitly.
- Added `k16-storage-volume-v1.md` for the earlier fixed-record `K16VOL`,
  `K16B`, and `K16K` storage0 media layout.
- Retired the previous host-decoded executable ABI package from active
  documentation.
- Removed the obsolete decoder, runner, disassembler, conformance examples,
  and fixture tests from `native/rux-vm`.
- Moved the active runtime contract to K16 guest execution from mapped
  BIOS flash with optional storage0 boot media.
- Kept machine profile v2 and computer profile v1 as the active guest-visible
  hardware contracts.

## Current Active Contracts

- `k16-machine-profile-v2.md`
- `k16-computer-profile-v1.md`
- `k16-cpu-v1.md`
- `k16-object-v1.md`
- `k16e-v1.md`
- `k16-storage-volume-v1.md`
- `k16fs-v1.md`
- `k16-computer-snapshot-v1.md`
