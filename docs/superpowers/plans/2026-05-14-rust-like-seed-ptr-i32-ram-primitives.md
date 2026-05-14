# Rust-Like Seed `ptr<i32>` RAM Primitives Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add unsafe raw `ptr<i32>` RAM load/store support to the Rust-like seed compiler.

**Architecture:** Extend the seed lexer/parser with `ptr<i32>(addr)`, represent `ptr` and `mmio` as pointer capabilities in codegen, and lower `.load()`/`.store(value)` to existing low VM `Load32`/`Store32`. Add address-context `+` lowering to existing `AddrAdd`.

**Tech Stack:** Rust 2021, `native/ckl-compiler`, `ckl-vm::low_image`, `ComputerMachine`, Cargo tests.

---

## File Structure

- Modify: `native/ckl-compiler/src/lib.rs`
  - Add `TokenKind::Ptr`.
  - Add `Expr::Ptr(Box<Expr>)`.
  - Add pointer capability expression values.
  - Add address addition lowering in `compile_addr_expr`.
  - Share pointer method lowering for `mmio<i32>` and `ptr<i32>`.
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`
  - Add lexer, lowering, diagnostics, and E2E tests.
- Modify: `docs/superpowers/specs/2026-05-14-rust-like-seed-ptr-i32-ram-primitives-design.md`
  - Add implementation status after code is complete.

## Task 1: Add `ptr` Token And Parser Node

**Files:**
- Modify: `native/ckl-compiler/src/lib.rs`
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [ ] **Step 1: Write failing lexer and parse/lowering tests**

Add tests:

```rust
#[test]
fn lexer_recognizes_ptr_keyword() {
    let tokens = lex("ptr<i32>(RAM_BASE)").unwrap();
    let kinds: Vec<TokenKind> = tokens.into_iter().map(|token| token.kind).collect();

    assert_eq!(
        kinds,
        vec![
            TokenKind::Ptr,
            TokenKind::Less,
            TokenKind::I32,
            TokenKind::Greater,
            TokenKind::LeftParen,
            TokenKind::Ident("RAM_BASE".to_string()),
            TokenKind::RightParen,
            TokenKind::Eof,
        ]
    );
}

#[test]
fn compile_lowers_unsafe_ptr_i32_store_and_load() {
    let image = compile(
        "fn main() -> i32 {
            unsafe {
                ptr<i32>(RAM_BASE + 4).store(42);
                return ptr<i32>(RAM_BASE + 4).load();
            }
        }",
    )
    .unwrap();

    assert!(image.functions[0]
        .instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::AddrAdd { .. })));
    assert!(image.functions[0]
        .instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::Store32 { .. })));
    assert!(image.functions[0]
        .instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::Load32 { .. })));
}
```

- [ ] **Step 2: Verify RED**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml ptr --test compiler_seed
```

Expected: FAIL because `ptr` is not a token or expression.

- [ ] **Step 3: Implement token and parser support**

Implement:

- `TokenKind::Ptr`;
- lex keyword `"ptr"`;
- `TokenKind::name`;
- `Expr::Ptr(Box<Expr>)`;
- parser branch matching `ptr<i32>(expr)`.

- [ ] **Step 4: Implement pointer capability lowering**

Implement:

- pointer expression value variant for `ptr`/`mmio`;
- `Expr::Mmio(address)` returns pointer capability kind `Mmio`;
- `Expr::Ptr(address)` returns pointer capability kind `Ptr`;
- method calls require pointer capability receivers;
- `.store(value)` and `.load()` lower to `Store32`/`Load32`.

