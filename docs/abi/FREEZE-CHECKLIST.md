# Rux Low ABI v1 Freeze Checklist

## Status

Status: not frozen; all required gates are satisfied for a later dedicated freeze commit.

Use this checklist before changing `docs/abi/rux-low-image-v1.md` from `pre-freeze candidate` to frozen.

## Required Gates

- [x] `docs/abi/rux-low-image-v1.md` states the final image layout, primitive encodings, function layout, register model, arithmetic semantics, control flow, validation, runtime errors, reference encoder, and stability policy.
- [x] `docs/abi/rux-low-image-v1-opcodes.json` is valid JSON and every opcode has operands, reads, writes, width, signedness, high-bit result policy, and trap conditions.
- [x] `docs/abi/rux-low-errors-v1.md` lists stable decode, encode, validation, and runtime error categories.
- [x] `docs/abi/rux-machine-profile-v1.md` clearly states whether the machine profile is frozen or still pre-freeze.
- [x] `docs/abi/PRE-FREEZE-GAPS.md` has no unresolved v1 instruction-set decisions.
- [x] `docs/abi/fixtures/*.ruxi` and matching `*.json` manifests cover golden execution, decode errors, validation errors, and runtime errors.
- [x] `native/rux-vm/examples/rux_abi_conformance.rs` passes against all fixtures.
- [x] `native/rux-vm/examples/write_abi_fixtures.rs` regenerates fixture files without changing committed bytes unless an intentional ABI update happened.
- [x] `docs/abi/cpp-frontend-notes.md` is current enough for an external C++ frontend to start implementation.
- [x] `docs/abi/CHANGELOG.md` records all pre-freeze ABI changes.

## Last Gate Verification

Verified on 2026-05-15 before freeze:

- `cargo test` from `native/rux-vm`;
- `cargo test` from `native/rux-compiler`;
- `cargo run --example rux_abi_conformance` from `native/rux-vm`;
- `cargo run --example write_abi_fixtures` from `native/rux-vm`, followed by an empty fixture diff;
- `jq . docs/abi/rux-low-image-v1-opcodes.json docs/abi/fixtures/*.json`;
- `git diff --check`.

## Verification Commands

Run these from the repository root unless a command specifies another working directory:

```bash
cargo test
```

from `native/rux-vm`.

```bash
cargo test
```

from `native/rux-compiler`.

```bash
cargo run --example rux_abi_conformance
```

from `native/rux-vm`.

```bash
jq . docs/abi/rux-low-image-v1-opcodes.json docs/abi/fixtures/*.json
```

from the repository root.

```bash
git diff --check
```

from the repository root.

## Freeze Procedure

1. Run the verification commands above.
2. Confirm every required gate is checked.
3. Change `docs/abi/rux-low-image-v1.md` status from `pre-freeze candidate` to frozen.
4. Change `docs/abi/README.md` to describe v1 as frozen.
5. Update `docs/abi/CHANGELOG.md` with the freeze date.
6. Commit the freeze as a dedicated commit.

## Post-Freeze Rules

After freeze:

- do not reuse instruction tags;
- do not change operand encoding for existing tags;
- do not change existing instruction semantics;
- do not change top-level image layout;
- do not remove v1 fixtures;
- do not remove v1 decode/run support;
- introduce breaking changes as a new numeric image format version.
