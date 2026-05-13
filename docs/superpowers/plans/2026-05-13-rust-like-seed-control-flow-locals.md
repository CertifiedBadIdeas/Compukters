# Rust-Like Seed Control Flow And Locals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the Rust-like seed compiler with mutable `i32` locals, assignment, comparisons, `if`, and `while`.

**Architecture:** Keep the seed compiler in `native/ckl-compiler/src/lib.rs` for now. Extend the lexer and parser, add a small local symbol table inside codegen, lower control flow directly to `ckl_vm::low_image::Instruction`, and keep emitting a single-function `low_image::Image`.

**Tech Stack:** Rust 2021, `ckl-vm` path dependency, Cargo integration tests, `low_image::Instruction`, `ComputerMachine`.

---

## File Structure

- Modify: `native/ckl-compiler/src/lib.rs`
  - Add lexer tokens.
  - Extend AST with locals, assignment, `if`, `while`, variables, and comparisons.
  - Add register-backed local symbol table.
  - Add jump patching helpers.
  - Add conservative block return analysis.
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`
  - Add focused lexer, codegen, diagnostics, and end-to-end tests.
- Modify: `docs/superpowers/specs/2026-05-13-rust-like-seed-control-flow-locals-design.md`
  - Update implementation status after the slice is complete.

## Task 1: Add Lexer Tokens For Locals And Control Flow

**Files:**
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`
- Modify: `native/ckl-compiler/src/lib.rs`

- [ ] **Step 1: Write the failing lexer test**

Append this test to `native/ckl-compiler/tests/compiler_seed.rs`:

```rust
#[test]
fn lexer_recognizes_locals_control_flow_and_comparison_tokens() {
    let tokens = lex("let mut i: i32 = 0; while i <= 3 { if i != 2 { i = i + 1; } else { i = i + 1; } }")
        .unwrap();
    let kinds: Vec<TokenKind> = tokens.into_iter().map(|token| token.kind).collect();

    assert_eq!(
        kinds,
        vec![
            TokenKind::Let,
            TokenKind::Mut,
            TokenKind::Ident("i".to_string()),
            TokenKind::Colon,
            TokenKind::I32,
            TokenKind::Equal,
            TokenKind::Int(0),
            TokenKind::Semicolon,
            TokenKind::While,
            TokenKind::Ident("i".to_string()),
            TokenKind::LessEqual,
            TokenKind::Int(3),
            TokenKind::LeftBrace,
            TokenKind::If,
            TokenKind::Ident("i".to_string()),
            TokenKind::BangEqual,
            TokenKind::Int(2),
            TokenKind::LeftBrace,
            TokenKind::Ident("i".to_string()),
            TokenKind::Equal,
            TokenKind::Ident("i".to_string()),
            TokenKind::Plus,
            TokenKind::Int(1),
            TokenKind::Semicolon,
            TokenKind::RightBrace,
            TokenKind::Else,
            TokenKind::LeftBrace,
            TokenKind::Ident("i".to_string()),
            TokenKind::Equal,
            TokenKind::Ident("i".to_string()),
            TokenKind::Plus,
            TokenKind::Int(1),
            TokenKind::Semicolon,
            TokenKind::RightBrace,
            TokenKind::RightBrace,
            TokenKind::Eof,
        ]
    );
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml lexer_recognizes_locals_control_flow_and_comparison_tokens
```

Expected: FAIL because `TokenKind::Let`, `Mut`, `If`, `Else`, `While`, `Colon`, `Equal`, `BangEqual`, and `LessEqual` do not exist.

- [ ] **Step 3: Add token variants**

In `native/ckl-compiler/src/lib.rs`, extend `TokenKind`:

```rust
pub enum TokenKind {
    Fn,
    Return,
    Unsafe,
    Mmio,
    Let,
    Mut,
    If,
    Else,
    While,
    I32,
    Ident(String),
    Int(i64),
    Arrow,
    LeftParen,
    RightParen,
    LeftBrace,
    RightBrace,
    Less,
    LessEqual,
    Greater,
    GreaterEqual,
    Equal,
    EqualEqual,
    BangEqual,
    Colon,
    Dot,
    Semicolon,
    Comma,
    Plus,
    Minus,
    Star,
    Slash,
    Eof,
}
```

