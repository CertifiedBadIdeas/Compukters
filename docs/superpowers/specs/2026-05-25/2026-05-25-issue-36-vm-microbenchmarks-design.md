# VM Microbenchmarks Design

> Issue: [#36](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/36)

## Context

Rux16 now executes instruction words from guest memory, while `LowImageVm` still executes a host-decoded program representation. We need a small, repeatable way to compare their current interpreter hot paths before adding decode caches, richer opcodes, or boot/exec wiring.

## Goal

Add a dependency-free native Rust benchmark harness that compares current Rux16 and LowImage execution on equivalent micro-workloads.

## Design

The first benchmark slice lives in `native/rux-vm` and uses `std::time::Instant` rather than Criterion. This avoids adding a network-fetched benchmark dependency while the VM shape is still moving. The harness reports best-of-N sample timings and stable checksums; it is meant for local before/after comparisons, not statistically rigorous publication.

The reusable workload builders live in a small Rust module so tests can assert semantic equivalence before timing is trusted. A CLI example prints TSV rows for both VMs.

Initial workloads:

- `compute-loop`: count from zero to `iterations` using constants, equality, branch/jump, and add.
- `memory-loop`: increment a RAM cell `iterations` times using load32/store32 plus the same loop shape.

Out of scope for this slice:

- Criterion integration.
- MMIO timing.
- decode-cache optimization.
- pass/fail performance thresholds in CI.

## Verification

- Tests assert that LowImage and Rux16 produce the same checksums for each workload.
- `cargo run --release --manifest-path native/rux-vm/Cargo.toml --example vm_microbenchmarks -- 10000 5` prints TSV benchmark rows.
- Full native `rux-vm` tests remain green.
