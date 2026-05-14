# Rust-Like Seed Functions And Consts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add top-level `const` declarations and multiple top-level functions with `i32` parameters and static calls to the Rust-like seed compiler.

**Architecture:** Keep `native/ckl-compiler/src/lib.rs` as the seed compiler file. Change the parser from single-main parsing to top-level item parsing, collect const/function signatures before codegen, then lower each function into `low_image::Function` using existing `CallStatic`.

**Tech Stack:** Rust 2021, `ckl-vm` path dependency, low image `Function`/`Instruction::CallStatic`, Cargo integration tests.

---

## File Structure

- Modify: `native/ckl-compiler/src/lib.rs`
  - Add `TokenKind::Const`.
  - Replace `Program { return_type, statements }` with `Program { consts, functions }`.
  - Add AST nodes for `ConstDecl`, `FunctionDecl`, `Parameter`, and `Expr::Call`.
  - Add const evaluation for `i32` compile-time expressions.
  - Add function signature collection and static call lowering.
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`
  - Add lexer, const, function, call, diagnostics, and e2e tests.
- Modify: `docs/superpowers/specs/2026-05-14-rust-like-seed-functions-and-consts-design.md`
  - Add implementation status.

## Task 1: Add Const Token And Top-Level Item Parser

**Files:**
- Modify: `native/ckl-compiler/src/lib.rs`
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [x] **Step 1: Write failing lexer/parser tests**

Add tests:

```rust
#[test]
fn lexer_recognizes_const_keyword() {
    let tokens = lex("const OK: i32 = 79;").unwrap();
    let kinds: Vec<TokenKind> = tokens.into_iter().map(|token| token.kind).collect();

    assert_eq!(
        kinds,
        vec![
            TokenKind::Const,
            TokenKind::Ident("OK".to_string()),
            TokenKind::Colon,
            TokenKind::I32,
            TokenKind::Equal,
            TokenKind::Int(79),
            TokenKind::Semicolon,
            TokenKind::Eof,
        ]
    );
}

#[test]
fn compile_accepts_const_before_main() {
    let image = compile("const OK: i32 = 79; fn main() -> i32 { return OK; }").unwrap();

    assert_eq!(
        image.functions[0].instructions,
        vec![
            Instruction::I32Const { dst: 0, value: 79 },
            Instruction::ReturnI32 { src: 0 },
        ]
    );
}
```

- [x] **Step 2: Verify RED**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml const
```

Expected: FAIL because `TokenKind::Const` and top-level const parsing do not exist.

- [x] **Step 3: Implement parser support**

Implement:

- `TokenKind::Const`;
- keyword lexing for `"const"`;
- `ConstDecl { name, value }`;
- `FunctionDecl { name, parameters, return_type, statements }`;
- `Program { consts, functions }`;
- `parse_program` loop over `const` and `fn`;
- `parse_function` with arbitrary function name and parameters;
- `parse_const_declaration`.

Keep function parameter type restricted to `i32`.

- [x] **Step 4: Add minimal const evaluation in codegen**

Implement source const map before function lowering:

- reject duplicate const names;
- reject const shadowing built-ins;
- evaluate `i32` literals, source const references, built-in `i32` constants, and arithmetic;
- reject address/unit/runtime expressions.

Existing single-main behavior must keep passing.

- [x] **Step 5: Verify GREEN and commit**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml const
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
git add native/ckl-compiler/src/lib.rs native/ckl-compiler/tests/compiler_seed.rs
git commit -m "Add rust language seed source consts"
```

## Task 2: Add Multiple Function Lowering

**Files:**
- Modify: `native/ckl-compiler/src/lib.rs`
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [x] **Step 1: Write failing function tests**

Add tests:

```rust
#[test]
fn compile_lowers_unit_helper_function() {
    let image = compile("fn helper() { return; } fn main() { helper(); }").unwrap();

    assert_eq!(image.functions.len(), 2);
    assert_eq!(image.entry_function_index, 1);
    assert_eq!(image.functions[0].name, "helper");
    assert_eq!(image.functions[1].name, "main");
    assert_eq!(image.functions[0].instructions, vec![Instruction::ReturnUnit]);
    assert_eq!(
        image.functions[1].instructions,
        vec![
            Instruction::CallStatic {
                return_register: None,
                function_index: 0,
                arguments: Vec::new(),
            },
            Instruction::ReturnUnit,
        ]
    );
}

#[test]
fn compile_lowers_i32_function_call_with_arguments() {
    let image = compile(
        "fn add(a: i32, b: i32) -> i32 { return a + b; }
         fn main() -> i32 { return add(1, 2); }",
    )
    .unwrap();

    assert_eq!(image.functions[0].parameters, vec![0, 1]);
    assert!(image.functions[0]
        .instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::I32Add { lhs: 0, rhs: 1, .. })));
    assert!(image.functions[1].instructions.iter().any(|instruction| {
        matches!(
            instruction,
            Instruction::CallStatic {
                return_register: Some(_),
                function_index: 0,
                arguments
            } if arguments.len() == 2
        )
    }));
}
```

- [x] **Step 2: Verify RED**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compile_lowers_
```

Expected: FAIL until function signatures/calls are implemented.

- [x] **Step 3: Implement function signature collection**

Implement function map:

- collect all functions before body codegen;
- reject missing `main`;
- reject duplicate function names;
- reject function name shadowing built-ins or consts;
- reject duplicate parameter names;
- set `entry_function_index` to `main` index.

