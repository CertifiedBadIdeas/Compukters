# План реализации CKL Class Language Support

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить нативные CKL classes с Kotlin-like primary constructors, `init`, instance/static methods, reference-object semantics и единым call-style construction для classes и structs.

**Architecture:** Frontend получает class AST, named call arguments, class/type bindings, `this`, member assignment и resolution для methods/static calls. Bytecode/runtime получают нативные object references и deterministic VM heap; `struct` остаётся value record, но создаётся через `Vec2(x = 1)`. IDE completions/imports обновляются после стабилизации compiler/runtime model.

**Tech Stack:** Kotlin, Gradle, CKL compiler front-end, stack bytecode VM, существующие suites `LanguageFrontend`, `LanguageRuntime`, `LanguageIde`.

---

## File structure

**Core language model**
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`: добавить `CLASS`, `STATIC`, `INIT`, `THIS`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`: class AST, call arguments, `ThisExpression`, member assignment, class bytecode metadata, object instructions.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TopLevelDeclaration.kt`: `ClassDeclaration` остаётся `TopLevelDeclaration`.

**Frontend/compiler**
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`: lexer keyword mapping, parser, semantic analyzer, bytecode compiler.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SymbolKind.kt`: добавить `CLASS`, `METHOD`; `FIELD` можно переиспользовать для class fields.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`: class, constructor, instance member, static member и import completions.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoader.kt`: добавить classes в indexed exports.

**Runtime**
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`: `VmValue.ObjectRef`, deterministic heap, object allocation, field get/set, method/static calls.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmValueSupport.kt`: deterministic render для object refs.

**Docs/tests**
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`: parser/semantic tests.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`: VM object identity/init/mutation tests.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`: completions/imports.
- Modify `docs/LANGUAGE.md`: class syntax и struct construction syntax.

---

## Task 1: Tokens, AST и RED parser tests

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`

- [ ] **Step 1: Написать failing lexer/parser tests**

Добавить рядом с текущими lexer/parser tests:

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

- [ ] **Step 2: Запустить tests и убедиться в RED**

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*lexesClassKeywords" --tests "*LanguageFrontendTest*parsesBasicClassDeclaration"
```

Expected: FAIL, потому что keywords/AST/parser для classes отсутствуют.

- [ ] **Step 3: Добавить token kinds и lexer keyword mapping**

В `TokenKind.kt` после `STRUCT`:

```kotlin
CLASS,
STATIC,
INIT,
THIS,
```

В keyword mapping lexer:

```kotlin
"class" -> TokenKind.CLASS
"static" -> TokenKind.STATIC
"init" -> TokenKind.INIT
"this" -> TokenKind.THIS
```

- [ ] **Step 4: Добавить AST nodes**

В `LanguageModel.kt` рядом со `StructDeclaration`:

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

sealed interface ClassMemberDeclaration { val range: SourceRange }

data class ClassFieldDeclaration(
    val name: String,
    val type: TypeSyntax?,
    val mutable: Boolean,
    val initializer: Expression,
    override val range: SourceRange,
) : ClassMemberDeclaration

data class ClassInitBlock(val body: BlockStatement, override val range: SourceRange) : ClassMemberDeclaration

data class ClassMethodDeclaration(
    val function: FunctionDeclaration,
    val static: Boolean,
    override val range: SourceRange,
) : ClassMemberDeclaration
```

Добавить call arguments и `this`:

```kotlin
sealed interface CallArgument { val expression: Expression; val range: SourceRange }
data class PositionalCallArgument(override val expression: Expression, override val range: SourceRange) : CallArgument
data class NamedCallArgument(val name: String, val nameRange: SourceRange, override val expression: Expression, override val range: SourceRange) : CallArgument
data class ThisExpression(override val range: SourceRange) : Expression
```

Изменить `CallExpression.arguments` на `List<CallArgument>`.

- [ ] **Step 5: Compile-fix существующие positional call sites**

Механически заменить использование call argument expressions на `argument.expression`. Например:

```kotlin
expression.arguments.map { analyzeExpression(it.expression, scope) }
```

Argument count остаётся `expression.arguments.size`.

- [ ] **Step 6: Реализовать минимальный parser support**

Добавить branch `TokenKind.CLASS` в `parseProgram()`. Реализовать `parseClass()` с primary constructor в круглых скобках, затем class body. `parseClassMember()` должен поддержать `init`, `fun`, `static fun`, `val`, `var`.

Named call arguments парсить так:

```kotlin
private fun parseCallArgument(): CallArgument? {
    if (check(TokenKind.IDENTIFIER) && checkNext(TokenKind.EQUAL)) {
        val name = advance()
        advance()
        val value = parseExpression() ?: return null
        return NamedCallArgument(name.text, name.range, value, SourceRange(name.range.start, value.range.end))
    }
    val value = parseExpression() ?: return null
    return PositionalCallArgument(value, value.range)
}
```

- [ ] **Step 7: Tests и commit**

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*lexesClassKeywords" --tests "*LanguageFrontendTest*parsesBasicClassDeclaration"
./gradlew :compiler:test
```

