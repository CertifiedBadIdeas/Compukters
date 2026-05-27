# CKL Class Language Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add native CKL classes with Kotlin-like primary constructors, `init`, instance/static methods, reference-object semantics, and unified call-style construction for classes and structs.

**Architecture:** Extend the compiler front-end with class AST nodes, named call arguments, class/type bindings, `this`, member assignment, and method/static resolution. Extend bytecode/runtime with native object references and a deterministic VM heap; keep structs as value records but migrate their construction syntax to `Vec2(x = 1)`. Update IDE completions/imports after the compiler/runtime model is stable.

**Tech Stack:** Kotlin, Gradle, CKL compiler front-end, stack bytecode VM, existing `LanguageFrontend`, `LanguageRuntime`, and `LanguageIde` test suites.

---

## File structure

**Core language model**
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`: add `CLASS`, `STATIC`, `INIT`, `THIS` tokens.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`: add class AST nodes, call arguments, `ThisExpression`, member assignment statements, class bytecode metadata, object instructions.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TopLevelDeclaration.kt`: `ClassDeclaration` remains a `TopLevelDeclaration` through the shared interface.

**Frontend/compiler**
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`: lexer keyword mapping, parser, semantic analyzer, bytecode compiler.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SymbolKind.kt`: add `CLASS`, `METHOD`, and optionally reuse `FIELD` for class fields.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/AnalyzedProgram.kt` only if class metadata must be exposed outside `SemanticResult`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt` only for IDE constructor/member completion helpers.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`: class, constructor, instance member, static member, and import completions.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoader.kt`: include classes in indexed exports if the current source index only extracts functions/structs.

**Runtime**
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`: add `VmValue.ObjectRef`, deterministic heap, object allocation, field get/set, method/static calls.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmValueSupport.kt`: render object refs deterministically.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt` only if snapshot/rendering needs to include object refs.

**Docs/tests**
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`: parser/semantic tests.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`: VM object identity/init/mutation tests.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`: completions/imports.
- Modify existing user-file import tests if `ModuleExports` grows a class map.
- Modify `docs/LANGUAGE.md`: class syntax and struct construction syntax.

---

## Task 1: Tokens, AST, and RED parser tests

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`

- [ ] **Step 1: Write failing lexer/parser tests**

Add tests near existing lexer/parser tests:

```kotlin
@Test
fun lexesClassKeywords() {
    val tokens = Lexer("class Counter(var value: Int) { init {} static fun zero(): Int { return 0; } }").lex()

    assertTrue(tokens.any { it.kind == TokenKind.CLASS }, tokens.joinToString { "${it.kind}:${it.text}" })
    assertTrue(tokens.any { it.kind == TokenKind.INIT }, tokens.joinToString { "${it.kind}:${it.text}" })
    assertTrue(tokens.any { it.kind == TokenKind.STATIC }, tokens.joinToString { "${it.kind}:${it.text}" })
}

@Test
fun parsesBasicClassDeclaration() {
    val artifact =
        frontend.compile(
            "class_parse.ck",
            """
            class Counter(var value: Int) {
                init { this.value = value; }
                fun current(): Int { return this.value; }
                static fun zero(): Counter { return Counter(value = 0); }
            }
            fun main() {}
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR && it.message.contains("Expected a top-level declaration") },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*lexesClassKeywords" --tests "*LanguageFrontendTest*parsesBasicClassDeclaration"
```

Expected: FAIL because class keywords/AST/parser are not implemented.

- [ ] **Step 3: Add token kinds and lexer keyword mapping**

In `TokenKind.kt`, add tokens after `STRUCT`:

```kotlin
CLASS,
STATIC,
INIT,
THIS,
```

In `LanguageFrontend.kt`, update the identifier keyword mapping so these texts produce the new tokens:

```kotlin
"class" -> TokenKind.CLASS
"static" -> TokenKind.STATIC
"init" -> TokenKind.INIT
"this" -> TokenKind.THIS
```

- [ ] **Step 4: Add AST nodes**

