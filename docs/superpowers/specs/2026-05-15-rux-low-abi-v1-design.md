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

## Control Flow

Jump targets are instruction indices within the current function. Functions must end with `Jump` or `Return*`; implicit fallthrough past the final instruction is invalid.

Static calls transfer argument register values into the callee parameter registers by position. A non-unit return writes the return value into the caller return register.

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

## Stability Policy

`RUXI` version `1` is the target for Rux and external compiler frontends. Any serialized layout change, instruction tag reuse, operand type change, or top-level field change requires a new numeric image format version.
