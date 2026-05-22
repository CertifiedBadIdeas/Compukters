# TODOs (inbox)

Free-form dump of ideas and wishes for the project. Structure is loose on
purpose — the goal is to lose nothing.

Conventions:

- Each idea is a level-2 section: `## YYYY-MM-DD — short name`.
- When an idea is promoted into [ROADMAP.md](ROADMAP.md), add a line right
  under its heading: `→ ROADMAP: R-NNN`.
- Implemented or rejected ideas stay here; mark them inline with
  **Реализовано.** / **Отклонено.** and a one-line reason.
- Append new sections at the end. Do not reorder existing sections.

## 2026-05-22 — Глубокая интеграция с модом Create

Глубокая интеграция с модом Create.

## 2026-05-22 — Capability-проверка программы по imports

На основе import можно определять, можно ли вообще запустить программу на этом
компьютере, возможно сделать какие-то запросы capability, peripheral, по
imports по сути можно однозначно понять от чего программа зависит. Зависеть
она может от инвентаря например, который есть только у черепашки.

## 2026-05-22 — Черепашка со своим набором builtins

Сделать черепашку со своим набором builtins — У черепашки появляется Fuel и
Inventory. Можно сделать флаг конечно, черепашка это или нет, но даже не знаю
надо ли.

## 2026-05-22 — Расширение системы import (peripheral, скрипты, dependency-проверка)

Сделать расширить систему import, добавив peripheral, возможно какой-то
обозреватель import чтобы можно было узнать вообще какие peripheral вообще
доступны. Так же сделать систему import файлов скриптов, чтобы можно было на
них ссылаться, и тогда по сути проверка файла на возможность запуска будет ещё
дополнена рекурсивной проверкой всех dependency файлов.

## 2026-05-22 — Внешняя и внутренняя сеть между компьютерами

Внешняя и внутренняя сеть между компьютерами, внутренняя сеть по сути своей
должна представлять систему broadcast channels, с id канала (не компьютера,
компьютеры не должны иметь возможность сообщатся просто напрямую).

## 2026-05-22 — git cli клиент

git cli клиент.

## 2026-05-22 — Связь между компьютерами: модемы и вышка

Сделать связь между компьютерами.

1. Сделать модемы — радиомодули или лазерные (если в космос например хахахаха).
2. Для модемов должна быть вышка, которая обслуживает эти модемы и обеспечивает
   связь между компьютерами.

## 2026-04-16 — Workbench как отдельный Authoring Station

~~Сделать workbench (компьютерный стол) где можно будет программировать
компьютеры.~~ **Реализовано.** Workbench выделен в отдельный Authoring Station,
описан в:

- `docs/superpowers/specs/2026-04-16-workbench-separate-entity-design.md` —
  изначальный дизайн отдельной сущности.
- `docs/superpowers/specs/2026-04-30-device-authoring-domain-model-design.md` —
  двухкатегорийная модель (Runtime Devices vs Authoring Stations).
- `docs/ARCHITECTURE.md` (раздел Domain Model) — формальное закрепление в
  архитектуре.

## 2026-05-22 — Workbench IDE: clipboard и выделение (отложено после переписывания UI на DSL)

- **Clipboard API для CodeEditor.** Сейчас Ctrl+X/C/V в редакторе кода не работают — модель `EditorViewModel` поддерживает `selection`, но клавиатурные сочетания не подключены. Для полноценной IDE надо вынести clipboard-операции в отдельный интерфейс (доступ к системному буферу через Minecraft API) и обработать их в `CodeEditor` поверх текущей логики.
- **Shift+Arrow и выделение текста.** `EditorViewModel.selection: SelectionRange?` уже зарезервировано, но никем не выставляется. Нужно: расширить `WorkbenchStore` действиями `extendSelection*`, отрисовать прямоугольник выделения в `CodeEditor` (внутри ScrollArea), сделать `Backspace`/`Delete`/`Tab`/printable consume selection.

## 2026-05-22 — CKL: библиотеки и общие утилиты

- Текущая import-модель уже удачная для маленьких библиотек: есть selective imports (
  `import "lib/math.ck" { add, Vec2 }`) и namespace aliases (`import "lib/math.ck" as math` + `math::add(...)`). Это
  лучше, чем неявное засорение global scope, и ближе по духу к Rust/Kotlin.
- IDE-часть хорошо располагает к использованию библиотек: auto-import умеет предлагать user-file
  functions/classes/structs и добавлять/обновлять import groups; formatter/cleanup сортируют imports и могут убирать
  неиспользованные selective import items.
