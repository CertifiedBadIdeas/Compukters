# Plan: Add `else if` and `when` to CKL

Две фичи контроля потока для языка CKL. Никаких новых инструкций VM не нужно — обе конструкции компилируются в существующие `JumpIfFalse`/`Jump`.

---

## Phase 1: `else if`

**1.1 — AST** (`compiler/src/main/kotlin/ck/lang/api/LanguageModel.kt` L66-72)
- Изменить `IfStatement.elseBranch` с `BlockStatement?` на `Statement?` — чтобы else-ветка могла быть как `BlockStatement`, так и вложенным `IfStatement`

**1.2 — Парсер** (`compiler/src/main/kotlin/ck/lang/frontend/LanguageFrontend.kt` L1310)
- В `parseIf()`, после `match(ELSE)`: если следующий токен `IF` → `match(IF)` + рекурсивный `parseIf()`, иначе → `parseBlock()` как раньше

**1.3 — Семантический анализатор** (`compiler/src/main/kotlin/ck/lang/frontend/LanguageFrontend.kt` L296)
- Обработать `elseBranch` двух типов: `BlockStatement` → `analyzeBlock(...)`, `IfStatement` → рекурсивный анализ

**1.4 — Компилятор байткода** (`compiler/src/main/kotlin/ck/lang/frontend/LanguageFrontend.kt` L792)
- Обработать else-ветку: `BlockStatement` → `compileBlock(...)`, `IfStatement` → `compileStatement(...)` рекурсивно

---

## Phase 2: `when` statement

**Синтаксис:**
```
when(subject) {
    1 -> { ... }
    2, 3 -> { ... }
    else -> { ... }
}

when {
    x > 5 -> { ... }
    x < 0 -> { ... }
    else -> { ... }
}
```

> **Важно**: subject оборачивается в скобки `when(subject)`. Это устраняет парсинг-амбигуитет с конструкцией записей: `when Foo { ... }` неотличимо от `RecordConstructionExpression` в `parsePrimary()` (IDENTIFIER + LBRACE → record construction). С `when(subject)` парсер корректно отделяет subject от тела when.

**2.1 — Новые токены** (`compiler/src/main/kotlin/ck/lang/api/TokenKind.kt`)
- Добавить `WHEN` и `ARROW` (`->`)

**2.2 — Новые AST-узлы** (`compiler/src/main/kotlin/ck/lang/api/LanguageModel.kt`)
- `WhenBranch(values: List<Expression>, body: BlockStatement, range)`
- `WhenStatement(subject: Expression?, branches: List<WhenBranch>, elseBranch: BlockStatement?, range) : Statement`

**2.3 — Лексер** (`compiler/src/main/kotlin/ck/lang/frontend/LanguageFrontend.kt` L1107)
- Маппинг `"when" -> TokenKind.WHEN`
- При лексировании `-` (L975): жадная проверка — если `peek() == '>'` → `advance()` + emit `ARROW("->")`; иначе `MINUS("-")` как раньше. Безопасно: последовательность `->` невалидна в текущей грамматике (`MINUS GT` = parse error)

**2.4 — Парсер: `parseWhen()`** (`compiler/src/main/kotlin/ck/lang/frontend/LanguageFrontend.kt` L1261)
- Добавить `match(TokenKind.WHEN) -> parseWhen()` в `parseStatement()`
- Subject парсинг:
  - Если `match(LPAREN)` → `parseExpression()` + `consume(RPAREN)` → subject
  - Иначе → subject = null (subjectless form)
- Тело: `consume(LBRACE)`
- Цикл до `RBRACE`:
  - Если `match(TokenKind.ELSE)` → `consume(ARROW)` + `parseBlock()` → elseBranch; **break**
  - Иначе → парсить выражения через запятую (до `ARROW`), `consume(ARROW)`, `parseBlock()` → WhenBranch
- `consume(RBRACE)`

**2.5 — Семантический анализатор** (`compiler/src/main/kotlin/ck/lang/frontend/LanguageFrontend.kt` L296)
- С subject: проверить тип subject, каждое значение ветки должно быть assignable к типу subject
- Без subject: каждое значение ветки должно быть `Bool`
- Анализ тел всех веток

**2.6 — Компилятор байткода** (`compiler/src/main/kotlin/ck/lang/frontend/LanguageFrontend.kt` L792)

**С subject:**
1. Аллоцировать скрытый локальный слот (имя `$when`, тип из `semantic.expressionTypes`)
2. `compileExpression(subject)` + `StoreLocal(slot)`
3. Для каждой ветки с N значениями:
   - Для каждого значения i: `LoadLocal(slot)` + `compileExpression(value_i)` + `Binary(EQUALS)` → если true → jump к телу
   - Если значение не последнее: `JumpIfFalse` → следующее значение; иначе `JumpIfFalse` → следующая ветка
   - Реализация через OR-цепочку: для мульти-значений сделать jump-to-body при первом совпадении