In `LanguageModel.kt`, add class model types near `StructDeclaration`:

```kotlin
data class ClassDeclaration(
    override val name: String,
    val constructorParameters: List<ClassConstructorParameter>,
    val members: List<ClassMemberDeclaration>,
    override val range: SourceRange,
) : TopLevelDeclaration

data class ClassConstructorParameter(
    val name: String,
    val type: TypeSyntax,
    val fieldMutability: FieldMutability?,
    val range: SourceRange,
)

enum class FieldMutability { VAL, VAR }

sealed interface ClassMemberDeclaration {
    val range: SourceRange
}

data class ClassFieldDeclaration(
    val name: String,
    val type: TypeSyntax?,
    val mutable: Boolean,
    val initializer: Expression,
    override val range: SourceRange,
) : ClassMemberDeclaration

data class ClassInitBlock(
    val body: BlockStatement,
    override val range: SourceRange,
) : ClassMemberDeclaration

data class ClassMethodDeclaration(
    val function: FunctionDeclaration,
    val static: Boolean,
    override val range: SourceRange,
) : ClassMemberDeclaration
```

Add call argument and `this` expression support:

```kotlin
sealed interface CallArgument {
    val expression: Expression
    val range: SourceRange
}

data class PositionalCallArgument(
    override val expression: Expression,
    override val range: SourceRange,
) : CallArgument

data class NamedCallArgument(
    val name: String,
    val nameRange: SourceRange,
    override val expression: Expression,
    override val range: SourceRange,
) : CallArgument

data class ThisExpression(
    override val range: SourceRange,
) : Expression
```

Change `CallExpression.arguments` from `List<Expression>` to `List<CallArgument>`.

- [ ] **Step 5: Compile-fix all positional call sites minimally**

Every existing analyzer/compiler path that iterates call arguments should unwrap `argument.expression`. This is a mechanical compile fix; do not add class semantics yet. For example, where code uses `expression.arguments.map { analyzeExpression(it, scope) }`, change it to:

```kotlin
expression.arguments.map { analyzeExpression(it.expression, scope) }
```

Where code checks argument count, use `expression.arguments.size`.

- [ ] **Step 6: Implement parser support minimally**

Add a `TokenKind.CLASS` branch to `parseProgram()`. Implement `parseClass()` with:

```kotlin
private fun parseClass(): ClassDeclaration? {
    val name = consume(TokenKind.IDENTIFIER, "Expected class name.") ?: return null
    consume(TokenKind.LPAREN, "Expected `(` after class name.") ?: return null
    val parameters = mutableListOf<ClassConstructorParameter>()
    if (!check(TokenKind.RPAREN)) {
        do {
            val mutability =
                when {
                    match(TokenKind.VAL) -> FieldMutability.VAL
                    match(TokenKind.VAR) -> FieldMutability.VAR
                    else -> null
                }
            val parameterName = consume(TokenKind.IDENTIFIER, "Expected constructor parameter name.") ?: return null
            consume(TokenKind.COLON, "Expected `:` after constructor parameter name.") ?: return null
            val type = parseType() ?: return null
            parameters += ClassConstructorParameter(parameterName.text, type, mutability, SourceRange(parameterName.range.start, type.range.end))
        } while (match(TokenKind.COMMA))
    }
    consume(TokenKind.RPAREN, "Expected `)` after class constructor parameters.") ?: return null
    consume(TokenKind.LBRACE, "Expected `{` after class constructor.") ?: return null
    val members = mutableListOf<ClassMemberDeclaration>()
    while (!check(TokenKind.RBRACE) && !isAtEnd()) {
        members += parseClassMember() ?: return null
    }
    val end = consume(TokenKind.RBRACE, "Expected `}` after class body.") ?: return null
    return ClassDeclaration(name.text, parameters, members, SourceRange(name.range.start, end.range.end))
}
```

Add `parseClassMember()` to parse `init`, `fun`, `static fun`, `val`, and `var` members. Reuse `parseFunction()` for method bodies and `parseVariable()` logic for field initializer syntax.