Expected: PASS.

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): parse class declarations"
```

---

## Task 2: Unified call-style construction и rejection old struct literals

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`

- [ ] **Step 1: Написать failing tests для new struct construction и old literal rejection**

Добавить frontend/runtime tests с источниками:

```ck
struct Point { x: Int, y: Int }
fun main() {
    val point: Point = Point(x = 1, y = 2);
    terminal::println("x=" + point.x);
}
```

и invalid case:

```ck
struct Point { x: Int, y: Int }
fun main() { val point: Point = Point { x: 1, y: 2 }; }
```

Assertions:

```kotlin
assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
assertNotNull(artifact.module)
assertTrue(artifact.analysis.diagnostics.any { it.message.contains("Old record construction syntax") }, artifact.analysis.diagnostics.joinToString { it.message })
```

- [ ] **Step 2: Запустить tests и убедиться в RED**

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*compilesStructCallStyleConstruction" --tests "*LanguageFrontendTest*rejectsOldRecordConstructionSyntax" --tests "*LanguageRuntimeTest*constructsStructsWithNamedCallSyntax"
```

Expected: FAIL.

- [ ] **Step 3: Добавить legacy record literal diagnostic path**

Добавить `LegacyRecordConstructionExpression` и использовать его для старого brace syntax. Analyzer должен emit:

```kotlin
FrontendDiagnostic(
    "Old record construction syntax is no longer valid. Use `${expression.typeName}(x = value)` instead.",
    expression.range,
)
```

- [ ] **Step 4: Treat named call to record type as construction**

В call analysis до function resolution определить `CallExpression(NameExpression(typeName), namedArguments)`, где `typeName` — record. Проверить: только named arguments, все fields ровно один раз, unknown/duplicates diagnostics, assignability. Bytecode переиспользует `Instruction.ConstructRecord(typeName, fieldNames)` и компилирует expressions в field order.

- [ ] **Step 5: Reject named arguments for normal functions**

Для обычных user/builtin functions с `NamedCallArgument` emit:

```kotlin
FrontendDiagnostic("Named arguments are only supported for constructors.", argument.range)
```

- [ ] **Step 6: Tests и commit**

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*compilesStructCallStyleConstruction" --tests "*LanguageFrontendTest*rejectsOldRecordConstructionSyntax" --tests "*LanguageRuntimeTest*constructsStructsWithNamedCallSyntax"
./gradlew :compiler:test
```

Expected: PASS.

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
git commit -m "feat(compiler): use call-style struct construction"
```

---

## Task 3: Class semantic registration и constructor checking

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SymbolKind.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`

- [ ] **Step 1: Написать failing semantic tests**

Добавить tests для успешного:

```ck
class Counter(var value: Int) {}
fun main() {
    val counter: Counter = Counter(value = 3);
    terminal::println("value=" + counter.value);
}
```

и errors:

```ck
class Counter(var value: Int) {}
fun main() { val counter: Counter = Counter(missing = 3); }
```

Проверить diagnostics "Unknown constructor parameter `missing`" и "Missing constructor argument `value`".

- [ ] **Step 2: Run RED**

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*compilesClassConstructorCall" --tests "*LanguageFrontendTest*reportsClassConstructorArgumentErrors"
```

Expected: FAIL.

- [ ] **Step 3: Добавить class bindings**

В `LanguageFrontend.kt` добавить `ClassFieldBinding`, `ClassMethodBinding`, `ClassBinding`, `userClassesByName`. Включить class names во все redeclaration checks рядом с functions, records, imports и modules. В `SymbolKind.kt` добавить `CLASS`, `METHOD`.

- [ ] **Step 4: Register classes as types**

В `registerTopLevel()` обработать classes после structs и до functions: `typeNames[className] = TypeRef(className)`, symbol detail `class Counter`, constructor field parameter symbols, body field symbols, method signatures.

- [ ] **Step 5: Analyze constructor call и class field reads**

`Counter(value = 3)` возвращает `TypeRef("Counter")`; validate duplicates, unknown, missing, type mismatch. Member access на receiver class type resolves fields from `ClassBinding.fields`.

- [ ] **Step 6: Tests и commit**

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*compilesClassConstructorCall" --tests "*LanguageFrontendTest*reportsClassConstructorArgumentErrors"
./gradlew :compiler:test
```

