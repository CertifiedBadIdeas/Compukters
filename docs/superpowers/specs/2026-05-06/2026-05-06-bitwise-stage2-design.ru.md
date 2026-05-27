# Дизайн Bitwise Stage2

Дата: 2026-05-06

## Контекст

Stage 1 перевёл ROM terminal glyph rendering с 35-character string masks на numeric `Glyph5x7` rows и добавил `display::blitMono5x7(...)`. Stage 2 добавляет language-level bitwise tools, чтобы CKL programs могли напрямую создавать, комбинировать, проверять и сдвигать numeric masks.

CKL сейчас поддерживает arithmetic, comparison и logical operators, но не поддерживает bitwise operators, binary integer literals и bitwise compound assignments.

## Цели

- Добавить bitwise binary operators: `&`, `|`, `^`, `<<`, `>>`.
- Добавить bitwise unary operator: `~`.
- Добавить binary integer literals: `0b1010`, `0B1010`, optional `L` suffix для `Long`.
- Добавить compound assignments для variables и member fields: `&=`, `|=`, `^=`, `<<=`, `>>=`.
- Сохранить deterministic, statically typed и readable язык.
- Сохранить numeric glyph display API, добавленный в Stage 1.

## Не цели

- Не добавлять unsigned integer types.
- Не добавлять unsigned right shift (`>>>`).
- Не добавлять hex или octal literals на этом этапе.
- Не менять existing arithmetic, comparison, logical или display behavior.
- Не менять stdin/stdout/stderr stream architecture.
- Не переводить ROM terminal glyph rows обратно на computed masks в рамках этого stage.

## Syntax

Новые binary operators:

- `left & right` — bitwise AND.
- `left | right` — bitwise OR.
- `left ^ right` — bitwise XOR.
- `left << count` — signed left shift.
- `left >> count` — signed arithmetic right shift.

Новый unary operator:

- `~value` — bitwise NOT.

Новые compound assignments:

- `value &= mask`
- `value |= mask`
- `value ^= mask`
- `value <<= count`
- `value >>= count`
- `this.field &= mask` и equivalent member-field forms.

Новые binary literals:

- `0b01110` и `0B01110` дают `Int`, если value помещается в `Int`.
- `0b01110L` и `0B01110L` дают `Long`.
- Binary literal должен содержать минимум одну binary digit после prefix.
- После `0b`/`0B` разрешены только `0` и `1` до optional `L` suffix.

## Type rules

- `&`, `|` и `^` принимают только numeric operands: `Int` или `Long`.
- `&`, `|` и `^` возвращают `Long`, если хотя бы один operand — `Long`; иначе возвращают `Int`.
- `<<` и `>>` требуют left operand `Int` или `Long` и shift count `Int` или `Long`.
- `<<` и `>>` сохраняют type left operand.
- `~` принимает `Int` или `Long` и сохраняет operand type.
- Bitwise operators не принимают `Bool`, `String`, structs, classes или nullable values.

Это намеренно permissive по сравнению с текущим arithmetic rule, который требует matching numeric types: masks часто комбинируют `Long` values с small `Int` literal counts или masks, а shifts естественно используют small counts.

## Precedence

Используем readable, CKL-friendly precedence model, а не C/Java precedence. От низкого к высокому:

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

Следствия:

- `flags & mask == 0` парсится как `(flags & mask) == 0`.
- `1 << 4 - col` парсится как `1 << (4 - col)`.
- Parentheses остаются доступными, formatter сохраняет их там, где они required.

## Runtime semantics

- `&`, `|`, `^` используют Kotlin/JVM signed integer bit operations.
- `>>` — arithmetic signed right shift (`shr`).
- `<<` — signed left shift (`shl`).
- Shift counts читаются как `Int` at runtime.
- Если left operand — `Int`, shift result — `Int`.
- Если left operand — `Long`, shift result — `Long`.
- `~` maps to `Int.inv()` или `Long.inv()`.

## Compiler and runtime changes

Stage 2 реализуется во всех layers:

- Tokens: добавить single-character и compound bitwise tokens в `TokenKind`.
- Lexer: различать `&` и `&&`, `|` и `||`, shifts и comparisons, bitwise compound assignments и existing assignment operators.
- Parser: добавить bitwise precedence levels и parse unary `~`.
- AST/operator model: добавить binary и unary operator enum values.
- Semantic analyzer: enforce numeric bitwise type rules и result types.
- Bytecode compiler: переиспользовать existing `Instruction.Binary` и `Instruction.Unary` emission.
- Runtime: execute new enum values in `applyBinary(...)` and `applyUnary(...)`.
- Formatter: печатать new operators with documented precedence.
- IDE presentation: добавить new operator tokens в operator highlighting.
- Documentation: обновить operator lists, precedence, literals и examples.

Core ROM estimator change не ожидается, потому что `Instruction.Binary` и `Instruction.Unary` уже имеют uniform ROM byte costs.

## Testing

Добавить tests для:

- Lexing `&`, `&&`, `&=`, `|`, `||`, `|=`, `^`, `^=`, `~`, `<<`, `<<=`, `>>`, `>>=`, `0b...`, `0b...L`.
- Rejecting malformed binary literals: `0b`, `0b102`, out-of-range `Int` literals without `L`.
- Parsing and type-checking всех bitwise operators.
- Precedence examples: `flags & mask == 0`, `a | b ^ c & d`, `1 << 4 - col`.
- Runtime results for `Int`, `Long` и mixed numeric operands.
- Variable и member compound assignments.
- Formatter output и required parentheses.
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

## Future work

