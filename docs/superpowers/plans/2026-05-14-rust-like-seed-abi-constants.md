# Rust-Like Seed ABI Constants Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose `rux_vm::computer_abi` constants as Rust-like seed compiler built-ins so firmware can avoid raw MMIO addresses.

**Architecture:** Keep the seed compiler in `native/rux-compiler/src/lib.rs`. Add a small `BuiltinConstant` resolver backed by `rux_vm::computer_abi`, resolve identifiers after locals and before undeclared-local errors, and lower built-ins directly to `AddrConst` or `I32Const`.

**Tech Stack:** Rust 2021, `rux-vm` path dependency, `computer_abi`, Cargo integration tests, `ComputerMachine`.

---

## File Structure

- Modify: `native/rux-compiler/src/lib.rs`
  - Import `rux_vm::computer_abi`.
  - Add `BuiltinConstant`.
  - Add `resolve_builtin_constant`.
  - Prevent local declarations from shadowing built-ins.
  - Resolve unknown identifiers as built-ins.
  - Reject wrong built-in type usage.
- Modify: `native/rux-compiler/tests/compiler_seed.rs`
  - Add codegen, diagnostics, and e2e tests.
- Modify: `docs/superpowers/specs/2026-05-14-rust-like-seed-abi-constants-design.md`
  - Add implementation status after code is complete.

## Task 1: Add Built-In ABI Constant Codegen

**Files:**
- Modify: `native/rux-compiler/tests/compiler_seed.rs`
- Modify: `native/rux-compiler/src/lib.rs`

- [ ] **Step 1: Write failing codegen tests**

Append these tests to `native/rux-compiler/tests/compiler_seed.rs`:

```rust
#[test]
fn compile_lowers_debug_write_builtin_address() {
    let image = compile("fn main() { unsafe { mmio<i32>(DEBUG_WRITE).store(79); } }").unwrap();
    let function = &image.functions[0];

    assert_eq!(
        function.instructions,
        vec![
            Instruction::AddrConst {
                dst: 0,
                value: ComputerMachine::DEBUG_WRITE,
            },
            Instruction::I32Const { dst: 1, value: 79 },
            Instruction::Store32 { addr: 0, src: 1 },
            Instruction::ReturnUnit,
        ]
    );
}

#[test]
fn compile_lowers_control_status_and_status_ready_builtins() {
    let image =
        compile("fn main() { unsafe { mmio<i32>(CONTROL_STATUS).store(STATUS_READY); } }")
            .unwrap();
    let function = &image.functions[0];

    assert_eq!(
        function.instructions,
        vec![
            Instruction::AddrConst {
                dst: 0,
                value: ComputerMachine::CONTROL_STATUS,
            },
            Instruction::I32Const {
                dst: 1,
                value: ComputerMachine::STATUS_READY,
            },
            Instruction::Store32 { addr: 0, src: 1 },
            Instruction::ReturnUnit,
        ]
    );
}

#[test]
fn compile_lowers_status_ready_builtin_i32_return() {
    let image = compile("fn main() -> i32 { return STATUS_READY; }").unwrap();
    let function = &image.functions[0];

    assert_eq!(
        function.instructions,
        vec![
            Instruction::I32Const {
                dst: 0,
                value: ComputerMachine::STATUS_READY,
            },
            Instruction::ReturnI32 { src: 0 },
        ]
    );
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml compile_lowers_
```

Expected: FAIL because identifiers such as `DEBUG_WRITE` and `STATUS_READY` are reported as undeclared locals.

- [ ] **Step 3: Import ABI and add resolver**

In `native/rux-compiler/src/lib.rs`, change the top import to include `computer_abi`:

```rust
use rux_vm::computer_abi;
use rux_vm::low_image::{Function, Image, Instruction};
```

Add near `ExprValue`:

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BuiltinConstant {
    Addr(u32),
    I32(i32),
}

