# План реализации: пользовательские импорты файлов

> **Для агентов:** ОБЯЗАТЕЛЬНЫЙ САБ-СКИЛЛ: используйте superpowers:subagent-driven-development (рекомендуется) или superpowers:executing-plans для пошагового выполнения. Шаги используют чекбокс-синтаксис (`- [ ]`) для отслеживания.

**Цель:** Добавить объявления `import "path.ck";` и `import "path.ck" as alias;`, загружающие другие CKL-файлы, с семантикой import-once (без циклов), без транзитивности символов, с жёсткими `Redeclaration`-диагностиками при конфликтах и mangling-ом имён в байткоде, чтобы независимые файлы могли использовать одинаковые top-level идентификаторы.

**Архитектура:** Ввести абстракцию `SourceLoader`, которая разрешает и читает `.ck`-файлы относительно текущего файла. `LanguageFrontend.compile()` получает перегрузку, принимающую loader; анализатор становится multi-file, выполняя BFS по импортам, парся каждый файл один раз (ключ — канонический путь), затем линкует. Top-level функции и структуры манглятся в байткоде как `<canonicalPath>#<name>`, чтобы плоские импорты из разных файлов сосуществовали; резолвер ведёт пер-файловые таблицы lookup, отображающие видимое имя (или `alias::name`) в манглированную идентичность. Встроенные модули (введённые в плане A) остаются ambient и не трогаются.

**Технологический стек:** Kotlin, kotlin-test (JUnit5), Gradle multi-module build (`:compiler`, `:core`, `:v1_21_1-*`).

**Предусловие:** План A (`docs/superpowers/plans/2026-05-01-scope-operator-and-implicit-builtins.md`) должен быть смёржен. Этот план предполагает, что `::` — оператор области, `.` — доступ к полю, builtins — ambient, а `import`-объявления сейчас выдают жёсткую ошибку.

---

## Сводка семантики

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

Правила:
- Расширение `.ck` обязательно в строке пути.
- Пути разрешаются **относительно импортирующего файла**.
- `import "p.ck";` (плоский): все top-level функции и структуры `p.ck` становятся видимы под голыми именами в импортирующем файле.
- `import "p.ck" as m;` (с алиасом): символы видны **только** как `m::name`. Алиас `m` живёт в одном неймспейсе со встроенными модулями и другими алиасами.
- **Без транзитивности:** если `a.ck` импортирует `b.ck`, а `main.ck` импортирует `a.ck`, то `main.ck` НЕ видит символы `b.ck` (если только сам не импортирует `b.ck`).
- **Import-once:** один и тот же канонизированный путь парсится/анализируется ровно один раз за компиляцию. Циклы поэтому невозможны — повторный заход — no-op для эффекта импорта этого файла, но import-объявления каждого файла всё равно применяются к его собственной области.
- **Все top-level декларации публичны.** Пока без `pub`/`export`.
- **Конфликты** (между любым из: имя builtin-модуля, алиас, плоско импортированный символ, локальная декларация) → диагностика `Redeclaration` со ссылкой на оба source range.
- **Дубликат `import` одного канонического пути в одном файле** → диагностика `Duplicate import`.
- IDE multi-file фичи (cross-file goto-definition, completion для `m::` из анализа файла `m`) **явно вне scope** этого плана и отложены в follow-up.

---

## Карта файлов

| Файл | Действие | Ответственность |
| --- | --- | --- |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt` | Изменить | Добавить keyword-токен `AS` |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/ImportDeclaration.kt` | Изменить | Заменить `moduleName: String` на `path, alias, pathRange, aliasRange` |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoader.kt` | Создать | Определить интерфейс `SourceLoader` и тестовую `MapSourceLoader` |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt` | Изменить | Лексер распознаёт `as`; парсер принимает новую import-форму; анализатор становится multi-file; bytecode эмитит mangled-имена |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/CompilationArtifact.kt` | Изменить | Добавить `analyses: Map<String, AnalyzedProgram>` ключ — канонический путь |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt` | Создать | E2E тесты с `MapSourceLoader` |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/UserFileImportsRuntimeTest.kt` | Создать | Runtime-тесты, доказывающие корректное выполнение multi-file программ |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspaceSourceLoader.kt` | Создать | Production `SourceLoader` поверх `DeviceWorkspace` |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspaceSourceLoaderTest.kt` | Создать | Тесты loader-а на основе workspace |
| `docs/LANGUAGE.md` | Изменить | Документировать новый import-синтаксис и семантику неймспейсов |

---

## Задача 1: Определить абстракцию `SourceLoader`

**Файлы:**
- Создать: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoader.kt`