- [x] **Step 4: Implement `Expr::Call` parsing and lowering**

Parser:

- when identifier is followed by `(`, parse `Expr::Call { name, args }`;
- otherwise keep `Expr::Local(name)`.

Codegen:

- resolve callee by function map;
- reject unknown function;
- reject wrong argument count;
- compile all args as `i32`;
- emit `CallStatic`;
- for `i32` return, allocate return register;
- for unit return as expression, return a compile error.

Statement calls:

- unit call emits `return_register: None`;
- i32 call as statement allocates a temp return register and ignores it.

- [x] **Step 5: Verify GREEN and commit**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compile_lowers_
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
git add native/ckl-compiler/src/lib.rs native/ckl-compiler/tests/compiler_seed.rs
git commit -m "Add rust language seed functions"
```

## Task 3: Add Function/Const Diagnostics And Recursion Guard

**Files:**
- Modify: `native/ckl-compiler/src/lib.rs`
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [x] **Step 1: Add diagnostics tests**

Add tests for:

```rust
#[test]
fn compile_rejects_missing_main() {
    let error = compile("fn helper() {}").unwrap_err();
    assert!(error.message.contains("missing `main` function"), "{error:?}");
}

#[test]
fn compile_rejects_duplicate_function() {
    let error = compile("fn main() {} fn main() {}").unwrap_err();
    assert!(error.message.contains("duplicate function `main`"), "{error:?}");
}

#[test]
fn compile_rejects_duplicate_const() {
    let error = compile("const A: i32 = 1; const A: i32 = 2; fn main() {}").unwrap_err();
    assert!(error.message.contains("duplicate const `A`"), "{error:?}");
}

#[test]
fn compile_rejects_unknown_function() {
    let error = compile("fn main() { missing(); }").unwrap_err();
    assert!(error.message.contains("unknown function `missing`"), "{error:?}");
}

#[test]
fn compile_rejects_wrong_argument_count() {
    let error = compile("fn add(a: i32) -> i32 { return a; } fn main() -> i32 { return add(1, 2); }").unwrap_err();
    assert!(error.message.contains("function `add` expects 1 arguments but got 2"), "{error:?}");
}

#[test]
fn compile_rejects_unit_function_used_as_i32() {
    let error = compile("fn unit() {} fn main() -> i32 { return unit(); }").unwrap_err();
    assert!(error.message.contains("unit function `unit` used as `i32` value"), "{error:?}");
}

#[test]
fn compile_rejects_direct_recursion() {
    let error = compile("fn main() -> i32 { return main(); }").unwrap_err();
    assert!(error.message.contains("recursive function call `main`"), "{error:?}");
}
```

- [x] **Step 2: Verify RED**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compile_rejects_
```

Expected: FAIL for any diagnostics not implemented yet.

- [x] **Step 3: Implement diagnostics**

Add checks in signature/const collection and call lowering. Track current function during body lowering and reject direct calls to the same function.

- [x] **Step 4: Verify GREEN and commit**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compile_rejects_
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
git add native/ckl-compiler/src/lib.rs native/ckl-compiler/tests/compiler_seed.rs
git commit -m "Validate rust language seed functions and consts"
```

## Task 4: Add ComputerMachine E2E Test And Docs

**Files:**
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`
- Modify: `docs/superpowers/specs/2026-05-14-rust-like-seed-functions-and-consts-design.md`

- [x] **Step 1: Add e2e test**

Add:

```rust
#[test]
fn compiled_seed_functions_and_consts_run_on_computer_machine() {
    let image = compile(
        "const OK_O: i32 = 79;
         const OK_K: i32 = 75;

         fn write_ok() {
             unsafe {
                 mmio<i32>(DEBUG_WRITE).store(OK_O);
                 mmio<i32>(DEBUG_WRITE).store(OK_K);
             }
         }

         fn main() -> i32 {
             unsafe {
                 mmio<i32>(CONTROL_STATUS).store(STATUS_BOOTING);
             }

             write_ok();

             unsafe {
                 mmio<i32>(CONTROL_STATUS).store(STATUS_READY);
             }

             return 0;
         }",
    )
    .unwrap();
    let mut machine = ComputerMachine::new(64 * 1024).unwrap();
    let cpu_id = machine.spawn_boot_cpu(image, 1_000_000).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltI32(0)
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
    assert_eq!(machine.exit_code(), 0);
    assert_eq!(machine.panic_code(), 0);
    assert_eq!(machine.debug_output_string(), "OK");
}
```

- [x] **Step 2: Verify e2e**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compiled_seed_functions_and_consts_run_on_computer_machine
```

Expected: PASS.

- [x] **Step 3: Update implementation status**

Append to the design:

```markdown
## Implementation Status

Implemented in `native/ckl-compiler`:

- top-level source `const` declarations for `i32`;
- multi-function source programs;
- `i32` parameters;
- static unit and `i32` function calls;
- forward function calls;
- function and const diagnostics;
- direct recursion rejection;
- end-to-end firmware test using helper functions and source constants.
```

- [ ] **Step 4: Commit**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
git add native/ckl-compiler/tests/compiler_seed.rs docs/superpowers/specs/2026-05-14-rust-like-seed-functions-and-consts-design.md
git commit -m "Run rust language seed functions and consts on computer machine"
```

## Task 5: Final Verification

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
