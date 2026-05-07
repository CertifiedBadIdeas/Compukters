# CKVM Image Runtime Parity Design

## Goal

Restore execution of real CKL programs on the Rust `CkVmImage` runtime. The target is not a single opcode slice; the target is runtime parity for bundled firmware/ROM programs and typical CKL user programs that currently compile through `BytecodeModule` scaffolding but fail during image lowering or native execution.

The milestone keeps the Rust image runtime as the only execution path. It does not restore the deleted JVM bytecode VM or the deleted legacy native bytecode ABI.

## Current State

`CkVmImage` currently supports a small control-flow core:

- `PUSH_UNIT`
- `RETURN`
- `PUSH_CONSTANT`
- `CALL_HOST`
- `POP`
- `PUSH_BOOL`
- `PUSH_NULL`
- `LOAD_LOCAL`
- `STORE_LOCAL`
- `JUMP`
- `JUMP_IF_FALSE`
- `JUMP_IF_TRUE`

This is enough for host-call smoke tests and simple local/control-flow programs. It is not enough for bundled CKL programs. Firmware, ROM terminal, shell, stdio, and utilities use operators, user-defined functions, records, field access, and collections.

## Non-Goals

- Do not change CKL source syntax.
- Do not redesign the frontend or type checker.
- Do not reintroduce the old JVM bytecode runtime.
- Do not reintroduce the deleted legacy native bytecode ABI.
- Do not optimize bytecode or introduce typed IR in this milestone.
- Do not implement classes before records and collections unless a parity test proves they are required earlier.

## Strategy

Implement runtime parity as a sequence of dependency-ordered slices. Each slice must be independently tested and committed. The final acceptance test should exercise bundled CKL resources through the image runtime rather than only asserting individual opcode behavior.

### Slice 1: Operators

Add image support for `Instruction.Binary` and `Instruction.Unary`.

The backend should lower each operation as an opcode plus a compact operator tag. The operator tag should follow the existing enum ordinal ordering unless a dedicated stable mapping is introduced in the same slice.

Rust execution must support:

- `Int` and `Long` arithmetic: `+`, `-`, `*`, `/`.
- `String +` concatenation using CKL frontend semantics where either operand may be `String`; non-string operands must use a deterministic CKL value-to-string conversion.
- Equality and inequality for `Unit`, `Null`, `Bool`, `Int`, `Long`, and `String`. Record, collection, and object equality are deferred until those value kinds are implemented in later slices.
- Ordered comparisons for numeric operands and `String` operands. Numeric comparisons should support `Int`/`Long` widening; string comparisons should be lexicographic and deterministic.
- `Bool` logical operators `&&` and `||` as value-level binary operations over already-evaluated operands.
- Bitwise operators `&`, `|`, `^`, `<<`, `>>` for numeric values.
- Unary `-`, `!`, and `~`.

Runtime errors must be deterministic for wrong operand types, invalid operator tags, stack underflow, and division by zero.

### Slice 2: User Function Calls

Add image support for `Instruction.CallFunction`.

The Rust runner should move from a single active function frame to an explicit call-frame model. A frame contains:

- function index;
- instruction pointer;
- locals;
- return destination in the caller.

Calling a function should pop arguments in call order, initialize parameter locals, allocate remaining locals as `Unit`, switch to the callee, and resume execution at byte offset `0`.

Returning from a nested function should restore the caller frame and push the return value onto the caller stack. Returning from the entry frame should halt the VM.

Runtime errors must be deterministic for invalid function indexes, invalid argument counts, negative frame sizes, and stack underflow.

### Slice 3: Records and Fields

Add image support for:

- `Instruction.ConstructRecord`
- `Instruction.GetField`

Records should use the existing `VmValue::Record` representation where practical. Record construction should preserve field names and source declaration order. Field access should validate receiver type and field presence rather than silently producing unrelated values.

Do not implement `Instruction.SetField` in this slice. Current `SetField` emission is tied to class/object mutation and class initialization, while CKL structs are value-shaped records. Field mutation belongs with the class/object slice.

This slice should unlock state structs used by `stdio.ck`, `terminal.ck`, shell contexts, and utility programs.

### Slice 4: Collections

Add image support for:

- `Instruction.ConstructArray`
- `Instruction.ConstructList`
- `Instruction.ConstructMap`
- `Instruction.IndexGet`
- `Instruction.IndexSet`
- `Instruction.CallCollectionMethod`

Collections require a deliberate runtime representation because arrays, lists, and maps are mutable and need identity-like behavior for writes. The preferred design is a Rust-side VM heap owned by `ImageVmHandle`, with `VmValue` handles pointing to heap objects. The detailed heap model should be specified before this slice is implemented.

Collection method dispatch should match the CKL language documentation and frontend type rules for arrays, lists, and maps.

### Slice 5: Classes and Object Methods

Add class/object support after records and collections unless bundled parity requires it earlier.

Candidate instructions:

- `Instruction.ConstructClass`
- `Instruction.SetField`
- `Instruction.CallMethod`
- `Instruction.CallStaticMethod`

This slice likely requires image metadata for classes, fields, method indexes, and init behavior. It should be designed separately because it introduces object identity and method lookup beyond record value semantics.

## Image Format Considerations

Opcode numbers must stay synchronized between Kotlin `CkVmImageOpcodes`, Rust `image_runner.rs`, and tests. New opcodes should be appended after the existing control-flow opcodes to avoid renumbering existing images.

Jump operands remain absolute byte offsets within the current function. Function-call operands use function indexes from the image function table, not byte offsets.

For strings used as metadata operands, prefer the existing image constant pool unless a later format revision introduces a dedicated symbol table.

## Testing Plan

Every slice should follow the same test shape:

1. Kotlin backend RED tests that assert image lowering for the relevant `Instruction` variants.
2. Rust image runner RED tests that execute hand-built image bytes and assert halt/host/error signals.
3. JNI end-to-end tests that compile CKL source to image and run it through `NativeImageVmRunner`.
4. Focused stale-reference checks for unsupported-instruction diagnostics removed by the slice.

After the operator, function-call, record, and collection slices, add a parity-oriented integration test that compiles bundled CKL resources into `CkVmImage` and executes non-interactive smoke scenarios through the native runner where possible.

## Acceptance Criteria

The runtime parity milestone is complete when:

- Bundled firmware/ROM CKL resources no longer fail image lowering because of unsupported instruction variants used by those resources.
- Focused native runner tests pass for operators, functions, records, fields, and collections.
- JNI end-to-end tests pass for representative CKL programs using those features together.
- Rust tests pass for the native VM crate.
- Focused Gradle tests pass with the Rust native library configured.
- The old JVM bytecode runtime and deleted legacy native bytecode ABI are not reintroduced.

## Risks

- Collections and classes require heap/object identity decisions that are larger than simple opcode lowering.
- Logical `&&` and `||` are currently represented as binary instructions after both operands are compiled; this milestone should preserve current frontend behavior rather than adding short-circuit semantics unless the frontend changes separately.
- Equality for composite values must be specified carefully before it is relied on by user-visible APIs.
- ROM parity tests may need host-call fakes for display, filesystem, process, stdio, and IPC APIs.

## Recommended Next Step

Start with Slice 1: operators. It unlocks arithmetic, comparisons, boolean logic, bitwise operations, and real loop conditions. It is also the smallest slice that directly supports the broader runtime parity goal.