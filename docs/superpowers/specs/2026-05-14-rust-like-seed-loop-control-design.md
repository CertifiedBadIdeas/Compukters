# Rust-Like Seed Loop Control Design

## Goal

Add `break;` and `continue;` statements to the Rust-like bare-metal seed language.

## Scope

This feature only targets `while` loops in the Rust seed compiler. It does not change the low VM ABI, instruction set, scheduler, or machine model. Both statements lower to existing `Jump` instructions.

## Semantics

- `break;` exits the nearest enclosing `while` loop.
- `continue;` jumps to the nearest enclosing `while` loop condition, so the condition is re-evaluated before the next iteration.
- `break;` and `continue;` outside a loop are compile errors.
- Statements after `break;` or `continue;` in the same block are unreachable and rejected.

## Parser And AST

The lexer recognizes `break` and `continue` as keywords. The parser accepts them only as semicolon-terminated statements:

```rust
break;
continue;
```

The AST gets dedicated statement variants instead of modeling them as function calls or expressions.

## Codegen

The compiler keeps a loop-context stack while lowering statements. Each loop context stores:

- the `continue` target, which is the instruction index of the loop condition;
- placeholder jump indices emitted by `break`.

Lowering a `while` loop:

1. Remember the loop condition start index.
2. Compile the condition and emit `JumpIfFalse` to a placeholder loop exit.
3. Push a loop context.
4. Compile the body.
5. Pop the loop context.
6. Emit the back edge only if the body can fall through.
7. Patch the condition-false jump and all `break` jumps to the loop end.

`continue` emits a direct `Jump` to the context's continue target. `break` emits a placeholder `Jump`, then records it in the current loop context.

## Block Outcomes

Block outcome tracking expands from "falls through or always returns" to "falls through or terminates". Returns, breaks, and continues all terminate the current block for unreachable-code checks.

Function return checking still only treats `return` as satisfying non-unit function returns. A loop body that ends with `break` or `continue` does not satisfy a function return requirement.

## Tests

Coverage should include:

- lexer keywords;
- lowering shape for `break` and `continue`;
- execution on `ComputerMachine`;
- compile errors for loop control outside loops;
- unreachable statements after loop control;
- preservation of the existing unreachable-after-return diagnostic.
