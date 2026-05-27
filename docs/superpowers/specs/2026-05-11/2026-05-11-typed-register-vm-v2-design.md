# Typed Register Bank VM Rewrite Design

## Goal

Replace the current CKL image VM execution core with a typed, predecoded Rust VM that stores scalar values in typed register banks while preserving the existing language, hostcall/signal boundary, daemon scheduler, filesystem/display model, and benchmark infrastructure.

## Motivation

The current Rust image runner already uses register-style instructions, but its register file is still dynamic:

- every register slot is a `VmValue`;
- integer operations clone `VmValue::Int` values before matching them back to integers;
- boolean and comparison operations also travel through generic value slots;
- function calls copy arguments as boxed `VmValue` values;
- hostcall-friendly value representation leaks into the compute hot path.

This is simple and flexible, but the compute benchmark shows the VM spends most of its time in interpreter/value/call machinery rather than CPU arithmetic. Because the mod is still in deep alpha, it is worth changing the execution architecture instead of only polishing the current dynamic register file.

## Non-Goals

- Do not add a JIT in this phase.
- Do not replace the Minecraft-facing daemon scheduler in the first slice.
- Do not expose raw pointers or unsafe host memory to CKL programs.
- Do not add linear RAM in the first slice.
- Do not make all CKL values live in linear RAM.
- Do not keep the old stack VM as a runtime fallback.
- Do not keep Kotlin execution fallbacks.

## Architecture

The replacement pipeline is:

```text
CKL frontend
  -> existing typed bytecode module
  -> typed register-bank CK image encoder
  -> Rust typed register-bank image decoder
  -> predecoded functions
  -> typed register-bank interpreter
  -> existing VM signals: Halt, Yield, Sleep, Wait*, HostCall, Error
```

This is a rewrite, not a fallback architecture. The old stack VM may be referenced by temporary parity tests during development, but production/runtime entry points should move to the register VM and fail fast if the register VM cannot execute an image.

## Register Banks

Each function declares fixed register counts per storage bank:

```rust
struct Function {
    name: String,
    i32_register_count: usize,
    i64_register_count: usize,
    bool_register_count: usize,
    ref_register_count: usize,
    parameters: Vec<TypedRegister>,
    instructions: Vec<Instruction>,
}
```

The runtime stores active frame registers in separate contiguous vectors:

```rust
struct RegisterBanks {
    i32_values: Vec<i32>,
    i64_values: Vec<i64>,
    bool_values: Vec<bool>,
    refs: Vec<HeapRef>,
}
```

Instructions address the bank implied by the opcode. For example, `I32Add dst, lhs, rhs` indexes only `i32_values`, while `JumpIfFalse cond, target` indexes only `bool_values`. The hot scalar path therefore never boxes integers into `VmValue` and never matches enum variants to recover primitive values.

## Register Frames

A call frame stores the base offset for each bank:

```rust
struct RegisterFrame {
    function_index: usize,
    instruction_pointer: usize,
    i32_base: usize,
    i64_base: usize,
    bool_base: usize,
    ref_base: usize,
    return_register: Option<TypedRegister>,
}
```

Function calls append a register window to each bank. Returns truncate each bank back to the caller frame and write the return value into the typed return register. This keeps function calls explicit without allocating per-call `Vec<VmValue>` locals.

## Boundary Value Representation

`VmValue` remains the external value type for hostcalls, signals, tests, snapshots, and diagnostics:

```rust
enum VmValue {
    Unit,
    Null,
    Bool(bool),
    Int(i32),
    Long(i64),
    String(String),
    Record { type_name: String, fields: Vec<(String, VmValue)> },
    ObjectRef(u32),
}
```

The interpreter must not use `VmValue` as scalar register storage. Conversions happen only at boundaries:

- hostcall arguments: typed registers to `Vec<VmValue>`;
- hostcall results: `VmValue` to a typed return register;
- halt signals: typed return value to `VmValue`;
- debugging/snapshot tooling: typed runtime state to `VmValue` when requested.

## Managed Heap And Deferred Linear RAM

VM internals use a managed heap for strings, arrays, records, lists, maps, and future objects:

```rust
enum HeapObject {
    String(String),
    Array(Vec<TypedValue>),
    Record { type_name: String, fields: Vec<TypedValue> },
    List(Vec<TypedValue>),
    Map(Vec<(TypedValue, TypedValue)>),
}
```