Expected: PASS.

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SymbolKind.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): resolve class constructors and fields"
```

---

## Task 4: `this`, `init` и member assignment analysis

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`

- [ ] **Step 1: Написать failing tests**

Покрыть:
- `init { this.value = value + 1; }`;
- `fun current(): Int { return this.value; }`;
- assignment to `val` field error;
- `this` inside `static fun` error.

Use assertions:

```kotlin
assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
assertTrue(artifact.analysis.diagnostics.any { it.message.contains("Cannot assign to val field `value`") }, artifact.analysis.diagnostics.joinToString { it.message })
assertTrue(artifact.analysis.diagnostics.any { it.message.contains("Static method cannot access `this`") }, artifact.analysis.diagnostics.joinToString { it.message })
```

- [ ] **Step 2: Run RED**

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*analyzesThisAndInitAssignments" --tests "*LanguageFrontendTest*rejectsAssignmentToValField" --tests "*LanguageFrontendTest*rejectsThisInStaticMethod"
```

Expected: FAIL.

- [ ] **Step 3: Добавить member assignment AST**

```kotlin
data class MemberAssignmentStatement(
    val receiver: Expression,
    val memberName: String,
    val memberRange: SourceRange,
    val expression: Expression,
    override val range: SourceRange,
) : Statement
```

Parser должен превращать `this.value = expr;` и `object.value = expr;` в `MemberAssignmentStatement`, а local assignment оставить `AssignmentStatement`.

- [ ] **Step 4: Добавить class-aware analyzer context**

```kotlin
private var currentClass: ClassBinding? = null
private var currentStaticMethod: Boolean = false
private var inConstruction: Boolean = false
```

Set context for `ClassInitBlock`, instance methods и static methods.

- [ ] **Step 5: Analyze `this` и member assignments**

`ThisExpression`: no current class -> error; static -> "Static method cannot access `this`."; otherwise `TypeRef(currentClass.symbol.name)`. `MemberAssignmentStatement`: resolve class field, reject unknown, reject `val` outside construction, check assignability.

- [ ] **Step 6: Tests и commit**

```bash
./gradlew :compiler:test --tests "*LanguageFrontendTest*analyzesThisAndInitAssignments" --tests "*LanguageFrontendTest*rejectsAssignmentToValField" --tests "*LanguageFrontendTest*rejectsThisInStaticMethod"
./gradlew :compiler:test
```

Expected: PASS.

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): analyze class this and field assignment"
```

---

## Task 5: Bytecode metadata и runtime object heap

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmValueSupport.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`

- [ ] **Step 1: Написать failing runtime test для reference identity**

Source:

```ck
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
```

Assertion: runtime lines equal `listOf("a=2")`.

- [ ] **Step 2: Run RED**

```bash
./gradlew :compiler:test --tests "*LanguageRuntimeTest*classInstancesHaveReferenceIdentityAndSharedMutation"
```

Expected: FAIL.

- [ ] **Step 3: Добавить bytecode class metadata/instructions**

Добавить `BytecodeClass`, `BytecodeClassField`; расширить `BytecodeModule` classes list. Добавить instructions `ConstructClass`, `SetField`, `CallMethod`, `CallStaticMethod`.

- [ ] **Step 4: Добавить runtime object heap**

Добавить `VmValue.ObjectRef(id)`, `VmObject(className, fields)`, `heap`, `nextObjectId`. `ConstructClass` allocates object, `GetField` читает from object/record, `SetField` mutates object field. `VmValueSupport.render()` должен deterministic render object refs.

- [ ] **Step 5: Compile constructors и field assignments**

Compile class constructor call to argument expressions in constructor order + `ConstructClass`. Compile `MemberAssignmentStatement` to receiver, value, `SetField` and pop according to statement convention.

- [ ] **Step 6: Tests и commit**

```bash
./gradlew :compiler:test --tests "*LanguageRuntimeTest*classInstancesHaveReferenceIdentityAndSharedMutation"
./gradlew :compiler:test
```

Expected: PASS.

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmValueSupport.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
git commit -m "feat(runtime): execute class objects with heap references"
```

---

