# ABI Specifications

This directory contains the active guest-visible hardware contracts for the
Rux computer runtime.

Design notes and implementation plans may explain how older interfaces were
chosen, but the files here are the current references for firmware and tooling.

Current specifications:

- `rux-machine-profile-v2.md`: machine boot info, RAM layout, MMIO allocation,
  and hardware table contract.
- `rux-computer-profile-v1.md`: concrete hardware IDs and MMIO register layout
  for the current `ComputerMachine`.
- `CHANGELOG.md`: active ABI history.

The supported execution model is Rux16 guest instruction-memory execution from
BIOS flash with optional storage0 boot media.
