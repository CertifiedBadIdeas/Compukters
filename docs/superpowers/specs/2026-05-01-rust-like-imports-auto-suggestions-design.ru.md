# Дизайн Rust-like selective imports и auto suggestions

## Контекст

Сейчас в CKL есть ambient built-in namespaces, например `terminal`, доступные через `::`, и user-file imports в двух формах: flat (`import "math.ck";`) и namespaced (`import "math.ck" as math;`). Этот дизайн удаляет flat imports и добавляет явные selective imports вместе с IDE auto-import suggestions.

Цель — разрешить короткие unqualified calls вроде `println("hi")`, но не делать все exports файла видимыми автоматически. Имя становится unqualified только если оно явно перечислено в import group.

## Цели

- Сохранить `terminal::println("hi")` без import.
- Добавить `import terminal { println };`, чтобы `println("hi")` был валиден в текущем файле.
- Добавить `import "lib/math.ck" { add, Vec2 };`, чтобы видимыми стали только перечисленные exports файла.
- Сохранить `import "lib/math.ck" as math;` для namespace-доступа через `math::name`.
- Запретить flat `import "lib/math.ck";`, чтобы случайно не импортировать всё.
- Показывать в completion источник результата справа: namespace или путь файла.
- При выборе importable completion вставлять локальное имя и добавлять или обновлять нужный import.

## Не цели

- В этой фазе нет rename imports (`println as print`).
- Нет nested Rust import trees.
- Нет wildcard imports.
- Нет transitive import visibility.

## Синтаксис и семантика

Поддерживаемые формы imports:

```ck
import terminal { println, clear };
import "lib/math.ck" { add, Vec2 };
import "lib/math.ck" as math;
```

Невалидные формы:

```ck
import terminal;
import "lib/math.ck";
```

Built-in namespaces остаются ambient. `terminal::println("hi")` работает даже без import. Selective built-in import только добавляет перечисленные members как unqualified symbols в текущий файл.

Selective imports из user files загружают и анализируют целевой файл, затем регистрируют только перечисленные top-level `fun` и `struct` exports. Imports остаются non-transitive: импорт `a.ck` не раскрывает то, что импортирует сам `a.ck`.

Конфликты между local declarations, selective imports, aliases и built-in namespaces дают redeclaration/conflict diagnostics. Неизвестные выбранные имена дают diagnostics с указанием source и отсутствующего member.

## AST и resolver model

`ImportDeclaration` стоит представить как source + mode:

- `ImportSource.BuiltinNamespace(name, range)` для sources вроде `terminal`.
- `ImportSource.FilePath(path, range)` для sources вроде `"lib/math.ck"`.
- `ImportMode.Namespace(alias)` для `as math`.
- `ImportMode.Selective(items)` для `{ println, clear }`.
- `ImportItem(name, range)` для каждого выбранного member.

Такая форма оставляет feature простой, но даёт естественное место для будущих `as` renames.

Resolver регистрирует selective imports как обычные visible symbols, которые указывают на исходный binding. Вызов `println()` после `import terminal { println };` резолвится в тот же built-in function binding, что и `terminal::println()`.

Для file imports существующий `SourceLoader` продолжает загружать конкретные файлы. Analyzer должен переиспользовать текущий canonical-path cache, чтобы один и тот же `.ck` файл парсился и анализировался максимум один раз за compilation.

## Completion и auto-import behavior

Completion results делятся на две категории:

1. Symbols, уже видимые в scope. Их применение только заменяет typed prefix на `label` или `insertText`.
2. Importable candidates из built-in namespaces или workspace `.ck` files. Их применение также добавляет или обновляет import group.

Примеры:

- При наборе `pri` предлагается `println`, а справа указан source `terminal`.
- Применение item вставляет `println()` и добавляет `import terminal { println };`, если его ещё нет.
- Если уже есть `import terminal { clear };`, применение `println` обновляет его до `import terminal { clear, println };`.
- User-file candidates показывают source path, например `lib/math.ck`, и добавляют `import "lib/math.ck" { add };`.

`CompletionItem` нужно расширить metadata:

- `sourceNamespace: String?` для текста справа в UI.
- `additionalTextEdits: List<TextEdit>` или специальное поле import edit для auto-import changes.
- Опционально priority/sort metadata, чтобы already-visible symbols были выше importable candidates.

Workbench completion row должен показывать label слева и `sourceNamespace` справа muted color. Применение completion должно atomically применять все edits через существующий local edit / CRDT path и сохранять cursor placement для function calls.

Если importable candidate конфликтует с local symbol, первая реализация должна скрывать такой candidate, а не вставлять код, который сразу создаёт diagnostic.

## Workspace source index

Auto-import suggestions для user files требуют discovery шире текущего import graph. Рядом с `SourceLoader` нужен lightweight source index capability:

- перечислить `.ck` файлы, видимые из текущего workspace;
- читать sources для indexing;
- отдавать top-level `fun` и `struct` exports для completion.

`MapSourceLoader` в тестах может строить index из map keys. Production device workspace implementation может перечислять workspace documents. Compiler остаётся deterministic, потому что imports всё ещё требуют явных source paths; широкий index используется только IDE suggestions.

Для отзывчивости первая версия index может быть parse-level: собрать top-level declarations и использовать доступный syntax для detail text. Ошибочные файлы не должны ломать completion текущего файла; invalid exports можно пропускать или сохранять partial results, если это безопасно.

## План тестирования

- Parser принимает selective built-in и file imports.
- Parser/analyzer отвергает flat imports и bare built-in imports.
- Resolver принимает `println("x")` после `import terminal { println };`.
- Resolver сохраняет `terminal::println("x")` без imports.
- Resolver отвергает non-selected names, например `clear()` после import только `println`.
- Selected user-file functions и structs видимы; non-selected exports не видимы.
- Conflicting selective imports дают diagnostics.
- Runtime tests исполняют selective imported built-in calls и user-file functions.
- IDE tests проверяют right-side namespace/source metadata.
- IDE/workbench tests проверяют auto-import insertion и update behavior.
- UI tests проверяют rendering label и source text в completion rows.

## Documentation и migration

`docs/LANGUAGE.md` должен удалить flat import examples и задокументировать:

- qualified built-in usage через `terminal::println()`;
- selective built-in imports через `import terminal { println };`;
- selective file imports через `import "math.ck" { add };`;
- namespace file imports через `import "math.ck" as math;`.

Существующий CKL code с `import "file.ck";` нужно мигрировать либо на selective import list, либо на namespace alias.