# K16 VM Code Flow Docs Implementation Plan

> Issue: [#164](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/164)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make current K16 VM code easier to understand without changing behavior.

**Architecture:** Add a reader-facing code-flow doc that maps host, machine,
bus, CPU, decoder, and MMIO devices. Refresh older docs to point to the current
K16 path and add small in-code phase comments.

**Tech Stack:** Markdown docs, Rust comments, existing K16 VM crate tests.

---

### Task 1: Documentation Map

**Files:**
- Create: `docs/k16-vm-code-flow.md`
- Create: `docs/superpowers/specs/2026-06-05/2026-06-05-issue-164-k16-vm-code-flow-docs-design.md`
- Create: `docs/superpowers/plans/2026-06-05/2026-06-05-issue-164-k16-vm-code-flow-docs.md`

- [ ] Add a code-flow document mapping `K16ComputerHandle`, `ComputerMachine`,
  `MachineBus`, `K16Cpu`, `K16Decoder`, RAM, and MMIO devices.

### Task 2: Refresh Existing Docs

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/MACHINE.md`
- Modify: `docs/PROFILING.md`

- [ ] Link the code-flow doc from architecture.
- [ ] Update stale superseded wording in `MACHINE.md`.
- [ ] Replace legacy CKL host-call interpretation notes in profiling docs with
  current K16/MMIO wording.

### Task 3: In-Code Orientation

**Files:**
- Modify: `rust/host/k16-vm/src/k16.rs`
- Modify: `rust/host/k16-vm/src/computer/machine.rs`

- [ ] Add short phase comments around decode/execute and full-machine
  construction/run-loop boundaries.

### Task 4: Verification

Run:

```bash
(cd rust/host/k16-vm && cargo fmt -- --check)
(cd rust/host/k16-vm && cargo test)
git diff --check
```

Expected: all commands pass.