- [ ] **Шаг 1: Написать абстракцию**

Создайте файл с содержимым:

```kotlin
package ru.lazyhat.compukterkraft.lang.frontend

/**
 * Загружает CKL-исходники для multi-file компиляции.
 *
 * - [resolve] принимает канонический путь файла, содержащего `import`,
 *   и литеральный путь, написанный в импорте (например `"lib/math.ck"`).
 *   Возвращает канонизированный путь, используемый как ключ дедупликации,
 *   или `null`, если путь нельзя разрешить.
 * - [read] возвращает текст исходника по ранее разрешённому каноническому
 *   пути, либо `null`, если файл больше не существует.
 *
 * Реализации ДОЛЖНЫ быть детерминированы: одна и та же пара (from, importPath)
 * всегда даёт один канонический путь, и один канонический путь всегда читается
 * одинаково в рамках одной компиляции.
 */
interface SourceLoader {
    fun resolve(from: String, importPath: String): String?
    fun read(canonical: String): String?
}

/**
 * Тестовая/утилитарная реализация на in-memory map.
 * Пути нормализуются с forward-slash; "../" сегменты сворачиваются.
 * `from` для корневого файла — по соглашению его собственный канонический путь.
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

/** Loader, используемый когда вызывающий не передал loader: любой user-file импорт становится ошибкой. */
object NoOpSourceLoader : SourceLoader {
    override fun resolve(from: String, importPath: String): String? = null
    override fun read(canonical: String): String? = null
}
```

- [ ] **Шаг 2: Написать падающий тест loader-а**

Создайте `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoaderTest.kt`:

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

- [ ] **Шаг 3: Запустить тест**

```
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.MapSourceLoaderTest"
```

Ожидание: PASS.

- [ ] **Шаг 4: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoader.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoaderTest.kt
git commit -m "feat(compiler): introduce SourceLoader abstraction with in-memory test impl"
```

---

## Задача 2: Лексить `as` как ключевое слово и обновить `ImportDeclaration`

**Файлы:**
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt` (таблица keyword-ов лексера)
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/ImportDeclaration.kt`

- [ ] **Шаг 1: Добавить enum-член `AS`**

В `TokenKind.kt` добавьте `AS` рядом с `IMPORT`:

```kotlin
FUN, VAL, VAR, IF, ELSE, WHILE, WHEN, RETURN, IMPORT, AS, STRUCT,
```

- [ ] **Шаг 2: Распознавать `as` в `lexIdentifier()`**

Найдите switch ключевых слов в лексере (секция со списком `"import" -> TokenKind.IMPORT`). Добавьте:

```kotlin
"as" -> TokenKind.AS
```

- [ ] **Шаг 3: Заменить форму `ImportDeclaration`**

Замените содержимое `ImportDeclaration.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.api

data class ImportDeclaration(
    val path: String,                 // литеральная строка из `import "PATH"`, включая .ck
    val pathRange: SourceRange,       // расположение строкового литерала
    val alias: String?,               // null = плоский импорт; не-null = с алиасом
    val aliasRange: SourceRange?,     // расположение идентификатора алиаса или null
    val range: SourceRange,           // полный range объявления
)
```

- [ ] **Шаг 4: Скомпилировать и убедиться, что вызывающий код ломается в ожидаемых местах**

```
./gradlew :compiler:compileKotlin
```

Ожидание: ошибки в `LanguageFrontend.kt`, ссылающиеся на `declaration.moduleName`. Перепишем их в задаче 3.

- [ ] **Шаг 5: Коммит (намеренное промежуточное состояние сборки — не коммитим сейчас)**

Не коммитьте сломанное состояние. Пропустите шаг и сразу переходите к задаче 3, которая восстанавливает компиляцию.

---

## Задача 3: Парсить новый синтаксис `import`

**Файлы:**
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`

- [ ] **Шаг 1: Заменить `parseImport()`**

Версия плана A выдавала жёсткую ошибку. Замените на новую грамматику:

```
import_decl = "import" STRING ("as" IDENTIFIER)? ";"
```

```kotlin
private fun parseImport(): ImportDeclaration? {
    val keyword = previous() // IMPORT уже съеден
    val pathToken = consume(
        TokenKind.STRING,
        "Expected file path string after `import` (e.g. `import \"lib/math.ck\";`).",
    ) ?: return null
    val path = pathToken.text  // уже без окружающих кавычек — проверьте, как хранится STRING
    if (!path.endsWith(".ck")) {
        diagnostics += FrontendDiagnostic(
            "Import path must end with `.ck` (got `$path`).",
            pathToken.range,
        )
        // продолжаем парсинг для recovery
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

Если ваш токен `STRING` сохраняет окружающие кавычки в `text`, снимите их: `val path = pathToken.text.removeSurrounding("\"")`. Проверьте, прочитав существующую ветку лексинга `STRING`.

- [ ] **Шаг 2: Заменить `registerImports()`**

Это становится точкой входа multi-file драйвера — но полностью реализуем в задаче 4. Пока оставьте заглушку, которая:
- Проверяет `path.endsWith(".ck")` (уже сделано в парсере, no-op здесь).
- Отслеживает дубликаты путей в файле (`Duplicate import`).
- Записывает пары `(canonical, alias)` в пер-файловый список `pendingImports` для обработки в задаче 4.

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
        // Канонизация + загрузка происходит в multi-file драйвере (задача 4).
        // Здесь просто записываем запрос.
        pendingImports += decl
    }
}
```

Добавьте `private val pendingImports = mutableListOf<ImportDeclaration>()` в `SemanticAnalyzer`.

- [ ] **Шаг 3: Проект компилируется**

```
./gradlew :compiler:compileKotlin
```

Ожидание: SUCCESS. (Тесты всё ещё падают, потому что multi-file драйвера нет.)

- [ ] **Шаг 4: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/ImportDeclaration.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt
git commit -m "feat(compiler): parse import \"path.ck\" and import \"path.ck\" as alias"
```

---

## Задача 4: Драйвер multi-file компиляции

**Файлы:**
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/CompilationArtifact.kt`
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt`

- [ ] **Шаг 1: Расширить `LanguageFrontend.compile()`**

Добавьте новую перегрузку, принимающую `SourceLoader`:

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

Обновите `CompilerFacade` и `DefaultCompilerFacade` (в `FrontendPipelines.kt`), чтобы прокидывать `loader`.

- [ ] **Шаг 2: Расширить `CompilationArtifact`**

```kotlin
data class CompilationArtifact(
    val module: BytecodeModule?,
    val analysis: AnalyzedProgram,                    // root-файл
    val analyses: Map<String, AnalyzedProgram> = mapOf(analysis.name to analysis),
)
```

- [ ] **Шаг 3: Реализовать multi-file драйвер в фасаде анализа**

В `DefaultAnalyzerFacade` (или там, где разводится `analyze()`) замените single-file анализ на:

```kotlin
fun analyzeProject(rootName: String, rootSource: String, loader: SourceLoader): MultiFileAnalysis {
    data class Pending(val canonical: String, val source: String, val program: Program)
    val parsed = LinkedHashMap<String, Pending>()  // дедупликация по канонизированному пути
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

    // Фаза 2: пер-файловый семантический анализ с cross-file линковкой символов.
    // Каждый файл анализируется независимо; его собственные импорты разрешаются
    // против уже-распарсенной таблицы для получения *экспортируемых* символов
    // (top-level fns + structs).
    val analysed = LinkedHashMap<String, AnalyzedProgram>()
    parsed.forEach { (canonical, pending) ->
        val resolveImport: (String) -> String? = { path -> loader.resolve(canonical, path) }
        val importedExports: (String) -> ModuleExports? = { canonicalDep ->
            // Собрать публичные top-level декларации из `parsed[canonicalDep]?.program`
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

`ModuleExports` — простой POKO с top-level функциями и структурами файла (имя, типы параметров, тип возврата, схема полей).

- [ ] **Шаг 4: Добавить cross-file разрешение в `SemanticAnalyzer`**

Конструктор получает:
- `sourceName: String` — канонический путь текущего файла
- `resolveImport: (String) -> String?`
- `lookupExports: (canonical: String) -> ModuleExports?`

В `registerImports()` (теперь больше не заглушка), для каждого `pendingImport`:
1. `val canonical = resolveImport(decl.path)` (уже валидировано драйвером, но ре-проверяем для диагностики).
2. `val exports = lookupExports(canonical) ?: return@forEach` (драйвер гарантирует наличие; если отсутствует — emit error).
3. **Если `decl.alias != null`**: зарегистрировать алиас как `MODULE`-символ. Область алиаса содержит каждую публичную функцию/структуру `exports`, индексированную по неквалифицированным именам. Конфликты проверяются против имён builtin-модулей, предыдущих алиасов и голых символов предыдущих плоских импортов.
4. **Если `decl.alias == null`** (плоский): зарегистрировать каждый export как top-level символ в этом файле. Каждый не должен конфликтовать с любым именем builtin-модуля, предыдущим алиасом, предыдущим плоским импортом или локальной декларацией.

Обе ветки должны производить `Redeclaration`-диагностики с обоими source range при конфликтах.

- [ ] **Шаг 5: Обновить `analyzeScope()`, чтобы находить алиасы**

В плане A `analyzeScope()` смотрел только в `importedModules[expression.qualifier]`. Теперь также проверяйте `importAliases[expression.qualifier]`. Запись алиаса даёт `ModuleExports`, в которой ищется `expression.name`. Эмитьте обычный `FunctionBinding`, ссылающийся на **mangled** имя функции (см. задачу 5).

То же для `resolveType()` и квалифицированной `RecordConstructionExpression` (снимите ограничение «not yet supported» из плана A): qualifier теперь может именовать зарегистрированный алиас; если да — ищите структуру в `exports.structs`.

- [ ] **Шаг 6: Написать первый E2E-тест**

Создайте `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt`:

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

- [ ] **Шаг 7: Итерируйте, пока тест не пройдёт**

```
./gradlew :compiler:test --tests "*aliasedImportExposesNamespace*"
```

Когда пройдёт — коммит.

- [ ] **Шаг 8: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/CompilationArtifact.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt
git commit -m "feat(compiler): multi-file compilation driver with aliased imports"
```

---

## Задача 5: Mangling байткода для cross-file символов

**Файлы:**
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt` (BytecodeCompiler)

Сегодня `BytecodeModule` индексирует пользовательские функции по голому имени. С multi-file нужна глобально уникальная идентичность. Манглим как `<canonical>#<name>` (например, `math.ck#add`). Record-типы манглим как `<canonical>#<typeName>`.

- [ ] **Шаг 1: Определить хелпер mangling**

```kotlin
private fun mangle(canonical: String, name: String): String = "$canonical#$name"
```

- [ ] **Шаг 2: Компилировать каждый распарсенный файл в один bytecode-модуль**

Сейчас `BytecodeCompiler.compile(name)` компилирует одну программу. Измените драйвер, чтобы он скармливал каждый `Pending`-файл одному `BytecodeCompiler`, использующему canonical-path-aware имена:

- Function declarations: эмитить под `mangle(canonical, fn.name)`.
- Struct declarations: хранить схему под `mangle(canonical, struct.name)`.
- `RecordConstructionExpression` (неквалифицированный): резолвер уже определил, какой canonical владеет типом; эмитить `RecordValue` с `typeName = mangle(canonical, name)`.
- Вызов `ScopeAccessExpression(qualifier=alias, name=fn)`: резолвер уже разрешил алиас в canonical-путь; эмитить обычную инструкцию вызова функции, целью — `mangle(canonical, fn)`.
- `NameExpression`, вызывающий плоско импортированную функцию: резолвер маппит голое имя в исходный canonical; эмитить `mangle(canonical, fn)`.
- Вызовы локальных функций: `mangle(currentCanonical, fn)`.

Убедитесь, что `RecordConstructionExpression` для плоско-импортированных типов также проходит через `mangle`: резолвер записывает source canonical для каждой импортированной структуры.

- [ ] **Шаг 3: Сделать `analyzeRecordConstruction` производящим mangled `typeName`**

Измените `RecordConstructionExpression.typeName` на post-stamp после анализа ИЛИ протяните разрешённый owning-canonical через тип-результат анализатора, чтобы байткод-компилятор знал, что манглить. Простее: добавьте `private val structOwners: MutableMap<String, String>` (видимое имя структуры → canonical файла-владельца), заполняемый `registerImports` и локальной регистрацией; байткод-компилятор делает lookup.