Change `parseCall()` so arguments can be positional or named:

```kotlin
private fun parseCallArgument(): CallArgument? {
    if (check(TokenKind.IDENTIFIER) && checkNext(TokenKind.EQUAL)) {
        val name = advance()
        advance() // equals
        val value = parseExpression() ?: return null
        return NamedCallArgument(name.text, name.range, value, SourceRange(name.range.start, value.range.end))
    }
    val value = parseExpression() ?: return null
    return PositionalCallArgument(value, value.range)
}
```

- [ ] **Step 7: Run tests and commit**

Run:

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*lexesClassKeywords" --tests "*LanguageFrontendTest*parsesBasicClassDeclaration"
./gradlew :compiler:test
```

Expected: targeted tests PASS; full compiler tests PASS after positional call unwrap fixes.

Commit:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): parse class declarations"
```

---

## Task 2: Unified call-style construction and old struct literal rejection

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`

- [ ] **Step 1: Write failing tests for new struct construction and old literal rejection**

Add frontend tests:

```kotlin
@Test
fun compilesStructCallStyleConstruction() {
    val artifact =
        frontend.compile(
            "struct_call.ck",
            """
            struct Point { x: Int, y: Int }
            fun main() {
                val point: Point = Point(x = 1, y = 2);
                terminal::println("x=" + point.x);
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
fun rejectsOldRecordConstructionSyntax() {
    val artifact =
        frontend.compile(
            "old_record.ck",
            """
            struct Point { x: Int, y: Int }
            fun main() { val point: Point = Point { x: 1, y: 2 }; }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.any { it.severity == FrontendSeverity.ERROR && it.message.contains("Old record construction syntax") },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    assertEquals(null, artifact.module)
}
```

Add runtime test:

```kotlin
@Test
fun constructsStructsWithNamedCallSyntax() {
    val artifact =
        frontend.compile(
            "struct_runtime.ck",
            """
            struct Point { x: Int, y: Int }
            fun main() {
                val point: Point = Point(x = 4, y = 5);
                terminal::println("sum=" + (point.x + point.y));
            }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    val runtime = RecordingRuntime()
    runBlocking { BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime) }
    assertEquals(listOf("sum=9"), runtime.lines)
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*compilesStructCallStyleConstruction" --tests "*LanguageFrontendTest*rejectsOldRecordConstructionSyntax" --tests "*LanguageRuntimeTest*constructsStructsWithNamedCallSyntax"
```

Expected: FAIL because constructor calls with named arguments are not treated as record construction and old literal syntax is still accepted.

- [ ] **Step 3: Add legacy record literal diagnostic path**

Keep parsing the old brace form only so the analyzer can emit a direct diagnostic. Add `LegacyRecordConstructionExpression`:

```kotlin
data class LegacyRecordConstructionExpression(
    val typeName: String,
    val fields: List<RecordFieldInitializer>,
    override val range: SourceRange,
    val qualifier: String? = null,
) : Expression
```

Change the existing brace parse path to produce `LegacyRecordConstructionExpression`. In semantic analysis, return `Unit` type and add:

```kotlin
diagnostics += FrontendDiagnostic(
    "Old record construction syntax is no longer valid. Use `${expression.typeName}(x = value)` instead.",
    expression.range,
)
```

- [ ] **Step 4: Treat named call to record type as construction**

In call analysis, before ordinary function call resolution, detect `CallExpression(NameExpression(typeName), namedArguments)` where `typeName` resolves to a record binding. Validate:

```kotlin
private fun namedArguments(expression: CallExpression): List<NamedCallArgument>? {
    val named = expression.arguments.filterIsInstance<NamedCallArgument>()
    if (named.size != expression.arguments.size) return null
    return named
}
```

For record construction:
- all arguments must be named;
- every record field appears exactly once;
- no unknown field names;
- no duplicate argument names;
- values are assignable to field types.

Reuse the existing `Instruction.ConstructRecord(typeName, fieldNames)` bytecode by compiling argument expressions in record field order.

- [ ] **Step 5: Reject named arguments for normal functions for now**

For `CallExpression` that resolves to a user function or built-in function, if any `NamedCallArgument` is present, emit:

```kotlin
FrontendDiagnostic("Named arguments are only supported for constructors.", argument.range)
```

- [ ] **Step 6: Run tests and commit**

Run:

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*compilesStructCallStyleConstruction" --tests "*LanguageFrontendTest*rejectsOldRecordConstructionSyntax" --tests "*LanguageRuntimeTest*constructsStructsWithNamedCallSyntax"
./gradlew :compiler:test
```

Expected: PASS.

Commit:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
git commit -m "feat(compiler): use call-style struct construction"
```

---

## Task 3: Class semantic registration and constructor checking

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SymbolKind.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`

- [ ] **Step 1: Write failing class constructor semantic tests**

Add tests:

```kotlin
@Test
fun compilesClassConstructorCall() {
    val artifact =
        frontend.compile(
            "class_ctor.ck",
            """
            class Counter(var value: Int) {}
            fun main() {
                val counter: Counter = Counter(value = 3);
                terminal::println("value=" + counter.value);
            }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    assertNotNull(artifact.module)
    assertTrue(artifact.analysis.symbols.any { it.name == "Counter" && it.detail.contains("class Counter") })
}

@Test
fun reportsClassConstructorArgumentErrors() {
    val artifact =
        frontend.compile(
            "class_ctor_errors.ck",
            """
            class Counter(var value: Int) {}
            fun main() { val counter: Counter = Counter(missing = 3); }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.any { it.message.contains("Unknown constructor parameter `missing`") },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    assertTrue(
        artifact.analysis.diagnostics.any { it.message.contains("Missing constructor argument `value`") },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    assertEquals(null, artifact.module)
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*compilesClassConstructorCall" --tests "*LanguageFrontendTest*reportsClassConstructorArgumentErrors"
```

Expected: FAIL because class declarations are parsed but not registered as types/constructors.

- [ ] **Step 3: Add class bindings**

In `LanguageFrontend.kt`, add internal semantic models:

```kotlin
internal data class ClassFieldBinding(
    val name: String,
    val type: TypeRef,
    val mutable: Boolean,
    val symbol: SymbolInfo,
)

internal data class ClassMethodBinding(
    val name: String,
    val function: FunctionDeclaration,
    val parameterTypes: List<TypeRef>,
    val returnType: TypeRef,
    val static: Boolean,
    val symbol: SymbolInfo,
)

internal data class ClassBinding(
    override val symbol: SymbolInfo,
    val declaration: ClassDeclaration,
    val constructorParameters: List<ClassConstructorParameter>,
    val fields: Map<String, ClassFieldBinding>,
    val instanceMethods: Map<String, ClassMethodBinding>,
    val staticMethods: Map<String, ClassMethodBinding>,
) : Binding
```

Add `private val userClassesByName = mutableMapOf<String, ClassBinding>()` and include class names in redeclaration checks with functions, records, imports, and modules.

- [ ] **Step 4: Register classes as types**

In `registerTopLevel()`, process classes after structs and before functions. For each class:
- add `typeNames[className] = TypeRef(className)`;
- create `SymbolInfo(kind = SymbolKind.CLASS, detail = "class $className")`;
- resolve constructor field parameter types;
- create field symbols for `val`/`var` constructor parameters;
- create field symbols for body fields;
- collect method signatures but do not compile method bodies yet.

Add `CLASS` and `METHOD` to `SymbolKind.kt`.

- [ ] **Step 5: Analyze class constructor call and class field reads**

Extend call analysis:
- `Counter(value = 3)` resolves to a class constructor when callee is `NameExpression("Counter")` and all args are named;
- return type is `TypeRef("Counter")`;
- validate duplicate, unknown, missing, and type mismatch diagnostics.

Extend member analysis:
- when receiver type is a class name, resolve fields from `ClassBinding.fields`;
- bind field access to `MemberBinding` with field type.

- [ ] **Step 6: Run tests and commit**

Run:

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*compilesClassConstructorCall" --tests "*LanguageFrontendTest*reportsClassConstructorArgumentErrors"
./gradlew :compiler:test
```

Expected: PASS.

Commit:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SymbolKind.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): resolve class constructors and fields"
```

---

## Task 4: `this`, `init`, and member assignment analysis

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`

- [ ] **Step 1: Write failing tests for `this`, `init`, and field mutability**

Add tests:

```kotlin
@Test
fun analyzesThisAndInitAssignments() {
    val artifact =
        frontend.compile(
            "this_init.ck",
            """
            class Counter(var value: Int) {
                init { this.value = value + 1; }
                fun current(): Int { return this.value; }
            }
            fun main() { val counter: Counter = Counter(value = 1); }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}

@Test
fun rejectsAssignmentToValField() {
    val artifact =
        frontend.compile(
            "val_field.ck",
            """
            class Holder(val value: Int) {
                fun bad(): Unit { this.value = 2; }
            }
            fun main() {}
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.any { it.message.contains("Cannot assign to val field `value`") },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    assertEquals(null, artifact.module)
}

@Test
fun rejectsThisInStaticMethod() {
    val artifact =
        frontend.compile(
            "static_this.ck",
            """
            class Holder(var value: Int) {
                static fun bad(): Int { return this.value; }
            }
            fun main() {}
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.any { it.message.contains("Static method cannot access `this`") },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*analyzesThisAndInitAssignments" --tests "*LanguageFrontendTest*rejectsAssignmentToValField" --tests "*LanguageFrontendTest*rejectsThisInStaticMethod"
```

Expected: FAIL because `this` and member assignment are not analyzed.

- [ ] **Step 3: Add member assignment AST**

In `LanguageModel.kt`, add:

```kotlin
data class MemberAssignmentStatement(
    val receiver: Expression,
    val memberName: String,
    val memberRange: SourceRange,
    val expression: Expression,
    override val range: SourceRange,
) : Statement
```

Extend parser statement handling so `this.value = expr;` and `object.value = expr;` become `MemberAssignmentStatement`. Keep local variable assignment as `AssignmentStatement`.

- [ ] **Step 4: Add class-aware analysis scope**

Add analyzer context fields:

```kotlin
private var currentClass: ClassBinding? = null
private var currentStaticMethod: Boolean = false
private var inConstruction: Boolean = false
```

When analyzing `ClassInitBlock`, set `currentClass`, `currentStaticMethod = false`, `inConstruction = true`. When analyzing instance methods, set `currentClass`, `currentStaticMethod = false`, `inConstruction = false`. When analyzing static methods, set `currentClass`, `currentStaticMethod = true`.

- [ ] **Step 5: Analyze `this` and member assignments**

For `ThisExpression`:
- if no current class, emit `this is only valid inside class instance context.`;
- if current static method, emit "Static method cannot access `this`.";
- otherwise return `TypeRef(currentClass.symbol.name)`.

For `MemberAssignmentStatement`:
- analyze receiver;
- resolve class field by receiver type;
- reject unknown fields;
- reject assignment to `val` field unless `inConstruction` is true and receiver is `ThisExpression`;
- check assignability of right-hand expression to field type.

- [ ] **Step 6: Run tests and commit**

Run:

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*analyzesThisAndInitAssignments" --tests "*LanguageFrontendTest*rejectsAssignmentToValField" --tests "*LanguageFrontendTest*rejectsThisInStaticMethod"
./gradlew :compiler:test
```

Expected: PASS.

Commit:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): analyze class this and field assignment"
```

---

## Task 5: Bytecode metadata and runtime object heap

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmValueSupport.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`

- [ ] **Step 1: Write failing runtime test for reference identity and field mutation**

Add test:

```kotlin
@Test
fun classInstancesHaveReferenceIdentityAndSharedMutation() {
    val artifact =
        frontend.compile(
            "class_identity.ck",
            """
            class Counter(var value: Int) {
                fun inc(): Unit { this.value = this.value + 1; }
                fun current(): Int { return this.value; }
            }
            fun main() {
                val a: Counter = Counter(value = 1);
                val b: Counter = a;
                b.inc();
                terminal::println("a=" + a.current());
            }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    val runtime = RecordingRuntime()
    runBlocking { BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime) }
    assertEquals(listOf("a=2"), runtime.lines)
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
./gradlew :compiler:test --tests "*LanguageRuntimeTest*classInstancesHaveReferenceIdentityAndSharedMutation"
```

Expected: FAIL because class bytecode/runtime execution is missing.

- [ ] **Step 3: Add bytecode class metadata and instructions**

In `LanguageModel.kt`, extend `BytecodeModule`:

```kotlin
val classes: List<BytecodeClass> = emptyList()
```

Add:

```kotlin
data class BytecodeClass(
    val name: String,
    val fields: List<BytecodeClassField>,
    val initFunctionIndex: Int?,
    val instanceMethods: Map<String, Int>,
    val staticMethods: Map<String, Int>,
)

data class BytecodeClassField(
    val name: String,
    val typeName: String,
    val mutable: Boolean,
)
```

Add instructions:

```kotlin
data class ConstructClass(val className: String, val fieldNames: List<String>) : Instruction
data class SetField(val fieldName: String) : Instruction
data class CallMethod(val methodName: String, val argumentCount: Int) : Instruction
data class CallStaticMethod(val className: String, val methodName: String, val argumentCount: Int) : Instruction
```

- [ ] **Step 4: Add runtime object heap**

In `LanguageRuntime.kt`, add VM value and heap state:

```kotlin
data class ObjectRef(val id: Int) : VmValue

private data class VmObject(
    val className: String,
    val fields: MutableMap<String, VmValue>,
)
```

Add to `BytecodeVirtualMachine`:

```kotlin
private val heap = mutableMapOf<Int, VmObject>()
private var nextObjectId = 1
```

For `ConstructClass`, pop values in reverse, create `ObjectRef(nextObjectId++)`, store object fields, push ref.

For `GetField`, if receiver is `ObjectRef`, read from heap object fields; keep existing `RecordValue` path.

For `SetField`, pop value then receiver, verify receiver is `ObjectRef`, mutate heap object field, push `UnitValue` or no value according to existing statement compilation convention.

Update `VmValue.render()`:

```kotlin
is VmValue.ObjectRef -> "${heapObjectClassNameOrObject}(#${value.id})"
```

If `render()` cannot access heap, render as `object#id`.

- [ ] **Step 5: Compile class constructors and field assignments**

In bytecode compiler:
- emit class metadata from `ClassBinding`;
- compile `Counter(value = 1)` to argument expressions in constructor parameter order plus `ConstructClass("Counter", listOf("value"))`;
- compile `MemberAssignmentStatement` to receiver expression, value expression, `SetField(fieldName)`, then pop if statement context requires no value.

- [ ] **Step 6: Run test and commit**

Run:

```bash
./gradlew :compiler:test --tests "*LanguageRuntimeTest*classInstancesHaveReferenceIdentityAndSharedMutation"
./gradlew :compiler:test
```

Expected: PASS.

Commit:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmValueSupport.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
git commit -m "feat(runtime): execute class objects with heap references"
```

---

## Task 6: Instance methods, static methods, and init execution

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`

- [ ] **Step 1: Write failing tests for init and static methods**

Add runtime test:

```kotlin
@Test
fun classInitAndStaticMethodsRun() {
    val artifact =
        frontend.compile(
            "class_init_static.ck",
            """
            class Counter(var value: Int) {
                init { this.value = this.value + 1; }
                fun current(): Int { return this.value; }
                static fun zero(): Counter { return Counter(value = 0); }
            }
            fun main() {
                val counter: Counter = Counter.zero();
                terminal::println("value=" + counter.current());
            }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    val runtime = RecordingRuntime()
    runBlocking { BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime) }
    assertEquals(listOf("value=1"), runtime.lines)
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
./gradlew :compiler:test --tests "*LanguageRuntimeTest*classInitAndStaticMethodsRun"
```

Expected: FAIL because method/static dispatch and init execution are incomplete.

- [ ] **Step 3: Analyze method calls**

For `CallExpression(MemberAccessExpression(receiver, methodName), args)`:
- if receiver expression type is a class, resolve instance method;
- prepend implicit receiver to bytecode call or emit `CallMethod`;
- reject unknown methods with `Class `Counter` has no method `name`.`;
- validate positional arguments only for methods in this version.

For `CallExpression(MemberAccessExpression(NameExpression(className), methodName), args)`:
- if `className` is a class binding and method is static, resolve static method;
- emit `CallStaticMethod(className, methodName, args.size)` or compile to direct function index;
- reject `Counter.missing()` directly.

- [ ] **Step 4: Compile methods as hidden bytecode functions**

Compile instance methods as bytecode functions with an implicit first local named `this` of the class type. Use deterministic mangled names:

```text
ClassName.methodName
ClassName.static.methodName
ClassName.<init>
```

Compile `init` blocks into a synthetic init function with implicit `this`. Constructor bytecode should allocate the object, then call init before leaving the constructed object on the stack.

- [ ] **Step 5: Execute method/static instructions**

If using direct function indices:
- `CallMethod` resolves class + method to function index at compile time and pushes receiver as argument 0;
- `CallStaticMethod` resolves to static function index.

If using instruction names, runtime looks up function index from `BytecodeClass`. Prefer compile-time direct indices if it fits the existing `CallFunction` frame model.

- [ ] **Step 6: Run tests and commit**

Run:

```bash
./gradlew :compiler:test --tests "*LanguageRuntimeTest*classInitAndStaticMethodsRun" --tests "*LanguageRuntimeTest*classInstancesHaveReferenceIdentityAndSharedMutation"
./gradlew :compiler:test
```

Expected: PASS.

Commit:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): compile class methods and init blocks"
```

---

## Task 7: Imports, source index, and IDE completions for classes

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoader.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`

- [ ] **Step 1: Write failing import and IDE tests**

Add import test:

```kotlin
@Test
fun selectiveImportCanImportClass() {
    val loader = MapSourceLoader(
        mapOf(
            "main.ck" to """
                import "model.ck" { Counter };
                fun main() {
                    val counter: Counter = Counter(value = 2);
                    terminal::println("value=" + counter.value);
                }
            """.trimIndent(),
            "model.ck" to "class Counter(var value: Int) {}",
        ),
    )
    val artifact = compileProject("main.ck", loader)

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    assertNotNull(artifact.module)
}
```

Add IDE tests:

```kotlin
@Test
fun suggestsUserFileClassWithPathAndImportEdit() {
    val loader = MapSourceLoader(mapOf("main.ck" to "fun main() { Cou }", "model.ck" to "class Counter(var value: Int) {}"))
    val ide = LanguageIde(sourceIndex = loader)
    val source = loader.read("main.ck")!!
    val cursor = lineAndColumnOf(source, "Cou") + 3

    val items = ide.complete("main.ck", source, cursor.first, cursor.second)
    val counter = items.single { it.label == "Counter" && it.sourceNamespace == "model.ck" }

    assertEquals(listOf(TextEdit(0, 0, "import \"model.ck\" { Counter };\n")), counter.additionalTextEdits)
}

@Test
fun completesMembersAfterThisDot() {
    val source =
        """
        class Counter(var value: Int) {
            fun current(): Int { return this. }
        }
        fun main() {}
        """.trimIndent()
    val cursor = lineAndColumnOf(source, "this.") + 5

    val items = ide.complete("counter.ck", source, cursor.first, cursor.second)

    assertTrue(items.any { it.label == "value" }, items.joinToString { it.label })
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew :compiler:test --tests "*UserFileImportsTest*selectiveImportCanImportClass" --tests "*LanguageIdeTest*suggestsUserFileClassWithPathAndImportEdit" --tests "*LanguageIdeTest*completesMembersAfterThisDot"
```

Expected: FAIL because classes are not exported/indexed/completed.

- [ ] **Step 3: Export classes from imported files**

Extend `ModuleExports` with:

```kotlin
val classes: Map<String, ClassDeclaration>
```

Update selective import registration so class names can be imported into `userClassesByName`. Add redeclaration checks with functions, structs, aliases, and built-in modules.

- [ ] **Step 4: Index classes for auto-import**

Update source indexing parse extraction in `LanguageIde`/`SourceLoader` to include top-level `ClassDeclaration`. Completion item:

```kotlin
CompletionItem(
    label = className,
    insertText = "$className(",
    kind = CompletionItemKind.CLASS,
    sourceNamespace = path,
    additionalTextEdits = listOf(importEdit),
    cursorOffset = className.length + 1,
)
```

If `CompletionItemKind` has no `CLASS`, add it in the runtime IDE model and render it like `STRUCT`/`TYPE`.

- [ ] **Step 5: Add member completions**

When completion context is after `receiver.`:
- if receiver is `this`, use current class fields and instance methods;
- if receiver type is class, suggest fields and instance methods;
- if receiver token is class name, suggest static methods.

Set `sourceNamespace` to the class name for member completions if the UI benefits from a right-side label.

- [ ] **Step 6: Run tests and commit**

Run:

```bash
./gradlew :compiler:test --tests "*UserFileImportsTest*selectiveImportCanImportClass" --tests "*LanguageIdeTest*suggestsUserFileClassWithPathAndImportEdit" --tests "*LanguageIdeTest*completesMembersAfterThisDot"
./gradlew :compiler:test
```

Expected: PASS.

Commit:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoader.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt
git commit -m "feat(ide): support class imports and completions"
```

---

## Task 8: Documentation and migration cleanup

**Files:**
- Modify: `docs/LANGUAGE.md`
- Modify tests/docs containing old record construction syntax.

- [ ] **Step 1: Update language docs**

Document:
- `class Counter(var value: Int) { init { ... } fun current(): Int { ... } static fun zero(): Counter { ... } }`;
- `Counter(value = 1)` construction;
- `Counter.zero()` static methods;
- reference semantics;
- `struct Vec2 { ... }` construction via `Vec2(x = 1, y = 2)`;
- old `Vec2 { x: 1 }` syntax is invalid.

- [ ] **Step 2: Find stale old record literals**

Run:

```bash
grep -rnE '[A-Z][A-Za-z0-9_]* \{ *[a-zA-Z_][a-zA-Z0-9_]*:' . --include='*.ck' --include='*.kt' --include='*.md' --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.git || true
```

Expected: only intentional rejection tests or historical spec/plan docs remain. Update live docs/tests to call-style construction.

- [ ] **Step 3: Run docs-related verification**

Run:

```bash
./gradlew :compiler:test
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add docs/LANGUAGE.md modules/compiler/src/test docs/superpowers/specs docs/superpowers/plans
git commit -m "docs(language): document class support"
```

---

## Task 9: Final verification

**Files:**
- No planned source changes.

- [ ] **Step 1: Run compiler tests**

```bash
./gradlew :compiler:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run full tests**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Check stale old record syntax outside historical docs**

```bash
(grep -rnE '[A-Z][A-Za-z0-9_]* \{ *[a-zA-Z_][a-zA-Z0-9_]*:' . --include='*.ck' --include='*.kt' --include='*.md' --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.git || true) | grep -v '^./docs/superpowers/' || true
```

Expected: only intentional rejection test references.

- [ ] **Step 4: Check git status and tag**

```bash
git status --short
git tag -f class-language-support-complete
```

Expected: clean status before tagging.
