# Bitwise Stage2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add CKL bitwise operators, binary integer literals, and bitwise compound assignments as a complete Stage 2 language feature.

**Architecture:** Treat bitwise as a compiler/runtime language feature, not as a ROM terminal patch. Extend lexical tokens first, then parse/type-check operator expressions, execute them in the bytecode VM, and finally wire compound assignment sugar plus docs/IDE coverage.

**Tech Stack:** Kotlin, CKL compiler/frontend, CKL bytecode runtime, Gradle, kotlin.test.

---

## File Structure

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`
  - Add bitwise operator and assignment tokens.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/BinaryOperator.kt`
  - Add bitwise binary operator enum values.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/UnaryOperator.kt`
  - Add bitwise unary operator enum value.
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

Expected: build fails because `TokenKind.AMP`, `TokenKind.PIPE`, `TokenKind.CARET`, `TokenKind.TILDE`, `TokenKind.LT_LT`, `TokenKind.GT_GT`, and compound bitwise token kinds do not exist.

- [ ] **Step 3: Add token kinds**

In `TokenKind.kt`, add these values near the existing operator tokens:

```kotlin
    PLUS,
    MINUS,
    STAR,
    SLASH,
    BANG,
    TILDE,
    EQUAL,
    PLUS_EQUAL,
    MINUS_EQUAL,
    STAR_EQUAL,
    SLASH_EQUAL,
    AMP_EQUAL,
    PIPE_EQUAL,
    CARET_EQUAL,
    LT_LT_EQUAL,
    GT_GT_EQUAL,
    EQUAL_EQUAL,
    BANG_EQUAL,
    LT,
    LTE,
    GT,
    GTE,
    LT_LT,
    GT_GT,
    AMP,
    PIPE,
    CARET,
    AMP_AMP,
    PIPE_PIPE,
```

- [ ] **Step 4: Lex bitwise tokens and binary numbers**

In `LanguageFrontend.kt`, add this import with the other imports:

```kotlin
import java.math.BigInteger
```

Replace the lexer branches for `<`, `>`, `&`, and `|`, and add branches for `^` and `~`:

```kotlin
                '<' -> {
                    if (match('<')) {
                        if (match('=')) {
                            addToken(TokenKind.LT_LT_EQUAL, "<<=", start)
                        } else {
                            addToken(TokenKind.LT_LT, "<<", start)
                        }
                    } else {
                        addToken(if (match('=')) TokenKind.LTE else TokenKind.LT, if (previous() == '=') "<=" else "<", start)
                    }
                }

                '>' -> {
                    if (match('>')) {
                        if (match('=')) {
                            addToken(TokenKind.GT_GT_EQUAL, ">>=", start)
                        } else {
                            addToken(TokenKind.GT_GT, ">>", start)
                        }
                    } else {
                        addToken(if (match('=')) TokenKind.GTE else TokenKind.GT, if (previous() == '=') ">=" else ">", start)
                    }
                }

                '&' -> {
                    when {
                        match('&') -> addToken(TokenKind.AMP_AMP, "&&", start)
                        match('=') -> addToken(TokenKind.AMP_EQUAL, "&=", start)
                        else -> addToken(TokenKind.AMP, "&", start)
                    }
                }

                '|' -> {
                    when {
                        match('|') -> addToken(TokenKind.PIPE_PIPE, "||", start)
                        match('=') -> addToken(TokenKind.PIPE_EQUAL, "|=", start)
                        else -> addToken(TokenKind.PIPE, "|", start)
                    }
                }

                '^' -> {
                    if (match('=')) {
                        addToken(TokenKind.CARET_EQUAL, "^=", start)
                    } else {
                        addToken(TokenKind.CARET, "^", start)
                    }
                }

                '~' -> {
                    addToken(TokenKind.TILDE, "~", start)
                }
