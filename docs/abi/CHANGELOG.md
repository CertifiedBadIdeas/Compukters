# Rux ABI Changelog

## Unreleased

- Added experimental `RUXE` v1 as the guest-loadable executable container for
  Rux16 program artifacts.
- `rux compile` now emits `RUXE` for the default `program` target. Explicit
  `bios` and `boot` targets continue to emit raw Rux16 instruction bytes for
  BIOS flash and current storage0 boot records.
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
- `ruxe-v1.md`