- [ ] **Step 4: Add keyword and punctuation lexing**

Update keyword matching:

```rust
let kind = match text {
    "fn" => TokenKind::Fn,
    "return" => TokenKind::Return,
    "unsafe" => TokenKind::Unsafe,
    "mmio" => TokenKind::Mmio,
    "let" => TokenKind::Let,
    "mut" => TokenKind::Mut,
    "if" => TokenKind::If,
    "else" => TokenKind::Else,
    "while" => TokenKind::While,
    "i32" => TokenKind::I32,
    _ => TokenKind::Ident(text.to_string()),
};
```

Update punctuation lexing before single-character fallback:

```rust
b'<' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
    offset += 2;
    tokens.push(Token {
        kind: TokenKind::LessEqual,
        offset: offset - 2,
    });
    continue;
}
b'>' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
    offset += 2;
    tokens.push(Token {
        kind: TokenKind::GreaterEqual,
        offset: offset - 2,
    });
    continue;
}
b'=' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
    offset += 2;
    tokens.push(Token {
        kind: TokenKind::EqualEqual,
        offset: offset - 2,
    });
    continue;
}
b'!' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
    offset += 2;
    tokens.push(Token {
        kind: TokenKind::BangEqual,
        offset: offset - 2,
    });
    continue;
}
```

Add single-character cases:

```rust
b':' => TokenKind::Colon,
b'=' => TokenKind::Equal,
```

Update `TokenKind::name()` for all new variants.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml lexer_recognizes_locals_control_flow_and_comparison_tokens
```

Expected: PASS.

- [ ] **Step 6: Format and commit**

Run:

```bash
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
```

Then commit:

```bash
git add native/ckl-compiler/src/lib.rs native/ckl-compiler/tests/compiler_seed.rs
git commit -m "Add rust language seed control flow tokens"
```

## Task 2: Parse Locals, Assignment, Branches, Loops, Variables, And Comparisons

**Files:**
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`
- Modify: `native/ckl-compiler/src/lib.rs`

- [ ] **Step 1: Write failing parser/codegen smoke tests**

Append these tests:

```rust
#[test]
fn compile_lowers_local_declaration_and_return() {
    let image = compile("fn main() -> i32 { let mut i: i32 = 7; return i; }").unwrap();
    let function = &image.functions[0];

    assert_eq!(
        function.instructions,
        vec![
            Instruction::I32Const { dst: 1, value: 7 },
            Instruction::I32Move { dst: 0, src: 1 },
            Instruction::ReturnI32 { src: 0 },
        ]
    );
}

#[test]
fn compile_lowers_assignment_to_local() {
    let image = compile("fn main() -> i32 { let mut i: i32 = 1; i = i + 2; return i; }").unwrap();
    let function = &image.functions[0];

    assert_eq!(
        function.instructions,
        vec![
            Instruction::I32Const { dst: 1, value: 1 },
            Instruction::I32Move { dst: 0, src: 1 },
            Instruction::I32Const { dst: 2, value: 2 },
            Instruction::I32Add {
                dst: 3,
                lhs: 0,
                rhs: 2,
            },
            Instruction::I32Move { dst: 0, src: 3 },
            Instruction::ReturnI32 { src: 0 },
        ]
    );
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compile_lowers_
```

Expected: FAIL because parser does not understand `let`, variable reads, or assignment.

- [ ] **Step 3: Extend AST**

Update `Statement`:

```rust
enum Statement {
    Let {
        name: String,
        initializer: Expr,
    },
    Assign {
        name: String,
        value: Expr,
    },
    If {
        condition: Expr,
        then_branch: Vec<Statement>,
        else_branch: Option<Vec<Statement>>,
    },
    While {
        condition: Expr,
        body: Vec<Statement>,
    },
    Return(Option<Expr>),
    Unsafe(Vec<Statement>),
    Expr(Expr),
}
```

Update `Expr`:

