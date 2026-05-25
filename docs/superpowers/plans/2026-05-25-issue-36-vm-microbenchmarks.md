# VM Microbenchmarks Implementation Plan

> Issue: [#36](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/36)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dependency-free native Rust harness that compares Rux16 and LowImage on equivalent compute and memory micro-workloads.

**Architecture:** Put benchmark workload construction and execution in a small library module so tests can verify checksums. Add an example CLI that runs both VMs, samples timings with `Instant`, and prints TSV. Keep this as a local baseline tool; do not add Criterion or CI thresholds in this slice.

**Tech Stack:** Rust 2021, `std::time::Instant`, existing `LowImageVm`, existing `Rux16Cpu`, integration tests in `native/rux-vm/tests`, example binary in `native/rux-vm/examples`.

---

## File Structure

- Create `docs/superpowers/specs/2026-05-25-issue-36-vm-microbenchmarks-design.md`: accepted benchmark design.
- Create `docs/superpowers/plans/2026-05-25-issue-36-vm-microbenchmarks.md`: this plan.
- Modify `native/rux-vm/src/lib.rs`: export the benchmark support module.
- Create `native/rux-vm/src/vm_microbenchmarks.rs`: workload builders and VM runners.
- Create `native/rux-vm/examples/vm_microbenchmarks.rs`: CLI timing harness.
- Create `native/rux-vm/tests/vm_microbenchmarks.rs`: checksum equivalence tests.

## Task 1: Docs

- [ ] **Step 1: Save spec and plan**

Use `apply_patch` to add the design and this implementation plan.

- [ ] **Step 2: Verify docs whitespace**

Run: `git diff --check`

Expected: exit 0.

- [ ] **Step 3: Commit docs**

```bash
git add docs/superpowers/specs/2026-05-25-issue-36-vm-microbenchmarks-design.md docs/superpowers/plans/2026-05-25-issue-36-vm-microbenchmarks.md
git commit -m "docs(vm): plan VM microbenchmarks"
```

## Task 2: Failing Tests

- [ ] **Step 1: Add checksum tests**

Create `native/rux-vm/tests/vm_microbenchmarks.rs` with tests that call:

```rust
use rux_vm::vm_microbenchmarks::{run_low_image_workload, run_rux16_workload, VmBenchmarkWorkload};

#[test]
fn compute_loop_matches_between_low_image_and_rux16() {
    let iterations = 7;

    assert_eq!(
        run_low_image_workload(VmBenchmarkWorkload::ComputeLoop, iterations).unwrap(),
        run_rux16_workload(VmBenchmarkWorkload::ComputeLoop, iterations).unwrap(),
    );
}

#[test]
fn memory_loop_matches_between_low_image_and_rux16() {
    let iterations = 7;

    assert_eq!(
        run_low_image_workload(VmBenchmarkWorkload::MemoryLoop, iterations).unwrap(),
        run_rux16_workload(VmBenchmarkWorkload::MemoryLoop, iterations).unwrap(),
    );
}
```

- [ ] **Step 2: Run tests to verify RED**

Run: `cargo test --test vm_microbenchmarks --manifest-path native/rux-vm/Cargo.toml`

Expected: compile failure because `rux_vm::vm_microbenchmarks` does not exist yet.

## Task 3: Implementation

- [ ] **Step 1: Add module export**

Add `pub mod vm_microbenchmarks;` to `native/rux-vm/src/lib.rs`.

- [ ] **Step 2: Implement workload runners**

Create `native/rux-vm/src/vm_microbenchmarks.rs` with:

- `VmBenchmarkWorkload`;
- `run_low_image_workload`;
- `run_rux16_workload`;
- Rux16 word encoders for `const4`, `const32`, `add`, `eq`, `load32`, `store32`, `branch_if_zero`, `branch_if_nonzero`, `jmp`, and `halt`;
- LowImage builders for equivalent compute and memory loops.

- [ ] **Step 3: Add example CLI**

Create `native/rux-vm/examples/vm_microbenchmarks.rs`. It accepts `iterations` and `samples`, runs each workload on both VMs, and prints:

```text
workload	vm	iterations	checksum	best_nanos	nanos_per_iteration
```

- [ ] **Step 4: Run tests to verify GREEN**

Run: `cargo test --test vm_microbenchmarks --manifest-path native/rux-vm/Cargo.toml`

Expected: checksum tests pass.

## Task 4: Verification And Commit

- [ ] **Step 1: Format Rust files**

Run: `rustfmt native/rux-vm/src/lib.rs native/rux-vm/src/vm_microbenchmarks.rs native/rux-vm/examples/vm_microbenchmarks.rs native/rux-vm/tests/vm_microbenchmarks.rs`

- [ ] **Step 2: Run focused tests**

Run: `cargo test --test vm_microbenchmarks --manifest-path native/rux-vm/Cargo.toml`

Expected: all benchmark tests pass.

- [ ] **Step 3: Run example**

Run: `cargo run --release --manifest-path native/rux-vm/Cargo.toml --example vm_microbenchmarks -- 1000 3`

Expected: TSV rows for `compute-loop` and `memory-loop` on both `low-image` and `rux16`.

- [ ] **Step 4: Run full native VM tests**

Run: `cargo test --manifest-path native/rux-vm/Cargo.toml`

Expected: all native `rux-vm` tests pass.

- [ ] **Step 5: Check whitespace**

Run: `git diff --check`

Expected: exit 0.

- [ ] **Step 6: Commit code**

```bash
git add native/rux-vm/src/lib.rs native/rux-vm/src/vm_microbenchmarks.rs native/rux-vm/examples/vm_microbenchmarks.rs native/rux-vm/tests/vm_microbenchmarks.rs
git commit -m "bench(vm): compare Rux16 and LowImage loops"
```

## Task 5: Roadmap Update

- [ ] **Step 1: Update #36**

Add links to the spec and plan, note that this first slice uses a dependency-free harness, and leave the issue open if Criterion/MMIO/baseline archive remain for later.

- [ ] **Step 2: Re-check roadmap status**

Verify #36 remains on the project and explain whether it stays `Now` or moves elsewhere.
