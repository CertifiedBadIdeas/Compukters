# User-File Imports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `import "path.ck";` and `import "path.ck" as alias;` declarations that load other CKL source files, with import-once semantics (no cycles), no transitive symbol exposure, hard `Redeclaration` diagnostics on conflicts, and bytecode-level name mangling so unrelated files can use the same top-level identifier.

**Architecture:** Introduce a `SourceLoader` abstraction that resolves and reads `.ck` files relative to the current file. `LanguageFrontend.compile()` gains an overload accepting a loader; the analyzer becomes multi-file by performing a BFS over imports, parsing each file once (keyed by canonical path), then linking. Top-level functions and structs are mangled in the bytecode as `<canonicalPath>#<name>` so flat imports from different files coexist; the resolver maintains per-file lookup tables that map a visible name (or `alias::name`) to a mangled identity. Built-in modules (added in Plan A) remain ambient and untouched.

**Tech Stack:** Kotlin, kotlin-test (JUnit5), Gradle multi-module build (`:compiler`, `:core`, `:v1_21_1-*`).

**Prerequisite:** Plan A (`docs/superpowers/plans/2026-05-01/2026-05-01-scope-operator-and-implicit-builtins.md`) must be merged. This plan assumes `::` is the scope operator, `.` is field access, builtins are ambient, and `import` declarations currently emit a hard error.

---

## Semantics Summary

```ckl
// math.ck
struct Vec2 { x: Int, y: Int }
fun add(a: Vec2, b: Vec2): Vec2 { return Vec2 { x: a.x + b.x, y: a.y + b.y }; }

// io.ck
fun greet(): Unit { terminal::println("hi"); }

// main.ck
import "math.ck" as m;
import "io.ck";

fun main() {
    val v: m::Vec2 = m::Vec2 { x: 1, y: 2 };
    val w: m::Vec2 = m::add(v, m::Vec2 { x: 3, y: 4 });
    terminal::println("x=" + w.x);
    greet();
}
```