```rust
enum Expr {
    Int(i64),
    Local(String),
    Mmio(Box<Expr>),
    MethodCall {
        receiver: Box<Expr>,
        method: String,
        args: Vec<Expr>,
    },
    Binary {
        op: BinaryOp,
        lhs: Box<Expr>,
        rhs: Box<Expr>,
    },
    Compare {
        op: CompareOp,
        lhs: Box<Expr>,
        rhs: Box<Expr>,
    },
}
```

Add:

```rust
enum CompareOp {
    Lt,
    Eq,
    Ne,
    Gt,
    Le,
    Ge,
}
```

- [ ] **Step 4: Parse new statements**

In `parse_statement`, add cases before generic expression statements:

```rust
if self.consume(TokenKind::Let) {
    self.expect(TokenKind::Mut)?;
    let name = self.take_ident()?;
    self.expect(TokenKind::Colon)?;
    self.expect(TokenKind::I32)?;
    self.expect(TokenKind::Equal)?;
    let initializer = self.parse_expr()?;
    self.expect(TokenKind::Semicolon)?;
    return Ok(Statement::Let { name, initializer });
}

if self.consume(TokenKind::If) {
    let condition = self.parse_expr()?;
    let then_branch = self.parse_block()?;
    let else_branch = if self.consume(TokenKind::Else) {
        Some(self.parse_block()?)
    } else {
        None
    };
    return Ok(Statement::If {
        condition,
        then_branch,
        else_branch,
    });
}

if self.consume(TokenKind::While) {
    let condition = self.parse_expr()?;
    let body = self.parse_block()?;
    return Ok(Statement::While { condition, body });
}

if let TokenKind::Ident(name) = self.peek().clone() {
    if self.peek_next() == &TokenKind::Equal {
        self.offset += 1;
        self.expect(TokenKind::Equal)?;
        let value = self.parse_expr()?;
        self.expect(TokenKind::Semicolon)?;
        return Ok(Statement::Assign { name, value });
    }
}
```

Add helper:

```rust
fn peek_next(&self) -> &TokenKind {
    self.tokens
        .get(self.offset + 1)
        .map(|token| &token.kind)
        .unwrap_or(&TokenKind::Eof)
}
```

- [ ] **Step 5: Parse comparisons and local reads**

Change `parse_expr`:

```rust
fn parse_expr(&mut self) -> Result<Expr, CompileError> {
    self.parse_comparison()
}
```

Add:

```rust
fn parse_comparison(&mut self) -> Result<Expr, CompileError> {
    let lhs = self.parse_add_sub()?;
    let op = if self.consume(TokenKind::Less) {
        Some(CompareOp::Lt)
    } else if self.consume(TokenKind::EqualEqual) {
        Some(CompareOp::Eq)
    } else if self.consume(TokenKind::BangEqual) {
        Some(CompareOp::Ne)
    } else if self.consume(TokenKind::Greater) {
        Some(CompareOp::Gt)
    } else if self.consume(TokenKind::LessEqual) {
        Some(CompareOp::Le)
    } else if self.consume(TokenKind::GreaterEqual) {
        Some(CompareOp::Ge)
    } else {
        None
    };

    if let Some(op) = op {
        let rhs = self.parse_add_sub()?;
        Ok(Expr::Compare {
            op,
            lhs: Box::new(lhs),
            rhs: Box::new(rhs),
        })
    } else {
        Ok(lhs)
    }
}
```

In `parse_primary`, add identifier reads:

```rust
if let Some(name) = self.take_ident_if_present() {
    return Ok(Expr::Local(name));
}
```

Add helper:

```rust
fn take_ident_if_present(&mut self) -> Option<String> {
    match self.tokens.get(self.offset) {
        Some(Token {
            kind: TokenKind::Ident(value),
            ..
        }) => {
            self.offset += 1;
            Some(value.clone())
        }
        _ => None,
    }
}
```

