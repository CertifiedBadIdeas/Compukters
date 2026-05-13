# Rust-Like Bare-Metal Language Seed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first Rust compiler seed for a new Rust-like bare-metal language that emits `ckl_vm::low_image::Image` and runs on `ComputerMachine`.

**Architecture:** Add a new `native/ckl-compiler` Rust crate. The crate owns lexer, parser, AST, diagnostics, and a tiny code generator that lowers one `main` function into low VM instructions. It depends on `ckl-vm` for ABI constants, low image types, and end-to-end machine tests.

**Tech Stack:** Rust 2021, `ckl-vm` path dependency, Cargo unit/integration tests, low VM `Image`/`Instruction`, `ComputerMachine`.

---

## File Structure

- Create: `native/ckl-compiler/Cargo.toml`
  - New Rust crate with `ckl-vm = { path = "../ckl-vm" }`.
- Create: `native/ckl-compiler/src/lib.rs`
  - Public `compile(source: &str) -> Result<Image, CompileError>`.
  - Lexer, parser, AST, and code generator live here for the seed. Split later when the compiler grows.
- Create: `native/ckl-compiler/tests/compiler_seed.rs`
  - End-to-end and behavior tests.
- Modify: `docs/superpowers/todos/2026-05-12-low-vm-shared-ram-ckl-os-research-note.md`
  - Record that the experiment now starts a new Rust-like language rather than CKL compatibility.

## Task 1: Scaffold Rust Compiler Crate With Public API

**Files:**
- Create: `native/ckl-compiler/Cargo.toml`
- Create: `native/ckl-compiler/src/lib.rs`
- Create: `native/ckl-compiler/tests/compiler_seed.rs`

- [ ] **Step 1: Write the failing public API test**

Create `native/ckl-compiler/tests/compiler_seed.rs`:

```rust
use ckl_compiler::{compile, CompileError};

#[test]
fn compiler_exposes_public_compile_api() {
    let error = compile("").unwrap_err();

    assert!(error.message.contains("expected `fn`"), "{error:?}");
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cargo test --manifest-path native/ckl-compiler/Cargo.toml compiler_exposes_public_compile_api
```

Expected: FAIL because `native/ckl-compiler/Cargo.toml` does not exist.

- [ ] **Step 3: Create the crate**

Create `native/ckl-compiler/Cargo.toml`:

```toml
[package]
name = "ckl-compiler"
version = "0.1.0"
edition = "2021"

[dependencies]
ckl-vm = { path = "../ckl-vm" }
thiserror = "1.0"
```

Create `native/ckl-compiler/src/lib.rs`:

```rust
use ckl_vm::low_image::Image;
use thiserror::Error;

#[derive(Debug, Clone, PartialEq, Eq, Error)]
#[error("{message}")]
pub struct CompileError {
    pub message: String,
}

pub fn compile(source: &str) -> Result<Image, CompileError> {
    let trimmed = source.trim_start();
    if !trimmed.starts_with("fn") {
        return Err(CompileError {
            message: "expected `fn`".to_string(),
        });
    }
    Err(CompileError {
        message: "compiler seed is not implemented yet".to_string(),
    })
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run:

```bash
cargo test --manifest-path native/ckl-compiler/Cargo.toml compiler_exposes_public_compile_api
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add native/ckl-compiler
git commit -m "Add rust language seed compiler crate"
```

## Task 2: Add Lexer For Seed Syntax

**Files:**
- Modify: `native/ckl-compiler/src/lib.rs`
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [ ] **Step 1: Write lexer tests**

Append to `native/ckl-compiler/tests/compiler_seed.rs`:

```rust
use ckl_compiler::{lex, TokenKind};

