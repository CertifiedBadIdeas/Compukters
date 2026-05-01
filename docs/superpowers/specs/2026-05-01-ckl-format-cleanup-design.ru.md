# Дизайн автоформатирования и auto cleanup для CKL

## Контекст

В CKL уже есть lexer, parser, semantic analyzer, bytecode compiler и IDE-сервисы для diagnostics, completion, hover и definitions. Formatter и cleanup API для `.ck` файлов пока нет.

Этот дизайн относится только к CKL. Он не меняет форматирование Kotlin/KTS проекта и не меняет repository-wide ktlint поведение.

## Цели

- Добавить deterministic CKL document formatter.
- Добавить CKL cleanup операции на той же formatter pipeline.
- Сохранять комментарии.
- Экспортировать formatter и cleanup через существующий IDE service layer.
- Сделать MVP безопасным: invalid или incomplete source не переписывается.
- Сделать formatter идемпотентным.

## Не цели

- Форматирование Kotlin, Gradle, Markdown, TOML или других файлов репозитория.
- Best-effort форматирование синтаксически невалидного CKL.
- Semantic refactorings, rename или перемещение кода.
- Изменение порядка declarations, statements или evaluation order expressions.
- Полная language-server protocol интеграция в рамках этой фичи; фича даёт backend/host API, который UI сможет вызвать.

## Пользовательское поведение

### Format Document

Для валидного CKL документа Format Document возвращает один full-document `TextEdit`, если canonical source отличается от входа. Если source уже canonical, edits пустые.

Canonical style:

- 4 пробела на уровень indentation.
- Imports печатаются перед top-level declarations.
- Пустая строка между import block и declarations.
- Пустая строка между top-level declarations.
- Пробелы вокруг binary operators.
- Пробел после запятых.
- Block constructs используют стиль, уже привычный для CKL: `fun name(...) { ... }`, `if (...) { ... }`, `class Name(...) { ... }`.
- Constructor calls остаются named call-style, например `Vec2(x = 1, y = 2)`.
- Formatter идемпотентен: повторное форматирование formatted source не даёт новых edits.

### Cleanup Document

Cleanup использует formatter плюс import organization.

Он должен:

- переносить все imports в начало файла,
- сортировать imports по source text,
- сортировать selective import items внутри `{ ... }`,
- объединять duplicate selective imports из одного source,
- удалять unused selective import items, когда analysis доказывает, что они не используются,
- удалять unused namespace alias только когда analysis доказывает, что alias не используется,
- сохранять imports, если analysis неоднозначен или содержит ошибки.

Cleanup не должен менять runtime semantics.

## Комментарии

MVP должен сохранять комментарии.

Сейчас lexer пропускает line comments и block comments. Formatter должен получать comment trivia из parsed source, поэтому lexer/parser pipeline нужно расширить: собирать комментарии отдельно от обычных tokens.

Добавить модель `CommentTrivia`:

- text,
- kind (`LINE` или `BLOCK`),
- source range.

`ParsedSource` должен включать comments. Parser почти не меняется: comments — это trivia, а не syntax nodes.

Formatter привязывает comments к ближайшему syntax по source positions:

- leading comments перед declaration/statement/expression печатаются перед этим construct с indentation construct,
- inline trailing comments остаются в конце соответствующей source line, если association однозначен,
- block comments сохраняют text и получают consistent indentation,
- ambiguous comments сохраняются рядом с исходной относительной позицией, а не удаляются.

Точные исходные whitespace вокруг comments не сохраняются. Они нормализуются к canonical formatter whitespace.

## Поведение на invalid source

Если вход содержит lexer или parser errors, Format Document и Cleanup Document возвращают no edits и diagnostic/status о том, что source нельзя форматировать с syntax errors.

Примеры, которые должны вернуть no edits:

- unterminated string literal,
- unterminated block comment,
- missing closing brace,
- partial expression вроде `val x =`.

