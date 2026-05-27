# Rux ABI Changelog

## Unreleased

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
