# K16 VM Code Flow Docs Design

> Issue: [#164](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/164)

## Context

The current K16 VM is implemented and tested, but its Rust code is hard to read
without a map. `k16.rs` mixes decode, CPU execution, traps, CSR handling, and
call stack semantics. `computer/machine.rs` owns full-computer orchestration,
device wiring, CPU lifecycle, snapshots, and host accessors. Older docs also
contain stale legacy VM wording.

## Decision

Add a reader-facing code-flow document instead of starting with a broad module
split. The document explains current ownership and data flow through the actual
Rust files. Then update existing architecture/profiling docs to link to it and
remove stale legacy wording where it can mislead readers.

## Scope

- Add `docs/k16-vm-code-flow.md`.
- Link the code-flow document from `docs/ARCHITECTURE.md`.
- Refresh stale wording in `docs/MACHINE.md` and `docs/PROFILING.md`.
- Add short in-code comments in `k16.rs` and `computer/machine.rs` where they
  mark phase boundaries.

## Out of Scope

- Behavioral changes.
- Rust module splitting.
- Instruction encoding changes.
- ABI/device contract changes.
- Exhaustive rewrite of historical docs.

## Verification

- `cargo fmt -- --check` in `rust/host/k16-vm`
- `cargo test` in `rust/host/k16-vm`
- `git diff --check`
