# Rux Low ABI v1 Frontend Quickstart

This is the shortest practical path for writing an external image producer for `RUXI` image format version `1`.

Use this document as a checklist. The full contract is still `rux-low-image-v1.md`.

## Read These First

Required:

- `rux-low-image-v1.md`: binary layout and instruction semantics.
- `rux-low-image-v1-opcodes.json`: machine-readable opcode table.
- `rux-low-errors-v1.md`: stable error categories.
- `fixtures/README.md`: fixture format and conformance expectations.

Recommended:

- `cpp-frontend-notes.md`: lowering guidance for C++-style frontends.
- `PRE-FREEZE-GAPS.md`: intentionally omitted v1 opcodes and why.

## Minimal Image Shape

Every image starts with:

```text
magic:                "RUXI"
image_format_version: 1
memory_size:          u32
rodata:               byte_list
data:                 byte_list
bss_size:             u32
entry_function_index: i32 non-negative index
functions:            function_list
```

The entry function must have no parameters. It halts the program by executing one of the `Return*` instructions.

Smallest useful program:

```text
memory_size = 0
rodata = []
data = []
bss_size = 0
entry_function_index = 0
functions = [
  {
    name = "main"
    register_count = 1
    parameters = []
    instructions = [
      I32Const r0, 42
      ReturnI32 r0
    ]
  }
]
```

## Encoder Rules

- Encode all numeric fields little-endian.
- Encode `length` and `index` as signed `i32`; reject negative values when decoding and reject values larger than `i32::MAX` when encoding.
- Encode register ids as `u16`.
- Encode optional registers as `tag u8`: `0` for none, `1` followed by `u16` for some register.
- Reject images that reference registers outside `0..register_count`.
- Reject functions that fall through past the last instruction. End every function with `Jump` or `Return*`.
- Reject entry functions with parameters.
- Keep source-language metadata out of the runtime image. The ABI is only the machine contract.

## Register And Memory Model

Registers are frame-local 64-bit machine-word slots. Instruction names define how the VM interprets the bits.

Memory is byte-addressed. `Load16/32/64` and `Store16/32/64` are little-endian and do not require aligned addresses.

Address values are 32-bit byte addresses. `AddrAdd` wraps as a 32-bit address operation.

## Integer Lowering Rules

The VM defines arithmetic behavior. Do not inherit host-language undefined behavior into emitted images.

Use canonical signed-named opcodes for add/sub/mul when the unsigned operation has the same stored bit pattern:

| Source operation | Emit |
| --- | --- |
| `i32 add/sub/mul` | `I32Add` / `I32Sub` / `I32Mul` |
| `u32 add/sub/mul` | `I32Add` / `I32Sub` / `I32Mul` |
| `i64 add/sub/mul` | `I64Add` / `I64Sub` / `I64Mul` |
| `u64 add/sub/mul` | `I64Add` / `I64Sub` / `I64Mul` |

Use signedness-specific opcodes when signedness changes the result:

| Source operation | Signed emit | Unsigned emit |
| --- | --- | --- |
| division | `I32Div` / `I64Div` | `U32Div` / `U64Div` |
| remainder | `I32Rem` / `I64Rem` | `U32Rem` / `U64Rem` |
| less-than | `I32Lt` / `I64Lt` | `U32Lt` / `U64Lt` |
| right shift | `I32Shr` / `I64Shr` | `U32Shr` / `U64Shr` |

Equality compares raw bit patterns:

```text
i32/u32 equality -> I32Eq
i64/u64 equality -> I64Eq
```

Shift counts are unbounded. Counts outside the value width use the ABI-defined result, not CPU-masked shift behavior.

## Calls And Returns

`CallStatic` uses positional arguments. The VM copies raw 64-bit register slots into the callee parameter registers.

For non-entry calls:

- scalar return: encode `return_register = Some(reg)`;
- unit return: encode `return_register = None`.

Mismatching scalar/unit returns with the caller return register is a runtime error.

## Fixtures And Conformance

Use the checked-in fixtures as the external contract:

- `docs/abi/fixtures/*.ruxi`: binary images.
- `docs/abi/fixtures/*.json`: expected result manifests.

Run the reference conformance runner from `native/rux-vm`:

```bash
cargo run --example rux_abi_conformance
```

The expected result is:

```text
rux abi conformance: 16 fixtures passed
```

If you change the ABI intentionally before freeze, regenerate fixtures from `native/rux-vm`:

```bash
cargo run --example write_abi_fixtures
```

Then review the fixture diff before committing.

## Compatibility Policy

Before freeze, `image_format_version = 1` may still change in place.

After freeze, `RUXI` version `1` is immutable:

- do not reuse instruction tags;
- do not change operand encodings;
- do not change existing instruction semantics;
- do not change top-level image layout;
- keep v1 decode/run support and fixtures available.

Breaking changes require a new numeric image format version.
