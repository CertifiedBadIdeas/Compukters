# K16 Native Rust Benchmark Comparison Design

> Issue: [#160](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/160)

## Context

The current K16 VM microbenchmark harness measures three VM-only loops:
`compute-loop`, `memory-loop`, and `mmio-loop`. Those numbers are useful for
before/after interpreter changes, but they do not show how much time is spent on
the benchmark algorithm itself versus K16 fetch, decode, dispatch, memory, and
call mechanics.

This slice extends the same dependency-free harness with Native Rust comparison
rows. The goal is local diagnostic context, not a portable performance claim.

## Goals

- Keep the benchmark CLI in `rust/host/k16-vm/examples/vm_microbenchmarks.rs`.
- Add a Native Rust runner beside the existing K16 runner.
- Print side-by-side TSV rows for every workload.
- Add VM-core workloads that cover branch-heavy execution and call/return stack
  behavior.
- Preserve checksum tests so benchmark timings are trusted only when equivalent
  work produces equivalent results.

## Non-Goals

- Do not add Criterion or another benchmark dependency.
- Do not add CI performance gates.
- Do not add framebuffer, display-device, storage, or device-heavy comparison
  workloads in this slice.
- Do not implement K16 optimizations such as decode caches, superinstructions,
  JIT, or unsafe memory access.
- Do not treat the resulting numbers as portable across machines.

## Workloads

The existing workloads stay unchanged:

- `compute-loop`: simple equality, add, and branch loop.
- `memory-loop`: RAM load/store loop.
- `mmio-loop`: mapped register load/store loop.

New workloads:

- `branch-mix`: a branch-heavy loop with two conditional paths. It exercises
  repeated comparisons, conditional branches, and arithmetic while keeping the
  checksum deterministic.
- `call-loop`: a loop that calls a helper function once per iteration. It
  exercises K16 `call`, `ret`, stack pointer setup, and return-to-loop behavior.

The Native Rust implementation for each workload mirrors the observable
checksum, not the exact K16 instruction sequence. This makes the comparison a
baseline for VM overhead versus direct native execution of the same algorithm.

## CLI Output

The existing TSV shape stays stable:

```text
workload	vm	iterations	checksum	best_nanos	nanos_per_iteration
```

For every workload, the CLI prints:

- one `k16` row;
- one `native-rust` row.

## Testing

Tests cover:

- every workload name appears in CLI order;
- every K16 workload returns the expected checksum for small inputs;
- every Native Rust workload returns the same checksum as K16;
- source no longer exposes retired LowImage/Rux comparison paths.

## Interpretation

The comparison answers: "For this algorithm, how much slower is K16 execution
than optimized host Rust on this machine?" It does not isolate every VM
subsystem. A high ratio points to a useful optimization target, but any concrete
optimization must still be justified by a focused before/after benchmark run.