- [ ] **Step 6: Run focused tests and verify parser reaches codegen**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compile_lowers_
```

Expected: FAIL with codegen errors for unsupported statements or undeclared locals, not parser errors.

## Task 3: Add Register-Backed Local Codegen

**Files:**
- Modify: `native/ckl-compiler/src/lib.rs`

- [ ] **Step 1: Add local type and symbol table**

Add imports:

```rust
use std::collections::HashMap;
```

Add:

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ValueType {
    Unit,
    I32,
    Addr,
}

#[derive(Debug, Clone, Copy)]
struct Local {
    register: u16,
    ty: ValueType,
    mutable: bool,
}
```

Update `Codegen`:

```rust
struct Codegen {
    instructions: Vec<Instruction>,
    next_register: u16,
    return_type: ReturnType,
    unsafe_depth: usize,
    locals: HashMap<String, Local>,
}
```

Remove `saw_return`; Task 5 replaces it with block outcomes.

- [ ] **Step 2: Generate local declarations and assignment**

In `compile_statement`, add:

```rust
Statement::Let { name, initializer } => {
    if self.locals.contains_key(name) {
        return Err(CompileError {
            message: format!("duplicate local `{name}`"),
        });
    }
    let src = self.compile_i32_expr(initializer)?;
    let dst = self.alloc_register()?;
    self.instructions.push(Instruction::I32Move { dst, src });
    self.locals.insert(
        name.clone(),
        Local {
            register: dst,
            ty: ValueType::I32,
            mutable: true,
        },
    );
    Ok(())
}
Statement::Assign { name, value } => {
    let local = *self.locals.get(name).ok_or_else(|| CompileError {
        message: format!("assignment to undeclared local `{name}`"),
    })?;
    if !local.mutable {
        return Err(CompileError {
            message: format!("assignment to immutable local `{name}`"),
        });
    }
    if local.ty != ValueType::I32 {
        return Err(CompileError {
            message: format!("assignment to non-i32 local `{name}`"),
        });
    }
    let src = self.compile_i32_expr(value)?;
    self.instructions.push(Instruction::I32Move {
        dst: local.register,
        src,
    });
    Ok(())
}
```

- [ ] **Step 3: Generate local reads**

In `compile_expr`, add:

```rust
Expr::Local(name) => {
    let local = self.locals.get(name).ok_or_else(|| CompileError {
        message: format!("use of undeclared local `{name}`"),
    })?;
    match local.ty {
        ValueType::I32 => Ok(ExprValue::I32(local.register)),
        ValueType::Addr => Ok(ExprValue::Addr(local.register)),
        ValueType::Unit => Ok(ExprValue::Unit),
    }
}
```

- [ ] **Step 4: Run focused tests and verify GREEN**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compile_lowers_
```

Expected: PASS.

- [ ] **Step 5: Run existing compiler tests**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml
```

Expected: PASS.

- [ ] **Step 6: Format and commit**

Run:

```bash
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
```

Then commit:

```bash
git add native/ckl-compiler/src/lib.rs native/ckl-compiler/tests/compiler_seed.rs
git commit -m "Add rust language seed register locals"
```

## Task 4: Lower Comparisons, If, And While

**Files:**
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`
- Modify: `native/ckl-compiler/src/lib.rs`

- [ ] **Step 1: Write failing control-flow tests**

Append:

```rust
#[test]
fn compile_lowers_if_else_with_i32_equality() {
    let image = compile(
        "fn main() -> i32 {
            let mut i: i32 = 0;
            if i == 0 {
                return 1;
            } else {
                return 2;
            }
        }",
    )
    .unwrap();
    let instructions = &image.functions[0].instructions;

    assert!(matches!(instructions[2], Instruction::I32Const { value: 0, .. }));
    assert!(matches!(instructions[3], Instruction::I32Eq { .. }));
    assert!(matches!(instructions[4], Instruction::JumpIfFalse { .. }));
    assert!(instructions.iter().any(|instruction| matches!(instruction, Instruction::Jump { .. })));
    assert!(instructions.iter().any(|instruction| matches!(instruction, Instruction::ReturnI32 { .. })));
}

