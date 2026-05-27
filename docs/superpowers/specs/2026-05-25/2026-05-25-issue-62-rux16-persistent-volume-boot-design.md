# Rux16 Persistent Volume Boot Design

> Issue: [#62](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/62)

## Context

Rux16 BIOS can already execute from BIOS flash, read a raw boot header from `storage0`, load a one-block stage2 image into RAM, and jump to the RAM entry point. That path is currently tested with in-memory `storage0` media. The remaining native runtime gap is proving the same BIOS-first path against a persistent `.ruxvol` file so bootable media can exist outside the mod jar.

## Goal

Allow a Rux16 BIOS flash computer to attach `storage0` from a `.ruxvol` path and boot stage2 through the same guest-executed storage MMIO path.

## Architecture

This slice adds a Rux16 BIOS flash handle constructor that accepts a `storage0` volume path. It should reuse `ComputerMachineProfile::computer_v1_with_storage0_path`, then start the machine through `ComputerMachine::from_rux16_bios_flash_with_profile`. The host still only wires devices, maps BIOS flash, and starts the initial Rux16 CPU.

The boot contract does not change: block 0 contains `RUXB`, `entry_pc`, `load_addr`, `block_count`, and `start_lba`; block 1 contains the stage2 Rux16 bytes. BIOS reads both blocks through guest-visible `storage0` MMIO and jumps to the RAM entry. There is no `LowImage` boot, host-side decode, or fallback to bundled firmware in this path.

## Out of Scope

- Changing the raw boot header format.
- Multi-block image validation.
- Prepared-media CLI or installer tooling.
- In-game item/block runtime wiring.
- User-facing BIOS text rendering.

## Verification

- Native Rust test: a temp `.ruxvol` containing block 0 header plus block 1 stage2 boots from BIOS flash and outputs `S2`.
- Regression: existing in-memory stage2 and corrupt header tests still pass.