Rules:
- `.ck` extension is required in the path string.
- Paths are resolved **relative to the importing file**.
- `import "p.ck";` (flat): all top-level functions and structs of `p.ck` become visible by their bare names in the importing file.
- `import "p.ck" as m;` (aliased): symbols are visible **only** as `m::name`. The alias `m` lives in the same namespace as built-in modules and other aliases.
- **No transitivity:** if `a.ck` imports `b.ck` and `main.ck` imports `a.ck`, `main.ck` does NOT see `b.ck`'s symbols (unless it imports `b.ck` itself).
- **Import-once:** the same canonicalised path is parsed/analyzed exactly once per compilation. Cycles are therefore impossible — a re-entry is a no-op for that file's import effect, but each file's import statements are still applied to its own scope.
- **All top-level declarations are public.** No `pub`/`export` modifier yet.
- **Conflicts** (collision between any of: built-in module name, alias, flat-imported symbol, local declaration) → `Redeclaration` diagnostic referencing both source ranges.
- **Duplicate `import` of the same canonical path in one file** → diagnostic `Duplicate import`.
- IDE multi-file features (cross-file goto-definition, completion of `m::` from analysis of `m`'s file) are **explicitly out of scope** of this plan and are deferred to a follow-up.

---

## File Map

| File | Action | Responsibility |
| --- | --- | --- |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt` | Modify | Add `AS` keyword token |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/ImportDeclaration.kt` | Modify | Replace `moduleName: String` with `path: String, alias: String?, pathRange: SourceRange, aliasRange: SourceRange?` |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoader.kt` | Create | Define `SourceLoader` interface and `MapSourceLoader` test impl |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt` | Modify | Lexer recognizes `as`; parser accepts new import form; analyzer becomes multi-file; bytecode emits mangled names |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/CompilationArtifact.kt` | Modify | Add `analyses: Map<String, AnalyzedProgram>` keyed by canonical path |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt` | Create | End-to-end tests using `MapSourceLoader` |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/UserFileImportsRuntimeTest.kt` | Create | Run-time tests proving multi-file programs execute correctly |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspaceSourceLoader.kt` | Create | Production `SourceLoader` backed by `DeviceWorkspace` |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspaceSourceLoaderTest.kt` | Create | Test the workspace-backed loader |
| `docs/LANGUAGE.md` | Modify | Document the new import syntax and namespace semantics |

---

## Task 1: Define `SourceLoader` Abstraction

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoader.kt`

- [ ] **Step 1: Write the abstraction**

Create the file with:

```kotlin
package ru.lazyhat.compukterkraft.lang.frontend

/**
 * Loads CKL source files for multi-file compilation.
 *
 * - [resolve] takes the canonical path of the file containing the `import`
 *   and the literal path written in the import (e.g. `"lib/math.ck"`).
 *   It returns a canonicalised path used as the dedup key, or `null` if
 *   the path cannot be resolved.
 * - [read] returns the source text of a previously-resolved canonical path,
 *   or `null` if the file no longer exists.
 *
 * Implementations MUST be deterministic: the same `(from, importPath)` pair
 * must always resolve to the same canonical path, and the same canonical
 * path must always read back the same text within a single compilation.
 */
interface SourceLoader {
    fun resolve(from: String, importPath: String): String?
    fun read(canonical: String): String?
}

/**
 * Test/utility implementation backed by an in-memory map.
 * Paths are resolved using forward-slash normalisation; "../" segments
 * are collapsed. The root file's [from] is conventionally its canonical path.
 */
class MapSourceLoader(private val files: Map<String, String>) : SourceLoader {

    override fun resolve(from: String, importPath: String): String? {
        val baseDir = from.substringBeforeLast('/', missingDelimiterValue = "")
        val combined = if (baseDir.isEmpty()) importPath else "$baseDir/$importPath"
        val normalised = normalise(combined)
        return if (files.containsKey(normalised)) normalised else null
    }

    override fun read(canonical: String): String? = files[canonical]

    private fun normalise(path: String): String {
        val parts = path.split('/').toMutableList()
        var i = 0
        while (i < parts.size) {
            when (parts[i]) {
                "", "." -> { parts.removeAt(i) }
                ".." -> {
                    if (i > 0) {
                        parts.removeAt(i)
                        parts.removeAt(i - 1)
                        i -= 1
                    } else {
                        parts.removeAt(i)
                    }
                }
                else -> i += 1
            }
        }
        return parts.joinToString("/")
    }
}

/** Loader used when the caller passes no loader: every user-file import becomes an error. */
object NoOpSourceLoader : SourceLoader {
    override fun resolve(from: String, importPath: String): String? = null
    override fun read(canonical: String): String? = null
}
```

- [ ] **Step 2: Write the failing loader test**

Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoaderTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapSourceLoaderTest {
    private val files = mapOf(
        "main.ck" to "fun main() {}",
        "lib/math.ck" to "fun add() {}",
        "lib/io/print.ck" to "fun p() {}",
    )
    private val loader = MapSourceLoader(files)

    @Test
    fun resolvesSiblingFile() {
        assertEquals("lib/math.ck", loader.resolve("main.ck", "lib/math.ck"))
    }

    @Test
    fun resolvesRelativeWithDotDot() {
        assertEquals("lib/math.ck", loader.resolve("lib/io/print.ck", "../math.ck"))
    }

    @Test
    fun resolvesCurrentDirectory() {
        assertEquals("lib/math.ck", loader.resolve("lib/io/print.ck", "../math.ck"))
    }

    @Test
    fun returnsNullForMissing() {
        assertNull(loader.resolve("main.ck", "nope.ck"))
    }

    @Test
    fun readsKnownFiles() {
        assertEquals("fun main() {}", loader.read("main.ck"))
        assertNull(loader.read("nope.ck"))
    }
}
```

- [ ] **Step 3: Run the test**

```
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.MapSourceLoaderTest"
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoader.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoaderTest.kt
git commit -m "feat(compiler): introduce SourceLoader abstraction with in-memory test impl"
```

---

## Task 2: Lex `as` as a Keyword and Update `ImportDeclaration`

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt` (lexer keyword table)
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/ImportDeclaration.kt`

- [ ] **Step 1: Add `AS` enum member**

In `TokenKind.kt`, add `AS` next to `IMPORT`:

```kotlin
FUN, VAL, VAR, IF, ELSE, WHILE, WHEN, RETURN, IMPORT, AS, STRUCT,
```

- [ ] **Step 2: Recognize `as` in `lexIdentifier()`**

Find the keyword switch in the lexer (the section listing `"import" -> TokenKind.IMPORT`). Add:

```kotlin
"as" -> TokenKind.AS
```

- [ ] **Step 3: Replace `ImportDeclaration` shape**

Replace the contents of `ImportDeclaration.kt` with:

```kotlin
package ru.lazyhat.compukterkraft.lang.api

data class ImportDeclaration(
    val path: String,                 // literal string from `import "PATH"`, including .ck extension
    val pathRange: SourceRange,       // location of the string literal
    val alias: String?,               // null = flat import; non-null = aliased
    val aliasRange: SourceRange?,     // location of the alias identifier or null
    val range: SourceRange,           // entire declaration range
)
```

- [ ] **Step 4: Compile and confirm callers break in expected places**

```
./gradlew :compiler:compileKotlin
```

Expected: failures in `LanguageFrontend.kt` referring to `declaration.moduleName`. We will rewrite those in Task 3.

- [ ] **Step 5: Commit (compile failure is intentional intermediate state — keep WIP)**

Don't commit a broken state. Skip commit and proceed directly to Task 3, which restores compilation.

---

## Task 3: Parse the New `import` Syntax

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`

- [ ] **Step 1: Replace `parseImport()`**

The Plan-A version emits a hard error. Replace with the new grammar:

```
import_decl = "import" STRING ("as" IDENTIFIER)? ";"
```

```kotlin
private fun parseImport(): ImportDeclaration? {
    val keyword = previous() // IMPORT already consumed
    val pathToken = consume(
        TokenKind.STRING,
        "Expected file path string after `import` (e.g. `import \"lib/math.ck\";`).",
    ) ?: return null
    val path = pathToken.text  // already without surrounding quotes — verify how STRING is stored
    if (!path.endsWith(".ck")) {
        diagnostics += FrontendDiagnostic(
            "Import path must end with `.ck` (got `$path`).",
            pathToken.range,
        )
        // continue parsing to recover
    }
    var aliasName: String? = null
    var aliasRange: SourceRange? = null
    if (match(TokenKind.AS)) {
        val aliasToken = consume(
            TokenKind.IDENTIFIER,
            "Expected alias name after `as`.",
        ) ?: return null
        aliasName = aliasToken.text
        aliasRange = aliasToken.range
    }
    val end = consumeOptional(TokenKind.SEMICOLON) ?: previous()
    return ImportDeclaration(
        path = path,
        pathRange = pathToken.range,
        alias = aliasName,
        aliasRange = aliasRange,
        range = SourceRange(keyword.range.start, end.range.end),
    )
}
```

If your `STRING` token retains the surrounding quotes in `text`, strip them: `val path = pathToken.text.removeSurrounding("\"")`. Verify by reading the existing `STRING` lexing branch.

- [ ] **Step 2: Replace `registerImports()`**

This becomes the multi-file driver entry point — but we'll fully implement it in Task 4. For now, leave a stub that:
- Validates `path.endsWith(".ck")` (already done in parser, no-op here).
- Tracks duplicate paths within a file (`Duplicate import`).
- Records `(canonical, alias)` pairs into a per-file `pendingImports` list to be processed in Task 4.

```kotlin
private fun registerImports(imports: List<ImportDeclaration>) {
    val seen = mutableSetOf<String>()
    imports.forEach { decl ->
        if (!seen.add(decl.path)) {
            diagnostics += FrontendDiagnostic(
                "Duplicate import of `${decl.path}`.",
                decl.range,
            )
            return@forEach
        }
        // Canonical resolution + loading happens in the multi-file driver (Task 4).
        // Here we simply record the request.
        pendingImports += decl
    }
}
```

Add a `private val pendingImports = mutableListOf<ImportDeclaration>()` to `SemanticAnalyzer`.

- [ ] **Step 3: Project compiles**

```
./gradlew :compiler:compileKotlin
```

Expected: SUCCESS. (Tests still fail because the multi-file driver isn't there yet.)

- [ ] **Step 4: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/ImportDeclaration.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt
git commit -m "feat(compiler): parse import \"path.ck\" and import \"path.ck\" as alias"
```

