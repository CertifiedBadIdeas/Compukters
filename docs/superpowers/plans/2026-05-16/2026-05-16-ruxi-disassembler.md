# RUXI Disassembler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a small `.ruxi` disassembler and a short repo-local CLI wrapper.

**Architecture:** `rux-vm` owns image decoding and will expose text disassembly. `rux-compiler` owns developer-facing CLI binaries, so `rux-disasm` lives next to `rux-emit`. A root `tools/rux` helper dispatches short commands to Cargo without changing the frozen RUXI ABI.

**Tech Stack:** Rust 2021, Cargo integration tests, shell wrapper.

---

### Task 1: Library Disassembler

**Files:**
- Create: `native/rux-vm/src/low_disasm.rs`
- Modify: `native/rux-vm/src/lib.rs`
- Test: `native/rux-vm/tests/low_image_disasm.rs`

- [ ] **Step 1: Write a failing test**

Create a test that builds a small `Image` with constants, arithmetic, a call, and returns. Assert the output contains the RUXI header, memory section summary, function metadata, and formatted instructions.

- [ ] **Step 2: Run the test and verify it fails**

Run: `cargo test --manifest-path native/rux-vm/Cargo.toml low_image_disasm`

Expected: compile failure because `rux_vm::low_disasm` does not exist.

- [ ] **Step 3: Implement minimal disassembler**

Add `disassemble_image(&Image) -> String` and `disassemble_bytes(&[u8]) -> Result<String, ImageError>`. Format all current `Instruction` variants.

- [ ] **Step 4: Run the test and verify it passes**

Run: `cargo test --manifest-path native/rux-vm/Cargo.toml low_image_disasm`

Expected: pass.

### Task 2: CLI Binary And Wrapper

**Files:**
- Create: `native/rux-compiler/src/bin/rux-disasm.rs`
- Create: `native/rux-compiler/tests/rux_disasm_cli.rs`
- Create: `tools/rux`

- [ ] **Step 1: Write a failing CLI test**

Create a Cargo integration test that encodes a small `Image`, invokes `rux-disasm`, and checks stdout.

- [ ] **Step 2: Run the test and verify it fails**

Run: `cargo test --manifest-path native/rux-compiler/Cargo.toml rux_disasm_cli`

Expected: compile or test failure because the binary is missing.

- [ ] **Step 3: Implement `rux-disasm` and `tools/rux`**

`rux-disasm <input.ruxi>` reads bytes, decodes/disassembles through `rux-vm`, prints to stdout, and exits with code `1` on read/decode errors or `2` on usage errors.

`tools/rux` supports:

```bash
tools/rux emit <input.rx> <output.ruxi>
tools/rux disasm <input.ruxi>
```

- [ ] **Step 4: Run the CLI test and verify it passes**

Run: `cargo test --manifest-path native/rux-compiler/Cargo.toml rux_disasm_cli`

Expected: pass.

### Task 3: Verification And Commit

**Files:**
- All changed files.

- [ ] **Step 1: Run focused Rust tests**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml low_image_disasm
cargo test --manifest-path native/rux-compiler/Cargo.toml rux_disasm_cli
```

- [ ] **Step 2: Smoke-test wrapper**

Run:

```bash
tools/rux emit native/rux-compiler/examples/firmware/echo_live.rx /tmp/echo_live.ruxi
tools/rux disasm /tmp/echo_live.ruxi
```

- [ ] **Step 3: Check diff**

Run: `git diff --check`

- [ ] **Step 4: Commit**

Commit message: `feat: add ruxi disassembler`
