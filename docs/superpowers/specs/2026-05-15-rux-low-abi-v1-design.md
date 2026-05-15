# Rux Low Image ABI v1 Design

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
- `Addr*` instructions use 32-bit addresses.
- `Load8`/`Store8` operate on a single byte.
- `Load32`/`Store32` operate on a little-endian 32-bit word.

Division and remainder are defined separately for signed and unsigned values:

- `I32Div` and `I32Rem` interpret operands as signed `i32`;
- `U32Div` and `U32Rem` interpret operands as unsigned `u32`;
- division or remainder by zero is a VM execution error.

## Control Flow

Jump targets are instruction indices within the current function. Functions must end with `Jump` or `Return*`; implicit fallthrough past the final instruction is invalid.

Static calls transfer argument register values into the callee parameter registers by position. A non-unit return writes the return value into the caller return register.

`CallStatic` encodes arguments as a `register_id_list`, so the call ABI is positional and does not include names or source-language type metadata.

## Instruction Tags

Instruction tags are stable within `RUXI` version `1`. Tags must not be reused with different operands.

The same table is available in machine-readable form at `docs/superpowers/specs/2026-05-15-rux-low-abi-v1-opcodes.json`.

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

`RUXI` version `1` is the target for Rux and external compiler frontends. Any serialized layout change, instruction tag reuse, operand type change, or top-level field change requires a new numeric image format version.
