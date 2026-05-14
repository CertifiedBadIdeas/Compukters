# Rux Bare-Metal Language Seed Design

## Goal

Create the first Rust compiler path for Rux, a new Rust-like bare-metal language that targets the low VM directly.

This is not a CKL compatibility project. CKL may remain as a legacy/product language elsewhere, but this experimental branch should start a new language designed for low VM computers, flat RAM, MMIO, and future ownership/RAII work.

## Motivation

The current CKL syntax and compiler architecture are not the desired foundation for the low-level VM direction. Porting the Kotlin CKL compiler to Rust would preserve too much of the old language shape and runtime model.

The experiment should instead start with a tiny Rust-written compiler for Rux:

```text
source text
  -> Rust lexer
  -> Rust parser
  -> small AST
  -> minimal semantic checks
  -> ckl_vm::low_image::Image
  -> ComputerMachine
```

The first milestone should prove the full path from source text to bare-metal machine behavior, not language completeness.

## Working Language Shape

Rux uses Rust-like surface syntax:

```rust
fn main() -> i32 {
    unsafe {
        mmio<i32>(0x10000000).store(1);
        mmio<i32>(0x10000100).store(79);
        mmio<i32>(0x10000100).store(75);
        mmio<i32>(0x10000000).store(2);
    }

    return 0;
}
```

The source file extension is `.rx`.

## Non-Goals

- Do not parse or compile CKL syntax.
- Do not keep Kotlin compiler compatibility.
- Do not add Kotlin fallback compilation.
- Do not add imports, modules, packages, or IDE support.
- Do not add heap strings, records, structs, classes, generics, or lambdas.
- Do not add variables in the first slice unless required by tests.
- Do not add guest OS processes, filesystem, display, shell, or daemon integration.
- Do not add a CLI in the first slice.

## Crate Layout

Add a new Rust crate next to the VM crate:

```text
native/
  ckl-vm/
  ckl-compiler/
```

`ckl-compiler` depends on `ckl-vm` and emits `ckl_vm::low_image::Image`.

This dependency direction is acceptable for the seed. Later, if the compiler grows, shared image/ISA types can move into a smaller `ckl-isa` crate so both VM and compiler depend on the same model without coupling.

## Seed Grammar

The first parser supports only one source file with one function:

```text
program             = function
function            = "fn" "main" "(" ")" return_annotation? block
return_annotation   = "->" "i32"
block               = "{" statement* "}"
statement           = return_statement | unsafe_block | method_call_statement
return_statement    = "return" expression? ";"
unsafe_block        = "unsafe" block
method_call_statement = method_call ";"
expression          = additive
additive            = multiplicative (("+" | "-") multiplicative)*
multiplicative      = postfix (("*" | "/") postfix)*
postfix             = primary ("." identifier "(" arguments? ")")*
primary             = int_literal | "(" expression ")" | mmio_expression
mmio_expression     = "mmio" "<" "i32" ">" "(" expression ")"
method_call         = expression "." identifier "(" arguments? ")"
arguments           = expression ("," expression)*
```

Integer literals support:

- decimal: `42`
- hexadecimal: `0x2a`

Comments are not required in the first slice.

`fn main() -> i32` returns a 32-bit integer. `fn main()` returns unit. The seed language does not have a `void` keyword.

## Unsafe MMIO

The compiler recognizes one unsafe MMIO constructor:

```rust
mmio<i32>(addr)
```

The constructor returns a typed MMIO capability. The seed compiler supports two methods on that capability:

- `.store(value)`:
  - lower the capability address into an address register;
  - lower `value` into an i32 register;
  - emit `Instruction::Store32`.
- `.load()`:
  - lower the capability address into an address register;
  - emit `Instruction::Load32` into an i32 register.

MMIO construction and MMIO methods are valid only inside `unsafe` blocks. They are not hostcalls. They lower directly to low VM memory instructions and model Rust embedded-style volatile access.

ABI constants are not language identifiers in the first slice. Tests use literal addresses from `computer_abi` when building the source string.

## Code Generation

The compiler emits a single-function low image:

```rust
Image {
    language_version: "rux-0".to_string(),
    memory_size: 64 * 1024,
    rodata: Vec::new(),
    data: Vec::new(),
    bss_size: 0,
    entry_function_index: 0,
    functions: vec![Function {
        name: "main".to_string(),
        register_count,
        parameters: Vec::new(),
        instructions,
    }],
}
```

Register allocation is simple and monotonic:

