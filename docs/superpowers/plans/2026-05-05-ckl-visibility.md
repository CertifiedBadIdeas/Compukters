# CKL Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add private-by-default CKL visibility with explicit `pub` exports for top-level declarations, class members, and `main`.

**Architecture:** Add visibility metadata to the language API, parse `pub`, filter file exports at `ModuleExports`, and enforce class-member privacy at semantic access boundaries. Keep private declarations in same-file semantic analysis and bytecode compilation.

**Tech Stack:** Kotlin, CKL compiler/frontend, Gradle, Kotlin test.

---

## File structure

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt` — add `PUB` token.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/FunctionDeclaration.kt` — add top-level/member function visibility.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt` — add `Visibility` enum and visibility fields for structs, classes, constructor field parameters, class fields, and class methods.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt` — lex/parse `pub`, expose only public declarations, require `pub fun main()`, and enforce member privacy.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt` — render `pub` in canonical formatting.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt` — add keyword completion and hide private user-file declarations from auto-import.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/IdePresentationSupport.kt` — highlight `pub` as a keyword.
- Modify tests under `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/` — add RED/GREEN coverage for parser, imports, entrypoint, class privacy, formatter, and IDE.
- Modify CKL snippets in runtime/workspace tests as needed — update runnable snippets to `pub fun main()` and importable declarations to `pub`.
- Modify ROM sources under `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/` — update each runnable file to `pub fun main()`.
- Modify `docs/LANGUAGE.md` — document private-by-default visibility and `pub`.

---

### Task 1: Parse `pub` and store visibility metadata

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/FunctionDeclaration.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`

- [ ] **Step 1: Write failing parser/lexer tests**

Add these tests to `LanguageFrontendTest`:

```kotlin
@Test
fun lexesPubKeyword() {
    val tokens = Lexer("pub fun main() {}").lex()

    assertTrue(tokens.any { it.kind == TokenKind.PUB }, tokens.joinToString { "${it.kind}:${it.text}" })
}

@Test
fun parsesPubTopLevelDeclarationsAndClassMembers() {
    val parsed = DefaultParserFacade().parse(
        "visibility.ck",
        """
        pub struct Vec2 { x: Int, y: Int }
        pub class Counter(pub var value: Int) {
            pub val label: String = "counter";
            var cached: Int = 0;
            pub fun current(): Int { return this.cached; }
            pub static fun zero(): Counter { return Counter(value = 0); }
        }
        pub fun main() {}
        fun helper(): Int { return 1; }
        """.trimIndent(),
    )

    assertTrue(parsed.syntaxDiagnostics.none { it.severity == FrontendSeverity.ERROR }, parsed.syntaxDiagnostics.joinToString { it.message })
    val struct = parsed.program.declarations.filterIsInstance<StructDeclaration>().single()
    val klass = parsed.program.declarations.filterIsInstance<ClassDeclaration>().single()
    val functions = parsed.program.declarations.filterIsInstance<FunctionDeclaration>()
    assertEquals(Visibility.PUBLIC, struct.visibility)
    assertEquals(Visibility.PUBLIC, klass.visibility)
    assertEquals(Visibility.PUBLIC, functions.single { it.name == "main" }.visibility)
    assertEquals(Visibility.PRIVATE, functions.single { it.name == "helper" }.visibility)
    assertEquals(Visibility.PUBLIC, klass.constructorParameters.single { it.name == "value" }.visibility)
    assertEquals(Visibility.PUBLIC, klass.members.filterIsInstance<ClassFieldDeclaration>().single { it.name == "label" }.visibility)
    assertEquals(Visibility.PRIVATE, klass.members.filterIsInstance<ClassFieldDeclaration>().single { it.name == "cached" }.visibility)
    assertEquals(Visibility.PUBLIC, klass.members.filterIsInstance<ClassMethodDeclaration>().single { it.function.name == "current" }.visibility)
    assertEquals(Visibility.PUBLIC, klass.members.filterIsInstance<ClassMethodDeclaration>().single { it.function.name == "zero" }.visibility)
}
```

Add imports if missing:

```kotlin
import ru.lazyhat.compukterkraft.lang.api.ClassDeclaration
import ru.lazyhat.compukterkraft.lang.api.ClassFieldDeclaration
import ru.lazyhat.compukterkraft.lang.api.ClassMethodDeclaration
import ru.lazyhat.compukterkraft.lang.api.FunctionDeclaration
import ru.lazyhat.compukterkraft.lang.api.StructDeclaration
import ru.lazyhat.compukterkraft.lang.api.Visibility
```

- [ ] **Step 2: Run tests to verify RED**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest"`

