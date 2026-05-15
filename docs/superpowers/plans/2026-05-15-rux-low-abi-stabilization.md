# Rux Low ABI Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the low VM image ABI numeric and Rux-specific by switching the magic to `RUXI`, resetting the low image format version to `1`, removing the language-version string from the low image payload, and making public function register counts `u16`.

**Architecture:** The serialized low image ABI is identified by magic bytes plus a numeric format version. The public low image model mirrors serialized ABI field widths, while the runner widens register counts and register ids to `usize` only in predecoded/runtime structures.

**Tech Stack:** Rust, `rux-vm`, `rux-compiler`, Cargo tests.

---

### Task 1: Add RED tests for the new low image header and public model

**Files:**
- Modify: `native/rux-vm/tests/low_image_decode.rs`
- Modify: `native/rux-compiler/tests/compiler_seed.rs`

- [ ] **Step 1: Update decode tests to expect `RUXI` + version `1` and no `language_version` field**

Change tests that currently assert `image.language_version` so they no longer read that field. Update the helper image bytes to start with:

```rust
out.extend_from_slice(b"RUXI");
out.push(1);
```

Expected new assertions:

```rust
assert_eq!(image.memory_size, 128);
assert_eq!(image.functions[0].register_count, 3u16);
```

- [ ] **Step 2: Update compiler tests to expect `register_count` as `u16` and no language metadata**

Replace:

```rust
assert_eq!(image.language_version, "rux-0");
assert_eq!(function.register_count, 5);
```

with:

```rust
assert_eq!(function.register_count, 5u16);
```

- [ ] **Step 3: Run tests and verify RED**

Run:

```bash
cargo test
```

from `native/rux-vm`, then from `native/rux-compiler`.

Expected: tests fail because production code still expects `CKIM`, version `5`, `language_version`, and `usize` register counts.

### Task 2: Implement the low image ABI change

**Files:**
- Modify: `native/rux-vm/src/low_image.rs`
- Modify: `native/rux-vm/src/low_image_runner.rs`
- Modify: `native/rux-vm/src/computer_machine.rs`
- Modify: `native/rux-vm/src/microcontroller_machine.rs`
- Modify: `native/rux-vm/tests/low_image_runner.rs`
- Modify: `native/rux-compiler/src/codegen.rs`

- [ ] **Step 1: Replace low image header constants**

In `low_image.rs`, define:

```rust
pub const IMAGE_MAGIC: &[u8; 4] = b"RUXI";
pub const IMAGE_FORMAT_VERSION: u8 = 1;
```

Use those constants in `decode_image`.

- [ ] **Step 2: Remove low image `language_version`**

Remove:

```rust
pub language_version: String,
```

from `Image`, and remove `reader.string()?` from `decode_image`.

- [ ] **Step 3: Make public `Function.register_count` a `u16`**

Change:

```rust
pub register_count: usize,
```

to:

```rust
pub register_count: u16,
```

In `read_function`, keep the decoded `reader.u16()?` directly.

- [ ] **Step 4: Widen only inside runner/runtime**

Convert public `u16` register counts with `usize::from(function.register_count)` when allocating vectors, validating bounds, building `LowFunction`, and resizing call frames.

- [ ] **Step 5: Update Rux compiler image creation**

Remove `language_version: "rux-0".to_string(),` and set:

```rust
register_count: codegen.next_register,
```

- [ ] **Step 6: Update VM tests and fixtures**

Update in-memory images to omit `language_version`. Update any checked-in low image fixture bytes to the new `RUXI` version `1` payload.

### Task 3: Verify, document, and commit

**Files:**
- Create: `docs/superpowers/specs/2026-05-15-rux-low-abi-v1-design.md`

- [ ] **Step 1: Write ABI spec**

Document:

```text
magic = RUXI
image_format_version = 1
no language_version field
register id = u16
function register_count = u16
runner may widen ids/counts to usize internally
```

- [ ] **Step 2: Run full Rust checks for touched crates**

Run:

```bash
cargo test
```

from `native/rux-vm`, then from `native/rux-compiler`.

Expected: both commands exit `0`.

- [ ] **Step 3: Commit**

Run:

```bash
git add native/rux-vm native/rux-compiler docs/superpowers
git commit -m "feat: stabilize rux low image abi"
```