- Главный пробел для авторов библиотек — нет visibility/API boundary. Каждый top-level `fun`, `struct`, `class`
  импортируемого файла публичен, а в классах нет `private`. Из-за этого internal helpers нельзя спрятать, и пользователи
  могут начать зависеть от деталей реализации.
- Второй важный пробел — нет re-export/facade-модулей. Imports не транзитивны, а файл экспортирует только собственные
  top-level declarations. Нельзя сделать удобный `lib/math.ck`, который переэкспортирует `vector.ck`, `angle.ck`,
  `clamp.ck`; пользователю приходится знать внутреннюю структуру библиотеки.
- Нет generics, interfaces/traits, inheritance и reflection. Это нормально для deterministic sandbox v1, но сильно
  ограничивает reusable abstractions: нельзя выразить `Result<T, E>`, `List<T>`, `Parser<T>`, contracts/protocols или
  generic helpers.
- Нет overloads, default arguments и named arguments для обычных функций. Для библиотечных API это ведёт к разрастанию
  имён вроде `readText`, `readTextOrDefault`, `readTextFrom`, вместо компактного и читаемого вызова.
- Ошибки пока плохо моделируются на уровне языка: host call failures превращаются в runtime error/crash, а в CKL нет
  `try/catch` и generic `Result`. Для библиотек остаются status codes, `Bool`, `String`-ошибки или вручную
  продублированные result-structs.
- Приоритетный путь развития библиотечности: сначала добавить explicit exports/visibility, затем re-export/facade files,
  top-level constants, named/default args для функций и базовую модель ошибок. Generics/traits лучше делать позже,
  потому что это более дорогой скачок для type system, bytecode, VM и IDE.

Я посмотрел документацию, модель языка, фронтенд и IDE-тесты. Код не менял и тесты не запускал — это статический обзор
текущего состояния.

### Короткий вердикт

CKL уже неплохо располагает к **использованию маленьких библиотек/утилит**: импорт явный, есть selective imports,
alias-namespace через `::`, автоимпорт в IDE, cleanup импортов, статическая типизация, `struct`/`class`.

Но CKL пока слабо подходит для **разработки крупных библиотек с устойчивым API**: нет `private`/`public`-контроля, нет
re-export/facade-модулей, нет generics, interfaces/traits, overload/default args, нормального моделирования ошибок и
пакетной организации. По ощущению это сейчас ближе к “маленький безопасный Lua/KotlinScript для in-game computer”, чем к
Rust/Kotlin/C++ как библиотечным платформам.

Моя оценка:

- **Использование простых утилит:** 7/10.
- **Авторство маленьких библиотек:** 5/10.
- **Авторство переиспользуемых абстрактных библиотек:** 2–3/10.
- **IDE-удобство относительно масштаба языка:** 8/10.

### Что уже хорошо для библиотек

#### 1. Импорт-модель уже правильная по направлению

CKL поддерживает два удобных способа потребления файла:

- selective import: `import "lib/math.ck" { add, Vec2 }`
- namespace alias: `import "lib/math.ck" as m`, дальше `m::add(...)`, `m::Vec2`

Это описано в LANGUAGE.md, а AST-модель импорта прямо разделяет `ImportSource` и `ImportMode` в ImportDeclaration.kt.

Это хороший выбор: он ближе к Rust/Kotlin, чем к C++ include-хаосу. Пользователь видит, откуда берётся имя, а alias даёт
нормальную защиту от конфликтов.

#### 2. Потребление библиотек довольно удобное в IDE

IDE умеет предлагать функции/типы из других файлов и добавлять import edit автоматически. Реализация auto-import для
user files находится в LanguageIde.kt, а вставка/обновление группы imports — в SourceTextSupport.kt.

Это подтверждено тестами:

- auto-import builtin function: LanguageIdeTest.kt
- auto-import user function: LanguageIdeTest.kt
- auto-import user class: LanguageIdeTest.kt

Для маленького in-game языка это очень сильная сторона. По UX это уже ближе к Kotlin/Rust IDE, чем к классическим
embedded scripting-языкам.

#### 3. Алиасы хорошо решают конфликты имён

Импорт двух файлов с одинаковыми `helper()` не ломается, если использовать aliases. Компилятор ещё и mangles имена в
байткоде как `canonical#name`, см. `mangle()` в LanguageFrontend.kt. Это проверяется в UserFileImportsTest.kt.

Для библиотечного кода это важно: можно иметь `math::clamp`, `ui::clamp`, `string::trim` без глобальной каши.

#### 4. Есть базовые building blocks для API

В языке есть:

- top-level `fun`, `struct`, `class`: LANGUAGE.md
- value-like `struct`: LANGUAGE.md
- reference-like `class` с fields, `init`, instance/static methods: LANGUAGE.md
- nullable type syntax в `TypeSyntax`: LanguageModel.kt