#[test]
fn compile_lowers_while_with_i32_less_than() {
    let image = compile(
        "fn main() -> i32 {
            let mut i: i32 = 0;
            while i < 3 {
                i = i + 1;
            }
            return i;
        }",
    )
    .unwrap();
    let instructions = &image.functions[0].instructions;

    assert!(instructions.iter().any(|instruction| matches!(instruction, Instruction::I32Lt { .. })));
    assert!(instructions.iter().any(|instruction| matches!(instruction, Instruction::JumpIfFalse { .. })));
    assert!(instructions.iter().any(|instruction| matches!(instruction, Instruction::Jump { target: 2 })));
    assert!(matches!(instructions.last(), Some(Instruction::ReturnI32 { .. })));
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compile_lowers_
```

Expected: FAIL because `Statement::If`, `Statement::While`, and `Expr::Compare` are not lowered.

- [ ] **Step 3: Add jump patch helpers**

Add methods to `Codegen`:

```rust
fn emit_jump_placeholder(&mut self) -> usize {
    let index = self.instructions.len();
    self.instructions.push(Instruction::Jump { target: usize::MAX });
    index
}

fn emit_jump_if_false_placeholder(&mut self, cond: u16) -> usize {
    let index = self.instructions.len();
    self.instructions.push(Instruction::JumpIfFalse {
        cond,
        target: usize::MAX,
    });
    index
}

fn patch_jump(&mut self, index: usize, target: usize) -> Result<(), CompileError> {
    match self.instructions.get_mut(index) {
        Some(Instruction::Jump { target: current }) => {
            *current = target;
            Ok(())
        }
        Some(Instruction::JumpIfFalse { target: current, .. }) => {
            *current = target;
            Ok(())
        }
        _ => Err(CompileError {
            message: format!("internal compiler error: instruction {index} is not a jump"),
        }),
    }
}
```

- [ ] **Step 4: Lower comparisons**

In `compile_expr`, add `Expr::Compare`:

```rust
Expr::Compare { op, lhs, rhs } => {
    let lhs = self.compile_i32_expr(lhs)?;
    let rhs = self.compile_i32_expr(rhs)?;
    self.compile_compare(*op, lhs, rhs)
}
```

Add:

```rust
fn compile_compare(
    &mut self,
    op: CompareOp,
    lhs: u16,
    rhs: u16,
) -> Result<ExprValue, CompileError> {
    match op {
        CompareOp::Lt => {
            let dst = self.alloc_register()?;
            self.instructions.push(Instruction::I32Lt { dst, lhs, rhs });
            Ok(ExprValue::I32(dst))
        }
        CompareOp::Eq => {
            let dst = self.alloc_register()?;
            self.instructions.push(Instruction::I32Eq { dst, lhs, rhs });
            Ok(ExprValue::I32(dst))
        }
        CompareOp::Ne => {
            let eq = self.alloc_register()?;
            self.instructions.push(Instruction::I32Eq { dst: eq, lhs, rhs });
            let zero = self.emit_i32_const(0)?;
            let dst = self.alloc_register()?;
            self.instructions.push(Instruction::I32Eq {
                dst,
                lhs: eq,
                rhs: zero,
            });
            Ok(ExprValue::I32(dst))
        }
        CompareOp::Gt => self.compile_compare(CompareOp::Lt, rhs, lhs),
        CompareOp::Le => self.compile_not_less_than(rhs, lhs),
        CompareOp::Ge => self.compile_not_less_than(lhs, rhs),
    }
}

fn compile_not_less_than(&mut self, lhs: u16, rhs: u16) -> Result<ExprValue, CompileError> {
    let lt = self.alloc_register()?;
    self.instructions.push(Instruction::I32Lt { dst: lt, lhs, rhs });
    let zero = self.emit_i32_const(0)?;
    let dst = self.alloc_register()?;
    self.instructions.push(Instruction::I32Eq {
        dst,
        lhs: lt,
        rhs: zero,
    });
    Ok(ExprValue::I32(dst))
}

