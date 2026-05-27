# Bitwise Stage2 Design

Date: 2026-05-06

## Context

Stage 1 moved ROM terminal glyph rendering from 35-character string masks to numeric `Glyph5x7` rows and added `display::blitMono5x7(...)`. Stage 2 adds language-level bitwise tools so CKL programs can author, combine, test, and shift numeric masks directly.

CKL currently supports arithmetic, comparison, and logical operators, but it does not support bitwise operators, binary integer literals, or bitwise compound assignments.

## Goals

- Add bitwise binary operators: `&`, `|`, `^`, `<<`, `>>`.
- Add bitwise unary operator: `~`.
- Add binary integer literals: `0b1010`, `0B1010`, and optional `L` suffix for `Long`.
- Add compound assignments for variables and member fields: `&=`, `|=`, `^=`, `<<=`, `>>=`.
- Keep the language deterministic, statically typed, and readable.
- Preserve the numeric glyph display API added in Stage 1.

## Non-goals

- Do not add unsigned integer types.
- Do not add unsigned right shift (`>>>`).
- Do not add hex or octal literals in this stage.
- Do not change existing arithmetic, comparison, logical, or display behavior.
- Do not change stdin/stdout/stderr stream architecture.
- Do not migrate ROM terminal glyph rows back to computed masks as part of this stage.

## Syntax

New binary operators:

- `left & right` — bitwise AND.
- `left | right` — bitwise OR.
- `left ^ right` — bitwise XOR.
- `left << count` — signed left shift.
- `left >> count` — signed arithmetic right shift.

New unary operator:

- `~value` — bitwise NOT.

New compound assignments:

- `value &= mask`
- `value |= mask`
- `value ^= mask`
- `value <<= count`
- `value >>= count`
- `this.field &= mask` and equivalent member-field forms.

New binary literals:

- `0b01110` and `0B01110` produce `Int` when the value fits in `Int`.
- `0b01110L` and `0B01110L` produce `Long`.
- Binary literals must contain at least one binary digit after the prefix.
- Only `0` and `1` are accepted after `0b`/`0B` before an optional `L` suffix.

## Type Rules

- `&`, `|`, and `^` accept numeric operands only: `Int` or `Long`.
- `&`, `|`, and `^` return `Long` if either operand is `Long`; otherwise they return `Int`.
- `<<` and `>>` require a left operand of `Int` or `Long` and a shift count of `Int` or `Long`.
- `<<` and `>>` preserve the left operand type.
- `~` accepts `Int` or `Long` and preserves the operand type.
- Bitwise operators do not accept `Bool`, `String`, structs, classes, or nullable values.

This is intentionally more permissive than the current arithmetic rule that requires matching numeric types: masks often combine `Long` values with small `Int` literal counts or masks, and shifts naturally use small counts.

## Precedence

Use a readable, CKL-friendly precedence model instead of C/Java precedence. Low to high:

1. `||`
2. `&&`
3. `==`, `!=`
4. `<`, `<=`, `>`, `>=`
5. `|`
6. `^`
7. `&`
8. `<<`, `>>`
9. `+`, `-`
10. `*`, `/`
11. unary `-`, `!`, `~`
12. call/member/namespace/primary expressions

Consequences:

- `flags & mask == 0` parses as `(flags & mask) == 0`.
- `1 << 4 - col` parses as `1 << (4 - col)`.
- Parentheses remain available and the formatter preserves them where required.

## Runtime Semantics

- `&`, `|`, `^` use Kotlin/JVM signed integer bit operations.
- `>>` is arithmetic signed right shift (`shr`).
- `<<` is signed left shift (`shl`).
- Shift counts are read as `Int` at runtime.
- If the left operand is an `Int`, shift results are `Int`.
- If the left operand is a `Long`, shift results are `Long`.
- `~` maps to `Int.inv()` or `Long.inv()`.

## Compiler and Runtime Changes

Implement Stage 2 across these layers:

- Tokens: add single-character and compound bitwise tokens in `TokenKind`.
- Lexer: distinguish `&` from `&&`, `|` from `||`, shifts from comparisons, and bitwise compound assignments from existing assignment operators.
- Parser: add bitwise precedence levels and parse unary `~`.
- AST/operator model: add binary and unary operator enum values.
- Semantic analyzer: enforce numeric bitwise type rules and result types.
- Bytecode compiler: reuse existing `Instruction.Binary` and `Instruction.Unary` emission.
- Runtime: execute new enum values in `applyBinary(...)` and `applyUnary(...)`.
- Formatter: print new operators with the documented precedence.
- IDE presentation: include new operator tokens in operator highlighting.
- Documentation: update operator lists, precedence, literals, and examples.

No core ROM estimator change is expected because `Instruction.Binary` and `Instruction.Unary` already have uniform ROM byte costs.

## Testing

Add tests for:

- Lexing `&`, `&&`, `&=`, `|`, `||`, `|=`, `^`, `^=`, `~`, `<<`, `<<=`, `>>`, `>>=`, `0b...`, and `0b...L`.
- Rejecting malformed binary literals such as `0b`, `0b102`, and out-of-range `Int` literals without `L`.
- Parsing and type-checking all bitwise operators.
- Precedence examples: `flags & mask == 0`, `a | b ^ c & d`, and `1 << 4 - col`.
- Runtime results for `Int`, `Long`, and mixed numeric operands.
- Variable and member compound assignments.
- Formatter output and required parentheses.
- Full compiler and ROM regression suites.

Verification commands:

- `./gradlew :compiler:test`
- `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`
- `./gradlew test`
- `git diff --check`

## Examples

```ck
pub fun main() {
    val glyphRow: Int = 0b01110
    val lowFive: Int = glyphRow & 0b11111
    val bit: Int = 1 << 4
    val inverted: Int = ~0b00001

    var flags: Int = 0
    flags |= 0b00100
    flags &= 0b11100

    if (flags & 0b00100 == 0b00100) {
        system::log("flag set")
    }
}
```

## Future Work

Potential later stages can add unsigned shifts, hex literals, bit helper libraries, or ROM terminal glyph authoring changes that use `0b...` literals. Those are intentionally outside this stage.# Bitwise Stage2 Design

Date: 2026-05-06

## Context

Stage 1 moved ROM terminal glyph rendering from 35-character string masks to numeric `Glyph5x7` rows and added `display::blitMono5x7(...)`. Stage 2 adds language-level bitwise tools so CKL programs can author, combine, test, and shift numeric masks directly.

CKL currently supports arithmetic, comparison, and logical operators, but it does not support bitwise operators, binary integer literals, or bitwise compound assignments.

## Goals

- Add bitwise binary operators: `&`, `|`, `^`, `<<`, `>>`.
- Add bitwise unary operator: `~`.
- Add binary integer literals: `0b1010`, `0B1010`, and optional `L` suffix for `Long`.
- Add compound assignments for variables and member fields: `&=`, `|=`, `^=`, `<<=`, `>>=`.
- Keep the language deterministic, statically typed, and readable.
- Preserve the numeric glyph display API added in Stage 1.

## Non-goals

- Do not add unsigned integer types.
- Do not add unsigned right shift (`>>>`).
- Do not add hex or octal literals in this stage.
- Do not change existing arithmetic, comparison, logical, or display behavior.
- Do not change stdin/stdout/stderr stream architecture.
- Do not migrate ROM terminal glyph rows back to computed masks as part of this stage.

## Syntax

New binary operators:

- `left & right` — bitwise AND.
- `left | right` — bitwise OR.
- `left ^ right` — bitwise XOR.
- `left << count` — signed left shift.
- `left >> count` — signed arithmetic right shift.

New unary operator:

- `~value` — bitwise NOT.

New compound assignments:

- `value &= mask`
- `value |= mask`
- `value ^= mask`
- `value <<= count`
- `value >>= count`
- `this.field &= mask` and equivalent member-field forms.

New binary literals:

- `0b01110` and `0B01110` produce `Int` when the value fits in `Int`.
- `0b01110L` and `0B01110L` produce `Long`.
- Binary literals must contain at least one binary digit after the prefix.
- Only `0` and `1` are accepted after `0b`/`0B` before an optional `L` suffix.

