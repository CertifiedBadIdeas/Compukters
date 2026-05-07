# CKVM Image CallFunction Design

## Goal

Add `Instruction.CallFunction` support to `CkVmImage` and the Rust native image runner. This is the second runtime parity slice after operators and is required for bundled CKL programs that organize behavior through user-defined functions.

## Scope

This slice adds direct user-function calls by function table index.

Included:

- Kotlin image opcode `CALL_FUNCTION = 15`.
- Kotlin lowering for `Instruction.CallFunction(functionIndex, argumentCount)`.
- Rust call-frame support in `ImageVmHandle`.
- Parameter local initialization from stack arguments.
- Return from callee to caller.
- Entry-frame return as VM halt.
- Host calls and pause/resume behavior across callee frames.

Excluded:

- Instance method dispatch.
- Static method dispatch.
- Class/object construction.
- Records, fields, and collections.
- Tail-call optimization.
- Recursion depth policy beyond the existing instruction budget.
- Image format changes for parameter metadata.

## Image Encoding

Append one opcode after the operator slice:

| Opcode | Name | Operands |
| --- | --- | --- |
| `15` | `CALL_FUNCTION` | `i32 functionIndex`, `i32 argumentCount` |

`functionIndex` is an index into the image function table. `argumentCount` is the number of values already pushed onto the VM value stack by the caller.

The opcode does not encode parameter names or parameter count. The current image function record only stores `frameSize`, so runtime validation is limited to function bounds, non-negative counts, stack availability, and `argumentCount <= frameSize`.

## Kotlin Backend

The backend should treat `Instruction.CallFunction` as a fixed-length nine-byte instruction:

- one opcode byte;
- four bytes for function index;
- four bytes for argument count.

Lowering must use existing `i32()` little-endian encoding. The source `functionIndex` is already produced by `LanguageFrontend` from the `BytecodeModule.functions` order.

## Rust Runtime Architecture

The current runner stores one active frame directly in `ImageVmHandle`:

- `function_index`
- `instruction_pointer`
- `locals`

This slice adds a saved caller frame stack:

```rust
struct CallFrame {
    function_index: usize,
    instruction_pointer: usize,
    locals: Vec<VmValue>,
}
```

`ImageVmHandle` keeps the current active frame in the existing fields and adds:

```rust
call_stack: Vec<CallFrame>
```

This avoids rewriting every opcode handler. Existing helpers such as `current_function()`, `local()`, `local_mut()`, and `jump()` continue to operate on the active frame.

## Call Semantics

`CALL_FUNCTION` executes as follows:

1. Read `functionIndex` and `argumentCount`.
2. Validate `functionIndex >= 0` and in bounds.
3. Validate `argumentCount >= 0`.
4. Pop `argumentCount` values from the VM stack using call order preservation.
5. Validate `argumentCount <= callee.frameSize`.
6. Save the current active frame to `call_stack`.
7. Switch the active frame to the callee:
   - `function_index = functionIndex`;
   - `instruction_pointer = 0`;
   - `locals = vec![Unit; callee.frameSize]`.
8. Copy popped arguments into `locals[0..argumentCount]` in original call order.

`pop_many()` already returns values in original stack push order because it uses `split_off(start)`. This order should be preserved.

## Return Semantics

`RETURN` pops the return value from the active stack, defaulting to `Unit` if no value is present.

If `call_stack` is empty, `RETURN` halts the VM with the return value.

If `call_stack` is not empty, `RETURN` restores the caller frame and pushes the return value onto the caller's value stack. Execution then continues in the caller at the saved instruction pointer.

The value stack is shared across frames. Function arguments are removed before entering the callee, and the callee result is pushed for the caller after return.

## Host Calls and Pause/Resume

Host calls inside callees should behave like host calls in the entry function. When `CALL_HOST` returns `HostCall`, the active frame remains the callee frame. `resume_with_value_bytes()` pushes the host result onto the shared value stack and sets the VM state back to `Ready`. Execution resumes in the same active function and instruction pointer.

Instruction budget pauses should also preserve the active frame and `call_stack` unchanged.

## Error Semantics

Rust runner errors must be deterministic and encoded through the existing error signal path.

Required errors:

- negative function index;
- function index out of bounds;
- negative argument count;
- argument count greater than callee frame size;
- stack underflow while popping arguments;
- negative callee frame size.

The runtime cannot validate exact parameter count yet because the image does not encode it. That can be added in a later image format revision if required.

## Testing

Required test layers:

1. Kotlin backend tests for `CALL_FUNCTION` lowering and fixed instruction length.
2. Rust direct image runner tests for:
   - returning a callee value to the entry frame;
   - parameter locals initialized from arguments;
   - caller locals restored after return;
   - nested function calls;
   - host call inside callee followed by resume;
   - invalid function index;
   - argument count greater than frame size;
   - call stack underflow prevention through normal entry return behavior.
3. JNI end-to-end test from CKL source through `NativeImageVmRunner`.
4. Stale unsupported-reference checks for `CallFunction`.

## Acceptance Criteria

This slice is complete when:

- Kotlin image backend lowers `Instruction.CallFunction` to opcode `15`.
- Rust image runner executes function calls with correct frame save/restore behavior.
- Return values from nested functions appear on the caller stack.
- Host calls inside callees work through JNI resume flow.
- Existing locals, jumps, operators, and host-call tests still pass.
- Active source/test code no longer reports `CkVmImage backend does not support CallFunction`.