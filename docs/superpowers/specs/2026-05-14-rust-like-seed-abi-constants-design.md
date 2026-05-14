# Rust-Like Seed ABI Constants Design

## Goal

Remove magic MMIO numbers from Rust-like seed firmware by exposing the current computer ABI constants as compiler built-ins.

The language must be able to write:

```rust
fn main() -> i32 {
    unsafe {
        mmio<i32>(CONTROL_STATUS).store(STATUS_BOOTING);
        mmio<i32>(DEBUG_WRITE).store(79);
        mmio<i32>(DEBUG_WRITE).store(75);
        mmio<i32>(CONTROL_STATUS).store(STATUS_READY);
    }

    return 0;
}
```

instead of:

```rust
mmio<i32>(0x10000100).store(79);
```

## Context

The low VM computer ABI already exists in `ckl_vm::computer_abi`:

```rust
pub const RAM_BASE: u32 = 0x0000_0000;

pub const CONTROL_BASE: u32 = 0x1000_0000;
pub const CONTROL_STATUS: u32 = CONTROL_BASE;
pub const CONTROL_PANIC_CODE: u32 = CONTROL_BASE + 4;
pub const CONTROL_EXIT_CODE: u32 = CONTROL_BASE + 8;
pub const CONTROL_SIZE: u32 = 12;

pub const DEBUG_BASE: u32 = 0x1000_0100;
pub const DEBUG_WRITE: u32 = DEBUG_BASE;
pub const DEBUG_SIZE: u32 = 4;

pub const STATUS_RESET: i32 = 0;
pub const STATUS_BOOTING: i32 = 1;
pub const STATUS_READY: i32 = 2;
pub const STATUS_HALTED: i32 = 3;
pub const STATUS_PANIC: i32 = 4;
```

The seed compiler currently treats identifiers only as local variables. This makes firmware examples noisy and fragile because they must repeat literal MMIO addresses.

## Approaches Considered

### Compiler Built-Ins

The compiler resolves a fixed set of ABI names when an identifier is not a local.

Trade-offs:

- simplest implementation;
- no parser changes;
- keeps one source file and no import system;
- ties the seed compiler directly to `ComputerMachine` ABI v0.

This is the selected approach.

### Generated Prelude Source

Generate or inject source-level constants before parsing user code.

Trade-offs:

- closer to a future real language;
- introduces source-level `const` before the language needs it;
- requires parser and semantic rules for declarations that are not otherwise useful yet.

Rejected for this slice.

### Explicit Imports

Support syntax like:

```rust
use computer::abi::*;
```

Trade-offs:

- scales better later;
- requires modules, namespaces, import diagnostics, and name resolution;
- too much structure for the current seed.

Rejected for this slice.

## Built-In Constant Set

Expose these names:

```text
RAM_BASE
CONTROL_BASE
CONTROL_STATUS
CONTROL_PANIC_CODE
CONTROL_EXIT_CODE
CONTROL_SIZE
DEBUG_BASE
DEBUG_WRITE
DEBUG_SIZE
STATUS_RESET
STATUS_BOOTING
STATUS_READY
STATUS_HALTED
STATUS_PANIC
```

Address-like constants have type `Addr`:

```text
RAM_BASE
CONTROL_BASE
CONTROL_STATUS
CONTROL_PANIC_CODE
CONTROL_EXIT_CODE
DEBUG_BASE
DEBUG_WRITE
```

Integer-like constants have type `I32`:

```text
CONTROL_SIZE
DEBUG_SIZE
STATUS_RESET
STATUS_BOOTING
STATUS_READY
STATUS_HALTED
STATUS_PANIC
```

## Name Resolution

Identifier resolution order:

1. Local variables.
2. Built-in ABI constants.
3. Compile error.

This lets locals stay cheap and predictable while making built-ins available without imports.

Local declarations cannot shadow built-in ABI constants. This avoids surprising firmware:

```rust
let mut DEBUG_WRITE: i32 = 1;
```

must fail with a deterministic compile error.

## Type Rules

Built-in address constants lower to `Instruction::AddrConst`.

Built-in integer constants lower to `Instruction::I32Const`.