fn resolve_builtin_constant(name: &str) -> Option<BuiltinConstant> {
    match name {
        "RAM_BASE" => Some(BuiltinConstant::Addr(computer_abi::RAM_BASE)),
        "CONTROL_BASE" => Some(BuiltinConstant::Addr(computer_abi::CONTROL_BASE)),
        "CONTROL_STATUS" => Some(BuiltinConstant::Addr(computer_abi::CONTROL_STATUS)),
        "CONTROL_PANIC_CODE" => Some(BuiltinConstant::Addr(computer_abi::CONTROL_PANIC_CODE)),
        "CONTROL_EXIT_CODE" => Some(BuiltinConstant::Addr(computer_abi::CONTROL_EXIT_CODE)),
        "CONTROL_SIZE" => Some(BuiltinConstant::I32(computer_abi::CONTROL_SIZE as i32)),
        "DEBUG_BASE" => Some(BuiltinConstant::Addr(computer_abi::DEBUG_BASE)),
        "DEBUG_WRITE" => Some(BuiltinConstant::Addr(computer_abi::DEBUG_WRITE)),
        "DEBUG_SIZE" => Some(BuiltinConstant::I32(computer_abi::DEBUG_SIZE as i32)),
        "STATUS_RESET" => Some(BuiltinConstant::I32(computer_abi::STATUS_RESET)),
        "STATUS_BOOTING" => Some(BuiltinConstant::I32(computer_abi::STATUS_BOOTING)),
        "STATUS_READY" => Some(BuiltinConstant::I32(computer_abi::STATUS_READY)),
        "STATUS_HALTED" => Some(BuiltinConstant::I32(computer_abi::STATUS_HALTED)),
        "STATUS_PANIC" => Some(BuiltinConstant::I32(computer_abi::STATUS_PANIC)),
        _ => None,
    }
}
```

- [ ] **Step 4: Lower built-ins from identifiers**

In `compile_expr`, update `Expr::Local(name)` to:

```rust
Expr::Local(name) => {
    if let Some(local) = self.locals.get(name) {
        return match local.ty {
            ValueType::I32 => Ok(ExprValue::I32(local.register)),
        };
    }

    match resolve_builtin_constant(name) {
        Some(BuiltinConstant::Addr(value)) => {
            let dst = self.alloc_register()?;
            self.instructions.push(Instruction::AddrConst { dst, value });
            Ok(ExprValue::Addr(dst))
        }
        Some(BuiltinConstant::I32(value)) => Ok(ExprValue::I32(self.emit_i32_const(value)?)),
        None => Err(CompileError {
            message: format!("use of undeclared local `{name}`"),
        }),
    }
}
```

- [ ] **Step 5: Run focused tests and verify GREEN**

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml compile_lowers_
```

Expected: PASS.