То есть писать простые библиотечные API уже можно: математика, строки, shell helpers, UI helpers, wrappers над
filesystem/process.

### Главные проблемы

#### 1. Нет инкапсуляции

Самая большая библиотечная проблема: каждый top-level `fun`, `struct`, `class` импортируемого файла является public, это
прямо указано в LANGUAGE.md. В классах тоже нет private members; ограничения v1 перечислены в LANGUAGE.md.

Практический эффект:

- нельзя спрятать `internalNormalizePath()`;
- нельзя отделить API от implementation detail;
- нельзя безопасно рефакторить внутренние функции;
- пользователи начнут зависеть от того, что библиотека не хотела экспортировать.

В Rust/Kotlin это решается `pub`/`private`/`internal`. В C++ — `private`, anonymous namespace, header/source split. В
CKL сейчас только naming convention: `_internalFoo`, `internal/foo.ck`.

#### 2. Нет re-export / facade-модулей

Imports are not transitive, это описано в LANGUAGE.md, и тестируется в UserFileImportsTest.kt.

Более того, `ModuleExports` собирает exports только из top-level declarations самого файла — `FunctionDeclaration`,
`StructDeclaration`, `ClassDeclaration` — см. LanguageFrontend.kt. Импортированные имена не становятся exports файла.

Это значит, что нельзя сделать нормальный `lib/prelude.ck` или `lib/math.ck`, который re-export-ит `vector.ck`,
`clamp.ck`, `random.ck`. Пользователь будет вынужден импортировать много конкретных файлов.

Для удобства использования библиотек это, вероятно, проблема №2 после visibility.

#### 3. Нет generics / traits / interfaces

Ограничение зафиксировано в LANGUAGE.md, а для классов явно сказано, что inheritance, interfaces, generics и private
members не входят в v1: LANGUAGE.md.

Это резко ограничивает библиотечные абстракции:

- нельзя `List<T>`;
- нельзя `Result<T, E>`;
- нельзя `Parser<T>`;
- нельзя `Comparator<T>`;
- нельзя `interface Drawable`;
- нельзя trait-like extension API;
- нельзя написать generic utility вроде `min<T>` или `map<T, R>`.

Для small scripts это терпимо. Для библиотек в духе Rust/Kotlin — главный потолок.

#### 4. Нет перегрузки пользовательских функций, default args и named args для обычных функций

Пользовательские функции с одинаковым именем конфликтуют: диагностика `Redeclaration of function` в LanguageFrontend.kt.
Named arguments разрешены только для constructors: LanguageFrontend.kt, а constructors обязаны использовать named args:
LanguageFrontend.kt.

Это делает API более шумным:

- вместо overloads надо `readText()`, `readTextOrDefault()`, `readTextFrom()`;
- без default args растёт число wrapper-функций;
- без named args для функций хуже читаются bool/int-heavy вызовы.

Kotlin тут сильно удобнее. Rust частично решает builder pattern, но в CKL без generics/traits builder тоже ограничен.

#### 5. Ошибки пока плохо моделируются на уровне языка

Host call errors превращаются в runtime error и могут привести к `Crashed(...)`, см. MACHINE.md. На уровне языка нет
`try/catch`, нет `Result<T,E>`, нет generic ADT.

Из-за этого библиотекам сложно давать безопасный API. Сейчас реалистичные паттерны:

- возвращать `Bool`;
- возвращать `Int` status code;
- возвращать `String` с ошибкой;
- делать custom `struct OperationResult { ok: Bool, value: String, error: String }`.

Но без generics это быстро превращается в дублирование: `StringResult`, `IntResult`, `PathResult`, etc.

### Сравнение с C++ / Kotlin / Rust

| Область                    | CKL сейчас |                 Kotlin |                    Rust |                    C++ |
|----------------------------|-----------:|-----------------------:|------------------------:|-----------------------:|
| Явные imports              |     Хорошо |                Отлично |                 Отлично |           Слабо/средне |
| Namespace alias            |     Хорошо |                 Средне |                  Хорошо |                Отлично |
| Auto-import IDE            |     Хорошо |                Отлично |                  Хорошо |         Зависит от IDE |
| Encapsulation              |      Плохо |                Отлично |                 Отлично |                 Хорошо |
| Generics/templates         |        Нет |                Отлично |                 Отлично |                Отлично |
| Traits/interfaces          |        Нет |                Отлично |                 Отлично | Через virtual/concepts |
| Re-export/facade           |        Нет |  Есть packages/imports | Отлично через `pub use` |          Через headers |
| Error modeling             |      Слабо | Exceptions/Result libs |                 Отлично |               Смешанно |
| Маленькие утилиты          |     Хорошо |                Отлично |                 Отлично |                 Хорошо |
| Большие reusable libraries |      Слабо |                Отлично |                 Отлично |                 Хорошо |

