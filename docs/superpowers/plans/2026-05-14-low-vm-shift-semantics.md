# Low VM Shift Semantics Implementation Plan

**Goal:** Replace masked wrapping shift behavior with explicit unbounded 32-bit semantics and add unsigned shift instructions for `u32`.

**Architecture:** Low VM keeps raw 32-bit register storage. Signed and unsigned right shifts diverge at the instruction level. Shift counts outside `0..32` are handled explicitly by the runner.

---

### Task 1: Add Failing Tests

**Files:**
- Modify: `native/ckl-vm/tests/low_image_runner.rs`
- Modify: `native/ckl-vm/tests/low_image_decode.rs`
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [ ] Add runner tests for unbounded signed shifts and unsigned logical shifts.
- [ ] Add decoder tests for `U32Shl` tag `29` and `U32Shr` tag `30`.
- [ ] Add compiler tests for `u32` shift lowering and end-to-end semantics.
- [ ] Run targeted tests and confirm RED.

### Task 2: Implement Low VM Shift Instructions

**Files:**
- Modify: `native/ckl-vm/src/low_image.rs`
- Modify: `native/ckl-vm/src/low_image_runner.rs`

- [ ] Add `Instruction::U32Shl` and `Instruction::U32Shr`.
- [ ] Add decoder tags `29` and `30`.
- [ ] Add validation, operation lowering, immediate lowering, liveness, and execution.
- [ ] Replace `I32Shl` / `I32Shr` execution with explicit unbounded helper semantics.

### Task 3: Implement Compiler Lowering

**Files:**
- Modify: `native/ckl-compiler/src/codegen.rs`

- [ ] Lower `u32 << u32` to `U32Shl`.
- [ ] Lower `u32 >> u32` to `U32Shr`.
- [ ] Keep existing raw-word instructions for `u32` arithmetic and bitwise operations where signedness is irrelevant.

### Task 4: Verify And Commit

- [ ] Run `cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check`.
- [ ] Run `cargo fmt --manifest-path native/ckl-vm/Cargo.toml --check`.
- [ ] Run `cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml`.
- [ ] Run `cargo test --offline --manifest-path native/ckl-vm/Cargo.toml`.
- [ ] Commit docs and implementation.
