# K16 Benchmark Current Snapshot Design

> Issue: [#163](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/163)

## Context

K16 VM microbenchmarks are now useful for local before/after optimization work,
and the current CLI output is readable enough for day-to-day terminal use. The
missing piece is a repo-tracked current snapshot so benchmark results can be
reviewed through normal Git history without maintaining a separate history file.

## Decision

Keep one plaintext file at `docs/benchmarks/k16-vm-current.txt`. It is rewritten
before commits by a repository pre-commit hook and contains:

- recording metadata;
- the exact benchmark command;
- host and toolchain information;
- the same aligned table shape printed by `vm_microbenchmarks`.

Normal benchmark runs keep printing directly to the terminal. The recording
script captures that same output and wraps it with metadata.

## Git History Model

There is no append-only `history.tsv`. Git already stores the history:

- `git diff docs/benchmarks/k16-vm-current.txt` shows the benchmark delta for the
  current change;
- `git show <commit>:docs/benchmarks/k16-vm-current.txt` shows the snapshot from
  a specific commit.

The snapshot does not embed its own commit hash. A file cannot reliably contain
the hash of the same commit without amending or creating a self-referential
workflow. The containing Git commit is the source of truth.

## Hook Semantics

The pre-commit hook refreshes the snapshot and stages it. To avoid recording
benchmarks for a dirty working tree that does not match the staged commit, the
hook refuses to run when unrelated unstaged changes are present. The only
allowed unstaged path is `docs/benchmarks/k16-vm-current.txt`, because the hook
itself rewrites that file.

## Scope

- Add `scripts/record-k16-vm-benchmark-current.sh`.
- Add `scripts/git-hooks/pre-commit`.
- Add `scripts/install-git-hooks.sh`.
- Add shell tests for recording and hook behavior.
- Add the initial current snapshot.
- Update `docs/PROFILING.md`.

## Out of Scope

- CI performance gates.
- A TSV history file.
- Framebuffer benchmark comparisons.
- Criterion or external benchmark frameworks.
- Portable performance claims.

## Verification

- `scripts/tests/k16-benchmark-current.sh`
- `scripts/record-k16-vm-benchmark-current.sh 1000 3`
- `cargo test --test vm_microbenchmarks` from `rust/host/k16-vm`
- `git diff --check`