---

## Task 4: Multi-File Compilation Driver

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/CompilationArtifact.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt`

- [ ] **Step 1: Extend `LanguageFrontend.compile()`**

Add a new overload accepting a `SourceLoader`:

```kotlin
class LanguageFrontend(
    val registry: BuiltinRegistry = LanguageBuiltins.defaultRuntimeRegistry,
) {
    fun compile(name: String, source: String): CompilationArtifact =
        compile(name, source, NoOpSourceLoader)

    fun compile(
        name: String,
        source: String,
        loader: SourceLoader,
    ): CompilationArtifact = compiler.compile(name, source, loader)
}
```

Update `CompilerFacade` and `DefaultCompilerFacade` (in `FrontendPipelines.kt`) to thread `loader` through.

- [ ] **Step 2: Extend `CompilationArtifact`**

```kotlin
data class CompilationArtifact(
    val module: BytecodeModule?,
    val analysis: AnalyzedProgram,                    // root file
    val analyses: Map<String, AnalyzedProgram> = mapOf(analysis.name to analysis),
)
```

- [ ] **Step 3: Implement multi-file driver in the analyzer facade**

In `DefaultAnalyzerFacade` (or wherever `analyze()` is wired), replace single-file analysis with:

```kotlin
fun analyzeProject(rootName: String, rootSource: String, loader: SourceLoader): MultiFileAnalysis {
    data class Pending(val canonical: String, val source: String, val program: Program)
    val parsed = LinkedHashMap<String, Pending>()  // dedup by canonical
    val parseDiagnostics = LinkedHashMap<String, MutableList<FrontendDiagnostic>>()

    fun parse(canonical: String, source: String) {
        if (parsed.containsKey(canonical)) return
        val tokens = Lexer(source).lex()
        val initial = mutableListOf<FrontendDiagnostic>()
        val program = Parser(tokens, initial).parseProgram()
        parsed[canonical] = Pending(canonical, source, program)
        parseDiagnostics[canonical] = initial

        program.imports.forEach { decl ->
            val resolved = loader.resolve(canonical, decl.path)
            if (resolved == null) {
                initial += FrontendDiagnostic(
                    "Cannot resolve import `${decl.path}`.",
                    decl.pathRange,
                )
                return@forEach
            }
            if (parsed.containsKey(resolved)) return@forEach
            val text = loader.read(resolved)
            if (text == null) {
                initial += FrontendDiagnostic(
                    "Failed to read source `${decl.path}` (resolved to `$resolved`).",
                    decl.pathRange,
                )
                return@forEach
            }
            parse(resolved, text)
        }
    }

    parse(rootName, rootSource)

    // Phase 2: per-file semantic analysis with cross-file symbol linking.
    // Each file is analysed independently; its own imports are resolved against
    // the already-parsed table to fetch the *exported* symbols (top-level fns + structs).
    val analysed = LinkedHashMap<String, AnalyzedProgram>()
    parsed.forEach { (canonical, pending) ->
        val resolveImport: (String) -> String? = { path -> loader.resolve(canonical, path) }
        val importedExports: (String) -> ModuleExports? = { canonicalDep ->
            // Collect public top-level decls from `parsed[canonicalDep]?.program`
            collectExports(parsed[canonicalDep]?.program)
        }
        val analyzer = SemanticAnalyzer(
            registry = registry,
            sourceName = canonical,
            program = pending.program,
            initialDiagnostics = parseDiagnostics[canonical] ?: emptyList(),
            resolveImport = resolveImport,
            lookupExports = importedExports,
        )
        analysed[canonical] = analyzer.analyze()
    }
    return MultiFileAnalysis(root = rootName, analyses = analysed)
}
```

`ModuleExports` is a simple POKO carrying the file's top-level functions and structs (name, parameter types, return type, field schema).

- [ ] **Step 4: Add cross-file resolution to `SemanticAnalyzer`**

Constructor gains:
- `sourceName: String` — current file's canonical path
- `resolveImport: (String) -> String?`
- `lookupExports: (canonical: String) -> ModuleExports?`

In `registerImports()` (now no longer just a stub), for each `pendingImport`:
1. `val canonical = resolveImport(decl.path)` (already validated by driver but re-check for diagnostics).
2. `val exports = lookupExports(canonical) ?: return@forEach` (driver guarantees presence; if missing, emit error).
3. **If `decl.alias != null`**: register the alias as a `MODULE` symbol. The alias scope contains every public function/struct of `exports`, looked up by their unqualified names. Conflicts checked against built-in module names, prior aliases, and prior flat imports' bare symbols.
4. **If `decl.alias == null`** (flat): register every export as a top-level symbol in this file. Each must not collide with any built-in module name, prior alias, prior flat import, or local declaration.

Both branches must produce `Redeclaration` diagnostics with both source ranges when conflicts occur.

- [ ] **Step 5: Update `analyzeScope()` to find aliases**

In Plan A, `analyzeScope()` only looked up `importedModules[expression.qualifier]`. Now also check `importAliases[expression.qualifier]`. The alias entry yields a `ModuleExports` from which the `expression.name` is found. Emit a regular `FunctionBinding` referring to the **mangled** function name (see Task 5).

Same for `resolveType()` and qualified `RecordConstructionExpression` (lift the Plan-A "not yet supported" diagnostic): qualifier may now name a registered alias; if it does, look up the struct in `exports.structs`.

- [ ] **Step 6: Write the first end-to-end test**

Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UserFileImportsTest {
    private val frontend = LanguageFrontend()

    @Test
    fun aliasedImportExposesNamespace() {
        val loader = MapSourceLoader(
            mapOf(
                "math.ck" to """
                    struct Vec2 { x: Int, y: Int }
                    fun add(a: Vec2, b: Vec2): Vec2 {
                        return Vec2 { x: a.x + b.x, y: a.y + b.y };
                    }
                """.trimIndent(),
                "main.ck" to """
                    import "math.ck" as m;
                    fun main() {
                        val v: m::Vec2 = m::Vec2 { x: 1, y: 2 };
                        val w: m::Vec2 = m::add(v, m::Vec2 { x: 3, y: 4 });
                        terminal::println("x=" + w.x);
                    }
                """.trimIndent(),
            ),
        )
        val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)
        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }
}
```