## Task 6: Instance methods, static methods и init execution

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`

- [ ] **Step 1: Написать failing tests для init/static**

Source:

```ck
class Counter(var value: Int) {
    init { this.value = this.value + 1; }
    fun current(): Int { return this.value; }
    static fun zero(): Counter { return Counter(value = 0); }
}
fun main() {
    val counter: Counter = Counter.zero();
    terminal::println("value=" + counter.current());
}
```

Assertion: runtime lines equal `listOf("value=1")`.

- [ ] **Step 2: Run RED**

```bash
./gradlew :compiler:test --tests "*LanguageRuntimeTest*classInitAndStaticMethodsRun"
```

Expected: FAIL.

- [ ] **Step 3: Analyze method calls**

`CallExpression(MemberAccessExpression(receiver, methodName), args)` resolves instance method when receiver type is class. `CallExpression(MemberAccessExpression(NameExpression(className), methodName), args)` resolves static method. Validate existence/count/types, reject named args for methods in this version.

- [ ] **Step 4: Compile methods as hidden bytecode functions**

Use deterministic names `ClassName.methodName`, `ClassName.static.methodName`, `ClassName.<init>`. Instance methods get implicit local `this`; init blocks compile into synthetic init function; constructor allocation calls init before returning object ref.

- [ ] **Step 5: Execute method/static instructions**

Prefer compile-time direct function indices fitting existing `CallFunction` frame model. If using `CallMethod`, push receiver as argument 0; if using `CallStaticMethod`, call static function index.

- [ ] **Step 6: Tests и commit**

```bash
./gradlew :compiler:test --tests "*LanguageRuntimeTest*classInitAndStaticMethodsRun" --tests "*LanguageRuntimeTest*classInstancesHaveReferenceIdentityAndSharedMutation"
./gradlew :compiler:test
```

Expected: PASS.

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): compile class methods and init blocks"
```

---

## Task 7: Imports, source index и IDE completions для classes

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoader.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`

- [ ] **Step 1: Написать failing import/IDE tests**

Import source:

```ck
import "model.ck" { Counter };
fun main() {
    val counter: Counter = Counter(value = 2);
    terminal::println("value=" + counter.value);
}
```

`model.ck`:

```ck
class Counter(var value: Int) {}
```

IDE test: typing `Cou` suggests `Counter` with `sourceNamespace == "model.ck"` and import edit `import "model.ck" { Counter };\n`. `this.` inside class suggests `value`.

- [ ] **Step 2: Run RED**

```bash
./gradlew :compiler:test --tests "*UserFileImportsTest*selectiveImportCanImportClass" --tests "*LanguageIdeTest*suggestsUserFileClassWithPathAndImportEdit" --tests "*LanguageIdeTest*completesMembersAfterThisDot"
```

Expected: FAIL.

- [ ] **Step 3: Export classes from imported files**

Extend `ModuleExports` with `classes: Map<String, ClassDeclaration>`. Selective import registration imports class names into `userClassesByName` with redeclaration checks.

- [ ] **Step 4: Index classes for auto-import**

Source index parse extraction includes `ClassDeclaration`. Completion item for class should insert `Counter(`, set `CompletionItemKind.CLASS` if added, set `sourceNamespace`, and add import edit.

- [ ] **Step 5: Add member completions**

After `receiver.` suggest class fields and instance methods. After `ClassName.` suggest static methods. Inside instance methods and `init`, `this.` suggests current class members.

- [ ] **Step 6: Tests и commit**

```bash
./gradlew :compiler:test --tests "*UserFileImportsTest*selectiveImportCanImportClass" --tests "*LanguageIdeTest*suggestsUserFileClassWithPathAndImportEdit" --tests "*LanguageIdeTest*completesMembersAfterThisDot"
./gradlew :compiler:test
```

Expected: PASS.

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

## Task 8: Documentation и migration cleanup

**Files:**
- Modify: `docs/LANGUAGE.md`
- Modify tests/docs containing old record construction syntax.

- [ ] **Step 1: Update language docs**

Document class syntax, `Counter(value = 1)`, `Counter.zero()`, reference semantics, `Vec2(x = 1, y = 2)`, and invalid old `Vec2 { x: 1 }`.

- [ ] **Step 2: Find stale old record literals**

```bash
grep -rnE '[A-Z][A-Za-z0-9_]* \{ *[a-zA-Z_][a-zA-Z0-9_]*:' . --include='*.ck' --include='*.kt' --include='*.md' --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.git || true
```

Expected: only intentional rejection tests or historical spec/plan docs remain. Live docs/tests use call-style construction.

- [ ] **Step 3: Verification**

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

- [ ] **Step 1: Compiler tests**

```bash
./gradlew :compiler:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Full tests**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Stale old record syntax check**

```bash
(grep -rnE '[A-Z][A-Za-z0-9_]* \{ *[a-zA-Z_][a-zA-Z0-9_]*:' . --include='*.ck' --include='*.kt' --include='*.md' --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.git || true) | grep -v '^./docs/superpowers/' || true
```

Expected: only intentional rejection test references.

- [ ] **Step 4: Clean status and tag**

```bash
git status --short
git tag -f class-language-support-complete
```

Expected: clean status before tagging.
