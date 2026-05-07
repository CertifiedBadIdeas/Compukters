# CkVmImage Control Flow and Locals Design

## Goal

Extend the Rust image VM from a host-call smoke runner into a minimal control-flow executor. The slice adds local variables and absolute byte-offset jumps to `CkVmImage`, so simple CKL programs with `val`, assignment, `if`, and boolean-driven `while` loops can lower to CKIM and execute through `NativeImageVmRunner`.

## Current State

The image runtime path is now the only runtime execution path. Kotlin still compiles CKL source into `BytecodeModule` as temporary compiler scaffolding, then lowers supported instructions into `CkVmImage`. The current image backend and Rust runner support only:

- `PUSH_UNIT`
- `RETURN`
- `PUSH_CONSTANT`
- `CALL_HOST`
- `POP`

Unsupported `Instruction` variants fail during image lowering with a clear `UnsupportedOperationException`.

## Scope

Add support for these `Instruction` variants:

- `Instruction.PushBool`
- `Instruction.PushNull`
- `Instruction.LoadLocal`
- `Instruction.StoreLocal`
- `Instruction.Jump`
- `Instruction.JumpIfFalse`
- `Instruction.JumpIfTrue`

This enables sequential programs with local state and basic branch/loop structure. It does not attempt to make the bundled ROM fully runnable yet.

## Non-Goals

This slice does not add:

- arithmetic, comparison, string, or bitwise `Binary` operations;
- `Unary` operations;
- `CallFunction` or multi-frame function calls;
- fields, records, classes, or object identity;
- arrays, lists, maps, indexing, or collection methods;
- a typed IR replacement for `BytecodeModule`.

Those remain separate follow-up slices.

## Image Opcode Model

Add new opcode constants in `CkVmImageOpcodes` and matching Rust constants:

| Opcode | Operand | Stack effect |
|---|---:|---|
| `PUSH_BOOL` | one byte: `0` or `1` | push `Bool` |
| `PUSH_NULL` | none | push `Null` |
| `LOAD_LOCAL` | `i32 slot` | push local value |
| `STORE_LOCAL` | `i32 slot` | pop value into local slot |
| `JUMP` | `i32 byteOffset` | set instruction pointer |
| `JUMP_IF_FALSE` | `i32 byteOffset` | pop `Bool`; jump if false |
| `JUMP_IF_TRUE` | `i32 byteOffset` | pop `Bool`; jump if true |

Jump operands are absolute byte offsets within the current image function code. They are not source-instruction indexes.

`CkVmImageAbi` does not need a container-format change because function bytecode is already serialized as raw bytes. The CKIM ABI version can stay unchanged for this development branch unless compatibility with older generated image files becomes important.

## Kotlin Backend Lowering

The backend must translate source-instruction jump targets into byte offsets. A single-pass `flatMap(::lowerInstruction)` is not enough because branch operand widths make byte offsets differ from source instruction indexes.

Use a two-phase lowering per function:

1. Measure each `Instruction` encoded length and build `instructionIndexToByteOffset`.
2. Encode each instruction, resolving jump targets through the offset table.

Validation rules:

- Negative jump targets fail during lowering.
- Jump targets greater than the instruction count fail during lowering.
- A jump target equal to `instructions.size` is valid and points to the end of the function code.
- Local slot operands are emitted as-is; runtime validates frame bounds.

Existing host import collection remains unchanged.

## Rust Image Runner Semantics

`ImageVmHandle` gains a `locals: Vec<VmValue>` initialized from `Function.frame_size` with `VmValue::Unit`.

Runtime rules:

- `LOAD_LOCAL` validates that the slot is non-negative and inside `locals`, then clones the value onto the stack.
- `STORE_LOCAL` validates the slot, pops one stack value, and writes it into `locals`.
- `JUMP` validates the target byte offset and sets `instruction_pointer`.
- `JUMP_IF_FALSE` and `JUMP_IF_TRUE` pop one value. Only `VmValue::Bool` is accepted as a condition.
- Jump targets may equal `code.len()`, which makes the next fetch halt with `Unit` if the program reaches the end without `RETURN`.
- All invalid runtime states return encoded error signals, not panics.

The instruction-budget pause check continues to run after successfully executing an instruction. A jump instruction counts as one instruction.

## Error Handling

Errors must be deterministic and specific enough for tests:

- local slot is negative;
- local slot is out of bounds;
- local load/store stack underflow;
- jump target is negative;
- jump target is outside the current function code;
- conditional jump condition is not `Bool`;
- malformed instruction stream ends inside an operand.

## Tests

Kotlin backend tests should cover:

- `PushBool` and `PushNull` lowering;
- `LoadLocal`/`StoreLocal` lowering from a small `val` or assignment program;
- `if (true) { system::log("x"); }` lowers without unsupported-instruction failure;
- jump target operands are byte offsets, not instruction indexes;
- the existing unsupported-instruction test moves to a still-unsupported instruction such as `Binary`.

Rust tests should cover manually encoded CKIM images that:

- store and load a local value, then return it;
- take `JUMP_IF_FALSE` false branch;
- skip a branch with `JUMP`;
- return encoded errors for non-bool conditions and out-of-range jumps.

JNI/integration tests should cover a CKL program that uses `if` plus a host call and executes through `NativeImageVmRunner`.

## Acceptance Criteria

- `CkVmImageCompiler.compile(...)` supports the scoped instruction variants.
- Rust `ImageVmHandle` executes the new opcodes correctly.
- Existing image ABI tests still pass.
- Existing JNI image runner tests still pass.
- A new end-to-end test proves a simple CKL `if` program reaches the expected host call through the Rust image VM.
- The branch does not introduce support for arithmetic, function calls, or object/collection operations in this slice.