```

Replace `lexNumber(...)` with binary-literal support:

```kotlin
    private fun lexNumber(
        start: SourceLocation,
        first: Char,
    ) {
        val builder = StringBuilder().append(first)
        if (first == '0' && !isAtEnd() && (peek() == 'b' || peek() == 'B')) {
            builder.append(advance())
            lexBinaryNumber(start, builder)
            return
        }
        while (!isAtEnd() && peek().isDigit()) {
            builder.append(advance())
        }
        if (!isAtEnd() && peek() == 'L') {
            builder.append(advance())
        }
        tokens += Token(TokenKind.NUMBER, builder.toString(), SourceRange(start, location()))
    }

    private fun lexBinaryNumber(
        start: SourceLocation,
        builder: StringBuilder,
    ) {
        var digitCount = 0
        while (!isAtEnd() && (peek() == '0' || peek() == '1')) {
            builder.append(advance())
            digitCount += 1
        }
        if (digitCount == 0) {
            diagnostics += FrontendDiagnostic("Binary literal requires at least one digit after `0b`.", range(start))
        }
        if (!isAtEnd() && peek().isDigit()) {
            while (!isAtEnd() && peek().isDigit()) {
                builder.append(advance())
            }
            diagnostics += FrontendDiagnostic("Binary literal can only contain 0 or 1 digits.", SourceRange(start, location()))
        }
        if (!isAtEnd() && peek() == 'L') {
            builder.append(advance())
        }
        tokens += Token(TokenKind.NUMBER, builder.toString(), SourceRange(start, location()))
    }
```

- [ ] **Step 5: Parse binary literal values**

In `LanguageFrontend.kt`, replace the `TokenKind.NUMBER` branch in `parsePrimary()` with a helper call:

```kotlin
            TokenKind.NUMBER -> {
                parseNumberLiteral(token)
            }
```

Add this helper near `parsePrimary()`:

```kotlin
    private fun parseNumberLiteral(token: Token): LiteralExpression? {
        val text = token.text
        val isLong = text.endsWith("L")
        val raw = if (isLong) text.dropLast(1) else text
        val isBinary = raw.startsWith("0b") || raw.startsWith("0B")
        val digits = if (isBinary) raw.drop(2) else raw
        val radix = if (isBinary) 2 else 10
        if (digits.isEmpty()) return null
        val value = digits.toBigIntegerOrNull(radix)
        if (value == null) {
            diagnostics += FrontendDiagnostic("Integer literal `${token.text}` is out of range.", token.range)
            return null
        }
        return if (isLong) {
            if (value > LONG_MAX) {
                diagnostics += FrontendDiagnostic("Long literal `${token.text}` is out of range.", token.range)
                null
            } else {
                LiteralExpression(LongLiteralValue(value.toLong()), token.range)
            }
        } else {
            if (value > INT_MAX) {
                val hint = "Integer literal `${token.text}` exceeds Int range; append `L` to make it a Long (e.g. `${token.text}L`)."
                diagnostics += FrontendDiagnostic(hint, token.range)
                null
            } else {
                LiteralExpression(IntLiteralValue(value.toInt()), token.range)
            }
        }
    }
```

Add these constants near `Parser` companion constants:

```kotlin
        val INT_MAX: BigInteger = BigInteger.valueOf(Int.MAX_VALUE.toLong())
        val LONG_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
