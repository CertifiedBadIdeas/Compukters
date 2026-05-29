# Kernel Trap Syscall ABI Implementation Plan

> Issue: [#119](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/119)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first minimal userspace-to-kernel trap ABI for Rux16 init programs.

**Architecture:** Rux16 already has trap CSRs and trap-vector delivery in the VM. This slice exposes CSR read/write builtins to Rux source, lets the kernel install a trap handler before jumping to init, and lets init request kernel-owned output by deliberately triggering the trap path. The first syscall is intentionally one fixed request: validated init asks the kernel handler to print `INIT OK`.

**Tech Stack:** Rux compiler backend, Rux16 CSR instructions, Rux kernel/init examples, Rust boot-chain tests.

---

### Task 1: Add RED Tests for CSR Builtins

**Files:**
- Modify: `native/rux-compiler/tests/rux16_artifact_backend.rs`

- [x] Add a test that compiles Rux source using `rux16_write_csr(1u32, 0x4200u32)` inside `unsafe`.
- [x] Assert the disassembly contains `write_csr 1, r`.
- [x] Add a test that compiles `rux16_read_csr(2u32)` into a `u32` local.
- [x] Assert the disassembly contains `read_csr r`.
- [x] Run `cargo test --test rux16_artifact_backend csr`.

### Task 2: Implement CSR Builtins

**Files:**
- Modify: `native/rux-compiler/src/rux16_asm.rs`
- Modify: `native/rux-compiler/src/artifact.rs`

- [x] Add `read_csr(dst, csr)` and `write_csr(csr, src)` encoders.
- [x] Lower `unsafe { rux16_write_csr(csr, value); }` as a statement.
- [x] Lower `unsafe { let value: u32 = rux16_read_csr(csr); }` as a `u32` expression.
- [x] Require CSR numbers to be compile-time `u32` constants in `0..=15`.
- [x] Run `cargo test --test rux16_artifact_backend csr`.

### Task 3: Add Trap Kernel/Init Examples and Boot Test

**Files:**
- Create: `native/rux-compiler/examples/kernel/trap_init_loader.rx`
- Create: `native/rux-compiler/examples/init/trap_init.rx`
- Modify: `native/rux-compiler/tests/rux_volume_cli.rs`

- [x] Add a Rux source test that checks the kernel installs `TRAP_HANDLER_ADDR` with `rux16_write_csr(1u32, TRAP_HANDLER_ADDR)`.
- [x] Add a Rux source test that checks init validates `RINI` and calls `rux16_write_csr(2u32, 1u32)` for the first syscall.
- [x] Add a boot-chain test that installs the trap kernel and trap init into storage0.
- [x] Assert the display row is `INIT OK` after BIOS -> bootloader -> kernel -> init -> trap handler.
- [x] Run `cargo test --test rux_volume_cli trap_init`.

### Task 4: Verify and Commit

- [x] Run `cargo fmt -- --check`.
- [x] Run `cargo test --test rux16_artifact_backend csr`.
- [x] Run `cargo test --test rux_volume_cli trap_init`.
- [x] Run `cargo test --test rux_volume_cli`.
- [x] Run `cargo test`.
- [x] Commit with `feat(os): add first trap syscall ABI`.
- [x] Update and close `#119` if all acceptance criteria are covered.
