# Scope Operator `::` and Implicit Builtins Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce `::` as the namespace/scope-resolution operator for built-in modules (`terminal::write`, `system::computerId`), make built-in modules implicitly available without `import`, restrict `.` to struct-field access only, and reject any `import` declaration with a hard error (preparing the syntactic slot for user-file imports in the follow-up plan).

**Architecture:** Add a new `COLON_COLON` token in the lexer, a new `ScopeAccessExpression` AST node distinct from `MemberAccessExpression`, parser support for `IDENTIFIER :: IDENTIFIER` in primary expressions and type syntax, and a resolver that routes `::` to the builtin registry while routing `.` to record fields. Built-in modules are pre-registered as ambient symbols at the start of analysis instead of via `import`. Migrate ROM `.ck` files and inline test snippets in the same commit boundary as the syntactic changes.

**Tech Stack:** Kotlin, kotlin-test (JUnit5 underneath), Gradle multi-module build (`:compiler`).

---

## File Map

| File | Action | Responsibility |
| --- | --- | --- |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt` | Modify | Add `COLON_COLON` enum member |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt` | Modify | Add `ScopeAccessExpression` data class; extend `TypeSyntax` to optionally carry a qualifier |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt` | Modify | Lexer (`::`), Parser (primary + type), SemanticAnalyzer (ambient builtins, scope vs field resolution, import-rejection), BytecodeCompiler (handle new node) |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt` | Modify | Add tests for `::` parsing, `.` field-only enforcement, ambient builtins, import rejection |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt` | Modify | Migrate inline `.ck` snippets to `::` syntax and remove `import terminal;` lines |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt` | Modify | Update inline snippets and any IDE expectations affected by the new operator |
| `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/*.ck` | Modify | Replace `import <module>;` with implicit usage; replace `module.func(...)` with `module::func(...)` |
| `docs/LANGUAGE.md` | Modify | Document `::` for scope, `.` for fields, implicit builtins, removed `import <ident>;` syntax |

---

## Notes for the Executor

- **Type syntax change:** `TypeSyntax` currently has only `name: String, nullable: Boolean, range`. We extend it with `qualifier: String?` (default `null`). Plan A sets `qualifier` only for parsing — the resolver in Plan A does **not** yet know any user-defined namespaces, so a non-null qualifier must produce a diagnostic `Qualified types are not yet supported`. Plan B turns this on for import aliases. Keep this constraint visible in the diagnostic message so the follow-up plan finds it.
- **Backward compatibility is explicitly NOT a goal.** All existing source must be migrated in the same plan execution.
- **Import keyword stays a token.** We keep parsing it so we can emit a precise diagnostic. The whole `parseImport()` body becomes `emit error + synchronize`. Plan B re-enables it with the new `import "path";` form.

---

