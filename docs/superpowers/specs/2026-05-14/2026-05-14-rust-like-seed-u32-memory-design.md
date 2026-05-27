# Rust-Like Seed U32 And Typed Memory Design

## Goal

Add a minimal `u32` type to the Rust-like seed language and allow `ptr<u32>` / `mmio<u32>` load/store calls.

## Scope

This is a compiler-level type-system extension. The low VM instruction set already operates on 32-bit words through `I32*`, `Load32`, and `Store32`, so this slice does not add new VM instructions or image ABI tags.

## Language Features

The seed language accepts:

```rust
let mut mask: u32 = 0xffff0000u32;
let mut value: i32 = mask as i32;
let mut back: u32 = value as u32;

unsafe {
    ptr<u32>(RAM_BASE + 4).store(mask);
    let mut loaded: u32 = ptr<u32>(RAM_BASE + 4).load();
    mmio<u32>(DEBUG_WRITE).store((loaded & 0xffu32) as i32 as u32);
}
```

Supported type names:

- `i32`
- `u32`
- `bool`

Supported casts:

- `i32 as u32`
- `u32 as i32`
- identity casts among `i32`/`u32`

Casts are bit reinterpretation in the compiler model and emit no runtime instruction.

## Literals

Decimal and hexadecimal integer literals remain integer literals. The lexer also accepts an adjacent `u32` suffix:

```rust
123u32
0xffff0000u32
```

The suffix marks the literal as unsigned. Unsuffixed literals are still context-typed by the expected type where possible. A literal used as `u32` must fit in `u32`; a literal used as `i32` must fit in `i32`.

## Memory Pointers

`mmio<T>(addr)` and `ptr<T>(addr)` accept only `T = i32` or `T = u32` in this slice.

Pointer capabilities carry both address and element type in the compiler:

- `mmio<i32>` and `ptr<i32>` load/store `i32`;
- `mmio<u32>` and `ptr<u32>` load/store `u32`.

Both lower to the existing `Load32` and `Store32` low VM instructions.

## Diagnostics

The compiler should reject:

- `u32` literals that do not fit in `u32`;
- assigning `u32` to `i32` without `as i32`;
- assigning `i32` to `u32` without `as u32`;
- `bool as i32` / `bool as u32`;
- memory stores where the pointer element type and value type differ.

## Tests

Coverage should include:

- lexer recognizes `u32`, `as`, and `123u32`/hex masks;
- `u32` locals, function parameters, returns, and casts;
- `ptr<u32>` store/load through shared RAM;
- `mmio<u32>` store to debug output using a byte-sized value;
- compile errors for missing casts and invalid casts.