- [ ] **Step 6: Run compiler tests and commit**

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml
cargo fmt --manifest-path native/rux-compiler/Cargo.toml --check
git add native/rux-compiler/src/lib.rs native/rux-compiler/tests/compiler_seed.rs
git commit -m "Add rust language seed ABI constants"
```

## Task 2: Add ABI Constant Diagnostics

**Files:**
- Modify: `native/rux-compiler/tests/compiler_seed.rs`
- Modify: `native/rux-compiler/src/lib.rs`

- [ ] **Step 1: Write failing diagnostics tests**

Append:

```rust
#[test]
fn compile_rejects_address_builtin_as_i32() {
    let error = compile("fn main() -> i32 { return DEBUG_WRITE; }").unwrap_err();

    assert!(
        error.message.contains("expected `i32`, found address"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_i32_builtin_as_mmio_address() {
    let error = compile("fn main() { unsafe { mmio<i32>(STATUS_READY).store(1); } }").unwrap_err();

    assert!(
        error.message.contains("MMIO address must be an address expression"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_builtin_shadowing() {
    let error = compile("fn main() { let mut DEBUG_WRITE: i32 = 1; }").unwrap_err();

    assert!(
        error.message.contains("local `DEBUG_WRITE` cannot shadow built-in ABI constant"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_unknown_identifier() {
    let error = compile("fn main() -> i32 { return UNKNOWN_CONSTANT; }").unwrap_err();

    assert!(
        error.message.contains("use of undeclared local `UNKNOWN_CONSTANT`"),
        "{error:?}"
    );
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml compile_rejects_
```

Expected: `compile_rejects_builtin_shadowing` FAILS until shadowing validation is added. The other tests may already pass after Task 1.

- [ ] **Step 3: Reject built-in shadowing in local declarations**

In `compile_statement`, inside `Statement::Let { name, initializer }`, add before duplicate-local check:

```rust
if resolve_builtin_constant(name).is_some() {
    return Err(CompileError {
        message: format!("local `{name}` cannot shadow built-in ABI constant"),
    });
}
```

- [ ] **Step 4: Run focused tests and verify GREEN**

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml compile_rejects_
```

Expected: PASS.

- [ ] **Step 5: Run full compiler tests and commit**

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml
cargo fmt --manifest-path native/rux-compiler/Cargo.toml --check
git add native/rux-compiler/src/lib.rs native/rux-compiler/tests/compiler_seed.rs
git commit -m "Validate rust language seed ABI constants"
```

## Task 3: Add ComputerMachine Built-In ABI E2E Test

**Files:**
- Modify: `native/rux-compiler/tests/compiler_seed.rs`

- [ ] **Step 1: Write e2e test**

Append:

```rust
#[test]
fn compiled_seed_abi_constants_run_on_computer_machine() {
    let image = compile(
        "fn main() -> i32 {
            unsafe {
                mmio<i32>(CONTROL_STATUS).store(STATUS_BOOTING);
                mmio<i32>(DEBUG_WRITE).store(79);
                mmio<i32>(DEBUG_WRITE).store(75);
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

- [ ] **Step 2: Run focused e2e test**

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml compiled_seed_abi_constants_run_on_computer_machine
```

Expected: PASS.

- [ ] **Step 3: Commit**

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml
cargo fmt --manifest-path native/rux-compiler/Cargo.toml --check
git add native/rux-compiler/tests/compiler_seed.rs
git commit -m "Run rust language seed ABI constants on computer machine"
```

## Task 4: Update Design Status

**Files:**
- Modify: `docs/superpowers/specs/2026-05-14-rust-like-seed-abi-constants-design.md`

- [ ] **Step 1: Add implementation status**

Append:

```markdown
## Implementation Status

Implemented in `native/rux-compiler`:

- built-in ABI constant resolver backed by `rux_vm::computer_abi`;
- address constants lowered through `AddrConst`;
- i32 constants lowered through `I32Const`;
- local variables take precedence over unknown identifiers but cannot shadow built-in constants;
- diagnostics for address-as-i32, i32-as-address, built-in shadowing, and unknown identifiers;
- end-to-end firmware test that uses ABI constants instead of raw MMIO numbers.
```

- [ ] **Step 2: Commit**

Run:

```bash
git add docs/superpowers/specs/2026-05-14-rust-like-seed-abi-constants-design.md
git commit -m "Document rust language seed ABI constants implementation"
```

## Task 5: Final Verification

**Files:**
- No file changes expected.

- [ ] **Step 1: Run compiler tests**

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml
```

Expected: PASS.

- [ ] **Step 2: Run compiler formatting check**

Run:

```bash
cargo fmt --manifest-path native/rux-compiler/Cargo.toml --check
```

Expected: PASS with no diff.

- [ ] **Step 3: Run low VM tests**

Run:

```bash
cargo test --offline --manifest-path native/rux-vm/Cargo.toml
```

Expected: PASS.

- [ ] **Step 4: Check git status**

Run:

```bash
git status --short
```

Expected: no output.