## Task 1: Add `COLON_COLON` Token

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt` (lexer `:` case)

- [ ] **Step 1: Write a failing lexer test**

Add to `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`:

```kotlin
@Test
fun lexesDoubleColonAsScopeOperator() {
    val artifact = frontend.compile("scope.ck", """
        fun main() { terminal::println("ok"); }
    """.trimIndent())
    // The current parser will at least produce no "unexpected character" diagnostic for `::`.
    assertTrue(
        artifact.analysis.diagnostics.none { it.message.contains("Unexpected character") },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.lexesDoubleColonAsScopeOperator"
```

Expected: FAIL — diagnostic contains "Unexpected character `:`" or similar (because `::` isn't recognized).

- [ ] **Step 3: Add the enum member**

Edit `TokenKind.kt`. Insert `COLON_COLON,` immediately after `COLON,`:

```kotlin
enum class TokenKind {
    IDENTIFIER, NUMBER, STRING, TRUE, FALSE, NULL,
    FUN, VAL, VAR, IF, ELSE, WHILE, WHEN, RETURN, IMPORT, STRUCT,
    COLON, COLON_COLON, SEMICOLON, COMMA, DOT, QUESTION,
    LPAREN, RPAREN, LBRACE, RBRACE,
    PLUS, MINUS, STAR, SLASH, BANG, EQUAL,
    PLUS_EQUAL, MINUS_EQUAL, STAR_EQUAL, SLASH_EQUAL,
    EQUAL_EQUAL, BANG_EQUAL, LT, LTE, GT, GTE,
    AMP_AMP, PIPE_PIPE, ARROW,
    EOF,
}
```

- [ ] **Step 4: Update the lexer `:` branch**

In `LanguageFrontend.kt` find the existing `':'` case in `Lexer.lex()` (around line 1313):

```kotlin
':' -> { addToken(TokenKind.COLON, ":", start) }
```

Replace with:

```kotlin
':' -> {
    if (match(':')) {
        addToken(TokenKind.COLON_COLON, "::", start)
    } else {
        addToken(TokenKind.COLON, ":", start)
    }
}
```

- [ ] **Step 5: Run the test to verify it passes (lex-only)**

```
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.lexesDoubleColonAsScopeOperator"
```

Expected: PASS (parser will still report errors about `::` because grammar doesn't accept it yet — but lexer-level "Unexpected character" must be gone).

- [ ] **Step 6: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): lex :: as COLON_COLON token"
```

---

## Task 2: Add `ScopeAccessExpression` AST Node and Extend `TypeSyntax`

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`

- [ ] **Step 1: Inspect existing `MemberAccessExpression` and `TypeSyntax`**

Read `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt` lines 30-45 and 175-185. Confirm:
- `TypeSyntax(name: String, nullable: Boolean, range: SourceRange)` — three fields.
- `MemberAccessExpression(receiver: Expression, memberName: String, range: SourceRange)`.

- [ ] **Step 2: Extend `TypeSyntax` with optional qualifier**

In `LanguageModel.kt` find:

```kotlin
data class TypeSyntax(
    val name: String,
    val nullable: Boolean,
    val range: SourceRange,
)
```

Replace with:

```kotlin
data class TypeSyntax(
    val name: String,
    val nullable: Boolean,
    val range: SourceRange,
    val qualifier: String? = null,
)
```

The default `null` keeps every existing construction call valid.

- [ ] **Step 3: Add `ScopeAccessExpression`**

In `LanguageModel.kt`, immediately after `data class MemberAccessExpression(...)`, add:

```kotlin
/**
 * Namespace/scope resolution: `qualifier::name`.
 * Always two flat identifiers. Unlike [MemberAccessExpression], the qualifier
 * never refers to a runtime value — it is a compile-time scope name (a built-in
 * module or, after Plan B lands, a user-file import alias).
 */
data class ScopeAccessExpression(
    val qualifier: String,
    val name: String,
    val qualifierRange: SourceRange,
    override val range: SourceRange,
) : Expression
```

- [ ] **Step 4: Compile to verify the API changes don't break callers**

```
./gradlew :compiler:compileKotlin
```

Expected: SUCCESS. (`TypeSyntax` callers all use the three-arg constructor, which is still valid because `qualifier` defaults to `null`.)

- [ ] **Step 5: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt
git commit -m "feat(compiler): add ScopeAccessExpression AST node and TypeSyntax qualifier"
```

---

## Task 3: Parse `::` in Primary Expressions and Type Syntax

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`

The current parser path for `terminal.write(...)` goes through `parseCall()` which sees a `NameExpression("terminal")` then a `DOT` and produces `MemberAccessExpression`. We replace the `::` alternative directly in the primary, so an `IDENTIFIER COLON_COLON IDENTIFIER` head produces a `ScopeAccessExpression` and then `parseCall()` may attach `(args)` or struct-construction `{...}` afterwards.

- [ ] **Step 1: Write failing parser tests**

Append to `LanguageFrontendTest.kt`:

```kotlin
@Test
fun parsesScopeCallToBuiltin() {
    val artifact = frontend.compile("ok.ck", """
        fun main() { terminal::println("hi"); }
    """.trimIndent())
    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    assertNotNull(artifact.module)
}

@Test
fun rejectsDotForBuiltinModuleAccess() {
    val artifact = frontend.compile("dot.ck", """
        fun main() { terminal.println("hi"); }
    """.trimIndent())
    assertTrue(
        artifact.analysis.diagnostics.any {
            it.severity == FrontendSeverity.ERROR && it.message.contains("Use `::` for module access")
        },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}
```

(Both will fail at this step — the second one fails because the resolver still accepts `terminal.println`. We will fix it in Task 4.)

- [ ] **Step 2: Locate `parsePrimary()` and `parseCall()`**

In `LanguageFrontend.kt`, find `parseCall()` near line 2025. Note the loop handling `LPAREN` and `DOT`. We will not modify the postfix loop. Instead, modify the identifier-primary branch.

Find `parsePrimary()` near line 2080 and the branch that handles `IDENTIFIER` (it currently produces either `RecordConstructionExpression` if next is `LBRACE`, or `NameExpression`).

- [ ] **Step 3: Add `::` handling in primary**

Inside the `IDENTIFIER` branch of `parsePrimary()`, BEFORE the existing logic that decides between record construction and bare name, insert:

```kotlin
if (check(TokenKind.COLON_COLON)) {
    advance() // consume `::`
    val nameToken = consume(TokenKind.IDENTIFIER, "Expected name after `::`.") ?: return null
    val scope = ScopeAccessExpression(
        qualifier = token.text,
        name = nameToken.text,
        qualifierRange = token.range,
        range = SourceRange(token.range.start, nameToken.range.end),
    )
    // Allow record construction `qualifier::Name { ... }`
    if (check(TokenKind.LBRACE) && looksLikeRecordConstruction()) {
        return parseQualifiedRecordConstruction(scope)
    }
    return scope
}
```

If `looksLikeRecordConstruction()` does not exist as a helper, mirror the look-ahead logic already used for unqualified construction (the existing parser inspects `LBRACE IDENTIFIER COLON` to disambiguate from a block). Extract it into a small private helper if needed.

- [ ] **Step 4: Add `parseQualifiedRecordConstruction()`**

Add this private helper alongside `parseRecordConstruction()`:

```kotlin
private fun parseQualifiedRecordConstruction(scope: ScopeAccessExpression): Expression? {
    val open = consume(TokenKind.LBRACE, "Expected `{` for record construction.") ?: return null
    val fields = mutableListOf<RecordFieldInitializer>()
    if (!check(TokenKind.RBRACE)) {
        do {
            val fieldName = consume(TokenKind.IDENTIFIER, "Expected field name.") ?: return null
            consume(TokenKind.COLON, "Expected `:` after field name.") ?: return null
            val value = parseExpression() ?: return null
            fields += RecordFieldInitializer(
                fieldName.text,
                value,
                SourceRange(fieldName.range.start, value.range.end),
            )
        } while (match(TokenKind.COMMA))
    }
    val end = consume(TokenKind.RBRACE, "Expected `}` after record fields.") ?: return null
    return RecordConstructionExpression(
        typeName = scope.name,
        qualifier = scope.qualifier,
        fields = fields,
        range = SourceRange(scope.range.start, end.range.end),
    )
}
```

This requires extending `RecordConstructionExpression` with a `qualifier: String? = null` field. Apply that in `LanguageModel.kt`:

```kotlin
data class RecordConstructionExpression(
    val typeName: String,
    val fields: List<RecordFieldInitializer>,
    override val range: SourceRange,
    val qualifier: String? = null,
) : Expression
```

- [ ] **Step 5: Update `parseType()` to accept `qualifier::name`**

Find `parseType()` near line 1837:

```kotlin
private fun parseType(): TypeSyntax? {
    val name = consume(TokenKind.IDENTIFIER, "Expected type name.") ?: return null
    val nullable = match(TokenKind.QUESTION)
    return TypeSyntax(name.text, nullable, SourceRange(name.range.start, previous().range.end))
}
```

Replace with:

```kotlin
private fun parseType(): TypeSyntax? {
    val first = consume(TokenKind.IDENTIFIER, "Expected type name.") ?: return null
    val (qualifier, nameToken) = if (match(TokenKind.COLON_COLON)) {
        first.text to (consume(TokenKind.IDENTIFIER, "Expected type name after `::`.") ?: return null)
    } else {
        null to first
    }
    val nullable = match(TokenKind.QUESTION)
    return TypeSyntax(
        name = nameToken.text,
        nullable = nullable,
        range = SourceRange(first.range.start, previous().range.end),
        qualifier = qualifier,
    )
}
```

- [ ] **Step 6: Run the first parser test**

```
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.parsesScopeCallToBuiltin"
```

Expected: still FAIL until Task 4 (resolver rewires `terminal::println`). Confirm the failure mode is now resolver-based ("Unresolved name `terminal`" or similar) rather than parser-based.

- [ ] **Step 7: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): parse :: in expressions, types, and record construction"
```

---

## Task 4: Resolver — Ambient Builtins, `::` for Scope, `.` for Fields

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`

- [ ] **Step 1: Make builtin modules ambient**

Locate `registerImports()` near line 254. Replace its body with a no-op for builtin lookup (the loop will become trivial after Task 5; for now keep it but stop registering anything from imports). Then add a new method `registerAmbientBuiltins()` called from the analyzer constructor or at the start of `analyze()`:

```kotlin
private fun registerAmbientBuiltins() {
    builtinModules.values.forEach { module ->
        val symbol = SymbolInfo(
            name = module.name,
            kind = SymbolKind.MODULE,
            range = SourceRange(SourceLocation(0, 0, 0), SourceLocation(0, 0, 0)),
            detail = "module ${module.name}",
            documentation = module.documentation,
        )
        symbols += symbol
        importedModules[module.name] = ModuleBinding(symbol, module)
    }
}
```

Call it once before walking the program. Make sure it runs before any user declarations are registered (so user names that collide with builtin module names produce a proper `Redeclaration` diagnostic — see Task 5).

- [ ] **Step 2: Resolve `ScopeAccessExpression` to a builtin**

Add a new analyzer entry point `analyzeScope(expression: ScopeAccessExpression)`:

```kotlin
private fun analyzeScope(expression: ScopeAccessExpression): Pair<Binding, TypeRef> {
    val module = importedModules[expression.qualifier]
    if (module == null) {
        diagnostics += FrontendDiagnostic(
            "Unknown namespace `${expression.qualifier}`.",
            expression.qualifierRange,
        )
        return ErrorBinding to TypeRef("Unit")
    }
    val member = module.module.functions.firstOrNull { it.name == expression.name }
        ?: module.module.types.firstOrNull { it.name == expression.name }?.let { /* type ref */ null }
    if (member == null) {
        diagnostics += FrontendDiagnostic(
            "Namespace `${expression.qualifier}` has no member `${expression.name}`.",
            expression.range,
        )
        return ErrorBinding to TypeRef("Unit")
    }
    val symbol = SymbolInfo(
        name = expression.name,
        kind = SymbolKind.BUILTIN_FUNCTION,
        range = expression.range,
        detail = "${module.module.name}::${member.name}(${member.parameterTypes.joinToString()}) : ${member.returnType}",
        documentation = member.documentation,
    )
    val binding = FunctionBinding(
        symbol,
        receiver = null,
        parameterTypes = member.parameterTypes.map(::TypeRef),
        returnType = TypeRef(member.returnType),
        moduleName = module.module.name,
    )
    references += ReferenceInfo(expression.name, expression.range, symbol, member.returnType)
    return binding to TypeRef(member.returnType)
}
```

Wire it into the expression-analysis dispatch (the big `when` near `analyzeExpression`):

```kotlin
is ScopeAccessExpression -> analyzeScope(expression)
```

- [ ] **Step 3: Restrict `MemberAccessExpression` to record fields only**

In `analyzeMember()` (line ~377), remove the branch that resolves `module.member` via `importedModules`. Replace with a diagnostic when the receiver is a `NameExpression` that names a registered builtin module:

```kotlin
private fun analyzeMember(expression: MemberAccessExpression, scope: Scope): Pair<Binding, TypeRef> {
    val receiverName = expression.receiver as? NameExpression
    if (receiverName != null && importedModules.containsKey(receiverName.name)) {
        diagnostics += FrontendDiagnostic(
            "Use `::` for module access (try `${receiverName.name}::${expression.memberName}`).",
            expression.range,
        )
        return ErrorBinding to TypeRef("Unit")
    }
    // existing field-on-record logic remains unchanged below
    // ...
}
```

- [ ] **Step 4: Update bytecode compiler dispatch**

Find every `is MemberAccessExpression ->` branch in `BytecodeCompiler` (file `LanguageFrontend.kt` around line 1262) and add a sibling `is ScopeAccessExpression -> compileScopeAccess(expression)`. The semantics for a host call are exactly the same as today's "module receiver" path — emit a `HostCall(moduleName = qualifier, functionName = name, args)`.

```kotlin
is ScopeAccessExpression -> error(
    "ScopeAccessExpression must be the callee of a CallExpression; bare scope refs are not values."
)
```

For the `CallExpression` dispatch case, add:

```kotlin
is CallExpression -> when (val callee = expression.callee) {
    is ScopeAccessExpression -> {
        compileArgs(expression.arguments)
        emit(HostCall(callee.qualifier, callee.name, expression.arguments.size))
    }
    // existing branches for MemberAccess (now field-only — error if reached)
    // and bare NameExpression (user functions)
    // ...
}
```

- [ ] **Step 5: Run all compiler tests**

```
./gradlew :compiler:test
```

Expected: many tests fail because their inline `.ck` snippets still use `import terminal;` / `terminal.println`. That migration happens in Task 7. The two parser tests added in Task 3 must now PASS. Confirm:

```
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.parsesScopeCallToBuiltin"
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.rejectsDotForBuiltinModuleAccess"
```

Both: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt
git commit -m "feat(compiler): resolve :: against ambient builtins, restrict . to record fields"
```

---

## Task 5: Reject Any `import` Declaration with a Hard Error

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`

- [ ] **Step 1: Write failing test**

Append to `LanguageFrontendTest.kt`:

```kotlin
@Test
fun rejectsImportDeclarationsHard() {
    val artifact = frontend.compile("import.ck", """
        import terminal;
        fun main() { terminal::println("ok"); }
    """.trimIndent())
    val errors = artifact.analysis.diagnostics.filter { it.severity == FrontendSeverity.ERROR }
    assertTrue(
        errors.any { it.message.contains("Built-in modules are available without `import`") },
        errors.joinToString { it.message },
    )
    assertEquals(null, artifact.module)
}

@Test
fun ambientBuiltinsWorkWithoutImport() {
    val artifact = frontend.compile("ambient.ck", """
        fun main() { terminal::println("ok"); }
    """.trimIndent())
    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    assertNotNull(artifact.module)
}
```

- [ ] **Step 2: Run to verify failure**

```
./gradlew :compiler:test --tests "*rejectsImportDeclarationsHard*"
```

Expected: FAIL — current code accepts `import terminal;`.

- [ ] **Step 3: Replace `parseImport()` body**

Find `parseImport()` near line 1621. Replace with:

```kotlin
private fun parseImport(): ImportDeclaration? {
    val keyword = previous() // the IMPORT token already consumed by parseProgram
    // Consume up to the next semicolon or top-level keyword to recover.
    while (!isAtEnd() && !check(TokenKind.SEMICOLON) &&
           !check(TokenKind.FUN) && !check(TokenKind.STRUCT) && !check(TokenKind.IMPORT)) {
        advance()
    }
    val end = if (check(TokenKind.SEMICOLON)) {
        val s = advance()
        s.range.end
    } else {
        previous().range.end
    }
    diagnostics += FrontendDiagnostic(
        "Built-in modules are available without `import`. " +
            "User-file imports are not yet supported in this version.",
        SourceRange(keyword.range.start, end),
    )
    return null
}
```

Also update `registerImports()` (now no-op aside from the ambient call) — delete its body or convert to a comment-only stub. The ambient builtins are registered by the new `registerAmbientBuiltins()` from Task 4.

- [ ] **Step 4: Run the import-rejection test**

```
./gradlew :compiler:test --tests "*rejectsImportDeclarationsHard*"
./gradlew :compiler:test --tests "*ambientBuiltinsWorkWithoutImport*"
```

Both: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): reject import declarations; built-ins are ambient"
```

---

## Task 6: Diagnose Qualified Types in Plan A

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`

- [ ] **Step 1: Write failing test**

```kotlin
@Test
fun rejectsQualifiedTypesUntilUserImportsLand() {
    val artifact = frontend.compile("qual.ck", """
        fun main() { val v: m::Foo = null; }
    """.trimIndent())
    assertTrue(
        artifact.analysis.diagnostics.any {
            it.severity == FrontendSeverity.ERROR &&
                it.message.contains("Qualified types are not yet supported")
        },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}
```

- [ ] **Step 2: In `resolveType()` or wherever `TypeSyntax` is consumed (line ~929), reject non-null qualifiers**

```kotlin
private fun resolveType(syntax: TypeSyntax): TypeRef {
    if (syntax.qualifier != null) {
        diagnostics += FrontendDiagnostic(
            "Qualified types are not yet supported. " +
                "User-file imports introducing namespaces will land in the next version.",
            syntax.range,
        )
        return TypeRef(syntax.name) // best-effort, treat as plain
    }
    // ... existing resolution
}
```

- [ ] **Step 3: Reject qualified record construction the same way**

In `analyzeRecordConstruction()` (line ~791) check `expression.qualifier`:

```kotlin
if (expression.qualifier != null) {
    diagnostics += FrontendDiagnostic(
        "Qualified record construction is not yet supported.",
        expression.range,
    )
    return ErrorBinding to TypeRef(expression.typeName)
}
```

- [ ] **Step 4: Run tests**

```
./gradlew :compiler:test --tests "*rejectsQualifiedTypesUntilUserImportsLand*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): diagnose qualified types/records pending user imports"
```

---

## Task 7: Migrate Inline Test Snippets

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt` (existing snippets, not the new tests)
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`

- [ ] **Step 1: Identify every inline `.ck` snippet**

```bash
grep -n 'import terminal\|import system\|import filesystem\|import events\|import process\|import strings\|terminal\.\|system\.\|filesystem\.\|events\.\|process\.\|strings\.\|stdout\.' \
    modules/compiler/src/test/kotlin -r
```

Make a checklist of every match.

- [ ] **Step 2: Mechanically rewrite each match**

For each match:
1. Delete `import <builtin>;` lines.
2. Replace `<builtin>.<name>` with `<builtin>::<name>`.

Be careful with field accesses like `event.name` — those stay as `.` (event is a value, not a builtin module).

- [ ] **Step 3: Run all compiler tests**

```
./gradlew :compiler:test
```

Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add modules/compiler/src/test
git commit -m "test(compiler): migrate inline .ck snippets to :: and ambient builtins"
```

---

## Task 8: Migrate ROM `.ck` Files

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/bios.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/shell.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/nano.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/ls.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/pwd.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/mkdir.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/rmdir.ck`

- [ ] **Step 1: Find every existing ROM source file**

```bash
find modules/v1_21_1 -name "*.ck" -not -path "*/build/*"
```

- [ ] **Step 2: For each file**

1. Delete every `import <ident>;` line.
2. Replace `<builtin>.<name>` with `<builtin>::<name>`. (Builtins are: `terminal`, `stdout`, `filesystem`, `system`, `events`, `process`, `strings`.)
3. Leave struct field access (`x.y` where `x` is a local var of struct type) as `.`.

- [ ] **Step 3: Find any "lang generation smoke test" or runtime that loads ROM files at compile time**

```bash
grep -rn "bios.ck\|rom/" modules --include="*.kt" | head
```

Run any such test:

```
./gradlew :compiler:test :core:test
# plus any other module where the ROM is parsed
```

Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom
git commit -m "feat(rom): migrate ROM programs to :: and implicit builtins"
```

---

## Task 9: Update IDE Completion / Hover for `::`

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`

- [ ] **Step 1: Inspect `SourceTextSupport.moduleMemberPrefix()`**

Find the helper that detects `module.` patterns (currently the trigger for showing module members in completion). It uses `.` — extend to also recognize `::`.

- [ ] **Step 2: Write failing IDE test**

Append to `LanguageIdeTest.kt`:

```kotlin
@Test
fun completesBuiltinMembersAfterDoubleColon() {
    val source = """
        fun main() { terminal:: }
    """.trimIndent()
    val ide = LanguageIde()
    val items = ide.complete("main.ck", source, line = 0, column = source.indexOf("terminal::") + "terminal::".length)
    assertTrue(items.any { it.label == "println" }, items.joinToString { it.label })
    assertTrue(items.any { it.label == "write" })
}
```

- [ ] **Step 3: Run to verify failure**

```
./gradlew :compiler:test --tests "*completesBuiltinMembersAfterDoubleColon*"
```

- [ ] **Step 4: Update `moduleMemberPrefix()` to accept `::`**

In `SourceTextSupport.kt`, modify the regex/state machine that detects `<ident>.` to also match `<ident>::`. Treat the suffix uniformly — return the qualifier and an empty member prefix when the cursor is right after `::`.

- [ ] **Step 5: Update completion dispatcher in `LanguageIde.kt`**

Make sure the path that produces module-member completions is taken when the prefix detector reports `::` as well.

- [ ] **Step 6: Run all IDE tests**

```
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageIdeTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt
git commit -m "feat(ide): trigger module completion after :: as well as ."
```

---

## Task 10: Update Language Documentation

**Files:**
- Modify: `docs/LANGUAGE.md`

- [ ] **Step 1: Edit syntax sections**

Update the "Syntax" and "Builtin Modules" sections:

- Remove the `import terminal;`, `import system;` examples from top-level declarations.
- Add a new **Operators** section explaining:
  - `::` resolves names inside a namespace (built-in modules; user-file import aliases in the next version).
  - `.` accesses fields of struct values.
- Update every example: `terminal::println(...)` instead of `terminal.println(...)`.
- Remove `import` from the list of statements/top-level forms entirely. Add a note: "User-file imports are coming in a future version."

- [ ] **Step 2: Add a small "Builtins are ambient" subsection**

```markdown
### Built-in Modules Are Ambient

Built-in modules (`terminal`, `system`, `filesystem`, `events`, `process`, `strings`, `stdout`) are always available — there is no `import` needed. Access their members with `::`:

    terminal::println("hi");
    val id = system::deviceId();
```

- [ ] **Step 3: Verify the document still renders sanely**

Open `docs/LANGUAGE.md` in a Markdown preview (or scan for stray references to `import terminal`).

- [ ] **Step 4: Commit**

```bash
git add docs/LANGUAGE.md
git commit -m "docs(language): document ::, ambient builtins, removal of import <ident>"
```

---

## Task 11: Final Verification

- [ ] **Step 1: Full repo test run**

```
./gradlew test
```

Expected: 100% pass.

- [ ] **Step 2: Sanity-grep for stale `import terminal` / `terminal.` patterns**

```bash
grep -rnE 'import (terminal|system|filesystem|events|process|strings|stdout) *;' . \
    --include='*.ck' --include='*.kt' --include='*.md'
grep -rnE '\b(terminal|system|filesystem|events|process|strings|stdout)\.[a-zA-Z]' . \
    --include='*.ck'
```

Expected: zero hits in non-build artifacts. (Hits inside `build/` are stale outputs and acceptable; clean with `./gradlew clean` if needed.)

- [ ] **Step 3: Tag the milestone (no push required)**

```bash
git tag scope-operator-and-implicit-builtins-complete
```

Plan A is complete. Plan B (user-file imports) builds on this.
