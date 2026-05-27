# План реализации: оператор `::` и неявные builtins

> **Для агентов:** ОБЯЗАТЕЛЬНЫЙ САБ-СКИЛЛ: используйте superpowers:subagent-driven-development (рекомендуется) или superpowers:executing-plans для пошагового выполнения. Шаги используют чекбокс-синтаксис (`- [ ]`) для отслеживания.

**Цель:** Ввести `::` как оператор разрешения области/неймспейса для встроенных модулей (`terminal::write`, `system::computerId`), сделать встроенные модули неявно доступными без `import`, ограничить `.` доступом к полям структур, и отвергать любые `import`-объявления жёсткой ошибкой (готовя синтаксический слот для пользовательских импортов файлов в следующем плане).

**Архитектура:** Добавить новый токен `COLON_COLON` в лексер, новый AST-узел `ScopeAccessExpression`, отличный от `MemberAccessExpression`, поддержку парсера для `IDENTIFIER :: IDENTIFIER` в первичных выражениях и type-синтаксисе, и резолвер, направляющий `::` в реестр builtins, а `.` — на поля структур. Встроенные модули регистрируются как ambient-символы в начале анализа вместо `import`. Миграция ROM `.ck`-файлов и инлайн тест-снипетов выполняется в тех же коммитах, что и синтаксические изменения.

**Технологический стек:** Kotlin, kotlin-test (под капотом JUnit5), Gradle multi-module build (`:compiler`).

---

## Карта файлов

| Файл | Действие | Ответственность |
| --- | --- | --- |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt` | Изменить | Добавить enum-член `COLON_COLON` |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt` | Изменить | Добавить data class `ScopeAccessExpression`; расширить `TypeSyntax` опциональным квалификатором |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt` | Изменить | Лексер (`::`), парсер (primary + type), SemanticAnalyzer (ambient builtins, scope vs field, отклонение `import`), BytecodeCompiler (новый узел) |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt` | Изменить | Тесты на `::`, на запрет `.` для модулей, на ambient builtins, на отклонение `import` |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt` | Изменить | Мигрировать инлайн `.ck`-снипеты на `::` и убрать `import terminal;` |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt` | Изменить | Обновить инлайн-снипеты и ожидания IDE, затронутые новым оператором |
| `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/*.ck` | Изменить | Заменить `import <module>;` на неявное использование; `module.func(...)` → `module::func(...)` |
| `docs/LANGUAGE.md` | Изменить | Документировать `::` для области, `.` для полей, неявные builtins, удаление `import <ident>;` |

---

## Заметки для исполнителя

- **Изменение типа `TypeSyntax`:** сейчас `TypeSyntax` имеет только `name: String, nullable: Boolean, range`. Расширяем полем `qualifier: String?` (по умолчанию `null`). План A проставляет `qualifier` только при парсинге — резолвер плана A ещё **не** знает никаких пользовательских неймспейсов, поэтому ненулевой квалификатор должен давать диагностику `Qualified types are not yet supported`. План B активирует это для импортных алиасов. Оставьте это ограничение видимым в сообщении диагностики, чтобы будущий план легко его нашёл.
- **Обратная совместимость явно НЕ цель.** Весь существующий исходный код мигрируется в рамках выполнения этого плана.
- **Ключевое слово `import` остаётся токеном.** Мы продолжаем его парсить, чтобы выдавать точную диагностику. Тело `parseImport()` становится «emit error + synchronize». План B заново активирует синтаксис `import "path";`.

---

## Задача 1: Добавить токен `COLON_COLON`

