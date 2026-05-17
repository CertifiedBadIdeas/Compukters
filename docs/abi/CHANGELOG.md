# Rux ABI Changelog

## Unreleased

- Added draft Rux machine profile v2. This does not change frozen `RUXI` image ABI v1; it defines a new machine profile with boot info, configurable page size, optional hardware, and hardware table discovery.

## 2026-05-15 - RUXI v1 Frozen

Status: frozen.

- Added `RUXI` low image ABI v1 as the external compiler target.
- Added machine-readable opcode table for low image v1.
- Added reference encoder in the Rust VM crate.
- Added ABI fixtures for golden execution and negative decode/validation cases.
- Added runtime-error ABI fixtures for divide by zero, memory faults, and call/return mismatches.
- Added `rux_abi_conformance` reference runner for golden, negative, and runtime-error fixtures.
- Added `U64Shl` so `u64` has symmetric left and right shift instructions.
- Expanded opcode JSON with machine-readable reads, writes, width, signedness, high-bit result policy, and trap conditions.
- Documented canonical unsigned aliases for wrapping add/sub/mul without adding duplicate serialized opcodes.
- Added advisory C++ frontend lowering notes.
- Added pre-freeze gap review and freeze checklist documents.
- Documented entry ABI, call/return ABI, arithmetic semantics, runtime error categories, machine profile boundaries, and stability policy.

## Freeze Policy

For frozen ABI versions:

- v1 image layout, instruction tags, operand encodings, and instruction semantics are immutable;
- breaking changes require a new numeric image format version;
- old fixtures remain in the repository;
- v1 decode/run conformance tests continue to pass;
- new machine/device profiles may be versioned separately from image format versions.
