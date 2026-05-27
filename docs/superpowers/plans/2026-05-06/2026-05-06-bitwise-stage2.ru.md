# План реализации Bitwise Stage2

> **Для agentic workers:** ОБЯЗАТЕЛЬНЫЙ SUB-SKILL: используйте superpowers:subagent-driven-development (рекомендуется) или superpowers:executing-plans для выполнения плана task-by-task. Steps используют checkbox (`- [ ]`) syntax для tracking.

**Goal:** Добавить в CKL bitwise operators, binary integer literals и bitwise compound assignments как полноценную language feature Stage 2.

**Architecture:** Реализуем bitwise как language feature compiler/runtime, а не как ROM terminal patch. Сначала расширяем tokens и binary literals, затем parser/analyzer/runtime/formatter, после этого compound assignment sugar, IDE/docs и full verification.

**Tech Stack:** Kotlin, CKL compiler/frontend, CKL bytecode runtime, Gradle, kotlin.test.

---

## File Structure

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`
  - Добавить bitwise operator и assignment tokens.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/BinaryOperator.kt`
  - Добавить bitwise binary operator enum values.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/UnaryOperator.kt`
  - Добавить bitwise unary operator enum value.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
  - Lex binary literals and bitwise tokens.
  - Parse bitwise precedence levels.
  - Parse bitwise compound assignments.
  - Type-check numeric bitwise operations.
  - Reuse existing bytecode emission for `Instruction.Binary` and `Instruction.Unary`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
  - Execute new `BinaryOperator` and `UnaryOperator` values.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
  - Render new operators and preserve approved precedence.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/IdePresentationSupport.kt`
  - Highlight new tokens as operators.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`
  - Add lexer, literal, parser, and analyzer tests.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
  - Add VM execution tests.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`
  - Add formatter precedence tests.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`
  - Add operator highlighting regression.
- Modify `docs/LANGUAGE.md`
  - Document syntax, precedence, binary literals, compound assignments, and examples.

## Task 1: Tokenize Bitwise Operators and Binary Literals

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/IdePresentationSupport.kt`

- [ ] **Step 1: Write failing lexer and literal diagnostics tests**

Add these tests near the existing lexer tests in `LanguageFrontendTest`:

```kotlin
@Test
fun lexesBitwiseTokensAndBinaryLiterals() {
    val tokens =
        Lexer(
            """
            pub fun main() {
                var flags: Int = 0b00101;
                flags &= 0B11111;
                flags |= 0b01000L;
                flags ^= 0b00001;
                flags <<= 1;
                flags >>= 2;
                val inverted: Int = ~flags;
                val shifted: Int = flags << 1 >> 1;
                val masked: Int = flags & 0b11111 | 0b00010 ^ 0b00001;
            }
            """.trimIndent(),
        ).lex()

    val kinds = tokens.map { it.kind }
    assertTrue(TokenKind.AMP in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
    assertTrue(TokenKind.PIPE in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
    assertTrue(TokenKind.CARET in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
    assertTrue(TokenKind.TILDE in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
    assertTrue(TokenKind.LT_LT in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
    assertTrue(TokenKind.GT_GT in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
    assertTrue(TokenKind.AMP_EQUAL in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
    assertTrue(TokenKind.PIPE_EQUAL in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
    assertTrue(TokenKind.CARET_EQUAL in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
    assertTrue(TokenKind.LT_LT_EQUAL in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
    assertTrue(TokenKind.GT_GT_EQUAL in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
    assertTrue(tokens.any { it.kind == TokenKind.NUMBER && it.text == "0b00101" }, tokens.joinToString { it.text })
    assertTrue(tokens.any { it.kind == TokenKind.NUMBER && it.text == "0B11111" }, tokens.joinToString { it.text })
    assertTrue(tokens.any { it.kind == TokenKind.NUMBER && it.text == "0b01000L" }, tokens.joinToString { it.text })
}

@Test
fun rejectsMalformedBinaryLiterals() {
    val empty = DefaultParserFacade().parse("empty_binary.ck", "pub fun main() { val value: Int = 0b; }")
    assertTrue(
        empty.syntaxDiagnostics.any { it.message.contains("Binary literal requires at least one digit") },
        empty.syntaxDiagnostics.joinToString { it.message },
    )

    val badDigit = DefaultParserFacade().parse("bad_binary.ck", "pub fun main() { val value: Int = 0b102; }")
    assertTrue(
        badDigit.syntaxDiagnostics.any { it.message.contains("Binary literal can only contain 0 or 1") },
        badDigit.syntaxDiagnostics.joinToString { it.message },
    )

    val tooLarge =
        frontend.compile(
            "large_binary.ck",
            "pub fun main() { val value: Int = 0b10000000000000000000000000000000; }",
        )
    assertTrue(
        tooLarge.analysis.diagnostics.any {
            it.message.contains("exceeds Int range") && it.message.contains("append `L`")
        },
        tooLarge.analysis.diagnostics.joinToString { it.message },
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.lexesBitwiseTokensAndBinaryLiterals --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.rejectsMalformedBinaryLiterals
```