CKL сейчас наиболее похож не на C++/Kotlin/Rust, а на **простую статически типизированную Lua-подобную среду с хорошей
IDE**.

### Насколько язык располагает к использованию библиотек

#### Для пользователя библиотеки

Плюсы:

- легко увидеть источник символа;
- можно выбрать direct import или namespace alias;
- IDE умеет auto-import;
- cleanup умеет удалять unused selective imports: LanguageFormatterTest.kt;
- aliases защищают от конфликтов;
- built-in modules ambient и доступны через `::`: LANGUAGE.md.

Минусы:

- без re-export пользователю придётся знать внутреннюю файловую структуру библиотеки;
- без package root imports пути могут стать шумными;
- без default args/overloads API будет многословнее;
- без error model пользователь вынужден читать документацию о sentinel values.

Итого: **для маленьких библиотек удобно; для “настоящих пакетов” пока не хватает слоя package/facade/API boundary.**

#### Для автора библиотеки

Плюсы:

- можно быстро вынести общий код в файл;
- можно экспортировать функции, structs, classes;
- bytecode mangling защищает от внутренних коллизий между файлами;
- IDE/formatter помогают поддерживать imports.

Минусы:

- невозможно скрыть internal helpers;
- невозможно re-export-ить под красивым публичным фасадом;
- невозможно сделать generic data structures;
- невозможно задать контракты через interfaces/traits;
- трудно выразить fallible APIs;
- нет constants/top-level values;
- нет namespace внутри файла, кроме имени файла/alias.

Итого: **авторство утилит нормальное, авторство библиотек с устойчивым API пока слабое.**

### Что я бы улучшал в первую очередь

#### 1. Visibility / explicit exports

Самый высокий ROI. Даже минимальный вариант:

- `private fun helper()`
- `pub fun parsePath(...)`
- или наоборот: всё private по умолчанию, export только через `export { ... }`

Для CKL, возможно, лучше не Rust-style глобально, а простой файл-уровневый механизм:

- top-level declarations private by default;
- `export fun`, `export struct`, `export class`;
- imported file exposes only `export` declarations.

Это сразу сделает библиотеки нормальными.

#### 2. Re-export / facade files

Нужен способ сделать “публичный вход” библиотеки:

- `export "vector.ck" { Vec2, add }`
- или `import "vector.ck" { Vec2, add }; export { Vec2, add }`
- или `pub use "vector.ck" { Vec2, add }` в стиле Rust.

Это критично для UX. Пользователь должен импортировать `lib/math.ck`, а не знать `lib/math/vector.ck`,
`lib/math/clamp.ck`, `lib/math/angle.ck`.

#### 3. Top-level constants

Для утилит очень не хватает:

- `const PI: Int = ...`
- `const ROOT: String = "/bin"`

Сейчас всё надо оборачивать в `fun pi(): Int`, что плохо для читаемости API.

#### 4. Default args или named args для обычных функций

Не обязательно делать overloads сразу. Более простой и полезный шаг:

- разрешить named args для обычных functions;
- потом добавить default values.

Это сильно улучшит ergonomics библиотек без сложного type-system скачка.

#### 5. Небольшой error model до generics

Полные generics — большой шаг. Но можно раньше добавить стандартный паттерн:

- builtin `ResultString`, `ResultInt`, `ResultUnit`;
- или language-level `result { ok, value, error }`;
- или хотя бы convention в stdlib.

Если позже появятся generics, это можно заменить на `Result<T, E>`.

#### 6. Только потом generics / traits

Generics и traits дадут самый большой скачок к Rust/Kotlin, но это и самый дорогой шаг для VM, байткода, IDE,
diagnostics и sandbox guarantees.

Я бы не начинал с них. Сначала visibility + re-export + constants + named/default args дадут гораздо больше удобства на
единицу сложности.

### Итог

CKL уже хорошо спроектирован для **явного и IDE-friendly подключения маленьких `.ck`-утилит**. Selective imports +
aliases + auto-import — это сильная база, и она гораздо лучше, чем “просто подключить файл и засорить global scope”.

Но как язык для библиотек CKL пока упирается не в imports, а в отсутствие **API boundaries и abstraction tools**. Если
цель — приблизиться к ощущениям Kotlin/Rust/C++ именно в библиотечном коде, то первый большой скачок должен быть не
generics, а:

1. explicit visibility/exports;
2. re-export/facade modules;
3. top-level constants;
4. named/default args для функций;
5. базовая модель ошибок.