- [ ] **Шаг 4: Написать тест на коллизию**

Допишите в `UserFileImportsTest.kt`:

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

- [ ] **Шаг 5: Запустить все тесты компилятора**

```
./gradlew :compiler:test
```

Ожидание: PASS.

- [ ] **Шаг 6: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt
git commit -m "feat(compiler): mangle user symbols by canonical path for multi-file linking"
```

---

## Задача 6: Диагностики конфликтов

**Файлы:**
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Изменить: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt`

- [ ] **Шаг 1: Написать падающие тесты для каждого класса конфликта**

Допишите:

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

- [ ] **Шаг 2: Реализовать диагностики**

В `registerImports()` и далее обеспечьте:
- Имя алиаса проверяется против `builtinModules.keys`, набора уже зарегистрированных алиасов в этом файле, и набора имён, введённых предыдущими плоскими импортами + локальными декларациями.
- Для плоских импортов каждый вводимый символ проверяется против объединения всего вышеперечисленного.
- Каждый конфликт даёт `FrontendDiagnostic`, чьё сообщение содержит `Redeclaration` и упоминает оба range при наличии (например, текст `(previously declared at line X)`).

Для теста дубликата импорта: существующая дедупликация в `registerImports` уже эмитит `Duplicate import`.

- [ ] **Шаг 3: Запустить тесты конфликтов**

```
./gradlew :compiler:test --tests "*aliasCollidesWithBuiltinModule*" \
                        --tests "*flatImportsClashOnSameName*" \
                        --tests "*flatImportClashesWithLocalFunction*" \
                        --tests "*duplicateImportPathDiagnostic*"
```

Ожидание: все PASS.

- [ ] **Шаг 4: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt
git commit -m "feat(compiler): redeclaration diagnostics for import conflicts"
```

---

## Задача 7: Транзитивность отключена

**Файлы:**
- Изменить: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt`

- [ ] **Шаг 1: Написать падающий тест non-transitivity**

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

- [ ] **Шаг 2: Убедиться, что реализация уже это обеспечивает**

Драйвер из задачи 4 кормит анализатор только **прямыми** импортами из `pendingImports` файла. Если тест проходит — реализационной работы не нужно. Иначе (если `deep` находится) — провести аудит `registerImports()`, убедиться, что она НЕ тянет рекурсивно символы из косвенных зависимостей.

- [ ] **Шаг 3: Запустить**

```
./gradlew :compiler:test --tests "*importsAreNotTransitive*"
```

Ожидание: PASS.

- [ ] **Шаг 4: Коммит**

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt
git commit -m "test(compiler): verify imports are not transitive"
```

---

## Задача 8: Import-once / cycle safety

**Файлы:**
- Изменить: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt`

- [ ] **Шаг 1: Написать падающие тесты**

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

- [ ] **Шаг 2: Аудит драйвера**

BFS в задаче 4 дедупит по каноническому пути. Подтвердите:

```kotlin
if (parsed.containsKey(resolved)) return@forEach
```

Если циклы всё ещё дают бесконечную рекурсию (потому что dedup происходит после рекурсивного вызова) — реструктурируйте, чтобы сначала дедупить:

```kotlin
program.imports.forEach { decl ->
    val resolved = loader.resolve(canonical, decl.path) ?: return@forEach
    if (parsed.containsKey(resolved)) return@forEach
    parsed[resolved] = placeholderPending  // помечаем до рекурсии
    val text = loader.read(resolved) ?: return@forEach
    parse(resolved, text)  // безопасно: цикл завершается
}
```

- [ ] **Шаг 3: Запустить**

```
./gradlew :compiler:test --tests "*importGraphCycleDoesNotInfinitelyRecurse*" \
                        --tests "*diamondImportCompilesOncePerFile*"
```

Ожидание: PASS.

- [ ] **Шаг 4: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt
git commit -m "test(compiler): import-once handles cycles and diamonds"
```

---

## Задача 9: Runtime E2E-тесты

**Файлы:**
- Создать: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/UserFileImportsRuntimeTest.kt`

- [ ] **Шаг 1: Написать тест**

Зеркалируйте паттерны `LanguageRuntimeTest.kt` (с `RecordingRuntime`, `runBlocking`, `BytecodeComputerProgram`):

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

