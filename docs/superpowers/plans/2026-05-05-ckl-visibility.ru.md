# План реализации CKL Visibility

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить в CKL private-by-default visibility с явными `pub` exports для top-level declarations, class members и `main`.

**Architecture:** Добавить visibility metadata в language API, распарсить `pub`, фильтровать file exports в `ModuleExports` и enforce class-member privacy на semantic access boundaries. Private declarations остаются в same-file semantic analysis и bytecode compilation.

**Tech Stack:** Kotlin, CKL compiler/frontend, Gradle, Kotlin test.

---

## File structure

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt` — добавить `PUB` token.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/FunctionDeclaration.kt` — добавить visibility для top-level/member functions.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt` — добавить `Visibility` enum и visibility fields для structs, classes, constructor field parameters, class fields и class methods.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt` — lex/parse `pub`, export only public declarations, require `pub fun main()` и enforce member privacy.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt` — render `pub` in canonical formatting.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt` — добавить keyword completion и скрыть private user-file declarations из auto-import.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/IdePresentationSupport.kt` — highlight `pub` as keyword.
- Modify tests under `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/` — добавить RED/GREEN coverage для parser, imports, entrypoint, class privacy, formatter и IDE.
- Modify CKL snippets in runtime/workspace tests as needed — runnable snippets обновить на `pub fun main()`, importable declarations на `pub`.
- Modify ROM sources under `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/` — в каждом runnable file использовать `pub fun main()`.
- Modify `docs/LANGUAGE.md` — задокументировать private-by-default visibility и `pub`.

---

### Task 1: Parse `pub` and store visibility metadata

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/FunctionDeclaration.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`

- [ ] **Step 1: Write failing parser/lexer tests**

Добавить эти tests в `LanguageFrontendTest`:

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

Добавить imports, если их нет:

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

Expected: FAIL, потому что `TokenKind.PUB`, `Visibility` и visibility properties ещё не существуют.

- [ ] **Step 3: Add API metadata**

В `TokenKind.kt` добавить `PUB` рядом с declaration keywords:

```kotlin
PUB,
FUN,
VAL,
```

В `LanguageModel.kt` добавить:

```kotlin
enum class Visibility { PUBLIC, PRIVATE }
```

Обновить data classes:

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

В `FunctionDeclaration.kt` добавить:

```kotlin
val visibility: Visibility = Visibility.PRIVATE,
```

перед `override val range: SourceRange`; `Visibility` находится в том же package.

- [ ] **Step 4: Add lexer/parser support**

В `Lexer.identifier` добавить:

```kotlin
"pub" -> TokenKind.PUB
```

В `Parser` добавить helper:

```kotlin
private fun parseVisibility(): Visibility =
    if (match(TokenKind.PUB)) Visibility.PUBLIC else Visibility.PRIVATE
```

Обновить `parseProgram()` так, чтобы top-level branches читали visibility перед declaration keywords:

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

Изменить parser signatures и constructors:

```kotlin
private fun parseFunction(visibility: Visibility = Visibility.PRIVATE): FunctionDeclaration?
return FunctionDeclaration(name.text, parameters, returnType, body, visibility, SourceRange(name.range.start, body.range.end))

private fun parseStruct(visibility: Visibility): StructDeclaration?
return StructDeclaration(name.text, fields, visibility, SourceRange(name.range.start, end.range.end))

private fun parseClass(visibility: Visibility): ClassDeclaration?
return ClassDeclaration(name.text, constructorParameters, members, visibility, SourceRange(keyword.range.start, end.range.end))
```

Обновить class constructor parameters для `pub val`/`pub var`:

```kotlin
val visibility = parseVisibility()
val mutabilityToken = when { check(TokenKind.VAL) -> advance(); check(TokenKind.VAR) -> advance(); else -> null }
if (visibility == Visibility.PUBLIC && mutabilityToken == null) diagnostics += FrontendDiagnostic("Unexpected `pub` modifier.", previous().range)
```

Передать `visibility` в `ClassConstructorParameter`.

Обновить `parseClassMember()` так, чтобы он читал `val visibility = parseVisibility()` перед matching `INIT`, `STATIC`, `FUN`, `VAL`, `VAR`. `pub init` должен давать `Unexpected `pub` modifier.`. Передать visibility в `ClassMethodDeclaration` и `ClassFieldDeclaration`.

- [ ] **Step 5: Run tests to verify GREEN for parsing**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest"`

Expected: parser tests compile and pass; unrelated tests may still fail later because old snippets do not use `pub fun main()` yet.

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

В `LanguageFrontendTest` добавить:

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

В `UserFileImportsTest` добавить:

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

Expected: FAIL, потому что private declarations всё ещё export и `main` ещё не требует `pub`.

- [ ] **Step 3: Filter `ModuleExports`**

Обновить constructor `ModuleExports` в `LanguageFrontend.kt`:

```kotlin
functions = program.declarations.filterIsInstance<FunctionDeclaration>().filter { it.visibility == Visibility.PUBLIC }.associateBy { it.name },
structs = program.declarations.filterIsInstance<StructDeclaration>().filter { it.visibility == Visibility.PUBLIC }.associateBy { it.name },
classes = program.declarations.filterIsInstance<ClassDeclaration>().filter { it.visibility == Visibility.PUBLIC }.associateBy { it.name },
```