Expected: FAIL because `TokenKind.PUB`, `Visibility`, and visibility properties do not exist.

- [ ] **Step 3: Add API metadata**

In `TokenKind.kt`, add `PUB` near declaration keywords:

```kotlin
PUB,
FUN,
VAL,
```

In `LanguageModel.kt`, add:

```kotlin
enum class Visibility { PUBLIC, PRIVATE }
```

Update data classes:

```kotlin
data class StructDeclaration(
    override val name: String,
    val fields: List<RecordFieldDeclaration>,
    val visibility: Visibility = Visibility.PRIVATE,
    override val range: SourceRange,
) : TopLevelDeclaration

data class ClassDeclaration(
    override val name: String,
    val constructorParameters: List<ClassConstructorParameter>,
    val members: List<ClassMemberDeclaration>,
    val visibility: Visibility = Visibility.PRIVATE,
    override val range: SourceRange,
) : TopLevelDeclaration

data class ClassConstructorParameter(
    val name: String,
    val type: TypeSyntax,
    val fieldMutability: FieldMutability?,
    val visibility: Visibility = Visibility.PRIVATE,
    val range: SourceRange,
)

data class ClassFieldDeclaration(
    val name: String,
    val type: TypeSyntax?,
    val mutable: Boolean,
    val visibility: Visibility = Visibility.PRIVATE,
    val initializer: Expression?,
    override val range: SourceRange,
) : ClassMemberDeclaration

data class ClassMethodDeclaration(
    val function: FunctionDeclaration,
    val static: Boolean,
    val visibility: Visibility = Visibility.PRIVATE,
    override val range: SourceRange,
) : ClassMemberDeclaration
```

In `FunctionDeclaration.kt`, add:

```kotlin
val visibility: Visibility = Visibility.PRIVATE,
```

before `override val range: SourceRange` and import/use `Visibility` from the same package.

- [ ] **Step 4: Add lexer/parser support**

In `Lexer.identifier`, add:

```kotlin
"pub" -> TokenKind.PUB
```

In `Parser`, add helper:

```kotlin
private fun parseVisibility(): Visibility =
    if (match(TokenKind.PUB)) Visibility.PUBLIC else Visibility.PRIVATE
```

Update `parseProgram()` so top-level branches read visibility before declaration keywords:

```kotlin
val visibility = parseVisibility()
when {
    visibility == Visibility.PRIVATE && match(TokenKind.IMPORT) -> {
        val imp = parseImport()
        if (imp != null) imports += imp else synchronize()
    }
    match(TokenKind.FUN) -> {
        val decl = parseFunction(visibility)
        if (decl != null) declarations += decl else synchronize()
    }
    match(TokenKind.STRUCT) -> {
        val decl = parseStruct(visibility)
        if (decl != null) declarations += decl else synchronize()
    }
    match(TokenKind.CLASS) -> {
        val decl = parseClass(visibility)
        if (decl != null) declarations += decl else synchronize()
    }
    visibility == Visibility.PUBLIC -> {
        diagnostics += FrontendDiagnostic("Unexpected `pub` modifier.", previous().range)
        synchronize()
    }
    check(TokenKind.EOF) -> break
    else -> {
        diagnostics += FrontendDiagnostic("Expected a top-level declaration.", peek().range)
        synchronize()
    }
}
```

Change parser signatures and constructors:

```kotlin
private fun parseFunction(visibility: Visibility = Visibility.PRIVATE): FunctionDeclaration?
return FunctionDeclaration(name.text, parameters, returnType, body, visibility, SourceRange(name.range.start, body.range.end))

private fun parseStruct(visibility: Visibility): StructDeclaration?
return StructDeclaration(name.text, fields, visibility, SourceRange(name.range.start, end.range.end))

private fun parseClass(visibility: Visibility): ClassDeclaration?
return ClassDeclaration(name.text, constructorParameters, members, visibility, SourceRange(keyword.range.start, end.range.end))
```

Update class constructor parameters to support `pub val`/`pub var`:

```kotlin
val visibility = parseVisibility()
val mutabilityToken = when { check(TokenKind.VAL) -> advance(); check(TokenKind.VAR) -> advance(); else -> null }
if (visibility == Visibility.PUBLIC && mutabilityToken == null) diagnostics += FrontendDiagnostic("Unexpected `pub` modifier.", previous().range)
```

Pass `visibility` to `ClassConstructorParameter`.

Update `parseClassMember()` to read `val visibility = parseVisibility()` before matching `INIT`, `STATIC`, `FUN`, `VAL`, `VAR`. Reject `pub init` with `Unexpected `pub` modifier.`. Pass visibility to `ClassMethodDeclaration` and `ClassFieldDeclaration`.

