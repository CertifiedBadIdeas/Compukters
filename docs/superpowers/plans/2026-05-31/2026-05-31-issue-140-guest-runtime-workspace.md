# Guest Runtime Workspace Implementation Plan

> Issue: [#140](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/140)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the first guest Rust workspace boundary with shared K16 ABI constants and compiler runtime memory helpers.

**Architecture:** Guest software lives under `guest/`, separate from host-side `native/` crates. `guest/k16-abi` contains no-std machine constants and typed MMIO helpers. `guest/k16-rt` contains no-std compiler/runtime helpers such as `memcpy`, `memmove`, `memset`, and `memcmp`; BIOS, bootloader, and kernel crates will link it instead of duplicating those helpers.

**Tech Stack:** Rust 2021, Cargo workspace, `#![no_std]`, Rust unit tests through `cargo test --manifest-path guest/Cargo.toml`.

---

### Task 1: Scaffold Guest ABI And Runtime Crates

**Files:**
- Create: `guest/Cargo.toml`
- Create: `guest/k16-abi/Cargo.toml`
- Create: `guest/k16-abi/src/lib.rs`
- Create: `guest/k16-rt/Cargo.toml`
- Create: `guest/k16-rt/src/lib.rs`

- [x] **Step 1: Write failing runtime helper tests**

Create unit tests in `guest/k16-rt/src/lib.rs` that call `k16_memcpy`, `k16_memmove`, `k16_memset`, and `k16_memcmp` before those helpers exist.

- [x] **Step 2: Run tests to verify RED**

Run: `cargo test --manifest-path guest/Cargo.toml`

Expected: FAIL because `k16_memcpy`, `k16_memmove`, `k16_memset`, and `k16_memcmp` are undefined.

- [x] **Step 3: Implement minimal helpers**

Implement byte-wise no-std memory helpers and C ABI wrappers:

```rust
pub unsafe fn k16_memcpy(dst: *mut u8, src: *const u8, n: usize) -> *mut u8;
pub unsafe fn k16_memmove(dst: *mut u8, src: *const u8, n: usize) -> *mut u8;
pub unsafe fn k16_memset(dst: *mut u8, value: i32, n: usize) -> *mut u8;
pub unsafe fn k16_memcmp(lhs: *const u8, rhs: *const u8, n: usize) -> i32;
```

Export `memcpy`, `memmove`, `memset`, and `memcmp` for non-test guest builds.

- [x] **Step 4: Run tests to verify GREEN**

Run: `cargo test --manifest-path guest/Cargo.toml`

Expected: PASS for `k16-abi` and `k16-rt`.

- [ ] **Step 5: Commit**

```bash
git add guest docs/superpowers/plans/2026-05-31/2026-05-31-issue-140-guest-runtime-workspace.md
git commit -m "feat(guest): add k16 runtime helpers"
```
