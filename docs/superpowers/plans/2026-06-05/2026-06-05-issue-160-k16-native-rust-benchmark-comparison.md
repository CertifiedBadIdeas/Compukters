# K16 Native Rust Benchmark Comparison Implementation Plan

> Issue: [#160](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/160)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the K16 VM microbenchmark harness with Native Rust comparison rows and two VM-core workloads.

**Architecture:** Keep the benchmark harness in `rust/host/k16-vm/src/vm_microbenchmarks.rs`. Add a Native Rust runner with the same public workload enum and checksum contract as the K16 runner, then update the example CLI to print both `k16` and `native-rust` rows for each workload.

**Tech Stack:** Rust 2021, existing `K16Cpu`, existing `MachineBus`, dependency-free `std::time::Instant` timing harness, Rust integration tests.

---

## File Structure

- Modify `rust/host/k16-vm/src/vm_microbenchmarks.rs`: add `BranchMix`, `CallLoop`, Native Rust runner, K16 word encoders for new instructions, and stack setup for call workloads.
- Modify `rust/host/k16-vm/examples/vm_microbenchmarks.rs`: import and print `run_native_rust_workload` rows.
- Modify `rust/host/k16-vm/tests/vm_microbenchmarks.rs`: add RED tests for workload list, K16 checksums, and Native Rust parity.
- Modify `docs/PROFILING.md`: document that the K16 VM microbenchmark CLI now prints side-by-side Native Rust comparison rows.

## Task 1: RED Tests

**Files:**
- Modify: `rust/host/k16-vm/tests/vm_microbenchmarks.rs`

- [ ] **Step 1: Add failing workload list and parity tests**

Add tests that expect five workloads and Native Rust parity:

```rust
use k16_vm::vm_microbenchmarks::{
    run_k16_workload, run_native_rust_workload, VmBenchmarkWorkload,
};

#[test]
fn branch_mix_runs_on_k16() {
    let iterations = 7;

    assert_eq!(
        run_k16_workload(VmBenchmarkWorkload::BranchMix, iterations).unwrap(),
        run_native_rust_workload(VmBenchmarkWorkload::BranchMix, iterations).unwrap(),
    );
}

#[test]
fn call_loop_runs_on_k16() {
    let iterations = 7;

    assert_eq!(
        run_k16_workload(VmBenchmarkWorkload::CallLoop, iterations).unwrap(),
        run_native_rust_workload(VmBenchmarkWorkload::CallLoop, iterations).unwrap(),
    );
}

#[test]
fn native_rust_workloads_match_k16_checksums() {
    let iterations = 11;

    for workload in VmBenchmarkWorkload::all() {
        assert_eq!(
            run_native_rust_workload(*workload, iterations).unwrap(),
            run_k16_workload(*workload, iterations).unwrap(),
            "{} checksum mismatch",
            workload.name(),
        );
    }
}
```

Update the workload list assertion to:

```rust
assert_eq!(
    names,
    vec![
        "compute-loop",
        "memory-loop",
        "mmio-loop",
        "branch-mix",
        "call-loop",
    ],
);
```

- [ ] **Step 2: Run tests to verify RED**

Run:

```bash
cargo test --test vm_microbenchmarks
```

Expected: compile failure because `run_native_rust_workload`, `BranchMix`, and
`CallLoop` do not exist.

## Task 2: Implement Native Rust Runner And Workload Enum

**Files:**
- Modify: `rust/host/k16-vm/src/vm_microbenchmarks.rs`

- [ ] **Step 1: Add enum variants and Native Rust runner**

Add `BranchMix` and `CallLoop` to `VmBenchmarkWorkload`, `name()`, `all()`, and
`FromStr`.

Add:

```rust
pub fn run_native_rust_workload(
    workload: VmBenchmarkWorkload,
    iterations: u32,
) -> Result<u32, String> {
    Ok(match workload {
        VmBenchmarkWorkload::ComputeLoop => iterations,
        VmBenchmarkWorkload::MemoryLoop => iterations,
        VmBenchmarkWorkload::MmioLoop => iterations,
        VmBenchmarkWorkload::BranchMix => native_branch_mix(iterations),
        VmBenchmarkWorkload::CallLoop => native_call_loop(iterations),
    })
}
```

Implement `native_branch_mix` so it loops from `0` to `iterations - 1`,
alternating between adding `3` for even counters and `1` for odd counters.

Implement `native_call_loop` so it calls a small helper once per iteration and
returns the accumulated helper result.

- [ ] **Step 2: Run tests**

Run:

```bash
cargo test --test vm_microbenchmarks
```

Expected: tests still fail because K16 variants are not implemented yet.

## Task 3: Implement K16 BranchMix And CallLoop

**Files:**
- Modify: `rust/host/k16-vm/src/vm_microbenchmarks.rs`

- [ ] **Step 1: Add K16 instruction helpers**

Add helpers for the new K16 code:

```rust
fn ne(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x9, lhs, rhs)
}

fn and(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x2, lhs, rhs)
}

fn call(target: u8) -> u16 {
    0x8000 | (u16::from(target) << 8)
}

fn ret() -> u16 {
    0x9000
}
```

- [ ] **Step 2: Add K16 `BranchMix` program**

Use registers:

- `r0`: iterations
- `r1`: counter
- `r2`: one
- `r3`: condition
- `r4`: checksum
- `r5`: loop target address
- `r6`: three
- `r7`: parity scratch

Loop until `counter == iterations`. Each iteration adds `3` when
`counter & 1 != 0`, otherwise adds `1`, then increments counter.

- [ ] **Step 3: Add K16 `CallLoop` program**

Use `r15` as stack pointer. Initialize it to `MEMORY_SIZE`. Use a helper
function that adds `1` to accumulator register `r4` and returns. The loop calls
the helper once per iteration and halts with `r4`.

- [ ] **Step 4: Update step budgets**

Add conservative `k16_max_steps` budgets:

```rust
VmBenchmarkWorkload::BranchMix => u64::from(iterations) * 12 + 32,
VmBenchmarkWorkload::CallLoop => u64::from(iterations) * 10 + 32,
```

- [ ] **Step 5: Run tests**

Run:

```bash
cargo test --test vm_microbenchmarks
```

Expected: all `vm_microbenchmarks` tests pass.

## Task 4: Update CLI And Docs

**Files:**
- Modify: `rust/host/k16-vm/examples/vm_microbenchmarks.rs`
- Modify: `docs/PROFILING.md`

- [ ] **Step 1: Print Native Rust rows**

Change the import to include `run_native_rust_workload`.

Inside the workload loop, print both rows:

```rust
print_sample(*workload, "k16", iterations, samples, run_k16_workload);
print_sample(
    *workload,
    "native-rust",
    iterations,
    samples,
    run_native_rust_workload,
);
```

- [ ] **Step 2: Update docs**

In `docs/PROFILING.md`, update the Kraft16 VM microbenchmark section to state
that each workload prints a K16 row and a Native Rust row.

- [ ] **Step 3: Run example**

Run:

```bash
cargo run --release --example vm_microbenchmarks -- 1000 3
```

Expected: TSV output includes `k16` and `native-rust` rows for all five
workloads.

## Task 5: Verification And Commit

**Files:**
- Modified files from Tasks 1-4.

- [ ] **Step 1: Format Rust**

Run:

```bash
cargo fmt -- --check
```

Expected: exit 0.

- [ ] **Step 2: Run focused tests**

Run:

```bash
cargo test --test vm_microbenchmarks
```

Expected: all focused tests pass.

- [ ] **Step 3: Run full crate tests**

Run:

```bash
cargo test
```

Expected: all `k16-vm` crate tests pass.

- [ ] **Step 4: Check whitespace**

Run from repo root:

```bash
git diff --check
```

Expected: exit 0.

- [ ] **Step 5: Commit**

Run from repo root:

```bash
git add docs/PROFILING.md docs/superpowers/specs/2026-06-05/2026-06-05-issue-160-k16-native-rust-benchmark-comparison-design.md docs/superpowers/plans/2026-06-05/2026-06-05-issue-160-k16-native-rust-benchmark-comparison.md rust/host/k16-vm/src/vm_microbenchmarks.rs rust/host/k16-vm/examples/vm_microbenchmarks.rs rust/host/k16-vm/tests/vm_microbenchmarks.rs
git commit -m "bench(vm): compare K16 benchmarks with native Rust"
```

## Self-Review

- Spec coverage: Native Rust comparison, branch-heavy workload, call/return
  workload, CLI output, checksum tests, docs, and non-goals are covered.
- Placeholder scan: no placeholder tasks remain.
- Type consistency: plan uses existing `VmBenchmarkWorkload`,
  `run_k16_workload`, and the new `run_native_rust_workload` consistently.
- Execution consistency: commands run from `rust/host/k16-vm` except
  `git diff --check` and commit staging, which run from repo root.
