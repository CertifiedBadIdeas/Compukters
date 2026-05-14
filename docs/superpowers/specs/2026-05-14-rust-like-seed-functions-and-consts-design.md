# Rust-Like Seed Functions And Consts Design

## Goal

Make Rust-like seed firmware easier to structure by adding top-level functions and source-level `const` declarations.

The language must be able to write:

```rust
const OK_O: i32 = 79;
const OK_K: i32 = 75;

fn write_ok() {
    unsafe {
        mmio<i32>(DEBUG_WRITE).store(OK_O);
        mmio<i32>(DEBUG_WRITE).store(OK_K);
    }
}

fn main() -> i32 {
    unsafe {
        mmio<i32>(CONTROL_STATUS).store(STATUS_BOOTING);
    }

    write_ok();

    unsafe {
        mmio<i32>(CONTROL_STATUS).store(STATUS_READY);
    }

    return 0;
}
```

This keeps the branch focused on firmware ergonomics without adding modules, heap allocation, strings, or a guest OS.

## Context

The current seed compiler supports:

- one `main` function;
- mutable `i32` locals;
- assignment;
- arithmetic and comparisons;
- `if` and `while`;
- built-in ABI constants from `ckl_vm::computer_abi`;
- direct lowering into `ckl_vm::low_image::Image`.

The low VM already supports multi-function images:

- `Function { name, register_count, parameters, instructions }`;
- `Instruction::CallStatic { return_register, function_index, arguments }`;
- register-window calls in `low_image_runner`.

No VM instruction changes are required.

## Non-Goals

- Do not add modules, imports, namespaces, or multiple source files.
- Do not add function pointers or dynamic dispatch.
- Do not add closures, lambdas, methods, structs, arrays, or strings.
- Do not add recursion in this slice.
- Do not add mutual recursion.
- Do not add generic functions.
- Do not add source-level address constants yet.
- Do not add local `const` declarations inside functions.
- Do not add type inference for function parameters or constants.
- Do not add stack allocation, references, borrowing, ownership, or RAII yet.

## Source-Level Consts

Add top-level `const` declarations:

```rust
const NAME: i32 = expression;
```

Supported const type:

- `i32` only.

Supported const initializer:

- integer literal;
- existing built-in `i32` ABI constant;
- previously declared source-level `i32` const;
- arithmetic using `+`, `-`, `*`, `/` over const-compatible `i32` operands.

Const declarations are evaluated at compile time and do not emit instructions by themselves.

Examples:

```rust
const OK_O: i32 = 79;
const OK_K: i32 = 75;
const READY: i32 = STATUS_READY;
const TEN: i32 = 4 + 6;
```

Rejected:

```rust
const DEBUG: i32 = DEBUG_WRITE; // address constant used as i32
const BAD: i32 = mmio<i32>(DEBUG_WRITE).load(); // runtime expression
```

Source-level consts cannot shadow built-in ABI constants or function names.

## Functions

Add multiple top-level functions:

```rust
fn helper() {
    return;
}

fn add(a: i32, b: i32) -> i32 {
    return a + b;
}
```

Supported function forms:

- `fn name() { ... }`
- `fn name() -> i32 { ... }`
- `fn name(a: i32, b: i32) { ... }`
- `fn name(a: i32, b: i32) -> i32 { ... }`

Supported parameter type:

- `i32` only.

There must be exactly one `main` function. `main` remains the image entry function.

Function names cannot shadow built-in ABI constants or source-level const names.

Duplicate function names are compile errors.

## Function Calls

Function call expression syntax:

```rust
name(arg1, arg2)
```

Call statements are allowed for unit-returning functions:

```rust
write_ok();
```

Call expressions are allowed for `i32`-returning functions:

```rust
let mut value: i32 = add(1, 2);
return add(value, 3);
```

Calling an `i32` function as a statement is allowed; the return value is discarded by emitting a `CallStatic` with a temporary return register or no return register depending on low VM behavior. The simpler seed rule is:

- if callee returns `i32`, statement calls allocate a temporary return register and ignore it.

Using a unit function as an `i32` expression is a compile error.

Argument count and argument type are checked at compile time.

## Recursion Policy

Recursion is rejected in this slice.

Reject:

- direct self-call;
- calls to functions that are currently on the compile stack.

This keeps the implementation simple and avoids accidentally turning firmware examples into unbounded CPU consumers before the language has better tooling.