- [ ] **Шаг 2: Запустить**

```
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.UserFileImportsRuntimeTest"
```

Ожидание: PASS.

- [ ] **Шаг 3: Коммит**

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/UserFileImportsRuntimeTest.kt
git commit -m "test(runtime): end-to-end multi-file import execution"
```

---

## Задача 10: Production `SourceLoader` поверх `DeviceWorkspace`

**Файлы:**
- Создать: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspaceSourceLoader.kt`
- Создать: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspaceSourceLoaderTest.kt`

- [ ] **Шаг 1: Написать loader**

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime

import ru.lazyhat.compukterkraft.lang.frontend.SourceLoader

/**
 * Разрешает и читает CKL-файлы из [DeviceWorkspace] для конкретного устройства.
 * Пути нормализуются с forward-slash; ".." сворачиваются.
 * Канонический путь разрешённого файла — workspace-путь, используемый [DeviceWorkspace].
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

- [ ] **Шаг 2: Написать тест**

Используйте минимальную in-memory реализацию `DeviceWorkspace` (поищите существующие тестовые fixture в `modules/compiler/src/test`; если нет — напишите inline `FakeDeviceWorkspace`). Проверьте:
- Разрешает sibling- и parent-relative-пути.
- Возвращает null для несуществующих файлов.
- `read()` возвращает текст документа workspace.

- [ ] **Шаг 3: Запустить**

```
./gradlew :compiler:test --tests "*DeviceWorkspaceSourceLoaderTest*"
```

Ожидание: PASS.

- [ ] **Шаг 4: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspaceSourceLoader.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspaceSourceLoaderTest.kt
git commit -m "feat(runtime): SourceLoader implementation backed by DeviceWorkspace"
```

---

## Задача 11: Подключить `DeviceWorkspaceSourceLoader` в production-вызовы

**Файлы:**
- Изменить: каждый production call site, компилирующий `.ck`-файлы для устройства. Найдите их:
  ```bash
  grep -rn "LanguageFrontend()\|frontend.compile" modules --include="*.kt" | grep -v "src/test"
  ```

- [ ] **Шаг 1: На каждом сайте передавать `DeviceWorkspaceSourceLoader`**

Замените `frontend.compile(name, source)` на `frontend.compile(name, source, DeviceWorkspaceSourceLoader(workspace, deviceId))`, где workspace и deviceId доступны в области видимости.

- [ ] **Шаг 2: Для сайтов без доступного workspace (например, lang-generation smoke tests)**

Решайте по каждому сайту отдельно: либо протянуть workspace, либо оставить no-loader перегрузку (тогда user-file импорты в этих контекстах упадут с понятной диагностикой — допустимо для тестов, не использующих их).

- [ ] **Шаг 3: Запустить все тесты модулей**

```
./gradlew test
```

Ожидание: PASS.

- [ ] **Шаг 4: Коммит**

```bash
git add -A
git commit -m "feat: pass DeviceWorkspaceSourceLoader to production frontend callers"
```

---

## Задача 12: Документация

**Файлы:**
- Изменить: `docs/LANGUAGE.md`

- [ ] **Шаг 1: Добавить секцию `Imports` после `Syntax`**

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

- [ ] **Шаг 2: Cross-link из подсекции «Files» верхнего уровня, если она есть**

- [ ] **Шаг 3: Коммит**

```bash
git add docs/LANGUAGE.md
git commit -m "docs(language): document user-file imports and alias namespaces"
```

---

## Задача 13: Финальная верификация

- [ ] **Шаг 1: Полный прогон тестов репо**

```
./gradlew test
```

Ожидание: 100% PASS.

- [ ] **Шаг 2: Smoke-тест примера**

Напишите небольшой ad-hoc Kotlin-скрипт (или REPL-сессию), который компилирует двухфайловую программу через `LanguageFrontend` и запускает её под `RecordingRuntime`, подтверждая, что пример из «Сводки семантики» печатает `x=4`.

- [ ] **Шаг 3: Тег**

```bash
git tag user-file-imports-complete
```

План B завершён. Запланирован follow-up: точечные импорты в стиле Rust (`use math::{add, Vec2};`) и multi-file IDE-фичи (cross-file goto, autocompletion импортированных алиасов). Они вне scope этого плана.
