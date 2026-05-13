# Rust-Like Seed Control Flow And Locals Design

## Goal

Make the Rust-like bare-metal seed language useful for small firmware programs by adding local variables, assignment, comparisons, `if`, and `while`.

This is still a seed compiler, not a full language implementation. The goal is to support simple polling loops, status checks, counters, and conditional MMIO writes while keeping the compiler direct and small.

## Context

The current `native/ckl-compiler` crate can compile one `main` function into `ckl_vm::low_image::Image`.

Current language support:

- `fn main()` and `fn main() -> i32`;
- integer literals;
- arithmetic `+`, `-`, `*`, `/`;
- `return`;
- `unsafe` blocks;
- `mmio<i32>(addr).store(value)`;
- `mmio<i32>(addr).load()`;
- direct lowering into low VM instructions.

The low VM already has the needed instruction surface for this slice:

- integer arithmetic;
- `I32Lt`;
- `I32Eq`;
- `Jump`;
- `JumpIfFalse`;
- `ReturnI32`;
- `ReturnUnit`.

No VM instruction changes are required.

## Non-Goals

- Do not add user-defined functions.
- Do not add structs, arrays, strings, heap allocation, references, borrows, ownership, or RAII yet.
- Do not add type inference beyond obvious integer literals.
- Do not add `for`, `loop`, `break`, or `continue`.
- Do not add boolean variables as a first-class storage type.
- Do not add bitwise operators in this slice.
- Do not add ABI/prelude constants in this slice.
- Do not split the compiler crate into modules unless the implementation becomes difficult to read.

## Language Additions

The next slice supports mutable `i32` locals:

```rust
fn main() -> i32 {
    let mut i: i32 = 0;
    while i < 10 {
        unsafe {
            mmio<i32>(0x10000100).store(65 + i);
        }
        i = i + 1;
    }
    return 0;
}
```

Only `let mut name: i32 = expression;` is supported. Immutable `let` is intentionally not added yet because the seed compiler does not have a borrow checker or richer semantic model. Requiring `mut` everywhere makes assignment rules explicit and avoids a half-implemented immutability story.

Assignment supports only direct local writes:

```rust
i = i + 1;
```

The left-hand side must be a declared mutable local. Assigning to MMIO uses `.store(...)`, not assignment syntax.

## Grammar

Extend the current grammar with:

```text
statement           = let_statement
                    | assignment_statement
                    | if_statement
                    | while_statement
                    | return_statement
                    | unsafe_block
                    | method_call_statement

let_statement       = "let" "mut" identifier ":" "i32" "=" expression ";"
assignment_statement = identifier "=" expression ";"
if_statement        = "if" expression block ("else" block)?
while_statement     = "while" expression block

expression          = comparison
comparison          = additive (("<" | "==" | "!=" | ">" | "<=" | ">=") additive)?
additive            = multiplicative (("+" | "-") multiplicative)*
multiplicative      = postfix (("*" | "/") postfix)*
postfix             = primary ("." identifier "(" arguments? ")")*
primary             = int_literal | identifier | "(" expression ")" | mmio_expression
```

The lexer adds tokens for:

- `let`;
- `mut`;
- `if`;
- `else`;
- `while`;
- `:`;
- `=`;
- `==`;
- `!=`;
- `<=`;
- `>=`.

## Types

The seed compiler keeps a very small type model:

```rust
enum Type {
    Unit,
    I32,
    Addr,
}
```

Comparison expressions return `i32` boolean values:

- false is `0`;
- true is `1`.

This matches the current low VM shape, where comparison instructions write integer-like register values and `JumpIfFalse` consumes a condition register.

There is no separate source-level `bool` type in this slice. That can be added later if it becomes useful.

## Local Model

Each local maps to one fixed register for the whole function:

```rust
struct Local {
    register: u16,
    ty: Type,
    mutable: bool,
}
```

Expression temporaries still use fresh monotonic registers. This keeps code generation simple:

- declaring `let mut i: i32 = expr;` allocates one local register and emits `I32Move` or expression code followed by `I32Move`;
- reading `i` emits no instruction and returns the local register;
- assigning `i = expr;` lowers `expr` and emits `I32Move { dst: local_register, src: expr_register }`.

The compiler does not reuse temporary registers in this slice. Register pressure is acceptable for seed firmware examples.

## Control Flow Lowering

### `if`

`if cond { then } else { otherwise }` lowers to:

```text
cond instructions
JumpIfFalse cond, else_start
then instructions
Jump end
else instructions
end:
```

`if cond { then }` lowers to:

```text
cond instructions
JumpIfFalse cond, end
then instructions
end:
```

### `while`

`while cond { body }` lowers to:

```text
loop_start:
cond instructions
JumpIfFalse cond, loop_end
body instructions
Jump loop_start
loop_end:
```

Jump targets are instruction indices in the final low image instruction vector. The compiler can emit placeholder targets and patch them after body generation.

## Comparison Lowering

Direct low VM support exists for:

- `<` through `I32Lt`;
- `==` through `I32Eq`.

Other comparisons lower using these primitives:

- `a != b`: emit `I32Eq(a, b)`, then compare the result with `0` using `I32Eq`;
- `a > b`: emit `I32Lt(b, a)`;
- `a <= b`: lower as `!(b < a)`;
- `a >= b`: lower as `!(a < b)`.

The implementation supports the full comparison set in this slice. This keeps `while i <= n` and `if status != 0` available for firmware without adding new VM instructions.

## Return And Reachability

The existing return rules stay:

- `fn main() -> i32` must return a value on every statically accepted path.
- `fn main()` may fall through and gets implicit `ReturnUnit`.

Use a small block-outcome analysis during code generation:

- `return expr;` always returns;
- a normal expression, assignment, `let`, `unsafe`, or `while` statement does not always return;
- `if cond { a } else { b }` always returns only when both branches always return;
- `if cond { a }` without `else` does not always return;
- any statement after an always-returning statement is rejected as unreachable.

This is intentionally conservative. `while 1 { return 0; }` is not considered always-returning in this slice.

## Diagnostics

Add deterministic errors for:

- use of an undeclared local;
- duplicate local declaration in the same function;
- assignment to an undeclared local;
- `let mut name: i32 = expr;` where `expr` is not `i32`;
- condition expression that is not `i32`;
- unsupported comparison operand type;
- malformed assignment;
- missing `:` or `=` in `let` syntax.

The error type remains:

```rust
pub struct CompileError {
    pub message: String,
}
```

Rich spans are still not required. Byte offsets from the lexer are enough for this slice.

## Testing Strategy

Add tests in `native/ckl-compiler/tests/compiler_seed.rs`.

Lexer tests:

- recognizes `let`, `mut`, `if`, `else`, `while`, `:`, `=`, `==`, `!=`, `<=`, `>=`.

Codegen tests:

- lowers `let mut i: i32 = 1; return i;`;
- lowers `i = i + 1;`;
- lowers `if i == 0 { return 1; } else { return 2; }`;
- lowers `while i < 3 { i = i + 1; } return i;`;
- lowers MMIO inside a `while` loop.

Diagnostics tests:

- rejects undeclared local reads;
- rejects duplicate local declarations;
- rejects assignment to undeclared locals;
- rejects unit-valued expressions in `let mut` initializer;
- rejects MMIO access outside `unsafe` inside control-flow blocks.

End-to-end test:

```rust
fn main() -> i32 {
    let mut i: i32 = 0;
    while i < 2 {
        unsafe {
            mmio<i32>(0x10000100).store(79 + i);
        }
        i = i + 1;
    }
    return i;
}
```

The test runs on `ComputerMachine` and expects:

- debug output bytes are `79`, `80`;
- halt signal is `HaltI32(2)`;
- machine status is halted;
- exit code is `2`.

## Success Criteria

- The seed language can express simple loops and branches.
- Local variables are register-backed and require no VM memory model changes.
- The compiler still emits `ckl_vm::low_image::Image` directly.
- No Kotlin compiler code is touched.
- No CKL compatibility path is added.
- Existing seed tests keep passing.
- New control-flow and locals tests pass.

## Implementation Status

Implemented in `native/ckl-compiler`:

- lexer tokens for locals, branching, loops, assignment, and comparisons;
- parser support for `let mut`, assignment, `if`, `else`, `while`, local reads, and comparison expressions;
- register-backed mutable `i32` locals;
- direct lowering of comparisons through existing low VM instructions;
- direct lowering of `if` and `while` through `Jump` and `JumpIfFalse`;
- conservative return/outcome analysis;
- diagnostics for undeclared locals, duplicate locals, undeclared assignment, missing i32 returns, unreachable statements, and MMIO outside `unsafe`;
- end-to-end loop firmware test on `ComputerMachine`.
