# CKVM Image Native Strings Design

## Goal

Reduce terminal VM overhead by handling hot `strings::*` host imports inside the Rust CKVM image runner when the operation can be executed with exact ASCII semantics.

## Context

The runtime image profile for the bundled terminal showed thousands of host-call round trips for `strings::charAt` and `strings::length`. These calls are simple, deterministic, and dominate the terminal input path more than the display blit/present calls.

The current CKL ABI already assigns stable host import ids:

- `7000` `strings::trim(String): String`
- `7001` `strings::beforeSpace(String): String`
- `7002` `strings::afterSpace(String): String`
- `7003` `strings::isBlank(String): Bool`
- `7004` `strings::toInt(String): Int`
- `7005` `strings::length(String): Int`
- `7006` `strings::charAt(String, Int): String`

## Design

The Rust image runner keeps the existing CKL image ABI and `OP_CALL_HOST` instruction. When `OP_CALL_HOST` targets one of the known `strings::*` ids, the runner validates that the host import is declared, then tries a native implementation before emitting a `VmSignal::HostCall`.

Native handling is intentionally conservative:

- If the string argument is ASCII, Rust executes the operation locally and pushes the result onto the VM stack.
- If the string argument is non-ASCII, Rust falls back to the existing host-call path so Kotlin keeps the current UTF-16 and Unicode whitespace behavior.
- If the import id is not one of the string ids, behavior is unchanged.
- If the import id is missing from the image import table, behavior is unchanged: the VM reports an undeclared host import.

## Native Semantics

For ASCII inputs, Rust mirrors the Kotlin host bridge:

- `trim`: remove ASCII whitespace at both ends.
- `beforeSpace`: trim leading ASCII whitespace, then return the substring before the first ASCII whitespace.
- `afterSpace`: trim leading ASCII whitespace, then return the substring after the first ASCII whitespace with leading ASCII whitespace removed.
- `isBlank`: return true when the string is empty or all ASCII whitespace.
- `toInt`: trim ASCII whitespace and parse an `i32`, returning `0` on parse failure.
- `length`: return byte length, which equals CKL/Kotlin character length for ASCII.
- `charAt`: return the one-character string at a valid zero-based ASCII index, or `""` when out of bounds.

## Testing

Rust image-runner tests cover two properties:

- ASCII `strings::*` imports complete without returning a `HostCall` signal.
- Non-ASCII string inputs still return a `HostCall` signal for Kotlin fallback.

The existing profiling task can then confirm that terminal `strings::length` and `strings::charAt` host-call counts drop from the hot path.
