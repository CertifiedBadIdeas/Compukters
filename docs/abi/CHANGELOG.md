# Rux ABI Changelog

## Unreleased

Status: pre-freeze candidate.

- Added `RUXI` low image ABI v1 as the external compiler target.
- Added machine-readable opcode table for low image v1.
- Added reference encoder in the Rust VM crate.
- Added ABI fixtures for golden execution and negative decode/validation cases.
- Added `U64Shl` so `u64` has symmetric left and right shift instructions.
- Documented entry ABI, arithmetic semantics, runtime error categories, machine profile boundaries, and stability policy.

## Freeze Policy

Before freeze, `image_format_version = 1` may still change in place.

After freeze:

- v1 image layout, instruction tags, operand encodings, and instruction semantics are immutable;
- breaking changes require a new numeric image format version;
- old fixtures remain in the repository;
- v1 decode/run conformance tests continue to pass;
- new machine/device profiles may be versioned separately from image format versions.