Expected: build fails because new bitwise token kinds do not exist.

- [ ] **Step 3: Add token kinds**

In `TokenKind.kt`, add these values near the existing operator tokens:

```kotlin
    TILDE,
    AMP_EQUAL,
    PIPE_EQUAL,
    CARET_EQUAL,
    LT_LT_EQUAL,
    GT_GT_EQUAL,
    LT_LT,
    GT_GT,
    AMP,
    PIPE,
    CARET,
```

Use the exact order from the English plan so `IdePresentationSupport` remains easy to read.

- [ ] **Step 4: Lex bitwise tokens and binary numbers**

In `LanguageFrontend.kt`, add:

```kotlin
import java.math.BigInteger
```

Replace lexer branches for `<`, `>`, `&`, `|`, add `^` and `~`, and replace `lexNumber(...)` with `0b`/`0B` support. Required behavior:

```kotlin
'&' -> `&&`, `&=`, or `&`
'|' -> `||`, `|=`, or `|`
'^' -> `^=` or `^`
'~' -> `~`
'<' -> `<<=`, `<<`, `<=`, or `<`
'>' -> `>>=`, `>>`, `>=`, or `>`
```

Binary number helper must emit one `TokenKind.NUMBER` token with the original text and diagnostics:

```kotlin
"Binary literal requires at least one digit after `0b`."
"Binary literal can only contain 0 or 1 digits."
```

- [ ] **Step 5: Parse binary literal values**

In `LanguageFrontend.kt`, route `TokenKind.NUMBER` through a helper:

```kotlin
            TokenKind.NUMBER -> {
                parseNumberLiteral(token)
            }
```

Helper requirements:

```kotlin
raw startsWith 0b or 0B -> radix 2
suffix L -> LongLiteralValue
no suffix -> IntLiteralValue
value > Int.MAX_VALUE without L -> diagnostic with "exceeds Int range" and "append `L`"
value > Long.MAX_VALUE with L -> diagnostic "Long literal `${token.text}` is out of range."
```

Add constants:

```kotlin
val INT_MAX: BigInteger = BigInteger.valueOf(Int.MAX_VALUE.toLong())
val LONG_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
```

- [ ] **Step 6: Highlight new tokens as operators**

In `IdePresentationSupport.kt`, add all new operator tokens to the `HighlightTokenKind.OPERATOR` branch:

```kotlin
TokenKind.TILDE,
TokenKind.AMP_EQUAL,
TokenKind.PIPE_EQUAL,
TokenKind.CARET_EQUAL,
TokenKind.LT_LT_EQUAL,
TokenKind.GT_GT_EQUAL,
TokenKind.LT_LT,
TokenKind.GT_GT,
TokenKind.AMP,
TokenKind.PIPE,
TokenKind.CARET,
```