- [ ] **Step 5: Verify GREEN and commit**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml ptr --test compiler_seed
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
git add native/ckl-compiler/src/lib.rs native/ckl-compiler/tests/compiler_seed.rs
git commit -m "Add rust language seed ptr i32 RAM primitives"
```

## Task 2: Add Address Arithmetic And Diagnostics

**Files:**
- Modify: `native/ckl-compiler/src/lib.rs`
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [ ] **Step 1: Write failing diagnostics tests**

Add tests:

```rust
#[test]
fn compile_rejects_ptr_outside_unsafe() {
    let error = compile("fn main() { ptr<i32>(RAM_BASE).store(1); }").unwrap_err();

    assert!(
        error.message.contains("pointer access requires `unsafe`"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_i32_const_as_ptr_address() {
    let error = compile("const VALUE: i32 = 4; fn main() { unsafe { ptr<i32>(VALUE).store(1); } }").unwrap_err();

    assert!(
        error.message.contains("pointer address must be an address expression"),
        "{error:?}"
    );
}
```

- [ ] **Step 2: Verify RED**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compile_rejects_ptr --test compiler_seed
```

Expected: FAIL until diagnostics are implemented.

- [ ] **Step 3: Implement address-context `+`**

Update `compile_addr_expr`:

- for `Expr::Binary { op: BinaryOp::Add, lhs, rhs }`, compile `lhs` as address and `rhs` as `i32`;
- emit `Instruction::AddrAdd`;
- reject other binary address operations with a deterministic error.

- [ ] **Step 4: Implement pointer-specific diagnostics**

Update method-call lowering:

- if unsafe depth is zero and receiver is `Expr::Ptr`, return `pointer access requires `unsafe``;
- if unsafe depth is zero and receiver is `Expr::Mmio`, keep `MMIO access requires `unsafe``;
- if pointer address expression lowers to `i32`, return `pointer address must be an address expression`;
- reject `Expr::Ptr` in const initializers.

- [ ] **Step 5: Verify GREEN and commit**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compile_rejects_ptr --test compiler_seed
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
git add native/ckl-compiler/src/lib.rs native/ckl-compiler/tests/compiler_seed.rs
git commit -m "Validate rust language seed ptr i32 RAM primitives"
```

## Task 3: Add ComputerMachine E2E And Docs

**Files:**
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`
- Modify: `docs/superpowers/specs/2026-05-14-rust-like-seed-ptr-i32-ram-primitives-design.md`
- Modify: `docs/superpowers/plans/2026-05-14-rust-like-seed-ptr-i32-ram-primitives.md`

- [ ] **Step 1: Add E2E test**

Add:

```rust
#[test]
fn compiled_seed_ptr_i32_ram_program_runs_on_computer_machine() {
    let image = compile(
        "fn main() -> i32 {
            unsafe {
                ptr<i32>(RAM_BASE + 4).store(42);
                return ptr<i32>(RAM_BASE + 4).load();
            }
        }",
    )
    .unwrap();
    let mut machine = ComputerMachine::new(64 * 1024).unwrap();
    let cpu_id = machine.spawn_boot_cpu(image, 1_000_000).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltI32(42)
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
    assert_eq!(machine.exit_code(), 42);
    assert_eq!(machine.panic_code(), 0);
}
```

- [ ] **Step 2: Verify E2E**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compiled_seed_ptr_i32_ram_program_runs_on_computer_machine --test compiler_seed
```

Expected: PASS.

- [ ] **Step 3: Update implementation status**

Append to the design:

```markdown
## Implementation Status

Implemented in `native/ckl-compiler`:

- `ptr<i32>(addr)` parsing;
- unsafe raw RAM `.store(value)` and `.load()`;
- address-context `RAM_BASE + i32` lowering to `AddrAdd`;
- pointer diagnostics;
- ComputerMachine E2E RAM read/write test.
```

Mark completed plan checkboxes.

- [ ] **Step 4: Final verification and commit**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
cargo test --offline --manifest-path native/ckl-vm/Cargo.toml
git add native/ckl-compiler/tests/compiler_seed.rs docs/superpowers/specs/2026-05-14-rust-like-seed-ptr-i32-ram-primitives-design.md docs/superpowers/plans/2026-05-14-rust-like-seed-ptr-i32-ram-primitives.md
git commit -m "Run rust language seed ptr i32 RAM program on computer machine"
```

## Final Verification

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
cargo test --offline --manifest-path native/ckl-vm/Cargo.toml
git status --short
```

Expected:

- compiler tests pass;
- formatting check passes;
- low VM tests pass;
- git status is clean.