**Файлы:**
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt` (ветка `:` в лексере)

- [ ] **Шаг 1: Написать падающий тест лексера**

Добавьте в `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`:

```kotlin
@Test
fun lexesDoubleColonAsScopeOperator() {
    val artifact = frontend.compile("scope.ck", """
        fun main() { terminal::println("ok"); }
    """.trimIndent())
    // Текущий парсер хотя бы не должен выдавать "Unexpected character" для `::`.
    assertTrue(
        artifact.analysis.diagnostics.none { it.message.contains("Unexpected character") },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}
```

- [ ] **Шаг 2: Запустить тест и убедиться, что он падает**

```
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.lexesDoubleColonAsScopeOperator"
```

Ожидание: FAIL — диагностика содержит «Unexpected character `:`» или подобное (потому что `::` ещё не распознаётся).

- [ ] **Шаг 3: Добавить enum-член**

Отредактируйте `TokenKind.kt`. Вставьте `COLON_COLON,` сразу после `COLON,`:

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

- [ ] **Шаг 4: Обновить ветку `:` в лексере**

В `LanguageFrontend.kt` найдите существующий case `':'` в `Lexer.lex()` (около строки 1313):

```kotlin
':' -> { addToken(TokenKind.COLON, ":", start) }
```

Замените на:

```kotlin
':' -> {
    if (match(':')) {
        addToken(TokenKind.COLON_COLON, "::", start)
    } else {
        addToken(TokenKind.COLON, ":", start)
    }
}
```

- [ ] **Шаг 5: Запустить тест и убедиться, что он проходит (только лексер)**

```
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.lexesDoubleColonAsScopeOperator"
```

Ожидание: PASS (парсер всё ещё ругается на `::`, потому что грамматика его не принимает — но «Unexpected character» уровня лексера должно исчезнуть).

- [ ] **Шаг 6: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): lex :: as COLON_COLON token"
```

---

## Задача 2: Добавить AST-узел `ScopeAccessExpression` и расширить `TypeSyntax`

**Файлы:**
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`

- [ ] **Шаг 1: Изучить существующие `MemberAccessExpression` и `TypeSyntax`**

Прочитайте `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt` строки 30-45 и 175-185. Подтвердите:
- `TypeSyntax(name: String, nullable: Boolean, range: SourceRange)` — три поля.
- `MemberAccessExpression(receiver: Expression, memberName: String, range: SourceRange)`.

- [ ] **Шаг 2: Расширить `TypeSyntax` опциональным квалификатором**

В `LanguageModel.kt` найдите:

```kotlin
data class TypeSyntax(
    val name: String,
    val nullable: Boolean,
    val range: SourceRange,
)
```

Замените на:

```kotlin
data class TypeSyntax(
    val name: String,
    val nullable: Boolean,
    val range: SourceRange,
    val qualifier: String? = null,
)
```

Дефолт `null` сохраняет валидность всех существующих вызовов конструктора.

- [ ] **Шаг 3: Добавить `ScopeAccessExpression`**

В `LanguageModel.kt` сразу после `data class MemberAccessExpression(...)` добавьте:

```kotlin
/**
 * Разрешение неймспейса/области: `qualifier::name`.
 * Всегда два плоских идентификатора. В отличие от [MemberAccessExpression],
 * квалификатор никогда не ссылается на runtime-значение — это compile-time
 * имя области (встроенный модуль или, после плана B, алиас импорта файла).
 */
data class ScopeAccessExpression(
    val qualifier: String,
    val name: String,
    val qualifierRange: SourceRange,
    override val range: SourceRange,
) : Expression
```

- [ ] **Шаг 4: Скомпилировать и проверить, что API-изменения не ломают вызывающий код**

```
./gradlew :compiler:compileKotlin
```

Ожидание: SUCCESS. (Все вызывающие `TypeSyntax` используют трёхаргументный конструктор, который всё ещё валиден благодаря дефолту `qualifier`.)

- [ ] **Шаг 5: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt
git commit -m "feat(compiler): add ScopeAccessExpression AST node and TypeSyntax qualifier"
```

---

## Задача 3: Парсить `::` в первичных выражениях и в типах

**Файлы:**
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`

Текущий путь парсера для `terminal.write(...)` идёт через `parseCall()`, который видит `NameExpression("terminal")`, затем `DOT` и производит `MemberAccessExpression`. Мы добавляем альтернативу `::` прямо в primary, так что голова `IDENTIFIER COLON_COLON IDENTIFIER` производит `ScopeAccessExpression`, а потом `parseCall()` может прицепить `(args)` или конструктор `{...}`.

- [ ] **Шаг 1: Написать падающие тесты парсера**

Допишите в `LanguageFrontendTest.kt`:

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

(Оба упадут на этом шаге — второй потому, что резолвер всё ещё принимает `terminal.println`. Поправим в задаче 4.)

- [ ] **Шаг 2: Найти `parsePrimary()` и `parseCall()`**

В `LanguageFrontend.kt` найдите `parseCall()` около строки 2025. Обратите внимание на цикл, обрабатывающий `LPAREN` и `DOT`. Не модифицируйте postfix-цикл. Вместо этого измените ветку `IDENTIFIER` в primary.

Найдите `parsePrimary()` около строки 2080 и ветку для `IDENTIFIER` (она сейчас производит либо `RecordConstructionExpression`, если следующий — `LBRACE`, либо `NameExpression`).

- [ ] **Шаг 3: Добавить обработку `::` в primary**

Внутри ветки `IDENTIFIER` в `parsePrimary()`, ПЕРЕД существующей логикой выбора между record construction и голым именем, вставьте:

```kotlin
if (check(TokenKind.COLON_COLON)) {
    advance() // съесть `::`
    val nameToken = consume(TokenKind.IDENTIFIER, "Expected name after `::`.") ?: return null
    val scope = ScopeAccessExpression(
        qualifier = token.text,
        name = nameToken.text,
        qualifierRange = token.range,
        range = SourceRange(token.range.start, nameToken.range.end),
    )
    // Разрешить конструктор `qualifier::Name { ... }`
    if (check(TokenKind.LBRACE) && looksLikeRecordConstruction()) {
        return parseQualifiedRecordConstruction(scope)
    }
    return scope
}
```

Если хелпера `looksLikeRecordConstruction()` нет, отзеркальте логику look-ahead, уже используемую для неквалифицированной конструкции (существующий парсер инспектирует `LBRACE IDENTIFIER COLON` для отделения от блока). При необходимости вынесите в маленький приватный хелпер.

- [ ] **Шаг 4: Добавить `parseQualifiedRecordConstruction()`**

Добавьте этот приватный хелпер рядом с `parseRecordConstruction()`:

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

Это требует расширить `RecordConstructionExpression` полем `qualifier: String? = null`. Сделайте это в `LanguageModel.kt`:

```kotlin
data class RecordConstructionExpression(
    val typeName: String,
    val fields: List<RecordFieldInitializer>,
    override val range: SourceRange,
    val qualifier: String? = null,
) : Expression
```

- [ ] **Шаг 5: Обновить `parseType()`, чтобы принимать `qualifier::name`**

Найдите `parseType()` около строки 1837:

```kotlin
private fun parseType(): TypeSyntax? {
    val name = consume(TokenKind.IDENTIFIER, "Expected type name.") ?: return null
    val nullable = match(TokenKind.QUESTION)
    return TypeSyntax(name.text, nullable, SourceRange(name.range.start, previous().range.end))
}
```

Замените на:

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

- [ ] **Шаг 6: Запустить первый тест парсера**

```
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.parsesScopeCallToBuiltin"
```

Ожидание: всё ещё FAIL до задачи 4 (резолвер перенаправит `terminal::println`). Подтвердите, что режим падения теперь резолвер-ориентированный («Unresolved name `terminal`» или подобное), а не парсерный.

- [ ] **Шаг 7: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): parse :: in expressions, types, and record construction"
```

---

## Задача 4: Резолвер — ambient builtins, `::` для области, `.` для полей

**Файлы:**
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`

- [ ] **Шаг 1: Сделать встроенные модули ambient**

Найдите `registerImports()` около строки 254. Замените его тело no-op-ом для builtin lookup (цикл станет тривиальным после задачи 5; пока оставьте, но прекратите регистрацию из imports). Затем добавьте новый метод `registerAmbientBuiltins()`, вызываемый из конструктора анализатора или в начале `analyze()`:

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

Вызовите его один раз перед обходом программы. Убедитесь, что он отрабатывает до регистрации пользовательских объявлений (чтобы коллизии пользовательских имён с именами builtin-модулей давали корректную диагностику `Redeclaration` — см. задачу 5).

- [ ] **Шаг 2: Резолвить `ScopeAccessExpression` в builtin**

Добавьте новую точку входа `analyzeScope(expression: ScopeAccessExpression)`:

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

Подключите в диспетчер анализа выражений (большой `when` около `analyzeExpression`):

```kotlin
is ScopeAccessExpression -> analyzeScope(expression)
```

- [ ] **Шаг 3: Ограничить `MemberAccessExpression` только полями структур**

В `analyzeMember()` (строка ~377) удалите ветку, разрешающую `module.member` через `importedModules`. Замените диагностикой, когда receiver — `NameExpression`, именующий зарегистрированный builtin-модуль:

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
    // существующая логика field-on-record ниже остаётся неизменной
    // ...
}
```

- [ ] **Шаг 4: Обновить диспетчер байткод-компилятора**

Найдите каждую ветку `is MemberAccessExpression ->` в `BytecodeCompiler` (файл `LanguageFrontend.kt` около строки 1262) и добавьте парную `is ScopeAccessExpression -> compileScopeAccess(expression)`. Семантика для host-вызова — ровно та же, что у сегодняшнего пути «module receiver» — эмитить `HostCall(moduleName = qualifier, functionName = name, args)`.

```kotlin
is ScopeAccessExpression -> error(
    "ScopeAccessExpression must be the callee of a CallExpression; bare scope refs are not values."
)
```

Для case `CallExpression` диспетчера добавьте:

```kotlin
is CallExpression -> when (val callee = expression.callee) {
    is ScopeAccessExpression -> {
        compileArgs(expression.arguments)
        emit(HostCall(callee.qualifier, callee.name, expression.arguments.size))
    }
    // существующие ветки для MemberAccess (теперь только поля — ошибка, если достигнуто)
    // и голый NameExpression (пользовательские функции)
    // ...
}
```

- [ ] **Шаг 5: Запустить все тесты компилятора**

```
./gradlew :compiler:test
```

Ожидание: многие тесты упадут, потому что их инлайн `.ck`-снипеты всё ещё используют `import terminal;` / `terminal.println`. Эта миграция — задача 7. Два теста парсера, добавленные в задаче 3, теперь должны PASS. Подтвердите:

```
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.parsesScopeCallToBuiltin"
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.rejectsDotForBuiltinModuleAccess"
```

Оба: PASS.

- [ ] **Шаг 6: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt
git commit -m "feat(compiler): resolve :: against ambient builtins, restrict . to record fields"
```

---

## Задача 5: Жёстко отвергать любое объявление `import`

**Файлы:**
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`

- [ ] **Шаг 1: Написать падающий тест**

Допишите в `LanguageFrontendTest.kt`:

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

- [ ] **Шаг 2: Запустить и убедиться, что падает**

```
./gradlew :compiler:test --tests "*rejectsImportDeclarationsHard*"
```

Ожидание: FAIL — текущий код принимает `import terminal;`.

- [ ] **Шаг 3: Заменить тело `parseImport()`**

Найдите `parseImport()` около строки 1621. Замените на:

```kotlin
private fun parseImport(): ImportDeclaration? {
    val keyword = previous() // токен IMPORT уже съеден parseProgram
    // Съедаем до следующего ; или top-level keyword для восстановления.
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

Также обновите `registerImports()` (теперь no-op кроме вызова ambient) — удалите тело или конвертируйте в комментарий-заглушку. Ambient builtins регистрируются новым `registerAmbientBuiltins()` из задачи 4.

- [ ] **Шаг 4: Запустить тесты на отклонение import**

```
./gradlew :compiler:test --tests "*rejectsImportDeclarationsHard*"
./gradlew :compiler:test --tests "*ambientBuiltinsWorkWithoutImport*"
```

Оба: PASS.

- [ ] **Шаг 5: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): reject import declarations; built-ins are ambient"
```

---

## Задача 6: Диагностировать квалифицированные типы в плане A

**Файлы:**
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`

- [ ] **Шаг 1: Написать падающий тест**

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

- [ ] **Шаг 2: В `resolveType()` или там, где `TypeSyntax` потребляется (строка ~929), отвергать ненулевые квалификаторы**

```kotlin
private fun resolveType(syntax: TypeSyntax): TypeRef {
    if (syntax.qualifier != null) {
        diagnostics += FrontendDiagnostic(
            "Qualified types are not yet supported. " +
                "User-file imports introducing namespaces will land in the next version.",
            syntax.range,
        )
        return TypeRef(syntax.name) // best-effort, считаем «голым»
    }
    // ... существующее разрешение
}
```

- [ ] **Шаг 3: Аналогично отвергать квалифицированную record construction**

В `analyzeRecordConstruction()` (строка ~791) проверьте `expression.qualifier`:

```kotlin
if (expression.qualifier != null) {
    diagnostics += FrontendDiagnostic(
        "Qualified record construction is not yet supported.",
        expression.range,
    )
    return ErrorBinding to TypeRef(expression.typeName)
}
```

- [ ] **Шаг 4: Запустить тесты**

```
./gradlew :compiler:test --tests "*rejectsQualifiedTypesUntilUserImportsLand*"
```

Ожидание: PASS.

- [ ] **Шаг 5: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat(compiler): diagnose qualified types/records pending user imports"
```

---

## Задача 7: Мигрировать инлайн тест-снипеты

**Файлы:**
- Изменить: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
- Изменить: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt` (существующие снипеты, не новые тесты)
- Изменить: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`

- [ ] **Шаг 1: Найти каждый инлайн `.ck`-снипет**

```bash
grep -n 'import terminal\|import system\|import filesystem\|import events\|import process\|import strings\|terminal\.\|system\.\|filesystem\.\|events\.\|process\.\|strings\.\|stdout\.' \
    modules/compiler/src/test/kotlin -r
```

Составьте чек-лист каждого совпадения.

- [ ] **Шаг 2: Механически переписать каждое**

Для каждого совпадения:
1. Удалить строки `import <builtin>;`.
2. Заменить `<builtin>.<name>` на `<builtin>::<name>`.

Будьте осторожны с доступом к полям типа `event.name` — они остаются `.` (event — это значение, а не builtin-модуль).

- [ ] **Шаг 3: Запустить все тесты компилятора**

```
./gradlew :compiler:test
```

Ожидание: все PASS.

- [ ] **Шаг 4: Коммит**

```bash
git add modules/compiler/src/test
git commit -m "test(compiler): migrate inline .ck snippets to :: and ambient builtins"
```

---

## Задача 8: Мигрировать ROM `.ck`-файлы

**Файлы:**
- Изменить: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/bios.ck`
- Изменить: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/shell.ck`
- Изменить: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/nano.ck`
- Изменить: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/ls.ck`
- Изменить: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/pwd.ck`
- Изменить: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/mkdir.ck`
- Изменить: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/rmdir.ck`

- [ ] **Шаг 1: Найти все существующие ROM-файлы**

```bash
find modules/v1_21_1 -name "*.ck" -not -path "*/build/*"
```

- [ ] **Шаг 2: Для каждого файла**

1. Удалить каждую строку `import <ident>;`.
2. Заменить `<builtin>.<name>` на `<builtin>::<name>`. (Builtins: `terminal`, `stdout`, `filesystem`, `system`, `events`, `process`, `strings`.)
3. Доступ к полям структур (`x.y`, где `x` — локальная переменная типа структуры) оставить как `.`.

- [ ] **Шаг 3: Найти любой «lang generation smoke test» или runtime, загружающий ROM-файлы при компиляции**

```bash
grep -rn "bios.ck\|rom/" modules --include="*.kt" | head
```

Запустите такие тесты:

```
./gradlew :compiler:test :core:test
# плюс любой другой модуль, где парсится ROM
```

Ожидание: все PASS.

- [ ] **Шаг 4: Коммит**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom
git commit -m "feat(rom): migrate ROM programs to :: and implicit builtins"
```

---

## Задача 9: Обновить IDE completion / hover для `::`

**Файлы:**
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt`
- Изменить: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`
- Изменить: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`

- [ ] **Шаг 1: Изучить `SourceTextSupport.moduleMemberPrefix()`**

Найдите хелпер, детектирующий паттерны `module.` (сейчас триггер показа членов модуля в completion). Он использует `.` — расширьте, чтобы распознавал и `::`.

- [ ] **Шаг 2: Написать падающий IDE-тест**

Допишите в `LanguageIdeTest.kt`:

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

- [ ] **Шаг 3: Запустить и убедиться, что падает**

```
./gradlew :compiler:test --tests "*completesBuiltinMembersAfterDoubleColon*"
```

- [ ] **Шаг 4: Обновить `moduleMemberPrefix()`, чтобы принимал `::`**

В `SourceTextSupport.kt` модифицируйте регекс/state-machine, детектирующий `<ident>.`, чтобы матчил и `<ident>::`. Обработайте суффикс единообразно — возвращайте квалификатор и пустой member-prefix, когда курсор стоит сразу после `::`.

- [ ] **Шаг 5: Обновить диспетчер completion в `LanguageIde.kt`**

Убедитесь, что путь, производящий module-member-completions, активируется и когда детектор префикса сообщает о `::`.

- [ ] **Шаг 6: Запустить все IDE-тесты**

```
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageIdeTest"
```

Ожидание: PASS.

- [ ] **Шаг 7: Коммит**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt
git commit -m "feat(ide): trigger module completion after :: as well as ."
```

---

## Задача 10: Обновить документацию языка

**Файлы:**
- Изменить: `docs/LANGUAGE.md`

- [ ] **Шаг 1: Отредактировать секции синтаксиса**

Обновите секции «Syntax» и «Builtin Modules»:

- Удалить примеры `import terminal;`, `import system;` из top-level declarations.
- Добавить новую секцию **Operators**, объясняющую:
  - `::` разрешает имена внутри неймспейса (встроенные модули; алиасы пользовательских импортов в следующей версии).
  - `.` обращается к полям значений-структур.
- Обновить каждый пример: `terminal::println(...)` вместо `terminal.println(...)`.
- Полностью удалить `import` из списка statements/top-level. Добавить заметку: «User-file imports are coming in a future version.»

- [ ] **Шаг 2: Добавить маленький подраздел «Builtins are ambient»**

```markdown
### Built-in Modules Are Ambient

Built-in modules (`terminal`, `system`, `filesystem`, `events`, `process`, `strings`, `stdout`) are always available — there is no `import` needed. Access their members with `::`:

    terminal::println("hi");
    val id = system::deviceId();
```

- [ ] **Шаг 3: Проверить, что документ всё ещё рендерится разумно**

Откройте `docs/LANGUAGE.md` в Markdown-preview (или просканируйте на висящие упоминания `import terminal`).

- [ ] **Шаг 4: Коммит**

```bash
git add docs/LANGUAGE.md
git commit -m "docs(language): document ::, ambient builtins, removal of import <ident>"
```

---

## Задача 11: Финальная верификация

- [ ] **Шаг 1: Полный прогон тестов репо**

```
./gradlew test
```

Ожидание: 100% pass.

- [ ] **Шаг 2: Sanity-grep устаревших паттернов `import terminal` / `terminal.`**

```bash
grep -rnE 'import (terminal|system|filesystem|events|process|strings|stdout) *;' . \
    --include='*.ck' --include='*.kt' --include='*.md'
grep -rnE '\b(terminal|system|filesystem|events|process|strings|stdout)\.[a-zA-Z]' . \
    --include='*.ck'
```

Ожидание: ноль попаданий вне build-артефактов. (Попадания в `build/` — устаревшие артефакты, допустимо; чистите `./gradlew clean` при необходимости.)

- [ ] **Шаг 3: Поставить тег milestone (push не требуется)**

```bash
git tag scope-operator-and-implicit-builtins-complete
```

План A завершён. План B (пользовательские импорты файлов) строится поверх него.