- [ ] **Step 5: Run tests to verify GREEN for parsing**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest"`

Expected: parser tests compile and pass, while unrelated tests may still fail later because old snippets do not use `pub fun main()` yet.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(ckl): parse pub visibility"
```

---

### Task 2: Enforce public exports and `pub fun main()`

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt`

- [ ] **Step 1: Write failing import and entrypoint tests**

In `LanguageFrontendTest`, add:

```kotlin
@Test
fun requiresPublicMainEntryPoint() {
    val artifact = frontend.compile("main.ck", "fun main() {}")

    assertTrue(
        artifact.analysis.diagnostics.any { it.severity == FrontendSeverity.ERROR && it.message.contains("pub fun main") },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    assertEquals(null, artifact.module)
}

@Test
fun acceptsPublicMainEntryPoint() {
    val artifact = frontend.compile("main.ck", "pub fun main() {}")

    assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
    assertNotNull(artifact.module)
}
```

In `UserFileImportsTest`, add:

```kotlin
@Test
fun selectiveImportCannotImportPrivateFunction() {
    val loader = MapSourceLoader(mapOf("lib.ck" to "fun helper(): Int { return 1; }", "main.ck" to "import \"lib.ck\" { helper }; pub fun main() {}"))

    val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)

    assertTrue(
        artifact.analysis.diagnostics.any { it.severity == FrontendSeverity.ERROR && it.message.contains("no public export `helper`") },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}

@Test
fun publicImportCanCallPrivateHelperInImportedFile() {
    val loader = MapSourceLoader(
        mapOf(
            "lib.ck" to "fun helper(): Int { return 1; } pub fun api(): Int { return helper(); }",
            "main.ck" to "import \"lib.ck\" { api }; pub fun main() { terminal::println(\"v=\" + api()); }",
        ),
    )

    val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)

    assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
    assertNotNull(artifact.module)
}
```

- [ ] **Step 2: Run tests to verify RED**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.UserFileImportsTest" --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest"`

Expected: FAIL because private declarations still export and `main` does not require `pub`.

- [ ] **Step 3: Filter `ModuleExports`**

Update `ModuleExports` constructor in `LanguageFrontend.kt`:

```kotlin
functions = program.declarations.filterIsInstance<FunctionDeclaration>().filter { it.visibility == Visibility.PUBLIC }.associateBy { it.name },
structs = program.declarations.filterIsInstance<StructDeclaration>().filter { it.visibility == Visibility.PUBLIC }.associateBy { it.name },
classes = program.declarations.filterIsInstance<ClassDeclaration>().filter { it.visibility == Visibility.PUBLIC }.associateBy { it.name },
```

Import `Visibility` where required.

Change missing selective import diagnostic to:

```kotlin
FrontendDiagnostic("File `${source.path}` has no public export `${item.name}`.", item.range)
```

Change alias missing member diagnostics to use `has no public member` or keep `has no member` only if tests assert the public-export selective diagnostic. Prefer `Namespace `${expression.qualifier}` has no public member `${expression.name}`.`.

- [ ] **Step 4: Add entrypoint validation**

In `DefaultCompilerFacade.compile`, after `analysis` is selected and before returning a module, reject missing/non-public main in the root file. Add a helper in `FrontendPipelines.kt`:

```kotlin
private fun entryPointDiagnostics(program: Program): List<FrontendDiagnostic> {
    val main = program.declarations.filterIsInstance<FunctionDeclaration>().firstOrNull { it.name == "main" }
    return when {
        main == null -> listOf(FrontendDiagnostic("Program must declare `pub fun main()`.", program.range ?: SourceRange(SourceLocation(0, 0, 0), SourceLocation(0, 0, 0))))
        main.visibility != Visibility.PUBLIC -> listOf(FrontendDiagnostic("Entry point `main` must be declared as `pub fun main()`.", main.range))
        else -> emptyList()
    }
}
```

Apply diagnostics to the root `AnalyzedProgram` in `analyzeProject` or in `compile` before module creation. The diagnostics must participate in the existing “any ERROR prevents module” check.

- [ ] **Step 5: Run tests to verify GREEN for exports/entrypoint**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.UserFileImportsTest" --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest"`

Expected: new visibility tests pass. Existing tests that still use old snippets should be updated in Task 5.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend
git commit -m "feat(ckl): enforce public exports"
```

---

### Task 3: Enforce class member privacy

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`

- [ ] **Step 1: Write failing class privacy tests**

Add:

```kotlin
@Test
fun rejectsExternalAccessToPrivateClassFieldAndMethod() {
    val artifact = frontend.compile(
        "private_member.ck",
        """
        pub class Counter(var value: Int) {
            fun hidden(): Int { return this.value; }
            pub fun shown(): Int { return this.hidden(); }
        }
        pub fun main() {
            val counter: Counter = Counter(value = 1);
            terminal::println("v=" + counter.value);
            terminal::println("h=" + counter.hidden());
        }
        """.trimIndent(),
    )

    assertTrue(artifact.analysis.diagnostics.any { it.message.contains("Member `value` of class `Counter` is private") }, artifact.analysis.diagnostics.joinToString { it.message })
    assertTrue(artifact.analysis.diagnostics.any { it.message.contains("Member `hidden` of class `Counter` is private") }, artifact.analysis.diagnostics.joinToString { it.message })
}

@Test
fun allowsClassOwnerToAccessPrivateMembers() {
    val artifact = frontend.compile(
        "owner_member.ck",
        """
        pub class Counter(var value: Int) {
            fun hidden(): Int { return this.value; }
            pub fun shown(): Int { return this.hidden(); }
        }
        pub fun main() {
            val counter: Counter = Counter(value = 1);
            terminal::println("v=" + counter.shown());
        }
        """.trimIndent(),
    )

    assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
}
```

- [ ] **Step 2: Run tests to verify RED**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest"`

Expected: FAIL because private fields/methods are still externally accessible or diagnostics are missing.

- [ ] **Step 3: Store member visibility in bindings**

Add `visibility: Visibility` to `ClassFieldBinding` and `ClassMethodBinding` in `LanguageFrontend.kt`.

When building class bindings in `registerTopLevel()` and `classBindingForExport()`, pass declaration visibility:

```kotlin
ClassFieldBinding(parameter.name, parameterType, mutability == FieldMutability.VAR, parameter.visibility, fieldSymbol)
ClassFieldBinding(field.name, fieldType, field.mutable, field.visibility, fieldSymbol)
ClassMethodBinding(function.name, function, parameterTypes, returnType, member.static, member.visibility, methodSymbol)
```

- [ ] **Step 4: Enforce field and method access privacy**

Add helper:

```kotlin
private fun canAccessClassMember(owner: ClassBinding): Boolean = currentClass?.declaration == owner.declaration
```

In `analyzeMember`, when a class field exists but is private and `!canAccessClassMember(classBinding)`, emit:

```kotlin
FrontendDiagnostic("Member `${expression.memberName}` of class `${classBinding.symbol.name}` is private.", expression.range)
```

Return an error binding/type after the diagnostic.

In `analyzeClassMethodCall`, when an instance or static method exists but is private and `!canAccessClassMember(classBinding)`, emit the same diagnostic and return `TypeRef("Unit")`.

- [ ] **Step 5: Run tests to verify GREEN for class privacy**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest"`

Expected: class privacy tests pass.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(ckl): enforce class member visibility"
```

---

### Task 4: Update formatter and IDE behavior

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/IdePresentationSupport.kt`

- [ ] **Step 1: Write failing formatter/IDE tests**

In `LanguageFormatterTest`, add:

```kotlin
@Test
fun formatPreservesPubVisibility() {
    val source = "pub struct Vec2{x:Int,y:Int} pub class Counter(pub var value:Int){pub fun current():Int{return this.value;}} pub fun main(){return;}"
    val expected = """
    pub struct Vec2 { x: Int, y: Int }

    pub class Counter(pub var value: Int) {
        pub fun current(): Int {
            return this.value
        }
    }

    pub fun main() {
        return
    }
    """.trimIndent() + "\n"

    val formatted = applySingleEdit(source, formatter.formatDocument("main.ck", source))

    assertEquals(expected, formatted)
}
```

In `LanguageIdeTest`, add:

```kotlin
@Test
fun autoImportSuggestsOnlyPublicUserFileDeclarations() {
    val loader = MapSourceLoader(mapOf("main.ck" to "pub fun main() { he }", "lib.ck" to "fun helper(): Int { return 1; } pub fun hello(): Int { return helper(); }"))
    val ide = LanguageIde(sourceIndex = loader)
    val source = loader.read("main.ck")!!
    val cursor = lineAndColumnOf(source, "he") + 2

    val items = ide.complete("main.ck", source, cursor.first, cursor.second)

    assertTrue(items.any { it.label == "hello" && it.sourceNamespace == "lib.ck" }, items.joinToString { it.label })
    assertTrue(items.none { it.label == "helper" && it.sourceNamespace == "lib.ck" }, items.joinToString { it.label })
}
```