Non-recursive forward calls are allowed:

```rust
fn main() -> i32 {
    return answer();
}

fn answer() -> i32 {
    return 42;
}
```

To support forward calls, the compiler first collects function signatures, then lowers function bodies.

## Grammar

Extend the program grammar:

```text
program             = item*
item                = const_declaration | function

const_declaration   = "const" identifier ":" "i32" "=" expression ";"

function            = "fn" identifier "(" parameters? ")" return_annotation? block
parameters          = parameter ("," parameter)*
parameter           = identifier ":" "i32"
return_annotation   = "->" "i32"

primary             = int_literal
                    | identifier
                    | function_call
                    | "(" expression ")"
                    | mmio_expression

function_call       = identifier "(" arguments? ")"
```

The lexer adds:

- `const`.

Existing `:` and `=` tokens are reused.

## Name Resolution

Identifier resolution order for expression identifiers:

1. Local variables and parameters.
2. Source-level consts.
3. Built-in ABI constants.
4. Compile error.

Function calls use the function namespace only:

1. Source-level functions.
2. Compile error.

The language has separate value and function namespaces for now. This means a const and a function cannot share a name, even though Rust itself has more nuanced namespaces. The seed keeps this stricter to avoid confusing diagnostics.

## Lowering

### Functions

Each source function lowers to one `low_image::Function`.

Parameter registers are assigned first:

```rust
fn add(a: i32, b: i32) -> i32
```

lowers with:

```rust
parameters: vec![0, 1]
```

and local symbol table entries:

```text
a -> r0
b -> r1
```

Other locals and temporaries continue after parameter registers.

### Calls

`add(1, 2)` lowers to:

```text
I32Const rA, 1
I32Const rB, 2
CallStatic {
    return_register: Some(rR),
    function_index: add_index,
    arguments: vec![rA, rB],
}
```

Unit calls lower with:

```rust
return_register: None
```

### Consts

Source-level consts are compile-time values.

Using `OK_O` in an `i32` expression emits:

```rust
Instruction::I32Const { dst, value: 79 }
```

Source-level consts do not occupy registers by themselves.

## Return Analysis

The existing conservative return analysis remains per function:

- `fn name() -> i32` must return an `i32` on every statically accepted path.
- `fn name()` may fall through and receives implicit `ReturnUnit`.
- unreachable statements after always-returning statements remain errors.

## Diagnostics

Add deterministic errors for:

- missing `main`;
- duplicate function name;
- duplicate parameter name;
- function name shadowing built-in or const name;
- const name shadowing built-in or function name;
- duplicate const name;
- unknown function call;
- wrong argument count;
- unit function used as an `i32` value;
- recursive function call;
- const initializer that is not compile-time evaluable;
- const initializer that produces address/unit instead of `i32`.

The error type remains:

```rust
pub struct CompileError {
    pub message: String,
}
```

Rich spans are still not required.

## Testing Strategy

Add tests in `native/ckl-compiler/tests/compiler_seed.rs`.

Lexer/parser tests:

- lexer recognizes `const`;
- parser accepts several top-level items before and after `main`;
- parser accepts function parameters.

Codegen tests:

- source const lowers to `I32Const` at use site;
- unit helper function lowers to a second low image function;
- `write_ok();` lowers to `CallStatic { return_register: None, ... }`;
- `add(1, 2)` lowers to `CallStatic { return_register: Some(...), arguments: ... }`;
- function parameters become callee parameter registers.

Diagnostics tests:

- missing `main`;
- duplicate function;
- duplicate const;
- duplicate parameter;
- unknown function;
- wrong argument count;
- unit function used as `i32`;
- direct recursion rejected;
- source const cannot shadow built-in.

End-to-end test:

```rust
const OK_O: i32 = 79;
const OK_K: i32 = 75;

fn write_ok() {
    unsafe {
        mmio<i32>(DEBUG_WRITE).store(OK_O);
        mmio<i32>(DEBUG_WRITE).store(OK_K);
    }
}

fn main() -> i32 {
    unsafe {
        mmio<i32>(CONTROL_STATUS).store(STATUS_BOOTING);
    }

    write_ok();

    unsafe {
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

- Firmware can be split into helper functions.
- Firmware can define named `i32` constants.
- Forward function calls work.
- Function arguments and return values work for `i32`.
- Unit helper calls work.
- Recursion is rejected.
- No low VM ISA changes are required.
- Existing seed tests keep passing.