#[test]
fn lexer_recognizes_seed_language_tokens() {
    let tokens = lex("fn main() -> i32 { unsafe { mmio<i32>(0x2a).store(79); } return 0; }").unwrap();
    let kinds: Vec<TokenKind> = tokens.into_iter().map(|token| token.kind).collect();

    assert_eq!(
        kinds,
        vec![
            TokenKind::Fn,
            TokenKind::Ident("main".to_string()),
            TokenKind::LeftParen,
            TokenKind::RightParen,
            TokenKind::Arrow,
            TokenKind::I32,
            TokenKind::LeftBrace,
            TokenKind::Unsafe,
            TokenKind::LeftBrace,
            TokenKind::Mmio,
            TokenKind::Less,
            TokenKind::I32,
            TokenKind::Greater,
            TokenKind::LeftParen,
            TokenKind::Int(42),
            TokenKind::RightParen,
            TokenKind::Dot,
            TokenKind::Ident("store".to_string()),
            TokenKind::LeftParen,
            TokenKind::Int(79),
            TokenKind::RightParen,
            TokenKind::Semicolon,
            TokenKind::RightBrace,
            TokenKind::Return,
            TokenKind::Int(0),
            TokenKind::Semicolon,
            TokenKind::RightBrace,
            TokenKind::Eof,
        ],
    );
}
```

- [ ] **Step 2: Run the lexer test and verify RED**

Run:

```bash
cargo test --manifest-path native/ckl-compiler/Cargo.toml lexer_recognizes_seed_language_tokens
```

Expected: FAIL because `lex` and `TokenKind` do not exist.

- [ ] **Step 3: Implement the lexer**

In `native/ckl-compiler/src/lib.rs`, add:

```rust
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Token {
    pub kind: TokenKind,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TokenKind {
    Fn,
    Return,
    Unsafe,
    Mmio,
    I32,
    Ident(String),
    Int(i32),
    LeftParen,
    RightParen,
    LeftBrace,
    RightBrace,
    Less,
    Greater,
    Dot,
    Comma,
    Semicolon,
    Plus,
    Minus,
    Star,
    Slash,
    Arrow,
    Eof,
}

pub fn lex(source: &str) -> Result<Vec<Token>, CompileError> {
    let mut lexer = Lexer {
        chars: source.chars().collect(),
        offset: 0,
    };
    lexer.lex()
}

struct Lexer {
    chars: Vec<char>,
    offset: usize,
}

impl Lexer {
    fn lex(&mut self) -> Result<Vec<Token>, CompileError> {
        let mut tokens = Vec::new();
        while let Some(ch) = self.peek() {
            match ch {
                ch if ch.is_whitespace() => {
                    self.advance();
                }
                '(' => tokens.push(self.single(TokenKind::LeftParen)),
                ')' => tokens.push(self.single(TokenKind::RightParen)),
                '{' => tokens.push(self.single(TokenKind::LeftBrace)),
                '}' => tokens.push(self.single(TokenKind::RightBrace)),
                '<' => tokens.push(self.single(TokenKind::Less)),
                '>' => tokens.push(self.single(TokenKind::Greater)),
                '.' => tokens.push(self.single(TokenKind::Dot)),
                ',' => tokens.push(self.single(TokenKind::Comma)),
                ';' => tokens.push(self.single(TokenKind::Semicolon)),
                '+' => tokens.push(self.single(TokenKind::Plus)),
                '*' => tokens.push(self.single(TokenKind::Star)),
                '/' => tokens.push(self.single(TokenKind::Slash)),
                '-' => {
                    self.advance();
                    if self.peek() == Some('>') {
                        self.advance();
                        tokens.push(Token { kind: TokenKind::Arrow });
                    } else {
                        tokens.push(Token { kind: TokenKind::Minus });
                    }
                }
                ch if ch.is_ascii_digit() => tokens.push(Token { kind: self.number()? }),
                ch if is_ident_start(ch) => tokens.push(Token { kind: self.identifier() }),
                _ => {
                    return Err(CompileError {
                        message: format!("unexpected character `{ch}`"),
                    });
                }
            }
        }
        tokens.push(Token { kind: TokenKind::Eof });
        Ok(tokens)
    }

    fn single(&mut self, kind: TokenKind) -> Token {
        self.advance();
        Token { kind }
    }

    fn number(&mut self) -> Result<TokenKind, CompileError> {
        if self.peek() == Some('0') && self.peek_next() == Some('x') {
            self.advance();
            self.advance();
            let start = self.offset;
            while self.peek().is_some_and(|ch| ch.is_ascii_hexdigit()) {
                self.advance();
            }
            if self.offset == start {
                return Err(CompileError {
                    message: "expected hexadecimal digits after `0x`".to_string(),
                });
            }
            let text: String = self.chars[start..self.offset].iter().collect();
            let value = i32::from_str_radix(&text, 16).map_err(|_| CompileError {
                message: format!("integer literal `0x{text}` does not fit i32"),
            })?;
            return Ok(TokenKind::Int(value));
        }

        let start = self.offset;
        while self.peek().is_some_and(|ch| ch.is_ascii_digit()) {
            self.advance();
        }
        let text: String = self.chars[start..self.offset].iter().collect();
        let value = text.parse::<i32>().map_err(|_| CompileError {
            message: format!("integer literal `{text}` does not fit i32"),
        })?;
        Ok(TokenKind::Int(value))
    }

    fn identifier(&mut self) -> TokenKind {
        let start = self.offset;
        while self.peek().is_some_and(is_ident_continue) {
            self.advance();
        }
        let text: String = self.chars[start..self.offset].iter().collect();
        match text.as_str() {
            "fn" => TokenKind::Fn,
            "return" => TokenKind::Return,
            "unsafe" => TokenKind::Unsafe,
            "mmio" => TokenKind::Mmio,
            "i32" => TokenKind::I32,
            _ => TokenKind::Ident(text),
        }
    }

    fn peek(&self) -> Option<char> {
        self.chars.get(self.offset).copied()
    }

    fn peek_next(&self) -> Option<char> {
        self.chars.get(self.offset + 1).copied()
    }

    fn advance(&mut self) {
        self.offset += 1;
    }
}

fn is_ident_start(ch: char) -> bool {
    ch.is_ascii_alphabetic() || ch == '_'
}

fn is_ident_continue(ch: char) -> bool {
    is_ident_start(ch) || ch.is_ascii_digit()
}
```

- [ ] **Step 4: Run the lexer test and verify GREEN**

Run:

```bash
cargo test --manifest-path native/ckl-compiler/Cargo.toml lexer_recognizes_seed_language_tokens
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add native/ckl-compiler/src/lib.rs native/ckl-compiler/tests/compiler_seed.rs
git commit -m "Add lexer for rust-like language seed"
```

## Task 3: Add Parser For Main Function, Unsafe Blocks, Returns, And MMIO Calls

**Files:**
- Modify: `native/ckl-compiler/src/lib.rs`
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [ ] **Step 1: Write parser tests through compile errors and image shape**

Append to `native/ckl-compiler/tests/compiler_seed.rs`:

```rust
#[test]
fn parser_accepts_i32_main_with_unsafe_mmio_and_return() {
    let image = compile(
        "fn main() -> i32 { unsafe { mmio<i32>(0x10000100).store(79); } return 0; }",
    )
    .unwrap();

    assert_eq!(image.language_version, "ckm-seed-0");
    assert_eq!(image.memory_size, 1024);
    assert_eq!(image.entry_function_index, 0);
    assert_eq!(image.functions.single().name, "main");
}

#[test]
fn parser_accepts_unit_main_without_return_annotation() {
    let image = compile("fn main() { return; }").unwrap();

    assert_eq!(image.functions.single().name, "main");
}

#[test]
fn parser_rejects_void_keyword() {
    let error = compile("fn main() -> void { return; }").unwrap_err();

    assert!(error.message.contains("expected `i32`"), "{error:?}");
}
```

- [ ] **Step 2: Run parser tests and verify RED**

Run:

```bash
cargo test --manifest-path native/ckl-compiler/Cargo.toml parser_
```

Expected: FAIL because `compile` still returns "compiler seed is not implemented yet" for valid functions.

- [ ] **Step 3: Implement AST, parser skeleton, and compile pipeline**

Replace the body of `compile` with a lexer/parser/codegen pipeline. Keep it minimal:

- Parse only one `fn main`.
- Parse optional `-> i32`; no annotation means unit.
- Parse `return`, `unsafe` blocks, and method-call statements.
- Parse arithmetic expressions with `+ - * /`.
- Parse `mmio<i32>(expr).store(expr)` and `mmio<i32>(expr).load()`.
- Generate `Image` with language version `"ckm-seed-0"` and memory size `1024`.
- For valid `fn main() { return; }`, emit `ReturnUnit`.
- For valid `fn main() -> i32 { return 0; }`, emit `I32Const` + `ReturnI32`.
- For MMIO store, emit `AddrConst` + value expression + `Store32`.

Implementation may keep all types private except `compile`, `CompileError`, `lex`, `Token`, and `TokenKind`.

- [ ] **Step 4: Run parser tests and verify GREEN**

Run:

```bash
cargo test --manifest-path native/ckl-compiler/Cargo.toml parser_
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add native/ckl-compiler/src/lib.rs native/ckl-compiler/tests/compiler_seed.rs
git commit -m "Parse rust-like bare-metal seed programs"
```

## Task 4: Add Codegen Tests For Arithmetic And MMIO

**Files:**
- Modify: `native/ckl-compiler/src/lib.rs`
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [ ] **Step 1: Write arithmetic codegen test**

Append:

```rust
use ckl_vm::low_image::Instruction;

#[test]
fn compiler_emits_precedence_correct_i32_arithmetic() {
    let image = compile("fn main() -> i32 { return 1 + 2 * 3; }").unwrap();
    let instructions = &image.functions.single().instructions;

    assert!(instructions.iter().any(|instruction| matches!(instruction, Instruction::I32Mul { .. })));
    assert!(instructions.iter().any(|instruction| matches!(instruction, Instruction::I32Add { .. })));
    assert!(matches!(instructions.last(), Some(Instruction::ReturnI32 { .. })));
}
```

- [ ] **Step 2: Write MMIO load/store codegen test**

Append:

```rust
#[test]
fn compiler_lowers_unsafe_mmio_store_and_load() {
    let image = compile(
        "fn main() -> i32 { unsafe { mmio<i32>(0x10000100).store(79); } return mmio<i32>(0x10000100).load(); }",
    )
    .unwrap();
    let instructions = &image.functions.single().instructions;

    assert!(instructions.iter().any(|instruction| matches!(instruction, Instruction::Store32 { .. })));
    assert!(instructions.iter().any(|instruction| matches!(instruction, Instruction::Load32 { .. })));
    assert!(matches!(instructions.last(), Some(Instruction::ReturnI32 { .. })));
}
```

- [ ] **Step 3: Run codegen tests and verify RED**

Run:

```bash
cargo test --manifest-path native/ckl-compiler/Cargo.toml compiler_emits_precedence_correct_i32_arithmetic
cargo test --manifest-path native/ckl-compiler/Cargo.toml compiler_lowers_unsafe_mmio_store_and_load
```

Expected: FAIL if arithmetic/MMIO expression lowering is incomplete. If both tests already pass, record that Task 3 covered this behavior and continue.

- [ ] **Step 4: Implement missing codegen and verify GREEN**

If Step 3 failed, add only the missing lowering required by the failing tests:

- `I32Add`, `I32Sub`, `I32Mul`, `I32Div` for arithmetic expressions;
- `Store32` for `mmio<i32>(addr).store(value)`;
- `Load32` for `mmio<i32>(addr).load()`;
- `ReturnI32` for `return expr;`.

Run the same two commands again.

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add native/ckl-compiler/src/lib.rs native/ckl-compiler/tests/compiler_seed.rs
git commit -m "Lower rust-like seed arithmetic and MMIO"
```

## Task 5: Add Diagnostics For Unsafe And Return Rules

**Files:**
- Modify: `native/ckl-compiler/src/lib.rs`
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [ ] **Step 1: Write diagnostics tests**

Append:

```rust
#[test]
fn compiler_rejects_mmio_outside_unsafe() {
    let error = compile("fn main() -> i32 { mmio<i32>(0x10000100).store(79); return 0; }").unwrap_err();

    assert!(error.message.contains("MMIO access requires `unsafe`"), "{error:?}");
}

#[test]
fn compiler_rejects_missing_i32_return() {
    let error = compile("fn main() -> i32 { unsafe { mmio<i32>(0x10000100).store(79); } }").unwrap_err();

    assert!(error.message.contains("missing return in `i32` function"), "{error:?}");
}

#[test]
fn compiler_rejects_return_value_in_unit_main() {
    let error = compile("fn main() { return 1; }").unwrap_err();

    assert!(error.message.contains("unit function cannot return a value"), "{error:?}");
}
```

- [ ] **Step 2: Run diagnostics tests and verify RED**

Run each command:

```bash
cargo test --manifest-path native/ckl-compiler/Cargo.toml compiler_rejects_mmio_outside_unsafe
cargo test --manifest-path native/ckl-compiler/Cargo.toml compiler_rejects_missing_i32_return
cargo test --manifest-path native/ckl-compiler/Cargo.toml compiler_rejects_return_value_in_unit_main
```

Expected: FAIL if safety/return diagnostics are incomplete. If all tests already pass, record that Task 3 covered this behavior and continue.

- [ ] **Step 3: Implement missing diagnostics and verify GREEN**

If Step 2 failed, add only the missing checks required by the failing tests:

- MMIO constructor/method use must require `unsafe`;
- `fn main() -> i32` must reject `return;`;
- `fn main() -> i32` must reject missing return;
- `fn main()` must reject `return expr;`.

Run the same three commands again.

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add native/ckl-compiler/src/lib.rs native/ckl-compiler/tests/compiler_seed.rs
git commit -m "Report seed compiler safety diagnostics"
```

## Task 6: Add End-To-End ComputerMachine Firmware Test

**Files:**
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [ ] **Step 1: Write end-to-end test**

Append:

```rust
use ckl_vm::computer_abi;
use ckl_vm::computer_machine::ComputerMachine;
use ckl_vm::low_image_runner::LowImageSignal;

#[test]
fn compiled_seed_program_runs_on_computer_machine() {
    let source = format!(
        "fn main() -> i32 {{ unsafe {{ mmio<i32>({}).store({}); mmio<i32>({}).store(79); mmio<i32>({}).store(75); mmio<i32>({}).store({}); }} return 0; }}",
        computer_abi::CONTROL_STATUS,
        computer_abi::STATUS_BOOTING,
        computer_abi::DEBUG_WRITE,
        computer_abi::DEBUG_WRITE,
        computer_abi::CONTROL_STATUS,
        computer_abi::STATUS_READY,
    );
    let image = compile(&source).unwrap();
    let mut machine = ComputerMachine::new(1024).unwrap();
    let cpu_id = machine.spawn_boot_cpu(image, 1_000_000).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltI32(0),
    );
    assert_eq!(machine.debug_output_string(), "OK");
    assert_eq!(machine.control_status(), computer_abi::STATUS_HALTED);
    assert_eq!(machine.exit_code(), 0);
    assert_eq!(machine.panic_code(), 0);
}
```

- [ ] **Step 2: Run end-to-end test and verify RED**

Run:

```bash
cargo test --manifest-path native/ckl-compiler/Cargo.toml compiled_seed_program_runs_on_computer_machine
```

Expected: FAIL if the generated program does not yet run correctly on `ComputerMachine`. If it already passes, record that previous tasks covered the pipeline and continue.

- [ ] **Step 3: Fix missing end-to-end behavior and verify GREEN**

If Step 2 failed, fix only the missing behavior required by the end-to-end test. Re-run:

```bash
cargo test --manifest-path native/ckl-compiler/Cargo.toml compiled_seed_program_runs_on_computer_machine
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add native/ckl-compiler/tests/compiler_seed.rs native/ckl-compiler/src/lib.rs
git commit -m "Run rust-like seed firmware on ComputerMachine"
```

## Task 7: Document Rust-Like Seed Direction

**Files:**
- Modify: `docs/superpowers/todos/2026-05-12-low-vm-shared-ram-ckl-os-research-note.md`

- [ ] **Step 1: Append note**

Append:

```markdown
## 2026-05-13 Update: Rust-Like Language Seed

