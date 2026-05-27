# Rust-Like Seed U32 And Typed Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `u32`, minimal `as i32` / `as u32` casts, and typed `ptr<u32>` / `mmio<u32>` memory access to the Rust-like seed compiler.

**Architecture:** Keep the low VM unchanged. The compiler tracks `u32` as a distinct source type but lowers values to existing 32-bit low VM registers and uses existing `Load32` / `Store32` operations.

**Tech Stack:** Rust compiler crate `native/rux-compiler`, low VM image instructions from `native/rux-vm`, Cargo tests run offline.

---

### Task 1: Add U32 And Memory Tests

**Files:**
- Modify: `native/rux-compiler/tests/compiler_seed.rs`

- [ ] **Step 1: Add failing tests**

Add tests for:

```rust
lexer_recognizes_u32_literals_casts_and_typed_memory_tokens
compile_lowers_u32_literals_locals_and_casts
compile_lowers_u32_function_call_with_argument
compile_lowers_unsafe_ptr_u32_store_and_load
compile_rejects_i32_assignment_to_u32_local_without_cast
compile_rejects_u32_assignment_to_i32_local_without_cast
compile_rejects_bool_cast_to_u32
compiled_seed_ptr_u32_ram_program_runs_on_computer_machine
compiled_seed_mmio_u32_debug_write_runs_on_computer_machine
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml u32
```

Expected: compilation fails because `u32`, suffixed literals, casts, and typed pointers are not implemented yet.

### Task 2: Add Lexer, Parser, And AST Support

**Files:**
- Modify: `native/rux-compiler/src/lexer.rs`
- Modify: `native/rux-compiler/src/ast.rs`
- Modify: `native/rux-compiler/src/parser.rs`

- [ ] **Step 1: Add tokens**

Add `U32` and `As`. Extend integer lexing so adjacent `u32` suffixes are stored on integer tokens.

- [ ] **Step 2: Add AST nodes**

Represent integer suffixes and cast expressions:

```rust
Expr::Int { value: i64, suffix: Option<IntSuffix> }
Expr::Cast { expr: Box<Expr>, target: TypeName }
```

- [ ] **Step 3: Parse casts and `ptr<u32>` / `mmio<u32>`**

Parse `expr as i32` and `expr as u32` after postfix expressions. Parse pointer element type using `parse_type`.

### Task 3: Add U32 Codegen

**Files:**
- Modify: `native/rux-compiler/src/codegen.rs`

- [ ] **Step 1: Extend value and type models**

Add `U32` to `ReturnType`, `TypeName`, `ValueType`, and `ExprValue`.

- [ ] **Step 2: Add literal and cast lowering**

Emit `Instruction::I32Const` with preserved two's-complement bits for `u32` literals. Lower `as i32` / `as u32` as type reinterpretation with no extra instruction.

- [ ] **Step 3: Add typed pointer lowering**

Carry `TypeName` on pointer capabilities and use that type for load/store checks. Lower both `i32` and `u32` loads/stores to existing `Load32` / `Store32`.

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

- [ ] **Step 3: VM regression tests**

Run:

```bash
cargo test --offline --manifest-path native/rux-vm/Cargo.toml
```

Expected: all VM tests pass.

- [ ] **Step 4: Commit**

Run:

```bash
git add docs/superpowers/specs/2026-05-14/2026-05-14-rust-like-seed-u32-memory-design.md docs/superpowers/plans/2026-05-14/2026-05-14-rust-like-seed-u32-memory.md native/rux-compiler/src/ast.rs native/rux-compiler/src/lexer.rs native/rux-compiler/src/parser.rs native/rux-compiler/src/codegen.rs native/rux-compiler/tests/compiler_seed.rs
git commit -m "feat: add seed u32 memory support"
```
