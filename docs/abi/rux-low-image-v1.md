# Rux Low Image ABI v1 Specification

## Status

Status: pre-freeze candidate.

This ABI may still change in place until the first external image producer starts targeting it, or until the project explicitly marks it frozen. During this pre-freeze window, changes may still keep `image_format_version = 1`.

After freeze, `RUXI` version `1` becomes immutable. Breaking changes require a new numeric image format version, and existing v1 decode/run support and fixtures must remain available for compatibility.

## Goal

Define the first stable low-level Rux VM image ABI for compiler frontends that want to target the VM directly.

## Non-Goals

- Do not define the Rux source language syntax.
- Do not require frontends to use the Rust Rux compiler.
- Do not preserve the old `CKIM` low-image header.
- Do not include source-language metadata in the runtime image contract.

## Identity

The image starts with a fixed header:

```text
magic:                "RUXI"
image_format_version: 1
```

The numeric image format version is the compatibility key. A decoder must reject unknown versions instead of trying to guess layout compatibility.

There is no `language_version` string in the low image. Source languages and producers are build-tool concerns, not VM ABI concerns.

## Primitive Encoding

All numeric fields are little-endian.

```text
u8:       1 byte
u16:      2 bytes, little-endian
u32:      4 bytes, little-endian
i32:      4 bytes, little-endian two's-complement
u64:      8 bytes, little-endian
i64:      8 bytes, little-endian two's-complement
index:    i32, must be non-negative
length:   i32, must be non-negative
string:   length + UTF-8 bytes, no trailing zero
bytes:    length + raw bytes
list<T>:  length + T repeated length times
```

An encoder must fail rather than truncate when a serialized `length` or `index` does not fit into a non-negative `i32`.

Optional registers use an explicit tag:

```text
None:       tag u8 = 0
Some(reg):  tag u8 = 1, then reg u16
```

## Top-Level Layout

After the header, fields are encoded in this order:

```text
memory_size:          u32
rodata:               byte_list
data:                 byte_list
bss_size:             u32
entry_function_index: i32 non-negative index
functions:            function_list
```

`rodata`, `data`, and `bss` initialize the beginning of the machine memory in that order. The initialized section length must fit within `memory_size`.

## Function Layout

Each function is encoded as:

```text
name:            string
register_count:  u16
parameters:      register_id_list
instructions:    instruction_list
```

Register ids are `u16`. A function can address at most `65535` registers. Every register operand must be lower than the function `register_count`.

The runtime may widen register ids and register counts to `usize` in predecoded/internal structures. That widening is not part of the serialized ABI.

## Register Model

Low VM registers are untyped machine-word slots. Instruction names define how bits are interpreted:

- `I32*` instructions use signed 32-bit integer semantics.
- `U32*` instructions use unsigned 32-bit integer semantics.
- `I64*` instructions use signed 64-bit integer semantics or raw 64-bit bit patterns where noted.
- `U64*` instructions use unsigned 64-bit integer semantics.
- `Addr*` instructions use 32-bit addresses.
- `Load8`/`Store8` operate on a single byte.
- `Load16`/`Store16` operate on a little-endian 16-bit word.
- `Load32`/`Store32` operate on a little-endian 32-bit word.
- `Load64`/`Store64` operate on a little-endian 64-bit word.

Memory is byte-addressed. `Load16`/`Store16`, `Load32`/`Store32`, and `Load64`/`Store64` do not require aligned addresses. Loads zero-extend `u8`/`u16` values into the destination register. `Load32` zeroes the high 32 bits of the destination register. `Load64` writes all 64 bits. Stores use the low 8/16/32/64 bits of the source register.

## Arithmetic Semantics

Arithmetic is defined by the VM. It is never host-language undefined behavior.

Signed and unsigned integer values are stored in the same 64-bit machine-word register slots. Instruction names define how bits are interpreted:

- `I32Add`, `I32Sub`, and `I32Mul` use signed `i32` wrapping arithmetic.
- `I32BitAnd`, `I32BitOr`, and `I32BitXor` operate on the raw 32-bit pattern.
- `I32Lt` interprets both operands as signed `i32`.
- `I32Eq` compares the raw 32-bit pattern.
- `U32Lt`, `U32Div`, `U32Rem`, `U32Shl`, and `U32Shr` interpret operands as unsigned `u32`.
- `I64Add`, `I64Sub`, and `I64Mul` use signed `i64` wrapping arithmetic.
- `I64BitAnd`, `I64BitOr`, and `I64BitXor` operate on the raw 64-bit pattern.
- `I64Lt` interprets both operands as signed `i64`.
- `I64Eq` compares the raw 64-bit pattern.
- `U64Lt`, `U64Div`, `U64Rem`, `U64Shl`, and `U64Shr` interpret operands as unsigned `u64`.
- `I32ToI64` sign-extends the low 32 bits into 64 bits.
- `U32ToU64` zero-extends the low 32 bits into 64 bits.
- `I64ToI32` truncates to the low 32 bits and zeroes the destination register's high 32 bits.
- `AddrAdd` uses 32-bit address wrapping arithmetic.

Shift operations use unbounded shift counts, not masked CPU-style shift counts:

- 32-bit left shift with a count outside `0..32` produces `0`;
- 32-bit unsigned right shift with a count outside `0..32` produces `0`;
- 32-bit signed right shift with a count outside `0..32` produces `-1` for negative values and `0` otherwise;
- 64-bit left shift with a count outside `0..64` produces `0`;
- 64-bit unsigned right shift with a count outside `0..64` produces `0`;
- 64-bit signed right shift with a count outside `0..64` produces `-1` for negative values and `0` otherwise.

Division and remainder are defined separately for signed and unsigned values:

- `I32Div` and `I32Rem` interpret operands as signed `i32`;
- `U32Div` and `U32Rem` interpret operands as unsigned `u32`;
- `I64Div` and `I64Rem` interpret operands as signed `i64`;
- `U64Div` and `U64Rem` interpret operands as unsigned `u64`;
- division or remainder by zero is a VM execution error.

## Control Flow

Jump targets are instruction indices within the current function. Functions must end with `Jump` or `Return*`; implicit fallthrough past the final instruction is invalid.

Static calls transfer argument register values into the callee parameter registers by position. A non-unit return writes the return value into the caller return register.

`CallStatic` encodes arguments as a `register_id_list`, so the call ABI is positional and does not include names or source-language type metadata.

## Instruction Tags

Instruction tags are stable within `RUXI` version `1`. Tags must not be reused with different operands.

The same table is available in machine-readable form at `docs/abi/rux-low-image-v1-opcodes.json`.