- [ ] **Step 7: Iterate until the test passes**

```
./gradlew :compiler:test --tests "*aliasedImportExposesNamespace*"
```

When it passes, commit.

- [ ] **Step 8: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/CompilationArtifact.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt
git commit -m "feat(compiler): multi-file compilation driver with aliased imports"
```

---

## Task 5: Bytecode Mangling for Cross-File Symbols

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt` (BytecodeCompiler)

The current `BytecodeModule` indexes user functions by their bare name. With multi-file we need a globally unique identity. We mangle as `<canonical>#<name>` (e.g. `math.ck#add`). Record types are mangled as `<canonical>#<typeName>`.

- [ ] **Step 1: Define a mangling helper**

```kotlin
private fun mangle(canonical: String, name: String): String = "$canonical#$name"
```

- [ ] **Step 2: Compile every parsed file in a single bytecode module**

Today, `BytecodeCompiler.compile(name)` compiles one program. Change the driver to feed every `Pending` file to a single `BytecodeCompiler` that uses canonical-path-aware names:

- Function declarations: emit under `mangle(canonical, fn.name)`.
- Struct declarations: store schema under `mangle(canonical, struct.name)`.
- `RecordConstructionExpression` (unqualified): the resolver has already determined which canonical owns the type; emit `RecordValue` with `typeName = mangle(canonical, name)`.
- `ScopeAccessExpression(qualifier=alias, name=fn)` call: resolver has already resolved alias to a canonical path; emit a regular function-call instruction targeting `mangle(canonical, fn)`.
- `NameExpression` calling a flat-imported function: resolver maps the bare name to the source canonical; emit `mangle(canonical, fn)`.
- Calls to local functions: `mangle(currentCanonical, fn)`.