- [ ] **Step 7: Run tests to verify Task 1 passes**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.lexesBitwiseTokensAndBinaryLiterals --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.rejectsMalformedBinaryLiterals
```

Expected: both tests pass.

- [ ] **Step 8: Commit Task 1**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/IdePresentationSupport.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat: lex bitwise tokens and binary literals"
```

## Task 2: Add Bitwise Operators to Parser, Analyzer, Runtime, and Formatter

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/BinaryOperator.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/UnaryOperator.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`

- [ ] **Step 1: Write failing frontend operator tests**

Add tests `compilesBitwiseOperatorsWithApprovedTypes` and `rejectsBitwiseOperatorsForNonNumericOperands` to `LanguageFrontendTest`. The source program must cover:

```ck
val a: Int = 0b01100 & 0b01010;
val b: Int = a | 0b00001;
val c: Int = b ^ 0b00100;
val d: Int = ~c;
val e: Int = 1 << 4 - 1;
val f: Int = e >> 2;
val g: Long = 0b1000L | 0b0011;
val h: Long = g & 0b1111L;
val i: Long = h << 2;
```

The invalid source must include:

```ck
val a: Bool = true & false;
val b: String = "a" | "b";
val c: Bool = ~true;
```

Expected diagnostics:

```text
Bitwise operators expect Int or Long operands
Bitwise not expects Int or Long
```

- [ ] **Step 2: Write failing runtime precedence test**

Add `executesBitwiseOperatorsWithApprovedPrecedence` to `LanguageRuntimeTest`. It must log exactly:

```kotlin
listOf(
    "and=8",
    "or=15",
    "xor=9",
    "not=-1",
    "left=8",
    "right=4",
    "compare=true",
    "mixed=11",
)
```

Use source expressions:

```ck
flags & 0b01010
flags | 0b00011
flags ^ 0b00101
~0b00000
1 << 4 - 1
0b10000 >> 2
flags & mask == mask
0b1000L | 0b0011
```

- [ ] **Step 3: Write failing formatter precedence test**

Add `formatsBitwiseOperatorsWithApprovedPrecedence` to `LanguageFormatterTest` using input:

```kotlin
"pub fun main(){val a:Int=flags&mask==mask;val b:Int=1<<4-col;val c:Int=a|b^c&d;val e:Int=~a;}"
```

Expected formatted output:

```ck
pub fun main() {
    val a: Int = flags & mask == mask
    val b: Int = 1 << 4 - col
    val c: Int = a | b ^ c & d
    val e: Int = ~a
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.compilesBitwiseOperatorsWithApprovedTypes --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.rejectsBitwiseOperatorsForNonNumericOperands --tests ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.executesBitwiseOperatorsWithApprovedPrecedence --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFormatterTest.formatsBitwiseOperatorsWithApprovedPrecedence
```

Expected: tests fail because bitwise operators are not yet parsed/analyzed/formatted/executed.

- [ ] **Step 5: Add operator enum values**

In `BinaryOperator.kt`, append:

```kotlin
BIT_AND,
BIT_OR,
BIT_XOR,
SHIFT_LEFT,
SHIFT_RIGHT,
```

In `UnaryOperator.kt`, append:

```kotlin
BIT_NOT,
```

- [ ] **Step 6: Parse approved bitwise precedence levels**

In `LanguageFrontend.kt`, implement parser chain:

```text
parseExpression -> parseOr -> parseAnd -> parseEquality -> parseComparison -> parseBitwiseOr -> parseBitwiseXor -> parseBitwiseAnd -> parseShift -> parseTerm -> parseFactor -> parseUnary -> parseCall
```

Operator mapping:

```kotlin
TokenKind.PIPE -> BinaryOperator.BIT_OR
TokenKind.CARET -> BinaryOperator.BIT_XOR
TokenKind.AMP -> BinaryOperator.BIT_AND
TokenKind.LT_LT -> BinaryOperator.SHIFT_LEFT
TokenKind.GT_GT -> BinaryOperator.SHIFT_RIGHT
TokenKind.TILDE -> UnaryOperator.BIT_NOT
```

`parseComparison()` must consume `parseBitwiseOr()` on both sides. `parseShift()` must consume `parseTerm()` on both sides so `1 << 4 - col` parses as `1 << (4 - col)`.

- [ ] **Step 7: Type-check bitwise operators**

In `LanguageFrontend.kt`, add helpers:

```kotlin
private fun isNumeric(type: TypeRef): Boolean = type.name in NUMERIC_TYPE_NAMES && !type.nullable

private fun widerNumericType(left: TypeRef, right: TypeRef): TypeRef =
    if (left.name == "Long" || right.name == "Long") TypeRef("Long") else TypeRef("Int")
```

Analyzer rules:

```text
BIT_AND / BIT_OR / BIT_XOR: both operands Int or Long; result Long if either operand is Long, otherwise Int.
SHIFT_LEFT / SHIFT_RIGHT: left and right operands Int or Long; result is left operand type.
BIT_NOT: operand Int or Long; result is operand type.
```

- [ ] **Step 8: Execute bitwise operators at runtime**

In `LanguageRuntime.kt`, add:

```text
BIT_AND -> Int.and / Long.and
BIT_OR -> Int.or / Long.or
BIT_XOR -> Int.xor / Long.xor
SHIFT_LEFT -> shl with right.asInt()
SHIFT_RIGHT -> shr with right.asInt()
BIT_NOT -> Int.inv() / Long.inv()
```

Runtime result rules:

```text
Int & Int, Int | Int, Int ^ Int -> Int
mixed Int/Long for &, |, ^ -> Long
Int shifts -> Int
Long shifts -> Long
```

- [ ] **Step 9: Format new operators with approved precedence**

In `LanguageFormatter.kt`, use precedence constants:

```kotlin
PRECEDENCE_OR = 1
PRECEDENCE_AND = 2
PRECEDENCE_EQUALITY = 3
PRECEDENCE_COMPARISON = 4
PRECEDENCE_BIT_OR = 5
PRECEDENCE_BIT_XOR = 6
PRECEDENCE_BIT_AND = 7
PRECEDENCE_SHIFT = 8
PRECEDENCE_TERM = 9
PRECEDENCE_FACTOR = 10
PRECEDENCE_UNARY = 11
PRECEDENCE_CALL = 12
PRECEDENCE_PRIMARY = 13
```

Symbols:

```kotlin
BIT_AND -> "&"
BIT_OR -> "|"
BIT_XOR -> "^"
SHIFT_LEFT -> "<<"
SHIFT_RIGHT -> ">>"
BIT_NOT -> "~"
```

- [ ] **Step 10: Run tests to verify Task 2 passes**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.compilesBitwiseOperatorsWithApprovedTypes --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.rejectsBitwiseOperatorsForNonNumericOperands --tests ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.executesBitwiseOperatorsWithApprovedPrecedence --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFormatterTest.formatsBitwiseOperatorsWithApprovedPrecedence
```

Expected: all selected tests pass.

- [ ] **Step 11: Commit Task 2**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/BinaryOperator.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/UnaryOperator.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt
git commit -m "feat: add ckl bitwise operators"
```

## Task 3: Add Bitwise Compound Assignments

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`

- [ ] **Step 1: Write failing compound assignment runtime test**

Add `bitwiseCompoundAssignmentOperatorsMutateLocalAndMember` to `LanguageRuntimeTest`. Source must include local and member forms:

```ck
flags |= 0b0100;
flags &= 0b0110;
flags ^= 0b0010;
flags <<= 2;
flags >>= 1;
this.value |= 0b0100;
this.value &= 0b0110;
this.value ^= 0b0010;
this.value <<= 2;
this.value >>= 1;
```

Expected logs:

```kotlin
assertEquals(listOf("local=8", "field=8"), runtime.lines)
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.bitwiseCompoundAssignmentOperatorsMutateLocalAndMember
```

Expected: test fails because assignment parsing does not accept `&=`, `|=`, `^=`, `<<=`, or `>>=`.

- [ ] **Step 3: Parse bitwise compound assignments**

In `parseAssignment()`, map:

```kotlin
TokenKind.AMP_EQUAL -> BinaryOperator.BIT_AND
TokenKind.PIPE_EQUAL -> BinaryOperator.BIT_OR
TokenKind.CARET_EQUAL -> BinaryOperator.BIT_XOR
TokenKind.LT_LT_EQUAL -> BinaryOperator.SHIFT_LEFT
TokenKind.GT_GT_EQUAL -> BinaryOperator.SHIFT_RIGHT
```

In `parseMemberAssignment()`, map the same tokens through `compoundMemberDesugar(...)`.

Update diagnostics in both assignment parsers:

```kotlin
"Expected assignment operator. Supported operators: `=`, `+=`, `-=`, `*=`, `/=`, `&=`, `|=`, `^=`, `<<=`, `>>=`."
```

Extend `COMPOUND_ASSIGN_KINDS`:

```kotlin
TokenKind.AMP_EQUAL,
TokenKind.PIPE_EQUAL,
TokenKind.CARET_EQUAL,
TokenKind.LT_LT_EQUAL,
TokenKind.GT_GT_EQUAL,
```

- [ ] **Step 4: Run test to verify Task 3 passes**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.bitwiseCompoundAssignmentOperatorsMutateLocalAndMember
```

Expected: test passes and logs `local=8`, `field=8`.

- [ ] **Step 5: Commit Task 3**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
git commit -m "feat: add bitwise compound assignments"
```

## Task 4: Documentation, IDE Highlight Regression, and Full Verification

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`
- Modify: `docs/LANGUAGE.md`

- [ ] **Step 1: Write IDE highlight regression test**

Add `highlightsBitwiseOperators` to `LanguageIdeTest` with source:

```ck
pub fun main() {
    val value: Int = ~0b0011 & 0b1111 | 0b0001 ^ 0b0010 << 1 >> 1;
}
```

Assertions:

```kotlin
assertTrue(snapshot.highlights.any { it.kind == HighlightTokenKind.OPERATOR }, snapshot.highlights.joinToString())
assertTrue(snapshot.diagnostics.none { it.severity == IdeDiagnosticSeverity.ERROR }, snapshot.diagnostics.joinToString { it.message })
```

If needed, import:

```kotlin
import ru.lazyhat.compukterkraft.lang.runtime.IdeDiagnosticSeverity
```

- [ ] **Step 2: Run IDE test**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageIdeTest.highlightsBitwiseOperators
```

Expected: test passes.

- [ ] **Step 3: Update language docs**

In `docs/LANGUAGE.md`, document:

```text
operators: + - * / & | ^ ~ << >> == != < <= > >= && || !
compound assignment: = += -= *= /= &= |= ^= <<= >>=
binary literals: 0b01110, 0B01110, 0b01110L
precedence: ||, &&, equality, comparison, |, ^, &, shifts, additive, multiplicative, unary, call/primary
runtime: signed arithmetic >>, no unsigned types, no >>>
```

Add examples:

```ck
val row: Int = 0b01110
val mask: Int = 0b11111
val lowFive: Int = row & mask
val bit: Int = 1 << 4
val wide: Long = 0b10000000000000000000000000000000L
```

- [ ] **Step 4: Run compiler tests**

Run:

```bash
./gradlew :compiler:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run ROM compile regression**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run full test suite**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Check whitespace and repository status**

Run:

```bash
git diff --check
git status --branch --short
```

Expected: `git diff --check` prints no output. `git status --branch --short` shows only intended documentation and language feature files before final commit.

- [ ] **Step 8: Commit Task 4**

Run:

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt docs/LANGUAGE.md
git commit -m "docs: document ckl bitwise stage2"
```

- [ ] **Step 9: Final status**

Run:

```bash
git status --branch --short
git log --oneline --decorate --max-count=8
```

Expected: working tree is clean on `feature/bitwise-stage2`, with Task 1-4 commits above `docs: design bitwise stage2`.
