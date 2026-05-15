# Rust-Like Seed `ptr<i32>` RAM Primitives Design

## Goal

Add the first raw RAM pointer primitive to the Rust-like seed compiler so bare-metal programs can read and write normal machine RAM, not only MMIO devices.

## Motivation

The seed language can already boot firmware, call helper functions, use `const`, and write to MMIO devices. The next useful step toward real firmware is ordinary RAM access for state, buffers, tables, and future runtime data structures.

This slice intentionally stays small. It adds raw `i32` pointer load/store and address arithmetic, without heap allocation, arrays, references, borrowing, or a guest OS.

## Source Syntax

The language adds a `ptr<i32>` constructor:

```rust
fn main() -> i32 {
    unsafe {
        ptr<i32>(RAM_BASE + 4).store(42);
        return ptr<i32>(RAM_BASE + 4).load();
    }
}
```

Supported methods:

- `ptr<i32>(addr).store(value);`
- `ptr<i32>(addr).load()`

`ptr<i32>` is a raw pointer capability. Like `mmio<i32>`, using it is unsafe.

## Address Expressions

The compiler supports address addition in address context:

```rust
RAM_BASE + offset
```

Lowering:

- address base compiles to an address register;
- offset compiles to an `i32` register;
- addition emits `Instruction::AddrAdd { dst, base, offset }`.

Address addition is only interpreted in address context, such as inside `ptr<i32>(...)` or `mmio<i32>(...)`. Normal `i32` arithmetic remains unchanged.

## Compiler Model

Add:

- `TokenKind::Ptr`;
- `Expr::Ptr(Box<Expr>)`;
- a pointer expression value variant that carries the address register and pointer kind;
- `compile_addr_expr` support for `BinaryOp::Add` as address arithmetic;
- method-call lowering shared by `mmio<i32>` and `ptr<i32>`.

Both `ptr<i32>` and `mmio<i32>` lower to the existing low VM memory instructions:

- `.store(value)` -> `Store32 { addr, src }`;
- `.load()` -> `Load32 { dst, addr }`.

No low VM ISA changes are required.

## Diagnostics

The compiler should reject:

- `ptr<i32>` access outside `unsafe`;
- non-address expressions used as pointer addresses;
- wrong argument count for `.store` and `.load`;
- unknown pointer methods;
- `ptr` in const initializers.

Messages should stay deterministic and compact.

## Tests

Add tests in `native/rux-compiler/tests/compiler_seed.rs`:

- lexer recognizes `ptr`;
- compiler lowers `ptr<i32>(RAM_BASE + 4).store(42)` and `.load()`;
- pointer access outside `unsafe` is rejected;
- RAM pointer firmware runs on `ComputerMachine` and returns the loaded value.

## Non-Goals

- No pointer variables.
- No `ptr<i64>` or byte pointers.
- No `&T`, `*mut T`, slices, arrays, or borrow checking.
- No allocator or heap.
- No volatile distinction yet; all raw memory operations use the low VM memory bus.

## Success Criteria

- Firmware can write an `i32` to RAM and read it back.
- `RAM_BASE + i32` address arithmetic lowers to `AddrAdd`.
- Existing MMIO firmware keeps working.
- Compiler tests and low VM tests pass.

## Implementation Status

Implemented in `native/rux-compiler`:

- `ptr<i32>(addr)` parsing;
- unsafe raw RAM `.store(value)` and `.load()`;
- address-context `RAM_BASE + i32` lowering to `AddrAdd`;
- pointer-specific diagnostics;
- `ComputerMachine` E2E RAM read/write test.