Cleanup также возвращает no edits, если semantic analysis имеет ERROR diagnostics, потому что решения об unused imports в таком состоянии ненадёжны.

## Архитектура

### Formatter service

Добавляем compiler/frontend service, рабочее имя — `LanguageFormatter`.

Responsibilities:

- parse input через `ParserFacade`,
- reject invalid input,
- render AST в canonical CKL source,
- preserve comments через comment trivia,
- return `TextEdit` results без мутации файлов.

Предлагаемый API:

```kotlin
data class FormatOptions(
    val cleanup: Boolean = false,
)

data class FormatResult(
    val edits: List<TextEdit>,
    val diagnostics: List<Diagnostic> = emptyList(),
    val changed: Boolean = edits.isNotEmpty(),
)

class LanguageFormatter(...) {
    fun formatDocument(name: String, source: String): FormatResult
    fun cleanupDocument(name: String, source: String, loader: SourceLoader = NoOpSourceLoader): FormatResult
}
```

Точные имена можно адаптировать при реализации под существующий style.

### IDE facade

Расширяем `IdeFacade` и `LanguageIde` formatting methods:

- `formatDocument(name, source): FormatResult`,
- `cleanupDocument(name, source, loader/sourceIndex as needed): FormatResult`.

Formatter должен использовать существующую runtime model `TextEdit`, которая уже применяется в completions.

### Device IDE host

Расширяем device IDE API request/response типами для format и cleanup:

- `DeviceFormatRequest`, `DeviceFormatResponse`,
- `DeviceCleanupRequest`, `DeviceCleanupResponse`.

Расширяем `DeviceIdeHost`, `WorkspaceDeviceIdeHost` и workbench gateway plumbing, чтобы UI code мог вызвать backend formatter и cleanup commands.

### Import cleanup metadata

Unused import cleanup требует надёжного import-to-symbol tracking.

Semantic analyzer должен записывать, какой imported symbol создан каким `ImportItem`. Cleanup может удалить selective item только если:

- imported item создал symbol,
- нет references к этому symbol вне import declaration,
- semantic analysis не содержит ERROR diagnostics.

Если хоть одно условие не выполнено, cleanup оставляет import.

## Formatter rendering model

Formatter должен render-ить из AST, а не патчить произвольные token whitespace.

Предлагаемая внутренняя структура:

- `CklWriter` с helpers для indentation, line, blank-line и tokens,
- render functions для declarations, statements, expressions, type syntax, imports и comments,
- helpers для precedence-aware expression printing, чтобы сохранять нужные parentheses.

Rendering должен покрыть весь текущий CKL syntax:

- imports и aliases,
- functions и parameters,
- structs,
- classes, constructors, fields, `init`, instance methods, static methods,
- variable declarations и assignments,
- member assignments,
- `if` / `else if` / `else`,
- `while`,
- `when`,
- `return`,
- calls, named arguments, scope access, member access, `this`, literals, unary и binary expressions.

## Стратегия тестирования

Добавить focused tests в compiler module.

Formatter tests:

- formats messy functions,
- formats structs and class declarations,
- formats `if`, `while`, and `when`,
- formats named constructor calls,
- preserves leading, trailing, inline, and block comments,
- is idempotent.

Cleanup tests:

- sorts imports,
- merges duplicate selective imports,
- removes unused selected items,
- preserves used function, struct, and class imports,
- preserves imports when semantic analysis has errors,
- safely handles namespace aliases.

API tests:

- `LanguageIde.formatDocument` returns expected edit,
- `LanguageIde.cleanupDocument` returns expected edit,
- device/workbench host methods pass through edits unchanged.

Verification commands:

- `./gradlew :compiler:test`,
- `./gradlew test`.

## Rollout notes

Реализацию лучше вести небольшими фазами:

1. comment trivia support в lexer/parser pipeline,
2. pure formatter и formatter tests,
3. cleanup import metadata и cleanup tests,
4. IDE facade и device/workbench API wiring,
5. documentation и final verification.