Make sure `RecordConstructionExpression` for flat-imported types also goes through `mangle`: the resolver records the source canonical for every imported struct.

- [ ] **Step 3: Make `analyzeRecordConstruction` produce a mangled `typeName`**

Change `RecordConstructionExpression.typeName` to be re-stamped after analysis, OR thread the resolved owning canonical through the analyzer's result type so the bytecode compiler knows what to mangle. The simpler form: add a `private val structOwners: MutableMap<String, String>` (visible struct name → canonical of file owning it) populated by `registerImports` and local registration; the bytecode compiler queries it.

- [ ] **Step 4: Write a clash test**

Add to `UserFileImportsTest.kt`:

```kotlin
@Test
fun flatImportsFromDifferentFilesShareNoNamesByMangling() {
    val loader = MapSourceLoader(
        mapOf(
            "a.ck" to "fun helper(): Int { return 1; }",
            "b.ck" to "fun helper(): Int { return 2; }",
            "main.ck" to """
                import "a.ck" as a;
                import "b.ck" as b;
                fun main() {
                    terminal::println("a=" + a::helper());
                    terminal::println("b=" + b::helper());
                }
            """.trimIndent(),
        ),
    )
    val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)
    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    assertNotNull(artifact.module)
}
```

- [ ] **Step 5: Run all compiler tests**

```
./gradlew :compiler:test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt
git commit -m "feat(compiler): mangle user symbols by canonical path for multi-file linking"
```

---