- every expression result receives a fresh register;
- address-valued expressions use address registers by convention, but the low image model has a unified register count;
- no reuse is attempted in the seed compiler.

Return lowering:

- `fn main() -> i32 { return expr; }` emits `ReturnI32`.
- `fn main() { return; }` emits `ReturnUnit`.
- Falling off the end of `fn main()` emits `ReturnUnit`.
- Falling off the end of an `i32` function is a compile error.

## Diagnostics

The seed compiler should return structured errors, not panic for normal bad input.

Use a compact error type such as:

```rust
pub struct CompileError {
    pub message: String,
}
```

The first slice does not need rich spans. Error messages should still be deterministic and useful:

- unexpected token;
- unknown function;
- unknown method;
- wrong argument count;
- MMIO access outside `unsafe`;
- `return;` in `i32` function;
- `return expr;` in unit function;
- missing return in `i32` function.

## End-To-End Test

The key success test compiles source text, boots it as firmware, and observes machine state:

```rust
fn main() -> i32 {
    unsafe {
        mmio<i32>(0x10000000).store(1);
        mmio<i32>(0x10000100).store(79);
        mmio<i32>(0x10000100).store(75);
        mmio<i32>(0x10000000).store(2);
    }

    return 0;
}
```

The test runs the emitted image through `ComputerMachine` and expects:

- debug output is `OK`;
- final status is `STATUS_HALTED`;
- exit code is `0`;
- panic code is `0`.

## Testing Strategy

Add tests inside `native/ckl-compiler`:

- lexer recognizes keywords, punctuation, decimal integers, and hex integers;
- parser parses a single `main` function;
- compiler emits arithmetic instructions for `return 1 + 2 * 3;`;
- compiler lowers `unsafe { mmio<i32>(addr).store(value); }`;
- compiler lowers `unsafe { mmio<i32>(addr).load(); }` when used as an expression;
- compiler rejects MMIO access outside `unsafe`;
- bad source produces compile errors rather than panics;
- end-to-end firmware test runs on `ComputerMachine`.

Run:

```bash
cargo test --manifest-path native/ckl-compiler/Cargo.toml
cargo test --manifest-path native/ckl-vm/Cargo.toml
```

## Success Criteria

- A new Rust compiler crate exists.
- No Kotlin compiler code is touched.
- No CKL compatibility path is added.
- A Rux source string compiles directly to `low_image::Image`.
- The generated image runs on `ComputerMachine` and writes `OK` through debug MMIO.
- The compiler reports deterministic errors for unsupported seed-language input.

## Implementation Status

The first Rux seed slice is implemented in `native/ckl-compiler`.

Current support:

- public `compile(source: &str) -> Result<Image, CompileError>` API;
- lexer for `fn`, `return`, `unsafe`, `mmio`, `i32`, punctuation, decimal integers, and hex integers;
- `u32`, suffixed `123u32` / `0xffffu32` literals, and minimal `as i32` / `as u32` casts;
- `u8`, suffixed `123u8` / `0xffu8` literals, minimal casts, and byte string literals such as `b"OK\n"`;
- one `fn main()` or `fn main() -> i32`;
- integer arithmetic expressions with `+`, `-`, `*`, `/` precedence;
- unary `-` for `i32`;
- signed `i32` and unsigned `u32` ordering/equality comparisons;
- compound assignment for mutable `i32` / `u32` locals: `+=`, `-=`, `*=`, `/=`, `&=`, `|=`, `^=`, `<<=`, `>>=`;
- `unsafe { ... }` blocks;
- `mmio<i32>(addr)` / `mmio<u32>(addr)` store/load lowered to `Store32` / `Load32`;
- `mmio<u8>(addr)` store/load lowered to `Store8` / `Load8`;
- `ptr<i32>(addr)` / `ptr<u32>(addr)` store/load lowered to shared RAM `Store32` / `Load32`;
- `ptr<u8>(addr)` store/load lowered to shared RAM `Store8` / `Load8`;
- pointer indexing syntax such as `ptr<u8>(RAM_BASE)[i]` and `b"OK"[i]`;
- unit functions with implicit `ReturnUnit`;
- i32 functions with explicit `ReturnI32`;
- diagnostics for missing i32 return, `return;` in i32 functions, value return from unit functions, `void`, and MMIO outside `unsafe`;
- end-to-end test that compiles firmware-shaped source and runs it on `ComputerMachine`.

The implementation intentionally keeps lexer, parser, AST, and codegen in one file for the seed. Split modules only when the language grows enough to justify the structure.
