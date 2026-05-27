# CKVM Image Records and Field Access Design

## Goal

Add `CkVmImage` runtime support for CKL struct value construction and field reads. After this slice, simple CKL programs that construct `struct` values and read fields with `.` should compile to image bytecode and execute in the Rust native image runner.

This design is part of the larger CKVM image runtime parity milestone. It keeps the Rust image runtime as the only execution path and does not restore the deleted JVM bytecode VM or the deleted legacy native bytecode ABI.

## Scope

Implement image support for:

- `Instruction.ConstructRecord`
- `Instruction.GetField`

Explicitly defer:

- `Instruction.SetField`
- `Instruction.ConstructClass`
- `Instruction.CallMethod`
- `Instruction.CallStaticMethod`
- collections and indexed access

The earlier runtime parity design listed `SetField` in the records/fields slice. This slice narrows that plan: CKL structs are value-shaped records, while current `SetField` emission is tied to class/object mutation and class initialization. Implementing `SetField` without object construction and heap semantics would either invent copy-update behavior that CKL does not currently require or provide incomplete class mutation support. `SetField` belongs in the class/object slice.

## Existing Foundation

The value model already has record support on both sides:

- Kotlin runtime values include `VmValue.RecordValue(typeName, fields)`.
- Rust runtime values include `VmValue::Record { type_name, fields }`.
- The native signal codec already encodes and decodes records.

The compiler frontend already emits:

- `ConstructRecord(typeName, fieldNames)` for struct constructor calls and record construction expressions.
- `GetField(fieldName)` for member access.

`ConstructRecord` values are emitted after the field expressions are compiled. `GetField` is emitted after the receiver expression is compiled.

## Image ABI

Append new opcodes after the current `CALL_FUNCTION = 15` opcode:

- `CONSTRUCT_RECORD = 16`
- `GET_FIELD = 17`

Do not renumber existing opcodes.

Use the existing image constant pool for record metadata strings. The backend must ensure that all type names and field names used by these instructions are present as string constants.

### `CONSTRUCT_RECORD` Encoding

`CONSTRUCT_RECORD` is encoded as:

1. opcode byte (`16`)
2. little-endian `i32 typeNameConstantIndex`
3. little-endian `i32 fieldCount`
4. `fieldCount` little-endian `i32 fieldNameConstantIndex` operands

All metadata indexes must refer to `StringConstant` entries.

### `GET_FIELD` Encoding

`GET_FIELD` is encoded as:

1. opcode byte (`17`)
2. little-endian `i32 fieldNameConstantIndex`

The metadata index must refer to a `StringConstant` entry.

## Kotlin Lowering

`CkVmImageBackend` should lower `ConstructRecord` by:

1. calculating instruction length as `1 + 4 + 4 + 4 * fieldNames.size`;
2. adding/reusing a string constant for the record type name;
3. adding/reusing string constants for all field names;
4. writing the opcode, type-name constant index, field count, and field-name constant indexes in frontend order.

`CkVmImageBackend` should lower `GetField` by:

1. calculating instruction length as `1 + 4`;
2. adding/reusing a string constant for the field name;
3. writing the opcode and field-name constant index.

The backend unsupported-instruction test should move from `ConstructRecord` to the next actual unsupported instruction after this slice.

## Rust Execution Semantics

### `CONSTRUCT_RECORD`

The Rust image runner should:

1. read the type-name constant index;
2. read `fieldCount`;
3. reject negative `fieldCount`;
4. read exactly `fieldCount` field-name constant indexes;
5. validate that all metadata indexes are in range and refer to string constants;
6. pop `fieldCount` values from the operand stack;
7. preserve frontend field order when pairing names with values;
8. push `VmValue::Record { type_name, fields }`.

If the frontend compiled field expressions in order `[x, y]`, and the stack contains those values in that order, the constructed Rust record must have fields `[('x', xValue), ('y', yValue)]`. The implementation should use an order-preserving pop strategy like the function-call argument handling rather than reversing fields accidentally.

### `GET_FIELD`

The Rust image runner should:

1. read and validate the field-name constant index;
2. pop one receiver value;
3. require the receiver to be `VmValue::Record`;
4. find the first field with the requested name;
5. push a clone of the stored value.

Runtime errors must be deterministic for:

- invalid metadata constant indexes;
- metadata constants that are not strings;
- truncated instruction operands;
- negative field counts;
- stack underflow;
- `GET_FIELD` on a non-record receiver;
- missing fields.

## Non-Goals

- Do not add record mutation or copy-update syntax.
- Do not implement `SetField` in this slice.
- Do not implement class construction, object references, method calls, or object field mutation.
- Do not add a new symbol table or change the top-level image schema.
- Do not alter CKL syntax or frontend type rules.
- Do not change record signal encoding unless tests expose an existing bug.

## Testing Plan

### Kotlin Backend Tests

Add focused backend tests that assert:

- `ConstructRecord` lowers to opcode `16` with type-name and field-name string metadata indexes.
- `GetField` lowers to opcode `17` with a field-name string metadata index.
- Constants are reused where practical and operands point to the expected strings.
- A compiled CKL sample that constructs a struct and reads fields no longer fails with unsupported `ConstructRecord`.
- The unsupported-instruction diagnostic now names the next unsupported instruction, not `ConstructRecord`.

Use order-sensitive examples such as `Point(x = 2, y = 5)` and reading/subtracting fields so field reversal cannot pass accidentally.

### Rust Image Runner Tests

Add direct native runner tests that assert:

- `CONSTRUCT_RECORD` creates a record with the expected type name, ordered fields, and values.
- `GET_FIELD` reads an existing field from a record.
- field order is preserved for non-commutative assertions.
- invalid metadata indexes are rejected.
- non-string metadata constants are rejected.
- missing fields are rejected.
- `GET_FIELD` on a non-record receiver is rejected.
- record construction stack underflow is rejected.

### JNI / End-to-End Tests

Add an integration test that compiles CKL source similar to:

- declare `struct Point { x: Int, y: Int }`;
- construct `Point(x = 2, y = 5)`;
- read `point.x` and `point.y`;
- return or log an order-sensitive result such as `point.x - point.y`.

The test should run through the native image runner, not the old JVM bytecode runtime.

## Acceptance Criteria

This slice is complete when:

- `CkVmImageBackend` supports `Instruction.ConstructRecord` and `Instruction.GetField`.
- Rust image execution supports opcodes `16` and `17` with deterministic errors.
- CKL struct construction and field-read programs run through the native `CkVmImage` path.
- Focused Kotlin backend tests pass.
- Focused Rust image runner tests pass.
- Focused JNI/native integration tests pass.
- The old JVM bytecode VM and deleted legacy native bytecode ABI remain absent.
- `SetField` remains explicitly deferred to the class/object slice.

## Risks

- Accidentally reversing field values during stack pops would produce structurally valid but semantically wrong records. Tests must use order-sensitive assertions.
- If future bundled CKL parity requires class fields earlier than expected, the next slice may need to prioritize `ConstructClass`/`SetField` before collections.
- Using the constant pool for metadata is simple and consistent today, but a future image format may still want a dedicated symbol table if metadata grows significantly.
