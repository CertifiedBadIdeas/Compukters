# Rux16 BIOS Flash Boot Design

> Issue: [#71](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/71)

## Context

The current Rux16 boot experiments still depend on a legacy `LowImage` BIOS CPU before an explicit handoff replaces it with Rux16 execution from guest RAM. That bridge proves the instruction-memory CPU, but it is not the desired machine model.

The desired model is closer to real hardware: firmware bytes live in a BIOS flash region mapped into the physical address space, the reset `pc` points into that region, and the CPU fetches instructions through the normal bus. RAM is just another region in the same address space; BIOS does not need to be copied into RAM before it can execute.

## Goal

Add a Rux16 boot path where `ComputerMachine` starts directly from read-only BIOS flash bytes with no `LowImage` BIOS and no host-side decode/start fallback.

## Architecture

`MachineBus` will map a read-only BIOS flash device at `ComputerMachine::RUX16_BIOS_FLASH_BASE`. The device exposes byte reads for instruction fetch and rejects all writes. Rux16 fetch already goes through `MemoryBus::load_u16`, so normal instruction decode can read from the mapped flash region without a special CPU path.

`ComputerMachine` will expose a constructor that accepts raw Rux16 BIOS bytes, maps them as BIOS flash, and spawns the boot CPU as `Rux16Cpu::new(RUX16_BIOS_FLASH_BASE)`. The public handle will expose a matching `RuxComputerHandle::create_rux16_bios_flash(...)` constructor and reuse `run_rux16_until_signal()`.

## Behavior

- Empty BIOS flash input is rejected before a CPU is spawned.
- BIOS flash is readable by instruction fetch and normal guest loads.
- BIOS flash is not writable; writes return a memory fault and surface through the existing Rux16 trap behavior.
- The new constructor never creates or decodes a `LowImage`.
- Existing `LowImage` support remains available only for legacy tests and current compatibility paths.

## Out of Scope

- Removing all `LowImageVm` support.
- Loading a kernel or OS from `storage0`.
- Compiler backend migration to emit final Rux16 firmware images.
- BIOS flash update/write protocols.

## Verification

- Native Rust test: valid Rux16 BIOS flash bytes execute from the reset vector and write debug/control MMIO state.
- Native Rust test: empty BIOS flash input fails clearly.
- Native Rust test: BIOS flash rejects writes.
- Regression: existing `rux_computer` and `rux16` tests continue to pass.
