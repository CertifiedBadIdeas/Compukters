# K16 Benchmark Current Snapshot Implementation Plan

> Issue: [#163](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/163)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a repo-tracked plaintext K16 VM benchmark current snapshot that refreshes automatically before commits.

**Architecture:** Keep the benchmark CLI output unchanged. Add a shell recording script that captures the existing release benchmark output into `docs/benchmarks/k16-vm-current.txt`, and add a repository pre-commit hook that runs the script and stages the snapshot.

**Tech Stack:** POSIX shell, Git hooks, Cargo K16 VM benchmark example.

---

### Task 1: Shell Tests

**Files:**
- Create: `scripts/tests/k16-benchmark-current.sh`

- [ ] **Step 1: Write tests for the recorder and hook**

Create a shell test that checks these observable behaviors:

- the recorder writes a plaintext current snapshot;
- the snapshot includes metadata and the benchmark table header;
- the pre-commit hook stages the snapshot after a successful run;
- the pre-commit hook rejects unrelated unstaged changes.

- [ ] **Step 2: Run tests to verify RED**

Run: `scripts/tests/k16-benchmark-current.sh`

Expected: fail because the scripts do not exist yet.

### Task 2: Recorder and Hook

**Files:**
- Create: `scripts/record-k16-vm-benchmark-current.sh`
- Create: `scripts/git-hooks/pre-commit`
- Create: `scripts/install-git-hooks.sh`
- Create: `docs/benchmarks/k16-vm-current.txt`

- [ ] **Step 1: Implement the recorder**

The recorder accepts optional `iterations` and `samples`, defaults to `100000`
and `5`, runs `cargo run --release --example vm_microbenchmarks`, and writes a
plaintext snapshot with metadata.

- [ ] **Step 2: Implement the pre-commit hook**

The hook refuses unrelated unstaged changes, runs the recorder, and stages
`docs/benchmarks/k16-vm-current.txt`.

- [ ] **Step 3: Implement the install script**

The install script sets `git config core.hooksPath scripts/git-hooks`.

- [ ] **Step 4: Run tests to verify GREEN**

Run: `scripts/tests/k16-benchmark-current.sh`

Expected: pass.

### Task 3: Documentation and Verification

**Files:**
- Modify: `docs/PROFILING.md`

- [ ] **Step 1: Document current snapshot workflow**

Document the current snapshot path, the manual refresh command, hook install
command, and Git-history inspection commands.

- [ ] **Step 2: Run verification**

Run:

```bash
scripts/tests/k16-benchmark-current.sh
scripts/record-k16-vm-benchmark-current.sh 1000 3
(cd rust/host/k16-vm && cargo test --test vm_microbenchmarks)
git diff --check
```

Expected: all commands pass.

- [ ] **Step 3: Commit**

```bash
git add docs/PROFILING.md docs/benchmarks/k16-vm-current.txt docs/superpowers/specs/2026-06-05/2026-06-05-issue-163-k16-benchmark-current-snapshot-design.md docs/superpowers/plans/2026-06-05/2026-06-05-issue-163-k16-benchmark-current-snapshot.md scripts/record-k16-vm-benchmark-current.sh scripts/git-hooks/pre-commit scripts/install-git-hooks.sh scripts/tests/k16-benchmark-current.sh
git commit -m "bench(vm): track current K16 benchmark snapshot"
```