```

- [ ] **Step 6: Highlight new tokens as operators**

In `IdePresentationSupport.kt`, add the new operator tokens to the `HighlightTokenKind.OPERATOR` branch:

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

Add these tests to `LanguageFrontendTest`:

```kotlin
@Test
fun compilesBitwiseOperatorsWithApprovedTypes() {
    val artifact =
        frontend.compile(
            "bitwise.ck",
            """
            pub fun main() {
                val a: Int = 0b01100 & 0b01010;
                val b: Int = a | 0b00001;
                val c: Int = b ^ 0b00100;
                val d: Int = ~c;
                val e: Int = 1 << 4 - 1;
                val f: Int = e >> 2;
                val g: Long = 0b1000L | 0b0011;
                val h: Long = g & 0b1111L;
                val i: Long = h << 2;
            }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    assertNotNull(artifact.module)
}

@Test
fun rejectsBitwiseOperatorsForNonNumericOperands() {
    val artifact =
        frontend.compile(
            "bad_bitwise.ck",
            """
            pub fun main() {
                val a: Bool = true & false;
                val b: String = "a" | "b";
                val c: Bool = ~true;
            }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.any { it.message.contains("Bitwise operators expect Int or Long operands") },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    assertTrue(
        artifact.analysis.diagnostics.any { it.message.contains("Bitwise not expects Int or Long") },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}
```

- [ ] **Step 2: Write failing runtime precedence test**

Add this test to `LanguageRuntimeTest` near the existing runtime expression tests:

```kotlin
@Test
fun executesBitwiseOperatorsWithApprovedPrecedence() {
    val artifact =
        frontend.compile(
            "bitwise_runtime.ck",
            """
            pub fun main() {
                val flags: Int = 0b01100;
                val mask: Int = 0b00100;
                system::log("and=" + (flags & 0b01010));
                system::log("or=" + (flags | 0b00011));
                system::log("xor=" + (flags ^ 0b00101));
                system::log("not=" + (~0b00000));
                system::log("left=" + (1 << 4 - 1));
                system::log("right=" + (0b10000 >> 2));
                system::log("compare=" + (flags & mask == mask));
                system::log("mixed=" + (0b1000L | 0b0011));
            }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )

    val runtime = RecordingRuntime()
    runBlocking {
        BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
    }

    assertEquals(
        listOf(
            "and=8",
            "or=15",
            "xor=9",
            "not=-1",
            "left=8",
            "right=4",
            "compare=true",
            "mixed=11",
        ),
        runtime.lines,
    )
}
```

- [ ] **Step 3: Write failing formatter precedence test**

Add this test to `LanguageFormatterTest`:

```kotlin
@Test
fun formatsBitwiseOperatorsWithApprovedPrecedence() {
    val source = "pub fun main(){val a:Int=flags&mask==mask;val b:Int=1<<4-col;val c:Int=a|b^c&d;val e:Int=~a;}"
    val expected =
        """
        pub fun main() {
            val a: Int = flags & mask == mask
            val b: Int = 1 << 4 - col
            val c: Int = a | b ^ c & d
            val e: Int = ~a
        }
        """.trimIndent() + "\n"

    val formatted = applySingleEdit(source, formatter.formatDocument("bitwise.ck", source))

    assertEquals(expected, formatted)
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.compilesBitwiseOperatorsWithApprovedTypes --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.rejectsBitwiseOperatorsForNonNumericOperands --tests ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.executesBitwiseOperatorsWithApprovedPrecedence --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFormatterTest.formatsBitwiseOperatorsWithApprovedPrecedence
```

Expected: tests fail because bitwise operators are not yet parsed, analyzed, formatted, or executed.

- [ ] **Step 5: Add operator enum values**

In `BinaryOperator.kt`, append these enum values:

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

In `LanguageFrontend.kt`, replace the expression parser chain from `parseExpression()` through `parseFactor()` with this structure:

```kotlin
    private fun parseExpression(): Expression? = parseOr()

    private fun parseOr(): Expression? {
        var expression = parseAnd() ?: return null
        while (match(TokenKind.PIPE_PIPE)) {
            val right = parseAnd() ?: return null
            expression = BinaryExpression(expression, BinaryOperator.OR, right, SourceRange(expression.range.start, right.range.end))
        }
        return expression
    }

    private fun parseAnd(): Expression? {
        var expression = parseEquality() ?: return null
        while (match(TokenKind.AMP_AMP)) {
            val right = parseEquality() ?: return null
            expression = BinaryExpression(expression, BinaryOperator.AND, right, SourceRange(expression.range.start, right.range.end))
        }
        return expression
    }

    private fun parseEquality(): Expression? {
        var expression = parseComparison() ?: return null
        while (true) {
            expression =
                when {
                    match(TokenKind.EQUAL_EQUAL) -> {
                        val right = parseComparison() ?: return null
                        BinaryExpression(expression, BinaryOperator.EQUALS, right, SourceRange(expression.range.start, right.range.end))
                    }

                    match(TokenKind.BANG_EQUAL) -> {
                        val right = parseComparison() ?: return null
                        BinaryExpression(expression, BinaryOperator.NOT_EQUALS, right, SourceRange(expression.range.start, right.range.end))
                    }

                    else -> return expression
                }
        }
    }

    private fun parseComparison(): Expression? {
        var expression = parseBitwiseOr() ?: return null
        while (true) {
            expression =
                when {
                    match(TokenKind.LT) -> {
                        val right = parseBitwiseOr() ?: return null
                        BinaryExpression(expression, BinaryOperator.LESS, right, SourceRange(expression.range.start, right.range.end))
                    }

                    match(TokenKind.LTE) -> {
                        val right = parseBitwiseOr() ?: return null
                        BinaryExpression(expression, BinaryOperator.LESS_EQUALS, right, SourceRange(expression.range.start, right.range.end))
                    }

                    match(TokenKind.GT) -> {
                        val right = parseBitwiseOr() ?: return null
                        BinaryExpression(expression, BinaryOperator.GREATER, right, SourceRange(expression.range.start, right.range.end))
                    }

                    match(TokenKind.GTE) -> {
                        val right = parseBitwiseOr() ?: return null
                        BinaryExpression(expression, BinaryOperator.GREATER_EQUALS, right, SourceRange(expression.range.start, right.range.end))
                    }

                    else -> return expression
                }
        }
    }

    private fun parseBitwiseOr(): Expression? {
        var expression = parseBitwiseXor() ?: return null
        while (match(TokenKind.PIPE)) {
            val right = parseBitwiseXor() ?: return null
            expression = BinaryExpression(expression, BinaryOperator.BIT_OR, right, SourceRange(expression.range.start, right.range.end))
        }
        return expression
    }

    private fun parseBitwiseXor(): Expression? {
        var expression = parseBitwiseAnd() ?: return null
        while (match(TokenKind.CARET)) {
            val right = parseBitwiseAnd() ?: return null
            expression = BinaryExpression(expression, BinaryOperator.BIT_XOR, right, SourceRange(expression.range.start, right.range.end))
        }
        return expression
    }

    private fun parseBitwiseAnd(): Expression? {
        var expression = parseShift() ?: return null
        while (match(TokenKind.AMP)) {
            val right = parseShift() ?: return null
            expression = BinaryExpression(expression, BinaryOperator.BIT_AND, right, SourceRange(expression.range.start, right.range.end))
        }
        return expression
    }

    private fun parseShift(): Expression? {
        var expression = parseTerm() ?: return null
        while (true) {
            expression =
                when {
                    match(TokenKind.LT_LT) -> {
                        val right = parseTerm() ?: return null
                        BinaryExpression(expression, BinaryOperator.SHIFT_LEFT, right, SourceRange(expression.range.start, right.range.end))
                    }

                    match(TokenKind.GT_GT) -> {
                        val right = parseTerm() ?: return null
                        BinaryExpression(expression, BinaryOperator.SHIFT_RIGHT, right, SourceRange(expression.range.start, right.range.end))
                    }

                    else -> return expression
                }
        }
    }
```

Keep the existing `parseTerm()` and `parseFactor()` bodies below this new `parseShift()` function.

In `parseUnary()`, add `~` before the final `else`:

```kotlin
            match(TokenKind.TILDE) -> {
                val operand = parseUnary() ?: return null
                UnaryExpression(
                    UnaryOperator.BIT_NOT,
                    operand,
                    SourceRange(previous().range.start, operand.range.end),
                )
            }
```

- [ ] **Step 7: Type-check bitwise operators**

In `LanguageFrontend.kt`, add helpers near `isAssignable(...)`:

```kotlin
    private fun isNumeric(type: TypeRef): Boolean = type.name in NUMERIC_TYPE_NAMES && !type.nullable

    private fun widerNumericType(
        left: TypeRef,
        right: TypeRef,
    ): TypeRef = if (left.name == "Long" || right.name == "Long") TypeRef("Long") else TypeRef("Int")
```

Add this constant to the parser/analyzer companion area where shared constants belong:

```kotlin
        val NUMERIC_TYPE_NAMES = setOf("Int", "Long")
```

In `analyzeUnary(...)`, add:

```kotlin
            UnaryOperator.BIT_NOT -> {
                if (!isNumeric(operandType)) {
                    diagnostics += FrontendDiagnostic("Bitwise not expects Int or Long.", expression.range)
                }
                operandType
            }
```

In `analyzeBinary(...)`, add these branches before comparison operators:

```kotlin
            BinaryOperator.BIT_AND,
            BinaryOperator.BIT_OR,
            BinaryOperator.BIT_XOR,
            -> {
                if (!isNumeric(left) || !isNumeric(right)) {
                    diagnostics += FrontendDiagnostic("Bitwise operators expect Int or Long operands.", expression.range)
                    TypeRef("Unit")
                } else {
                    widerNumericType(left, right)
                }
            }

            BinaryOperator.SHIFT_LEFT,
            BinaryOperator.SHIFT_RIGHT,
            -> {
                if (!isNumeric(left) || !isNumeric(right)) {
                    diagnostics += FrontendDiagnostic("Shift operators expect Int or Long operands.", expression.range)
                }
                left
            }
```

- [ ] **Step 8: Execute bitwise operators at runtime**

In `LanguageRuntime.kt`, add `UnaryOperator.BIT_NOT`:

```kotlin
                UnaryOperator.BIT_NOT -> {
                    when (value) {
                        is VmValue.IntValue -> VmValue.IntValue(value.value.inv())
                        is VmValue.LongValue -> VmValue.LongValue(value.value.inv())
                        else -> error("Bitwise not expects a numeric value.")
                    }
                }
```

Add these `BinaryOperator` branches:

```kotlin
                BinaryOperator.BIT_AND -> {
                    if (left is VmValue.IntValue && right is VmValue.IntValue) {
                        VmValue.IntValue(left.value and right.value)
                    } else {
                        VmValue.LongValue(left.asLong() and right.asLong())
                    }
                }

                BinaryOperator.BIT_OR -> {
                    if (left is VmValue.IntValue && right is VmValue.IntValue) {
                        VmValue.IntValue(left.value or right.value)
                    } else {
                        VmValue.LongValue(left.asLong() or right.asLong())
                    }
                }

                BinaryOperator.BIT_XOR -> {
                    if (left is VmValue.IntValue && right is VmValue.IntValue) {
                        VmValue.IntValue(left.value xor right.value)
                    } else {
                        VmValue.LongValue(left.asLong() xor right.asLong())
                    }
                }

                BinaryOperator.SHIFT_LEFT -> {
                    if (left is VmValue.IntValue) {
                        VmValue.IntValue(left.value shl right.asInt())
                    } else {
                        VmValue.LongValue(left.asLong() shl right.asInt())
                    }
                }

                BinaryOperator.SHIFT_RIGHT -> {
                    if (left is VmValue.IntValue) {
                        VmValue.IntValue(left.value shr right.asInt())
                    } else {
                        VmValue.LongValue(left.asLong() shr right.asInt())
                    }
                }
```

- [ ] **Step 9: Format new operators with approved precedence**

In `LanguageFormatter.kt`, replace precedence constants with:

```kotlin
private const val PRECEDENCE_OR = 1
private const val PRECEDENCE_AND = 2
private const val PRECEDENCE_EQUALITY = 3
private const val PRECEDENCE_COMPARISON = 4
private const val PRECEDENCE_BIT_OR = 5
private const val PRECEDENCE_BIT_XOR = 6
private const val PRECEDENCE_BIT_AND = 7
private const val PRECEDENCE_SHIFT = 8
private const val PRECEDENCE_TERM = 9
private const val PRECEDENCE_FACTOR = 10
private const val PRECEDENCE_UNARY = 11
private const val PRECEDENCE_CALL = 12
private const val PRECEDENCE_PRIMARY = 13
```

Add new binary precedence branches:

```kotlin
        BinaryOperator.BIT_OR -> PRECEDENCE_BIT_OR
        BinaryOperator.BIT_XOR -> PRECEDENCE_BIT_XOR
        BinaryOperator.BIT_AND -> PRECEDENCE_BIT_AND
        BinaryOperator.SHIFT_LEFT, BinaryOperator.SHIFT_RIGHT -> PRECEDENCE_SHIFT
```

Add new binary symbols:

```kotlin
        BinaryOperator.BIT_AND -> "&"
        BinaryOperator.BIT_OR -> "|"
        BinaryOperator.BIT_XOR -> "^"
        BinaryOperator.SHIFT_LEFT -> "<<"
        BinaryOperator.SHIFT_RIGHT -> ">>"
```

Add the unary symbol:

```kotlin
        UnaryOperator.BIT_NOT -> "~"
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

Add this test to `LanguageRuntimeTest` near `compoundAssignmentOperatorsMutateLocal`:

```kotlin
@Test
fun bitwiseCompoundAssignmentOperatorsMutateLocalAndMember() {
    val artifact =
        frontend.compile(
            "bitwise_compound.ck",
            """
            class Register(pub var value: Int) {
                pub fun apply(): Unit {
                    this.value |= 0b0100;
                    this.value &= 0b0110;
                    this.value ^= 0b0010;
                    this.value <<= 2;
                    this.value >>= 1;
                }
            }

            pub fun main() {
                var flags: Int = 0b0011;
                flags |= 0b0100;
                flags &= 0b0110;
                flags ^= 0b0010;
                flags <<= 2;
                flags >>= 1;
                system::log("local=" + flags);

                val register: Register = Register(value = 0b0011);
                register.apply();
                system::log("field=" + register.value);
            }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )

    val runtime = RecordingRuntime()
    runBlocking {
        BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
    }

    assertEquals(listOf("local=8", "field=8"), runtime.lines)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.bitwiseCompoundAssignmentOperatorsMutateLocalAndMember
```

Expected: test fails because assignment parsing does not accept `&=`, `|=`, `^=`, `<<=`, or `>>=`.

- [ ] **Step 3: Parse bitwise compound assignments**

In `LanguageFrontend.kt`, extend the `parseAssignment()` operator `when` with:

```kotlin
                TokenKind.AMP_EQUAL -> {
                    compoundDesugar(nameTok, BinaryOperator.BIT_AND, rhs)
                }

                TokenKind.PIPE_EQUAL -> {
                    compoundDesugar(nameTok, BinaryOperator.BIT_OR, rhs)
                }

                TokenKind.CARET_EQUAL -> {
                    compoundDesugar(nameTok, BinaryOperator.BIT_XOR, rhs)
                }

                TokenKind.LT_LT_EQUAL -> {
                    compoundDesugar(nameTok, BinaryOperator.SHIFT_LEFT, rhs)
                }

                TokenKind.GT_GT_EQUAL -> {
                    compoundDesugar(nameTok, BinaryOperator.SHIFT_RIGHT, rhs)
                }
```

In `parseMemberAssignment()`, add equivalent member branches:

```kotlin
                TokenKind.AMP_EQUAL -> {
                    compoundMemberDesugar(receiver, field, BinaryOperator.BIT_AND, rhs)
                }

                TokenKind.PIPE_EQUAL -> {
                    compoundMemberDesugar(receiver, field, BinaryOperator.BIT_OR, rhs)
                }

                TokenKind.CARET_EQUAL -> {
                    compoundMemberDesugar(receiver, field, BinaryOperator.BIT_XOR, rhs)
                }

                TokenKind.LT_LT_EQUAL -> {
                    compoundMemberDesugar(receiver, field, BinaryOperator.SHIFT_LEFT, rhs)
                }

                TokenKind.GT_GT_EQUAL -> {
                    compoundMemberDesugar(receiver, field, BinaryOperator.SHIFT_RIGHT, rhs)
                }
```

Update the assignment diagnostic string in both assignment parsers to:

```kotlin
"Expected assignment operator. Supported operators: `=`, `+=`, `-=`, `*=`, `/=`, `&=`, `|=`, `^=`, `<<=`, `>>=`."
```

Extend `COMPOUND_ASSIGN_KINDS` with:

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

Add this test to `LanguageIdeTest` near `producesDiagnosticsAndHighlights`:

```kotlin
@Test
fun highlightsBitwiseOperators() {
    val source =
        """
        pub fun main() {
            val value: Int = ~0b0011 & 0b1111 | 0b0001 ^ 0b0010 << 1 >> 1;
        }
        """.trimIndent()

    val snapshot = ide.analyze("bitwise.ck", source)

    assertTrue(snapshot.highlights.any { it.kind == HighlightTokenKind.OPERATOR }, snapshot.highlights.joinToString())
    assertTrue(snapshot.diagnostics.none { it.severity == IdeDiagnosticSeverity.ERROR }, snapshot.diagnostics.joinToString { it.message })
}
```

If `IdeDiagnosticSeverity` is not imported in `LanguageIdeTest`, add:

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

In `docs/LANGUAGE.md`, update the operator overview to include:

```markdown
- arithmetic, bitwise, and logic: `+` `-` `*` `/` `&` `|` `^` `~` `<<` `>>` `==` `!=` `<` `<=` `>` `>=` `&&` `||` `!`
- assignment and compound assignment: `=` `+=` `-=` `*=` `/=` `&=` `|=` `^=` `<<=` `>>=`
```

Add a subsection for bitwise semantics:

```markdown
### Bitwise operators

CKL supports signed bitwise operations on `Int` and `Long`:

- `a & b` bitwise AND.
- `a | b` bitwise OR.
- `a ^ b` bitwise XOR.
- `~a` bitwise NOT.
- `a << count` signed left shift.
- `a >> count` signed arithmetic right shift.

`&`, `|`, and `^` return `Long` if either operand is `Long`; otherwise they return `Int`. Shifts preserve the left operand type. Shift counts can be `Int` or `Long` and are evaluated as integer counts at runtime. CKL does not currently have unsigned integer types or unsigned right shift.

Binary integer literals use `0b` or `0B`:

```ck
val row: Int = 0b01110
val mask: Int = 0b11111
val wide: Long = 0b10000000000000000000000000000000L
```

Bitwise precedence is designed for readable flag and mask checks. `flags & mask == mask` parses as `(flags & mask) == mask`; `1 << 4 - col` parses as `1 << (4 - col)`.
```

If `docs/LANGUAGE.md` already has a precedence section, update it to the approved order from the spec. If it only has a short operator list, add the subsection after the expression/operator overview.

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

Expected: `git diff --check` prints no output. `git status --branch --short` shows only intended documentation and language feature files before the final commit.

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