Examples:

```rust
mmio<i32>(DEBUG_WRITE).store(79);
```

lowers the address expression as `AddrConst { value: computer_abi::DEBUG_WRITE }`.

```rust
mmio<i32>(CONTROL_STATUS).store(STATUS_READY);
```

lowers `CONTROL_STATUS` as an address constant and `STATUS_READY` as an i32 constant.

Using an address constant where an `i32` is required is an error:

```rust
return DEBUG_WRITE;
```

Using an i32 constant where an address is required is an error:

```rust
mmio<i32>(STATUS_READY).store(1);
```

## Address Expressions

This slice supports direct address constants and direct address literals in `mmio<i32>(...)`.

It does not add address arithmetic:

```rust
mmio<i32>(CONTROL_BASE + 4).store(1);
```

This remains a compile error for now. Existing named constants already cover the current control and debug registers.

## Implementation Shape

Add a small built-in resolver in `native/ckl-compiler/src/lib.rs`:

```rust
enum BuiltinConstant {
    Addr(u32),
    I32(i32),
}

fn resolve_builtin_constant(name: &str) -> Option<BuiltinConstant> {
    match name {
        "DEBUG_WRITE" => Some(BuiltinConstant::Addr(computer_abi::DEBUG_WRITE)),
        "STATUS_READY" => Some(BuiltinConstant::I32(computer_abi::STATUS_READY)),
        _ => None,
    }
}
```

The actual implementation must include the full constant set listed above.

`Expr::Local(name)` can stay as the parsed AST shape for identifiers. During codegen, if `name` is not a local, resolve it as a built-in constant before returning an undeclared-local error.

## Diagnostics

Add deterministic errors for:

- unknown identifier that is neither a local nor a built-in;
- local declaration that attempts to shadow a built-in ABI constant;
- address constant used as an i32 value;
- i32 constant used as an MMIO address.

The error type remains:

```rust
pub struct CompileError {
    pub message: String,
}
```

Rich spans are still not required.

## Testing Strategy

Add tests in `native/ckl-compiler/tests/compiler_seed.rs`.

Codegen tests:

- `mmio<i32>(DEBUG_WRITE).store(79);` lowers `DEBUG_WRITE` to an `AddrConst`.
- `mmio<i32>(CONTROL_STATUS).store(STATUS_READY);` lowers address and status constants correctly.
- `return STATUS_READY;` lowers to `I32Const` and `ReturnI32`.

Diagnostics tests:

- `return DEBUG_WRITE;` rejects address-as-i32.
- `mmio<i32>(STATUS_READY).store(1);` rejects i32-as-address.
- `let mut DEBUG_WRITE: i32 = 1;` rejects shadowing a built-in.
- `return UNKNOWN_CONSTANT;` reports an undeclared identifier.

End-to-end test:

```rust
fn main() -> i32 {
    unsafe {
        mmio<i32>(CONTROL_STATUS).store(STATUS_BOOTING);
        mmio<i32>(DEBUG_WRITE).store(79);
        mmio<i32>(DEBUG_WRITE).store(75);
        mmio<i32>(CONTROL_STATUS).store(STATUS_READY);
    }

    return 0;
}
```

The test runs on `ComputerMachine` and expects:

- debug output is `OK`;
- halt signal is `HaltI32(0)`;
- final machine status is halted;
- exit code is `0`;
- panic code is `0`.

## Success Criteria

- Firmware tests no longer need raw ABI addresses.
- The compiler reads ABI values from `ckl_vm::computer_abi`.
- The low VM ISA does not change.
- No import system is added.
- No source-level `const` declaration is added.
- Existing seed tests keep passing.

## Implementation Status

Implemented in `native/ckl-compiler`:

- built-in ABI constant resolver backed by `ckl_vm::computer_abi`;
- address constants lowered through `AddrConst`;
- i32 constants lowered through `I32Const`;
- local variables take precedence over unknown identifiers but cannot shadow built-in constants;
- diagnostics for address-as-i32, i32-as-address, built-in shadowing, and unknown identifiers;
- end-to-end firmware test that uses ABI constants instead of raw MMIO numbers.