Добавить import/use `Visibility`, где требуется.

Изменить missing selective import diagnostic на:

```kotlin
FrontendDiagnostic("File `${source.path}` has no public export `${item.name}`.", item.range)
```

Alias missing member diagnostics лучше поменять на `Namespace `${expression.qualifier}` has no public member `${expression.name}`.`. Если это затрагивает слишком много tests, можно оставить старый текст для alias и тестировать selective diagnostic.

- [ ] **Step 4: Add entrypoint validation**

В `DefaultCompilerFacade.compile`, после выбора `analysis` и перед module creation, reject missing/non-public main in root file. Добавить helper в `FrontendPipelines.kt`:

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

Применить diagnostics к root `AnalyzedProgram` в `analyzeProject` или `compile` до module creation. Diagnostics должны участвовать в существующей проверке “any ERROR prevents module”.

- [ ] **Step 5: Run tests to verify GREEN for exports/entrypoint**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.UserFileImportsTest" --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest"`

Expected: новые visibility tests проходят. Старые tests со snippets обновляются в Task 5.

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

Добавить:

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

Expected: FAIL, потому что private fields/methods всё ещё externally accessible или diagnostics отсутствуют.

- [ ] **Step 3: Store member visibility in bindings**

Добавить `visibility: Visibility` в `ClassFieldBinding` и `ClassMethodBinding` в `LanguageFrontend.kt`.

При построении class bindings в `registerTopLevel()` и `classBindingForExport()` передавать declaration visibility:

```kotlin
ClassFieldBinding(parameter.name, parameterType, mutability == FieldMutability.VAR, parameter.visibility, fieldSymbol)
ClassFieldBinding(field.name, fieldType, field.mutable, field.visibility, fieldSymbol)
ClassMethodBinding(function.name, function, parameterTypes, returnType, member.static, member.visibility, methodSymbol)
```

- [ ] **Step 4: Enforce field and method access privacy**

Добавить helper:

```kotlin
private fun canAccessClassMember(owner: ClassBinding): Boolean = currentClass?.declaration == owner.declaration
```

В `analyzeMember`, если class field существует, но private и `!canAccessClassMember(classBinding)`, emit:

```kotlin
FrontendDiagnostic("Member `${expression.memberName}` of class `${classBinding.symbol.name}` is private.", expression.range)
```

После diagnostic вернуть error binding/type.

В `analyzeClassMethodCall`, если instance/static method существует, но private и `!canAccessClassMember(classBinding)`, emit same diagnostic and return `TypeRef("Unit")`.

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

В `LanguageFormatterTest` добавить:

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

В `LanguageIdeTest` добавить:

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

Expected: FAIL, потому что formatter omits `pub`, а auto-import still sees private declarations.

- [ ] **Step 3: Update formatter**

В `LanguageFormatter.kt` добавить helper:

```kotlin
private fun Visibility.prefix(): String = if (this == Visibility.PUBLIC) "pub " else ""
```

Использовать его в `renderStruct`, `renderClass`, `renderFunction`, constructor parameter rendering, field rendering и class method rendering:

```kotlin
writer.write("${declaration.visibility.prefix()}struct ${declaration.name} { ")
writer.write("${declaration.visibility.prefix()}class ${declaration.name}($parameters)")
if (declaration.visibility == Visibility.PUBLIC) writer.write("pub ")
if (static) writer.write("static ")
```

Для constructor parameters:

```kotlin
val visibilityPrefix = parameter.visibility.prefix()
val mutabilityPrefix = when (parameter.fieldMutability) { FieldMutability.VAL -> "val "; FieldMutability.VAR -> "var "; null -> "" }
"$visibilityPrefix$mutabilityPrefix${parameter.name}: ${renderType(parameter.type)}"
```

Для class fields:

```kotlin
writer.write(member.visibility.prefix())
writer.write(if (member.mutable) "var " else "val ")
```

- [ ] **Step 4: Update IDE/highlighting**

В `LanguageIde.KEYWORDS` добавить `"pub"`; в `BODY_KEYWORDS` добавить `"pub"`.

В `LanguageIde.userFileImportableCompletions`, filter declarations:

```kotlin
if (declaration.visibility != Visibility.PUBLIC) return@mapNotNull null
```

Добавить `TokenKind.PUB` в keyword highlighting в `IdePresentationSupport`.

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

Для runnable programs заменить `fun main()` на `pub fun main()`.

Для importable library declarations in test loaders заменить declarations, которые должны потребляться из другого файла, на `pub fun`, `pub struct`, `pub class`, а public class members на `pub val`/`pub var`/`pub fun`.

- [ ] **Step 2: Update ROM programs**

Для каждого `.ck` file under `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/` убедиться, что entrypoint такой:

```ck
pub fun main() {
}
```

Не помечать helper functions как `pub`, если они не предназначены для import другим ROM file.

- [ ] **Step 3: Update docs**

В `docs/LANGUAGE.md`:

- изменить top-level examples на `pub fun main()` for runnable programs;
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
