# VM Microbenchmarks Design

> Issue: [#36](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/36)

## Context

Rux16 executes instruction words from guest memory and is now the active runtime
path. We need a small, repeatable way to measure current interpreter hot paths
before adding decode caches, richer opcodes, device-heavy workloads, or
kernel/user ABI layers.

## Goal

Add a dependency-free native Rust benchmark harness that measures current Rux16
execution on representative compute, memory, and MMIO micro-workloads.

## Design

The benchmark harness lives in `native/rux-vm` and uses `std::time::Instant`
rather than Criterion. This avoids adding a network-fetched benchmark dependency
while the VM shape is still moving. The harness reports best-of-N sample timings
and stable checksums; it is meant for local before/after comparisons, not
statistically rigorous publication.

The reusable workload builders live in a small Rust module so tests can assert
stable checksums before timing is trusted. A CLI example prints TSV rows for all
workloads.

Initial workloads:

- `compute-loop`: count from zero to `iterations` using constants, equality, branch/jump, and add.
- `memory-loop`: increment a RAM cell `iterations` times using load32/store32 plus the same loop shape.
- `mmio-loop`: increment through a mapped MMIO register using store32/load32
  plus the same loop shape.

Out of scope for this slice:

- Criterion integration.
- decode-cache optimization.
- pass/fail performance thresholds in CI.
- storing a baseline archive before benchmark output becomes part of regression
  review.

## Verification

- Tests assert that Rux16 benchmark workloads produce stable expected checksums.
- `cargo run --release --manifest-path native/rux-vm/Cargo.toml --example vm_microbenchmarks -- 10000 5` prints TSV benchmark rows.
- Full native `rux-vm` tests remain green.
