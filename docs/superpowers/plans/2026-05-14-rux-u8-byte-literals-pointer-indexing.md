# Rux U8 Byte Literals Pointer Indexing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `u8`, byte string literals, and pointer indexing so Rux can manipulate byte buffers on the low VM.

**Architecture:** Add real `Load8` / `Store8` instructions to the low VM because byte indexing cannot be safely modeled with `Store32`. Extend Rux lexer/parser/AST/codegen with `u8`, `123u8`, `b"..."`, `expr[index]`, and `expr[index] = value;`. Byte string literals are emitted into `Image.rodata` and evaluate to a pointer capability with `u8` element type.

**Tech Stack:** Rust, `native/ckl-vm`, `native/ckl-compiler`, cargo tests.

---

### Task 1: Low VM Byte Memory Operations

**Files:**
- Modify: `native/ckl-vm/src/low_machine.rs`
- Modify: `native/ckl-vm/src/low_image.rs`
- Modify: `native/ckl-vm/src/low_image_runner.rs`

- [ ] Add failing VM tests for `MachineMemory::load_u8` / `store_u8`.
- [ ] Add `MemoryBus::load_u8` / `store_u8`.
- [ ] Add `Instruction::Load8` / `Instruction::Store8` with new image tags.
- [ ] Add executable operations, validation, dependency analysis, and interpreter execution for byte ops.
- [ ] Run `cargo test --offline --manifest-path native/ckl-vm/Cargo.toml`.
- [ ] Commit `feat: add low vm byte memory ops`.

### Task 2: Rux Syntax For U8 And Byte Strings

**Files:**
- Modify: `native/ckl-compiler/src/lexer.rs`
- Modify: `native/ckl-compiler/src/ast.rs`
- Modify: `native/ckl-compiler/src/parser.rs`
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [ ] Add failing lexer tests for `u8`, `123u8`, brackets, and `b"OK\n"`.
- [ ] Add failing parser/codegen tests for `let mut ch: u8 = 65u8; return ch as i32;`.
- [ ] Add `TokenKind::U8`, `TokenKind::IntU8`, `TokenKind::ByteString`, `LeftBracket`, and `RightBracket`.
- [ ] Add `TypeName::U8`, `ReturnType::U8`, `Expr::IntU8`, `Expr::ByteString`, and `Expr::Index`.
- [ ] Parse `u8`, `123u8`, byte string escape sequences, and postfix indexing.
- [ ] Run `cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml --test compiler_seed`.
- [ ] Commit `feat: add rux u8 syntax`.

### Task 3: Rux Pointer Indexing And Rodata Lowering

**Files:**
- Modify: `native/ckl-compiler/src/codegen.rs`
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [ ] Add failing end-to-end tests for `ptr<u8>(RAM_BASE)[i] = value`, `ptr<u8>(RAM_BASE)[i]`, and `b"OK"[i]`.
- [ ] Emit byte string literals into `Image.rodata` and return a `ptr<u8>` capability at the rodata address.
- [ ] Lower `ptr<u8>[index]` / `mmio<u8>[index]` load to `Load8` and store to `Store8`.
- [ ] Lower `ptr<i32/u32>[index]` / `mmio<i32/u32>[index]` with element-size scaling of 4 bytes and `Load32` / `Store32`.
- [ ] Keep pointer indexing inside `unsafe` for raw memory access.
- [ ] Run `cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml`.
- [ ] Run `cargo test --offline --manifest-path native/ckl-vm/Cargo.toml`.
- [ ] Commit `feat: add rux pointer indexing`.

### Task 4: Documentation And Runner Smoke

**Files:**
- Modify: `docs/superpowers/specs/2026-05-13-rust-like-bare-metal-language-seed-design.md`
- Modify: `native/ckl-compiler/examples/firmware/terminal.rx`

- [ ] Update implementation status for `u8`, byte strings, and indexing.
- [ ] Update the demo firmware to use a byte string loop instead of numeric character writes.
- [ ] Run `cargo run --offline --manifest-path native/ckl-compiler/Cargo.toml --bin rux-run -- native/ckl-compiler/examples/firmware/terminal.rx`.
- [ ] Commit `docs: document rux byte buffers`.