## Type Rules

- `&`, `|`, and `^` accept numeric operands only: `Int` or `Long`.
- `&`, `|`, and `^` return `Long` if either operand is `Long`; otherwise they return `Int`.
- `<<` and `>>` require a left operand of `Int` or `Long` and a shift count of `Int` or `Long`.
- `<<` and `>>` preserve the left operand type.
- `~` accepts `Int` or `Long` and preserves the operand type.
- Bitwise operators do not accept `Bool`, `String`, structs, classes, or nullable values.

This is intentionally more permissive than the current arithmetic rule that requires matching numeric types: masks often combine `Long` values with small `Int` literal counts or masks, and shifts naturally use small counts.

## Precedence

Use a readable, CKL-friendly precedence model instead of C/Java precedence. Low to high:

1. `||`
2. `&&`
3. `==`, `!=`
4. `<`, `<=`, `>`, `>=`
5. `|`
6. `^`
7. `&`
8. `<<`, `>>`
9. `+`, `-`
10. `*`, `/`
11. unary `-`, `!`, `~`
12. call/member/namespace/primary expressions

Consequences:

- `flags & mask == 0` parses as `(flags & mask) == 0`.
- `1 << 4 - col` parses as `1 << (4 - col)`.
- Parentheses remain available and the formatter preserves them where required.

## Runtime Semantics

- `&`, `|`, `^` use Kotlin/JVM signed integer bit operations.
- `>>` is arithmetic signed right shift (`shr`).
- `<<` is signed left shift (`shl`).
- Shift counts are read as `Int` at runtime.
- If the left operand is an `Int`, shift results are `Int`.
- If the left operand is a `Long`, shift results are `Long`.
- `~` maps to `Int.inv()` or `Long.inv()`.

## Compiler and Runtime Changes

Implement Stage 2 across these layers:

- Tokens: add single-character and compound bitwise tokens in `TokenKind`.
- Lexer: distinguish `&` from `&&`, `|` from `||`, shifts from comparisons, and bitwise compound assignments from existing assignment operators.
- Parser: add bitwise precedence levels and parse unary `~`.
- AST/operator model: add binary and unary operator enum values.
- Semantic analyzer: enforce numeric bitwise type rules and result types.
- Bytecode compiler: reuse existing `Instruction.Binary` and `Instruction.Unary` emission.
- Runtime: execute new enum values in `applyBinary(...)` and `applyUnary(...)`.
- Formatter: print new operators with the documented precedence.
- IDE presentation: include new operator tokens in operator highlighting.
- Documentation: update operator lists, precedence, literals, and examples.

No core ROM estimator change is expected because `Instruction.Binary` and `Instruction.Unary` already have uniform ROM byte costs.

## Testing

Add tests for:

- Lexing `&`, `&&`, `&=`, `|`, `||`, `|=`, `^`, `^=`, `~`, `<<`, `<<=`, `>>`, `>>=`, `0b...`, and `0b...L`.
- Rejecting malformed binary literals such as `0b`, `0b102`, and out-of-range `Int` literals without `L`.
- Parsing and type-checking all bitwise operators.
- Precedence examples: `flags & mask == 0`, `a | b ^ c & d`, and `1 << 4 - col`.
- Runtime results for `Int`, `Long`, and mixed numeric operands.
- Variable and member compound assignments.
- Formatter output and required parentheses.
- Full compiler and ROM regression suites.

Verification commands:

- `./gradlew :compiler:test`
- `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`
- `./gradlew test`
- `git diff --check`

## Examples

```ck
pub fun main() {
    val glyphRow: Int = 0b01110
    val lowFive: Int = glyphRow & 0b11111
    val bit: Int = 1 << 4
    val inverted: Int = ~0b00001

    var flags: Int = 0
    flags |= 0b00100
    flags &= 0b11100

    if (flags & 0b00100 == 0b00100) {
        system::log("flag set")
    }
}
```

## Future Work

Potential later stages can add unsigned shifts, hex literals, bit helper libraries, or ROM terminal glyph authoring changes that use `0b...` literals. Those are intentionally outside this stage.