Heap values are referenced by `ref_registers`. Linear RAM is intentionally deferred. It will be added later as a separate byte-addressable subsystem for low-level programs, buffers, framebuffer-like data, and hand-edited binary workflows:

```rust
struct LinearMemory {
    bytes: Vec<u8>,
    quota_bytes: usize,
}
```

The first register-bank rewrite must not depend on linear memory. CKL can later expose buffers through safe APIs or low-level instructions using handles, offsets, and lengths.

## Instruction Set

The register-bank VM starts with typed instructions that cover the current compute benchmark and basic language constructs. Operand names are bank-local:

```text
I32Const i32_dst, const_id
I64Const i64_dst, const_id
BoolConst bool_dst, value
RefConst ref_dst, const_id
LoadUnit ref_dst
LoadNull ref_dst

I32Move i32_dst, i32_src
I64Move i64_dst, i64_src
BoolMove bool_dst, bool_src
RefMove ref_dst, ref_src

I32Add i32_dst, i32_lhs, i32_rhs
I32Sub i32_dst, i32_lhs, i32_rhs
I32Mul i32_dst, i32_lhs, i32_rhs
I32Div i32_dst, i32_lhs, i32_rhs
I32BitAnd i32_dst, i32_lhs, i32_rhs
I32BitOr i32_dst, i32_lhs, i32_rhs
I32BitXor i32_dst, i32_lhs, i32_rhs
I32Shl i32_dst, i32_lhs, i32_rhs
I32Shr i32_dst, i32_lhs, i32_rhs
I32Eq bool_dst, i32_lhs, i32_rhs
I32Lt bool_dst, i32_lhs, i32_rhs
I32Le bool_dst, i32_lhs, i32_rhs
I32Gt bool_dst, i32_lhs, i32_rhs
I32Ge bool_dst, i32_lhs, i32_rhs

BoolNot bool_dst, bool_src
BoolAnd bool_dst, bool_lhs, bool_rhs
BoolOr bool_dst, bool_lhs, bool_rhs

Jump target
JumpIfFalse bool_cond, target
JumpIfTrue bool_cond, target

CallStatic typed_return_dst, function_index, typed_arg_registers
Return typed_src
ReturnUnit

CallHost typed_return_dst, import_id, typed_arg_registers
Yield
Sleep i64_or_i32_ticks_reg
```

Later stages add string/record/list/map/array instructions and collection methods.

## Image ABI Strategy

Use one active image ABI after the rewrite:

- `CKIM` version `3`: typed register-bank image format.

The compiler should emit the typed register-bank format by default once the first full slice lands. The Rust decoder should reject unsupported or legacy image versions with a clear error instead of dispatching to old stack or dynamic-register runners.

## Hostcalls And Signals

The register-bank VM keeps the same external signal protocol:

- `Halt(value)`
- `Pause`
- `Yield`
- `Sleep(ticks)`
- `WaitEvent(filter)`
- `WaitPoll(channel, wakeSequence)`
- `WaitProcess(pid, wakeSequence)`
- `HostCall(module, function, args)`
- `Error(message)`

This keeps the daemon scheduler, process table, display pump, filesystem hostcalls, and Kotlin/JNI bridge stable while the execution core is replaced. Hostcall marshalling is the explicit conversion point between typed registers and `VmValue`.

## Rollout

1. Add typed register-bank image data model and ABI tests.
2. Replace the Kotlin image compiler output with register-bank images for a compute-only subset.
3. Replace the Rust image runner internals with a register-bank decoder and image-only runner.
4. Run compute benchmark parity against Kotlin/Python/Rust baselines.
5. Add control flow and function calls.
6. Add hostcall/scheduler signal support.
7. Add heap-backed strings and collections.
8. Move ROM tests to the register-bank VM.
9. Delete old stack opcodes, old dynamic-register image ABI support, and old runner data structures.
10. Add linear RAM as a later low-level programming feature after the register-bank VM is stable.

## Success Criteria

- Compute benchmark runs on the register-bank VM and matches Kotlin/JVM, Python, and Rust baselines.
- The register-bank VM supports `yield`, `sleep`, and hostcall signals through the same JNI protocol.
- Bundled ROM image compiles to the register-bank image format.
- NeoForge tests pass with only the register VM enabled.
- Old stack VM code, old dynamic-register opcodes, and old image execution paths are removed.
- Unsupported legacy images fail fast with a clear error.
