# ABI Specifications

This directory contains the active guest-visible hardware contracts for the
K16 computer runtime.

Design notes and implementation plans may explain how older interfaces were
chosen, but the files here are the current references for firmware and tooling.

Current specifications:

- `k16-abi-conformance-matrix.md`: maintainer checklist mapping supported
  K16 ABI patterns to lit, Rust smoke, and documentation tests.
- `k16-object-v1.md`: experimental ELF32 relocatable object ABI for external
  K16 toolchains before final `K16E` linking.
- `k16e-v1.md`: experimental guest-loadable executable container for K16
  bootloader, kernel, and user-space program images.
- `k16-cpu-v1.md`: experimental K16 CPU ABI, including the register file and
  stack pointer convention.
- `k16-virtual-memory-v1.md`: planned K16 virtual-memory and process
  address-space contract for a later CPU/MMU ABI slice.
- `k16-storage-volume-v1.md`: current storage0 volume and partitioned `K16PT`
  layout.
- `k16fs-v1.md`: extent-based filesystem targeted for the `ROOT` partition in
  the partitioned storage0 layout.
- `k16-machine-profile-v2.md`: machine boot info, RAM layout, MMIO allocation,
  and hardware table contract.
- `k16-computer-profile-v1.md`: concrete hardware IDs and MMIO register layout
  for the current `ComputerMachine`.
- `k16-computer-snapshot-v1.md`: experimental host-side `ComputerMachine`
  snapshot container.
- `CHANGELOG.md`: active ABI history.

The supported execution model is K16 guest instruction-memory execution from
BIOS flash with optional storage0 boot media.