| Tag | Instruction | Operands |
| --- | --- | --- |
| 1 | `I32Const` | `dst: u16, value: i32` |
| 2 | `I64Const` | `dst: u16, value: i64` |
| 3 | `AddrConst` | `dst: u16, value: u32` |
| 4 | `I32Move` | `dst: u16, src: u16` |
| 5 | `AddrMove` | `dst: u16, src: u16` |
| 6 | `I32Add` | `dst: u16, lhs: u16, rhs: u16` |
| 7 | `I32Sub` | `dst: u16, lhs: u16, rhs: u16` |
| 8 | `I32Mul` | `dst: u16, lhs: u16, rhs: u16` |
| 9 | `I32Div` | `dst: u16, lhs: u16, rhs: u16` |
| 10 | `I32BitXor` | `dst: u16, lhs: u16, rhs: u16` |
| 11 | `I32Shl` | `dst: u16, lhs: u16, rhs: u16` |
| 12 | `I32Shr` | `dst: u16, lhs: u16, rhs: u16` |
| 13 | `I32Lt` | `dst: u16, lhs: u16, rhs: u16` |
| 14 | `Load32` | `dst: u16, addr: u16` |
| 15 | `Store32` | `addr: u16, src: u16` |
| 16 | `AddrAdd` | `dst: u16, base: u16, offset: u16` |
| 17 | `Jump` | `target: index` |
| 18 | `JumpIfFalse` | `cond: u16, target: index` |
| 19 | `CallStatic` | `return_register: optional_register, function_index: index, arguments: register_id_list` |
| 20 | `ReturnI32` | `src: u16` |
| 21 | `ReturnUnit` | none |
| 22 | `ReturnI64` | `src: u16` |
| 23 | `ReturnAddr` | `src: u16` |
| 24 | `ReturnBool` | `src: u16` |
| 25 | `I32Eq` | `dst: u16, lhs: u16, rhs: u16` |
| 26 | `I32BitAnd` | `dst: u16, lhs: u16, rhs: u16` |
| 27 | `I32BitOr` | `dst: u16, lhs: u16, rhs: u16` |
| 28 | `U32Lt` | `dst: u16, lhs: u16, rhs: u16` |
| 29 | `U32Shl` | `dst: u16, lhs: u16, rhs: u16` |
| 30 | `U32Shr` | `dst: u16, lhs: u16, rhs: u16` |
| 31 | `Load8` | `dst: u16, addr: u16` |
| 32 | `Store8` | `addr: u16, src: u16` |
| 33 | `I32Rem` | `dst: u16, lhs: u16, rhs: u16` |
| 34 | `U32Div` | `dst: u16, lhs: u16, rhs: u16` |
| 35 | `U32Rem` | `dst: u16, lhs: u16, rhs: u16` |
| 36 | `Load16` | `dst: u16, addr: u16` |
| 37 | `Store16` | `addr: u16, src: u16` |
| 38 | `U64Const` | `dst: u16, value: u64` |
| 39 | `Load64` | `dst: u16, addr: u16` |
| 40 | `Store64` | `addr: u16, src: u16` |
| 41 | `I64Add` | `dst: u16, lhs: u16, rhs: u16` |
| 42 | `I64Sub` | `dst: u16, lhs: u16, rhs: u16` |
| 43 | `I64Mul` | `dst: u16, lhs: u16, rhs: u16` |
| 44 | `I64Div` | `dst: u16, lhs: u16, rhs: u16` |
| 45 | `I64Rem` | `dst: u16, lhs: u16, rhs: u16` |
| 46 | `U64Div` | `dst: u16, lhs: u16, rhs: u16` |
| 47 | `U64Rem` | `dst: u16, lhs: u16, rhs: u16` |
| 48 | `I64BitAnd` | `dst: u16, lhs: u16, rhs: u16` |
| 49 | `I64BitOr` | `dst: u16, lhs: u16, rhs: u16` |
| 50 | `I64BitXor` | `dst: u16, lhs: u16, rhs: u16` |
| 51 | `I64Shl` | `dst: u16, lhs: u16, rhs: u16` |
| 52 | `I64Shr` | `dst: u16, lhs: u16, rhs: u16` |
| 53 | `U64Shr` | `dst: u16, lhs: u16, rhs: u16` |
| 54 | `I64Eq` | `dst: u16, lhs: u16, rhs: u16` |
| 55 | `I64Lt` | `dst: u16, lhs: u16, rhs: u16` |
| 56 | `U64Lt` | `dst: u16, lhs: u16, rhs: u16` |
| 57 | `I32ToI64` | `dst: u16, src: u16` |
| 58 | `U32ToU64` | `dst: u16, src: u16` |
| 59 | `I64ToI32` | `dst: u16, src: u16` |
| 60 | `U64Shl` | `dst: u16, lhs: u16, rhs: u16` |

## Validation

The VM validates images before execution:

- entry function index is in bounds;
- functions are non-empty;
- parameter and instruction register ids are in bounds;
- jump targets are in bounds;
- static call targets are in bounds;
- static call argument count matches callee parameter count;
- memory initialization sections fit in `memory_size`.

Invalid images fail at load/create time instead of producing partial execution.

## Execution Errors

The ABI separates image validation errors from runtime execution errors. A valid image can still trap while running if it executes an operation that cannot complete.

Runtime errors include:

- division or remainder by zero;
- memory load/store outside machine RAM or mapped devices;
- falling through past the final instruction if validation did not reject the image first;
- stack/frame overflow caused by calls exceeding the runtime call-depth limit.

These are VM errors, not undefined behavior. The VM must report an error signal/state instead of corrupting host memory.

## Reference Encoder

The Rust VM crate exposes a reference encoder:

```rust
encode_image(image: &Image) -> Result<Vec<u8>, ImageEncodeError>
```

External compiler frontends should treat the reference encoder, decode tests, and golden fixtures as executable ABI examples. The encoder is intentionally strict: oversized lengths and indices are errors instead of lossy casts.

## Stability Policy

`RUXI` version `1` is the target for Rux and external compiler frontends.

While the ABI status is `pre-freeze candidate`, this document may still change in place. After freeze, any serialized layout change, instruction tag reuse, operand type change, top-level field change, or semantic change to an existing instruction requires a new numeric image format version.

After freeze, new instructions also require a new numeric image format version. This keeps v1 decoders simple: an unknown instruction tag remains an invalid v1 image, not a partially supported extension.