The experimental compiler direction no longer targets CKL compatibility. The branch now starts a new Rust-like bare-metal language seed written in Rust.

The first language slice intentionally supports only one `main` function, `i32` returns, unit `fn main()`, integer arithmetic, `unsafe` blocks, and typed MMIO capability calls such as `mmio<i32>(addr).store(value)`.

This keeps the experiment focused on source-to-low-image-to-machine execution before adding variables, ownership, borrowing, RAII, modules, strings, heap allocation, or device-safe wrappers.
```

- [ ] **Step 2: Verify note**

Run:

```bash
rg -n "Rust-Like Language Seed|no longer targets CKL compatibility|typed MMIO capability" docs/superpowers/todos/2026-05-12-low-vm-shared-ram-ckl-os-research-note.md
```

Expected: all phrases appear.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/todos/2026-05-12-low-vm-shared-ram-ckl-os-research-note.md
git commit -m "Document rust-like language seed direction"
```

## Task 8: Final Verification

**Files:**
- Verify only.

- [ ] **Step 1: Run compiler tests**

Run:

```bash
cargo test --manifest-path native/ckl-compiler/Cargo.toml
```

Expected: PASS.

- [ ] **Step 2: Run VM tests**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml
```

Expected: PASS.

- [ ] **Step 3: Run formatting checks**

Run:

```bash
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
cargo fmt --manifest-path native/ckl-vm/Cargo.toml --check
```

Expected: both PASS.

- [ ] **Step 4: Run diff check and status**

Run:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` passes and `git status --short` has no output after all commits.