Potential later stages can add unsigned shifts, hex literals, bit helper libraries или ROM terminal glyph authoring changes that use `0b...` literals. Всё это intentionally outside this stage.# Дизайн Bitwise Stage2

Дата: 2026-05-06

## Контекст

Stage 1 перевёл ROM terminal glyph rendering с 35-character string masks на numeric `Glyph5x7` rows и добавил `display::blitMono5x7(...)`. Stage 2 добавляет language-level bitwise tools, чтобы CKL programs могли напрямую создавать, комбинировать, проверять и сдвигать numeric masks.

CKL сейчас поддерживает arithmetic, comparison и logical operators, но не поддерживает bitwise operators, binary integer literals и bitwise compound assignments.

## Цели

- Добавить bitwise binary operators: `&`, `|`, `^`, `<<`, `>>`.
- Добавить bitwise unary operator: `~`.
- Добавить binary integer literals: `0b1010`, `0B1010`, optional `L` suffix для `Long`.
- Добавить compound assignments для variables и member fields: `&=`, `|=`, `^=`, `<<=`, `>>=`.
- Сохранить deterministic, statically typed и readable язык.
- Сохранить numeric glyph display API, добавленный в Stage 1.

## Не цели

- Не добавлять unsigned integer types.
- Не добавлять unsigned right shift (`>>>`).
- Не добавлять hex или octal literals на этом этапе.
- Не менять existing arithmetic, comparison, logical или display behavior.
- Не менять stdin/stdout/stderr stream architecture.
- Не переводить ROM terminal glyph rows обратно на computed masks в рамках этого stage.

## Syntax

Новые binary operators:

- `left & right` — bitwise AND.
- `left | right` — bitwise OR.
- `left ^ right` — bitwise XOR.
- `left << count` — signed left shift.
- `left >> count` — signed arithmetic right shift.

Новый unary operator:

- `~value` — bitwise NOT.

Новые compound assignments:

- `value &= mask`
- `value |= mask`
- `value ^= mask`
- `value <<= count`
- `value >>= count`
- `this.field &= mask` и equivalent member-field forms.

Новые binary literals:

- `0b01110` и `0B01110` дают `Int`, если value помещается в `Int`.
- `0b01110L` и `0B01110L` дают `Long`.
- Binary literal должен содержать минимум одну binary digit после prefix.
- После `0b`/`0B` разрешены только `0` и `1` до optional `L` suffix.

## Type rules

- `&`, `|` и `^` принимают только numeric operands: `Int` или `Long`.
- `&`, `|` и `^` возвращают `Long`, если хотя бы один operand — `Long`; иначе возвращают `Int`.
- `<<` и `>>` требуют left operand `Int` или `Long` и shift count `Int` или `Long`.
- `<<` и `>>` сохраняют type left operand.
- `~` принимает `Int` или `Long` и сохраняет operand type.
- Bitwise operators не принимают `Bool`, `String`, structs, classes или nullable values.

Это намеренно permissive по сравнению с текущим arithmetic rule, который требует matching numeric types: masks часто комбинируют `Long` values с small `Int` literal counts или masks, а shifts естественно используют small counts.

## Precedence

Используем readable, CKL-friendly precedence model, а не C/Java precedence. От низкого к высокому:

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

Следствия:

- `flags & mask == 0` парсится как `(flags & mask) == 0`.
- `1 << 4 - col` парсится как `1 << (4 - col)`.
- Parentheses остаются доступными, formatter сохраняет их там, где они required.

## Runtime semantics

- `&`, `|`, `^` используют Kotlin/JVM signed integer bit operations.
- `>>` — arithmetic signed right shift (`shr`).
- `<<` — signed left shift (`shl`).
- Shift counts читаются как `Int` at runtime.
- Если left operand — `Int`, shift result — `Int`.
- Если left operand — `Long`, shift result — `Long`.
- `~` maps to `Int.inv()` или `Long.inv()`.

## Compiler and runtime changes

Stage 2 реализуется во всех layers:

- Tokens: добавить single-character и compound bitwise tokens в `TokenKind`.
- Lexer: различать `&` и `&&`, `|` и `||`, shifts и comparisons, bitwise compound assignments и existing assignment operators.
- Parser: добавить bitwise precedence levels и parse unary `~`.
- AST/operator model: добавить binary и unary operator enum values.
- Semantic analyzer: enforce numeric bitwise type rules и result types.
- Bytecode compiler: переиспользовать existing `Instruction.Binary` и `Instruction.Unary` emission.
- Runtime: execute new enum values in `applyBinary(...)` and `applyUnary(...)`.
- Formatter: печатать new operators with documented precedence.
- IDE presentation: добавить new operator tokens в operator highlighting.
- Documentation: обновить operator lists, precedence, literals и examples.

Core ROM estimator change не ожидается, потому что `Instruction.Binary` и `Instruction.Unary` уже имеют uniform ROM byte costs.

## Testing

Добавить tests для:

- Lexing `&`, `&&`, `&=`, `|`, `||`, `|=`, `^`, `^=`, `~`, `<<`, `<<=`, `>>`, `>>=`, `0b...`, `0b...L`.
- Rejecting malformed binary literals: `0b`, `0b102`, out-of-range `Int` literals without `L`.
- Parsing and type-checking всех bitwise operators.
- Precedence examples: `flags & mask == 0`, `a | b ^ c & d`, `1 << 4 - col`.
- Runtime results for `Int`, `Long` и mixed numeric operands.
- Variable и member compound assignments.
- Formatter output и required parentheses.
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

## Future work

Potential later stages can add unsigned shifts, hex literals, bit helper libraries или ROM terminal glyph authoring changes that use `0b...` literals. Всё это intentionally outside this stage.