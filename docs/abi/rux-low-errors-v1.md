# Rux Low Error Categories v1

## Status

Status: pre-freeze candidate.

This document names stable error categories for tools that decode, validate, generate, or execute `RUXI` low images. Exact human-readable messages may vary; categories are the compatibility surface.

## Decode Errors

Decode errors mean the byte stream is not a valid `RUXI` image.

| Category | Meaning |
| --- | --- |
| `InvalidMagic` | The first four bytes are not `RUXI`. |
| `UnsupportedVersion` | `image_format_version` is not supported by this decoder. |
| `UnexpectedEnd` | A serialized field extends past the end of the byte stream. |
| `InvalidUtf8` | A serialized string is not valid UTF-8. |
| `NegativeLength` | A serialized `length` field is negative. |
| `NegativeIndex` | A serialized `index` field is negative. |
| `UnknownOptionalRegisterTag` | An optional register tag is neither `0` nor `1`. |
| `UnknownInstructionTag` | An instruction tag is not defined for the image version. |

## Encode Errors

Encode errors mean an in-memory image cannot be represented in the v1 byte format.

| Category | Meaning |
| --- | --- |
| `LengthTooLarge` | A serialized list/string/byte length does not fit in non-negative `i32`. |
| `IndexTooLarge` | A serialized index does not fit in non-negative `i32`. |

## Validation Errors

Validation errors mean the byte stream decoded, but the executable image violates VM invariants.

| Category | Meaning |
| --- | --- |
| `EntryFunctionOutOfBounds` | `entry_function_index` does not refer to an existing function. |
| `EntryFunctionHasParameters` | The entry function declares parameters in a context that requires no-arg entry. |
| `EmptyFunction` | A function has no instructions. |
| `RegisterOutOfBounds` | A parameter or instruction references a register outside `register_count`. |
| `JumpTargetOutOfBounds` | A jump target does not refer to an instruction in the same function. |
| `InvalidCallTarget` | `CallStatic.function_index` does not refer to an existing function. |
| `CallArgumentCountMismatch` | `CallStatic.arguments.len` differs from callee parameter count. |
| `InvalidFunctionTerminator` | A function can fall through past its final instruction. |
| `MemorySectionsOverflow` | `rodata.len + data.len + bss_size` exceeds `memory_size`. |

## Runtime Errors

Runtime errors mean the image passed validation but trapped while executing.

| Category | Meaning |
| --- | --- |
| `DivideByZero` | Division or remainder used zero as the divisor. |
| `MemoryOutOfBounds` | A load/store touches memory outside the active memory bus. |
| `CallDepthExceeded` | Static calls exceeded the host's call-depth/resource limit. |
| `RuntimeResourceExceeded` | The host refused to continue because of a runtime resource limit. |

## Compatibility Rule

External tools should branch on categories, not message strings. Message text is diagnostic and may change before freeze.