4. Тело ветки + `Jump` к концу when
5. `elseBranch` как fallthrough в конце
6. Патч всех jump-адресов

**Без subject:**
1. Для каждой ветки: `compileExpression(condition)` + `JumpIfFalse(nextBranch)`
2. Тело ветки + `Jump(end)`
3. `elseBranch` как fallthrough в конце
4. Патч всех jump-адресов

> Скрытый слот `$when` не конфликтует с пользовательскими переменными — `$` невалидный символ в идентификаторах CKL

**2.7 — IDE** (`compiler/src/main/kotlin/ck/lang/frontend/LanguageIde.kt` L148, `compiler/src/main/kotlin/ck/lang/frontend/IdePresentationSupport.kt` L92)
- `"when"` в список `KEYWORDS`
- `TokenKind.WHEN` → `KEYWORD`, `TokenKind.ARROW` → `OPERATOR` в подсветке

---

## Phase 3: Документация и тесты

**3.1** — Обновить `LANGUAGE.md` — добавить `else if` и `when` с примерами

**3.2** — Тесты в `compiler/src/test/kotlin/ck/lang/frontend/LanguageFrontendTest.kt` и `compiler/src/test/kotlin/ck/lang/runtime/LanguageRuntimeTest.kt`: компиляция и выполнение обеих конструкций

---

## Decisions

- `when` — **statement** (не expression), консистентно с текущим `if`
- `when` subject в **скобках**: `when(value) { ... }` — устраняет амбигуитет с record construction
- `when` **без subject**: `when { condition -> { ... } }` — subjectless form
- Множественные значения в одной ветке: `1, 2 -> { ... }`
- Тела веток — **только блоки** (`{ }`), консистентно с `if`/`while`
- `else if` реализуется расширением типа `elseBranch` на `IfStatement` с `BlockStatement?` на `Statement?` (тип шире, но парсер производит только `BlockStatement` или `IfStatement`)
- `->` лексируется жадно как один `ARROW` токен (безопасно: `MINUS GT` невалидна в грамматике)
- Скрытая переменная `$when` для subject — `$` невалиден в пользовательских идентификаторах

## Риски и mitigation

| Риск | Серьёзность | Mitigation |
|------|-------------|------------|
| Расползание сложности языка (ключевой риск из родительского плана) | Высокая | `when` — строго statement, без exhaustiveness, range-паттернов и type-check. Минимальная семантика |
| `->` ломает существующий код с `-` `>` рядом | Низкая | `a - >b` уже невалиден (`MINUS GT` = parse error). Только контактный `->` становится ARROW |
| Парсинг `when Subject { }` — амбигуитет с record construction | Высокая | Решено: subject в скобках `when(subject)` |
| Persistent VM — новые инструкции ломают сериализацию | Нет | Новых instruction нет. Всё на `JumpIfFalse`/`Jump`/`StoreLocal`/`LoadLocal`/`Binary(EQUALS)` |
| Скрытая переменная `$when` влияет на snapshot | Низкая | Это обычный локальный слот, уже поддерживается VM-сериализацией |
| IDE не знает про новые конструкции | Низкая | Добавляем WHEN + ARROW в highlighting, `when` в completion. Frontend един для IDE и компилятора |

## Scope

- **Включено**: `else if`, `when` (обе формы), множественные значения, `else`-ветка, IDE, тесты, доки
- **Исключено**: `when` как expression, range-паттерны (`in 1..10`), type-check (`is Type`), проверка exhaustiveness

## Verification

1. `./gradlew :compiler:test` — все тесты проходят
2. `./gradlew :compiler:detekt :compiler:ktlintCheck` — без ошибок линтера
3. Ручная проверка в игре: `.ck` программа с `else if` и `when`
4. IDE: автодополнение `when`, подсветка `when` и `->`

## Relevant files

- `compiler/src/main/kotlin/ck/lang/api/TokenKind.kt` — add WHEN, ARROW tokens
- `compiler/src/main/kotlin/ck/lang/api/LanguageModel.kt` — modify IfStatement.elseBranch type; add WhenStatement, WhenBranch
- `compiler/src/main/kotlin/ck/lang/frontend/LanguageFrontend.kt` — lexer (WHEN keyword, -> arrow), parser (parseIf change, new parseWhen), semantic analyzer (else-if + when), bytecode compiler (else-if + when)
- `compiler/src/main/kotlin/ck/lang/frontend/LanguageIde.kt` — KEYWORDS list
- `compiler/src/main/kotlin/ck/lang/frontend/IdePresentationSupport.kt` — syntax highlighting for WHEN, ARROW
- `LANGUAGE.md` — documentation
- `compiler/src/test/kotlin/ck/lang/frontend/LanguageFrontendTest.kt` — compilation tests
- `compiler/src/test/kotlin/ck/lang/runtime/LanguageRuntimeTest.kt` — execution tests