- [ ] **Step 2: Run tests to verify RED**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFormatterTest" --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageIdeTest"`

Expected: FAIL because formatter omits `pub` and auto-import still sees private declarations.

- [ ] **Step 3: Update formatter**

In `LanguageFormatter.kt`, add helper:

```kotlin
private fun Visibility.prefix(): String = if (this == Visibility.PUBLIC) "pub " else ""
```

Use it in `renderStruct`, `renderClass`, `renderFunction`, constructor parameter rendering, field rendering, and class method rendering:

```kotlin
writer.write("${declaration.visibility.prefix()}struct ${declaration.name} { ")
writer.write("${declaration.visibility.prefix()}class ${declaration.name}($parameters)")
if (declaration.visibility == Visibility.PUBLIC) writer.write("pub ")
if (static) writer.write("static ")
```

For constructor parameters:

```kotlin
val visibilityPrefix = parameter.visibility.prefix()
val mutabilityPrefix = when (parameter.fieldMutability) { FieldMutability.VAL -> "val "; FieldMutability.VAR -> "var "; null -> "" }
"$visibilityPrefix$mutabilityPrefix${parameter.name}: ${renderType(parameter.type)}"
```

For class fields:

```kotlin
writer.write(member.visibility.prefix())
writer.write(if (member.mutable) "var " else "val ")
```

- [ ] **Step 4: Update IDE/highlighting**

In `LanguageIde.KEYWORDS`, add `"pub"`; in `BODY_KEYWORDS`, add `"pub"`.

In `LanguageIde.userFileImportableCompletions`, filter declarations:

```kotlin
if (declaration.visibility != Visibility.PUBLIC) return@mapNotNull null
```

Add `TokenKind.PUB` to keyword highlighting in `IdePresentationSupport`.

- [ ] **Step 5: Run tests to verify GREEN for formatter/IDE**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFormatterTest" --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageIdeTest"`

Expected: formatter and IDE tests pass.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/IdePresentationSupport.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend
git commit -m "feat(ckl): update visibility IDE support"
```

---

### Task 5: Migrate CKL snippets, ROM programs, and docs

**Files:**
- Modify: `docs/LANGUAGE.md`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/*.ck`
- Modify: compiler/runtime/workspace tests containing CKL snippets.

- [ ] **Step 1: Update runnable CKL snippets in tests**

Search:

```bash
grep -RIn 'fun main' modules/compiler/src/test modules/v1_21_1/*/src/test docs modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom
```

For runnable programs, change `fun main()` to `pub fun main()`.

For importable library declarations in test loaders, change declarations that should be consumed from another file to `pub fun`, `pub struct`, `pub class`, and public class members to `pub val`/`pub var`/`pub fun`.

- [ ] **Step 2: Update ROM programs**

For each `.ck` file under `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/`, ensure the entrypoint is:

```ck
pub fun main() {
}
```

Do not mark helper functions as `pub` unless they are intended to be imported by another ROM file.

- [ ] **Step 3: Update docs**

In `docs/LANGUAGE.md`:

- change top-level examples to `pub fun main()` for runnable programs;
- document that declarations are private by default;
- document `pub fun`, `pub struct`, `pub class`;
- document `pub val`/`pub var`/`pub fun`/`pub static fun` inside classes;
- update import rules from “each top-level declaration is public” to “only `pub` top-level declarations are exported”.

- [ ] **Step 4: Run compiler tests**

Run: `./gradlew :compiler:test`

Expected: BUILD SUCCESSFUL. If failures show old snippets, migrate those snippets with `pub` instead of weakening visibility rules.

- [ ] **Step 5: Commit**

Run:

```bash
git add docs/LANGUAGE.md modules/compiler/src/test modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom modules/v1_21_1/*/src/test
git commit -m "chore(ckl): migrate sources to explicit pub"
```

---

### Task 6: Full validation and cleanup

**Files:**
- Review all modified files.

- [ ] **Step 1: Run fast validation**

Run: `./gradlew :compiler:test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run full validation**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Inspect git diff**

Run:

```bash
git status --short
git --no-pager diff --stat HEAD~5..HEAD
```

Expected: only CKL visibility implementation, tests, docs, ROM migration, and specs/plans are changed.

- [ ] **Step 4: Commit plan if not committed by executor workflow**

Run:

```bash
git add docs/superpowers/plans/2026-05-05-ckl-visibility.md docs/superpowers/plans/2026-05-05-ckl-visibility.ru.md
git commit -m "docs: add CKL visibility implementation plan"
```

Expected: plan files are committed before implementation branch completion.
