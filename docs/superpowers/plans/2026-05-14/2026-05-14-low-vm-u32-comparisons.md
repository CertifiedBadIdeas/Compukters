# Low VM U32 Comparisons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add correct unsigned ordering comparisons for seed `u32` values.

**Architecture:** Add `Instruction::U32Lt` to low image and runner. Keep registers as raw word storage. Change compiler comparison lowering so `u32` ordering uses `U32Lt` while equality reuses `I32Eq`.

**Tech Stack:** Rust crates `native/rux-vm` and `native/rux-compiler`, Cargo tests run offline.

---

### Task 1: Add Failing Tests

**Files:**
- Modify: `native/rux-vm/tests/low_image_runner.rs`
- Modify: `native/rux-vm/tests/low_image_decode.rs`
- Modify: `native/rux-compiler/tests/compiler_seed.rs`

- [ ] **Step 1: Add VM tests**

Add tests proving `U32Lt` treats `0xffff0000` as greater than `1` and decoder tag `28` decodes to `Instruction::U32Lt`.

- [ ] **Step 2: Add compiler tests**

Add tests for `u32 <`, `>`, `<=`, `>=`, plus a rejection test for mixed `i32` / `u32` comparison.

- [ ] **Step 3: Verify RED**

Run:

```bash
cargo test --offline --manifest-path native/rux-vm/Cargo.toml u32
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml u32
```

Expected: failures because `U32Lt` does not exist yet and compiler lowering still uses signed comparison.

### Task 2: Implement Low VM U32Lt

**Files:**
- Modify: `native/rux-vm/src/low_image.rs`
- Modify: `native/rux-vm/src/low_image_runner.rs`

- [ ] **Step 1: Add image instruction**

Add `Instruction::U32Lt { dst, lhs, rhs }` and decoder tag `28`.

- [ ] **Step 2: Add runner support**

Add validation, block compilation, executable operation, optional immediate lowering, register liveness, and runtime execution for `U32Lt`.

### Task 3: Implement Compiler Lowering

**Files:**
- Modify: `native/rux-compiler/src/codegen.rs`

- [ ] **Step 1: Compare typed operands**

Compile comparison operands as expressions, inspect their source types, reject mixed numeric types, and route `i32` ordering to `I32Lt` and `u32` ordering to `U32Lt`.

- [ ] **Step 2: Preserve equality behavior**

Use `I32Eq` for both `i32` and `u32` equality/inequality.

### Task 4: Verify And Commit

**Files:**
- All changed files

- [ ] **Step 1: Format check**

Run:

```bash
cargo fmt --manifest-path native/rux-compiler/Cargo.toml --check
```

Expected: exit code 0.

- [ ] **Step 2: Full compiler tests**

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml
```

Expected: all compiler tests pass.

- [ ] **Step 3: Full VM tests**

Run:

```bash
cargo test --offline --manifest-path native/rux-vm/Cargo.toml
```

Expected: all VM tests pass.

- [ ] **Step 4: Commit**

Run:

```bash
git add docs/superpowers/specs/2026-05-14/2026-05-14-low-vm-u32-comparisons-design.md docs/superpowers/plans/2026-05-14/2026-05-14-low-vm-u32-comparisons.md native/rux-vm/src/low_image.rs native/rux-vm/src/low_image_runner.rs native/rux-vm/tests/low_image_runner.rs native/rux-vm/tests/low_image_decode.rs native/rux-compiler/src/codegen.rs native/rux-compiler/tests/compiler_seed.rs
git commit -m "feat: add low vm u32 comparisons"
```