fn emit_i32_const(&mut self, value: i32) -> Result<u16, CompileError> {
    let dst = self.alloc_register()?;
    self.instructions.push(Instruction::I32Const { dst, value });
    Ok(dst)
}
```

Replace duplicated integer literal code with `emit_i32_const(value)` if convenient.

- [ ] **Step 5: Lower `if` and `while`**

In `compile_statement`, add:

```rust
Statement::If {
    condition,
    then_branch,
    else_branch,
} => {
    let cond = self.compile_i32_expr(condition)?;
    let false_jump = self.emit_jump_if_false_placeholder(cond);
    self.compile_statements(then_branch)?;
    if let Some(else_branch) = else_branch {
        let end_jump = self.emit_jump_placeholder();
        let else_start = self.instructions.len();
        self.patch_jump(false_jump, else_start)?;
        self.compile_statements(else_branch)?;
        let end = self.instructions.len();
        self.patch_jump(end_jump, end)?;
    } else {
        let end = self.instructions.len();
        self.patch_jump(false_jump, end)?;
    }
    Ok(())
}
Statement::While { condition, body } => {
    let loop_start = self.instructions.len();
    let cond = self.compile_i32_expr(condition)?;
    let exit_jump = self.emit_jump_if_false_placeholder(cond);
    self.compile_statements(body)?;
    self.instructions.push(Instruction::Jump { target: loop_start });
    let loop_end = self.instructions.len();
    self.patch_jump(exit_jump, loop_end)?;
    Ok(())
}
```

- [ ] **Step 6: Run focused tests and verify GREEN**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compile_lowers_
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
git add native/ckl-compiler/src/lib.rs native/ckl-compiler/tests/compiler_seed.rs
git commit -m "Lower rust language seed branches and loops"
```

## Task 5: Add Return Outcome Analysis And Diagnostics