## Task 6: Conflict Diagnostics

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt`

- [ ] **Step 1: Write failing tests for each conflict class**

Append:

```kotlin
@Test
fun aliasCollidesWithBuiltinModule() {
    val loader = MapSourceLoader(mapOf("foo.ck" to "fun x(): Int { return 0; }"))
    val artifact = frontend.compile(
        "main.ck",
        """import "foo.ck" as terminal; fun main() { }""",
        loader,
    )
    assertTrue(
        artifact.analysis.diagnostics.any {
            it.severity == FrontendSeverity.ERROR && it.message.contains("Redeclaration")
        },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}

@Test
fun flatImportsClashOnSameName() {
    val loader = MapSourceLoader(
        mapOf(
            "a.ck" to "fun shared(): Int { return 1; }",
            "b.ck" to "fun shared(): Int { return 2; }",
            "main.ck" to """
                import "a.ck";
                import "b.ck";
                fun main() {}
            """.trimIndent(),
        ),
    )
    val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)
    assertTrue(
        artifact.analysis.diagnostics.any {
            it.severity == FrontendSeverity.ERROR && it.message.contains("Redeclaration")
        },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}

@Test
fun flatImportClashesWithLocalFunction() {
    val loader = MapSourceLoader(mapOf("a.ck" to "fun util(): Int { return 1; }"))
    val artifact = frontend.compile(
        "main.ck",
        """
            import "a.ck";
            fun util(): Int { return 0; }
            fun main() {}
        """.trimIndent(),
        loader,
    )
    assertTrue(
        artifact.analysis.diagnostics.any {
            it.severity == FrontendSeverity.ERROR && it.message.contains("Redeclaration")
        },
    )
}

@Test
fun duplicateImportPathDiagnostic() {
    val loader = MapSourceLoader(mapOf("x.ck" to "fun a(): Int { return 0; }"))
    val artifact = frontend.compile(
        "main.ck",
        """
            import "x.ck";
            import "x.ck" as x;
            fun main() {}
        """.trimIndent(),
        loader,
    )
    assertTrue(
        artifact.analysis.diagnostics.any { it.message.contains("Duplicate import") },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}
```

- [ ] **Step 2: Implement diagnostics**

In `registerImports()` and downstream, ensure:
- Alias name is checked against `builtinModules.keys`, the set of already-registered aliases in this file, and the set of names introduced by prior flat imports + local declarations.
- For flat imports, every introduced symbol is checked against the union of all of the above.
- Each conflict produces a `FrontendDiagnostic` whose message contains `Redeclaration` and references both ranges if available (e.g. include `(previously declared at line X)` text).

For the duplicate-import test: the existing dedup in `registerImports` already emits `Duplicate import`.

- [ ] **Step 3: Run conflict tests**

```
./gradlew :compiler:test --tests "*aliasCollidesWithBuiltinModule*" \
                        --tests "*flatImportsClashOnSameName*" \
                        --tests "*flatImportClashesWithLocalFunction*" \
                        --tests "*duplicateImportPathDiagnostic*"
```

Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt
git commit -m "feat(compiler): redeclaration diagnostics for import conflicts"
```

---

## Task 7: Transitivity is Off

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt`

- [ ] **Step 1: Write failing test for non-transitivity**

```kotlin
@Test
fun importsAreNotTransitive() {
    val loader = MapSourceLoader(
        mapOf(
            "deep.ck" to "fun deep(): Int { return 7; }",
            "mid.ck" to """import "deep.ck"; fun mid(): Int { return deep(); }""",
            "main.ck" to """
                import "mid.ck";
                fun main() { val z: Int = deep(); }
            """.trimIndent(),
        ),
    )
    val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)
    assertTrue(
        artifact.analysis.diagnostics.any {
            it.severity == FrontendSeverity.ERROR && it.message.contains("Unresolved")
        },
        "expected `deep` to be unresolved in main; got: " +
            artifact.analysis.diagnostics.joinToString { it.message },
    )
}
```

- [ ] **Step 2: Verify implementation already enforces this**

The driver in Task 4 only feeds **direct** imports from a file's `pendingImports` into its analyzer. If the test passes, no implementation work needed. Otherwise (test fails because `deep` is found), audit `registerImports()` to ensure it does NOT recursively pull symbols from indirect dependencies.

- [ ] **Step 3: Run**

```
./gradlew :compiler:test --tests "*importsAreNotTransitive*"
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt
git commit -m "test(compiler): verify imports are not transitive"
```

---

## Task 8: Import-Once / Cycle Safety

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
@Test
fun importGraphCycleDoesNotInfinitelyRecurse() {
    val loader = MapSourceLoader(
        mapOf(
            "a.ck" to """import "b.ck"; fun aFn(): Int { return 1; }""",
            "b.ck" to """import "a.ck"; fun bFn(): Int { return 2; }""",
            "main.ck" to """
                import "a.ck";
                import "b.ck";
                fun main() {
                    terminal::println("a=" + aFn() + " b=" + bFn());
                }
            """.trimIndent(),
        ),
    )
    val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)
    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    assertNotNull(artifact.module)
}

@Test
fun diamondImportCompilesOncePerFile() {
    val loader = MapSourceLoader(
        mapOf(
            "leaf.ck" to "fun leaf(): Int { return 9; }",
            "left.ck" to """import "leaf.ck" as l; fun left(): Int { return l::leaf(); }""",
            "right.ck" to """import "leaf.ck" as l; fun right(): Int { return l::leaf(); }""",
            "main.ck" to """
                import "left.ck";
                import "right.ck";
                fun main() {
                    terminal::println("sum=" + (left() + right()));
                }
            """.trimIndent(),
        ),
    )
    val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)
    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
    assertNotNull(artifact.module)
}
```

- [ ] **Step 2: Audit driver**

The BFS in Task 4 dedups by canonical path. Confirm:

```kotlin
if (parsed.containsKey(resolved)) return@forEach
```

If cycles still cause infinite recursion (because the dedup happens after the recursive call), restructure to dedup first:

```kotlin
program.imports.forEach { decl ->
    val resolved = loader.resolve(canonical, decl.path) ?: return@forEach
    if (parsed.containsKey(resolved)) return@forEach
    parsed[resolved] = placeholderPending  // mark before recursing
    val text = loader.read(resolved) ?: return@forEach
    parse(resolved, text)  // safe: cycle terminates
}
```

- [ ] **Step 3: Run**

```
./gradlew :compiler:test --tests "*importGraphCycleDoesNotInfinitelyRecurse*" \
                        --tests "*diamondImportCompilesOncePerFile*"
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt
git commit -m "test(compiler): import-once handles cycles and diamonds"
```

---

## Task 9: Runtime End-to-End Tests

**Files:**
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/UserFileImportsRuntimeTest.kt`

- [ ] **Step 1: Write the test**

Mirror the patterns of `LanguageRuntimeTest.kt` (using `RecordingRuntime`, `runBlocking`, `BytecodeComputerProgram`):

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.frontend.MapSourceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UserFileImportsRuntimeTest {
    private val frontend = LanguageFrontend()

    @Test
    fun executesAcrossFiles() {
        val loader = MapSourceLoader(
            mapOf(
                "math.ck" to """
                    fun add(x: Int, y: Int): Int { return x + y; }
                """.trimIndent(),
                "main.ck" to """
                    import "math.ck" as m;
                    fun main() { terminal::println("sum=" + m::add(2, 3)); }
                """.trimIndent(),
            ),
        )
        val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)
        assertNotNull(artifact.module)

        val runtime = RecordingRuntime()
        runBlocking { BytecodeComputerProgram(artifact.module!!).run(runtime) }
        assertEquals(listOf("sum=5"), runtime.lines)
    }

    @Test
    fun flatImportCallsAcrossFiles() {
        val loader = MapSourceLoader(
            mapOf(
                "io.ck" to "fun greet(): Unit { terminal::println(\"hi\"); }",
                "main.ck" to """
                    import "io.ck";
                    fun main() { greet(); }
                """.trimIndent(),
            ),
        )
        val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)
        assertNotNull(artifact.module)
        val runtime = RecordingRuntime()
        runBlocking { BytecodeComputerProgram(artifact.module!!).run(runtime) }
        assertEquals(listOf("hi"), runtime.lines)
    }
}
```

- [ ] **Step 2: Run**

```
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.UserFileImportsRuntimeTest"
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/UserFileImportsRuntimeTest.kt
git commit -m "test(runtime): end-to-end multi-file import execution"
```

---

## Task 10: Production `SourceLoader` over `DeviceWorkspace`

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspaceSourceLoader.kt`
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspaceSourceLoaderTest.kt`

- [ ] **Step 1: Write the loader**

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime

import ru.lazyhat.compukterkraft.lang.frontend.SourceLoader

/**
 * Resolves and reads CKL files from a [DeviceWorkspace] for a specific device.
 * Paths are normalised with forward slashes; ".." segments are collapsed.
 * The canonical path of a resolved file is the workspace path used by [DeviceWorkspace].
 */
class DeviceWorkspaceSourceLoader(
    private val workspace: DeviceWorkspace,
    private val deviceId: Int,
) : SourceLoader {

    override fun resolve(from: String, importPath: String): String? {
        val baseDir = from.substringBeforeLast('/', missingDelimiterValue = "")
        val combined = if (baseDir.isEmpty()) importPath else "$baseDir/$importPath"
        val normalised = normalise(combined) ?: return null
        return if (workspace.readDocument(deviceId, normalised) != null) normalised else null
    }

    override fun read(canonical: String): String? =
        workspace.readDocument(deviceId, canonical)?.text

    private fun normalise(path: String): String? {
        val parts = path.split('/').toMutableList()
        var i = 0
        while (i < parts.size) {
            when (parts[i]) {
                "", "." -> parts.removeAt(i)
                ".." -> {
                    if (i == 0) return null
                    parts.removeAt(i); parts.removeAt(i - 1); i -= 1
                }
                else -> i += 1
            }
        }
        return parts.joinToString("/")
    }
}
```

- [ ] **Step 2: Write the test**

Use a minimal in-memory `DeviceWorkspace` implementation (look for existing test fixtures in `modules/compiler/src/test`; if none, write a `FakeDeviceWorkspace` inline). Verify:
- Resolves sibling and parent-relative paths.
- Returns null for non-existent files.
- `read()` returns the workspace document text.

- [ ] **Step 3: Run**

```
./gradlew :compiler:test --tests "*DeviceWorkspaceSourceLoaderTest*"
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspaceSourceLoader.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspaceSourceLoaderTest.kt
git commit -m "feat(runtime): SourceLoader implementation backed by DeviceWorkspace"
```

---

## Task 11: Wire `DeviceWorkspaceSourceLoader` into Production Callers

**Files:**
- Modify: every production call site that compiles `.ck` files for a device. Find them with:
  ```bash
  grep -rn "LanguageFrontend()\|frontend.compile" modules --include="*.kt" | grep -v "src/test"
  ```

- [ ] **Step 1: For each site, pass a `DeviceWorkspaceSourceLoader`**

Replace `frontend.compile(name, source)` with `frontend.compile(name, source, DeviceWorkspaceSourceLoader(workspace, deviceId))` where the workspace and deviceId are in scope.

- [ ] **Step 2: For sites where no workspace is available (e.g. lang-generation smoke tests)**

Decide per call site: either thread a workspace through, or keep using the no-loader overload (which means user-file imports in those contexts will fail with a clear diagnostic — acceptable for tests that don't use them).

- [ ] **Step 3: Run all module tests**

```
./gradlew test
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: pass DeviceWorkspaceSourceLoader to production frontend callers"
```

---

## Task 12: Documentation

**Files:**
- Modify: `docs/LANGUAGE.md`

- [ ] **Step 1: Add `Imports` section after `Syntax`**

```markdown
## Imports

CKL programs may import other `.ck` files. The path is interpreted relative to
the importing file and must end with `.ck`.

    import "lib/math.ck";              // flat: top-level names visible directly
    import "lib/math.ck" as m;         // aliased: access via `m::name`

Rules:
- Each top-level `fun` and `struct` of an imported file is public.
- Imports are not transitive: importing `a.ck` does not import `a.ck`'s imports.
- The same file is parsed and analysed at most once per compilation, so import
  cycles are safe (the second visit is a no-op).
- Importing the same path twice in one file is a `Duplicate import` error.
- Conflicts between flat-imported names, aliases, local declarations, and
  built-in module names produce `Redeclaration` diagnostics.

### Aliases as namespaces

An alias behaves like a built-in module and uses `::`:

    import "math.ck" as m;
    val v: m::Vec2 = m::Vec2 { x: 1, y: 2 };
    val w: m::Vec2 = m::add(v, v);
```

- [ ] **Step 2: Cross-link from the top-level "Files" subsection if any**

- [ ] **Step 3: Commit**

```bash
git add docs/LANGUAGE.md
git commit -m "docs(language): document user-file imports and alias namespaces"
```

---

## Task 13: Final Verification

- [ ] **Step 1: Full repo test run**

```
./gradlew test
```

Expected: 100% PASS.

- [ ] **Step 2: Smoke test the example program**

Write a small ad-hoc Kotlin script (or REPL session) that compiles a two-file program with the `LanguageFrontend` and runs it under `RecordingRuntime`, confirming the example from the "Semantics Summary" prints `x=4`.

- [ ] **Step 3: Tag**

```bash
git tag user-file-imports-complete
```

Plan B is complete. Follow-up planned: selective Rust-style imports (`use math::{add, Vec2};`) and multi-file IDE features (cross-file goto, auto-completion of imported aliases). These are out of scope here.