**Files:**
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`
- Modify: `native/ckl-compiler/src/lib.rs`

- [ ] **Step 1: Write failing diagnostics tests**

Append:

```rust
#[test]
fn compile_rejects_undeclared_local_read() {
    let error = compile("fn main() -> i32 { return missing; }").unwrap_err();

    assert!(
        error.message.contains("use of undeclared local `missing`"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_duplicate_local_declaration() {
    let error = compile("fn main() { let mut i: i32 = 0; let mut i: i32 = 1; }").unwrap_err();

    assert!(error.message.contains("duplicate local `i`"), "{error:?}");
}

#[test]
fn compile_rejects_assignment_to_undeclared_local() {
    let error = compile("fn main() { i = 1; }").unwrap_err();

    assert!(
        error.message.contains("assignment to undeclared local `i`"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_missing_return_after_if_without_else() {
    let error = compile("fn main() -> i32 { if 1 { return 1; } }").unwrap_err();

    assert!(
        error.message.contains("missing return in `i32` function"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_unreachable_statement_after_return() {
    let error = compile("fn main() -> i32 { return 1; let mut i: i32 = 2; }").unwrap_err();

    assert!(
        error.message.contains("unreachable statement after return"),
        "{error:?}"
    );
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compile_rejects_
```

Expected: at least the return-analysis tests fail until block outcomes are implemented.

- [ ] **Step 3: Add statement outcomes**

Add:

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BlockOutcome {
    FallsThrough,
    AlwaysReturns,
}

impl BlockOutcome {
    fn combines_with(self, next: BlockOutcome) -> BlockOutcome {
        match (self, next) {
            (BlockOutcome::AlwaysReturns, _) => BlockOutcome::AlwaysReturns,
            (_, BlockOutcome::AlwaysReturns) => BlockOutcome::AlwaysReturns,
            _ => BlockOutcome::FallsThrough,
        }
    }
}
```

Change:

```rust
fn compile_statements(&mut self, statements: &[Statement]) -> Result<BlockOutcome, CompileError> {
    let mut outcome = BlockOutcome::FallsThrough;
    for statement in statements {
        if outcome == BlockOutcome::AlwaysReturns {
            return Err(CompileError {
                message: "unreachable statement after return".to_string(),
            });
        }
        outcome = self.compile_statement(statement)?;
    }
    Ok(outcome)
}
```

Change `compile_statement` to return `Result<BlockOutcome, CompileError>`.

Return statements return `BlockOutcome::AlwaysReturns`.
Let, assignment, expression, unsafe, and while return `BlockOutcome::FallsThrough`.
An `if` with `else` returns `AlwaysReturns` only if both branches return.

For `unsafe`, use the nested block outcome only if it always returns:

```rust
Statement::Unsafe(statements) => {
    self.unsafe_depth += 1;
    let result = self.compile_statements(statements);
    self.unsafe_depth -= 1;
    result
}
```

- [ ] **Step 4: Enforce final return rule**

In `Codegen::compile`, replace `saw_return` logic with:

```rust
let outcome = codegen.compile_statements(&program.statements)?;
if outcome == BlockOutcome::FallsThrough {
    match codegen.return_type {
        ReturnType::Unit => codegen.instructions.push(Instruction::ReturnUnit),
        ReturnType::I32 => {
            return Err(CompileError {
                message: "missing return in `i32` function".to_string(),
            });
        }
    }
}
```

- [ ] **Step 5: Run focused tests and verify GREEN**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compile_rejects_
```

Expected: PASS.

- [ ] **Step 6: Run full compiler tests and commit**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
git add native/ckl-compiler/src/lib.rs native/ckl-compiler/tests/compiler_seed.rs
git commit -m "Validate rust language seed locals and returns"
```

## Task 6: Add ComputerMachine End-To-End Loop Test

**Files:**
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [ ] **Step 1: Write the e2e test**

Append:

```rust
#[test]
fn compiled_seed_loop_runs_on_computer_machine() {
    let image = compile(
        "fn main() -> i32 {
            let mut i: i32 = 0;
            while i < 2 {
                unsafe {
                    mmio<i32>(0x10000100).store(79 + i);
                }
                i = i + 1;
            }
            return i;
        }",
    )
    .unwrap();
    let mut machine = ComputerMachine::new(64 * 1024).unwrap();
    let cpu_id = machine.spawn_boot_cpu(image, 1_000_000).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltI32(2)
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
    assert_eq!(machine.exit_code(), 2);
    assert_eq!(machine.debug_output_bytes(), &[79, 80]);
}
```

- [ ] **Step 2: Run e2e test**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compiled_seed_loop_runs_on_computer_machine
```

Expected: PASS after Tasks 1-5.

- [ ] **Step 3: Commit**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
git add native/ckl-compiler/tests/compiler_seed.rs
git commit -m "Run rust language seed loops on computer machine"
```

## Task 7: Update Design Status

**Files:**
- Modify: `docs/superpowers/specs/2026-05-13-rust-like-seed-control-flow-locals-design.md`

- [ ] **Step 1: Add implementation status**

Append:

```markdown
## Implementation Status

Implemented in `native/ckl-compiler`:

- lexer tokens for locals, branching, loops, assignment, and comparisons;
- parser support for `let mut`, assignment, `if`, `else`, `while`, local reads, and comparison expressions;
- register-backed mutable `i32` locals;
- direct lowering of comparisons through existing low VM instructions;
- direct lowering of `if` and `while` through `Jump` and `JumpIfFalse`;
- conservative return/outcome analysis;
- diagnostics for undeclared locals, duplicate locals, undeclared assignment, missing i32 returns, unreachable statements, and MMIO outside `unsafe`;
- end-to-end loop firmware test on `ComputerMachine`.
```

- [ ] **Step 2: Commit**

Run:

```bash
git add docs/superpowers/specs/2026-05-13-rust-like-seed-control-flow-locals-design.md
git commit -m "Document rust language seed control flow implementation"
```

## Task 8: Final Verification

**Files:**
- No file changes expected.

- [ ] **Step 1: Run compiler tests**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml
```

Expected: PASS.

- [ ] **Step 2: Run compiler formatting check**

Run:

```bash
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
```

Expected: PASS with no diff.

- [ ] **Step 3: Run low VM tests**

Run:

```bash
cargo test --offline --manifest-path native/ckl-vm/Cargo.toml
```

Expected: PASS.

- [ ] **Step 4: Check git status**

Run:

```bash
git status --short
```

Expected: no output.
