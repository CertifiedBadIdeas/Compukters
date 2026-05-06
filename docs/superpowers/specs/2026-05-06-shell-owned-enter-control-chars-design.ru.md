# Дизайн Shell-Owned Enter Echo и Control Characters

## Цель

Исправить поведение пустого Enter в ROM shell/terminal и сделать visible line commits ответственностью программы, а не terminal.

## Проблема

`terminal.ck` сейчас на Enter отправляет текущую input line в stdin и локально добавляет `line + "\n"` в display buffer. Если строка пустая, это превращается в terminal-owned голый newline. Рядом с нижней строкой это может проскроллить prompt до того, как shell output сможет владеть результатом.

`shell.ck` сейчас игнорирует blank lines через `yield()`, поэтому видимый эффект пустого Enter в основном становится terminal-side cursor movement, а не shell-owned command handling.

## Область

- Меняем ROM behavior в `rom/terminal.ck` и `rom/shell.ck`.
- Добавляем CKL string literal support для `\r` и formatter preservation для `\r`/`\b`, чтобы программы могли явно писать эти control characters.
- Копируем bundled `.ck` resources raw во время Gradle resource generation, чтобы template expansion не превращал escape sequences в raw control bytes.
- Добавляем source-level regressions в `RomScriptCompileTest.kt`.
- Добавляем базовую поддержку terminal control characters в `appendText()` для `\n`, `\r` и `\b`.
- Не добавляем CKL bitwise operators и numeric glyph masks в этом этапе.
- Не вводим `stdio-v2` или ANSI/vt100 escape parsing в этом этапе.

## Архитектура

Terminal остаётся ответственным за interactive input overlay до Enter. На Enter он отправляет текущую строку через stdin и очищает pending input state, но не делает локальный commit `line + "\n"`.

Shell становится ответственным за commit введённой строки в visible output. Сразу после `readLine(ctx)` shell пишет `line + "\n"` в stdout, затем обрабатывает trimmed command. Blank input остаётся no-op command, но newline теперь является shell-owned visible output, а следующий prompt выводится обычным shell loop.

`terminal.ck` обрабатывает output control characters как часть text rendering:

- `\n`: перейти в column 0 на следующей строке, со scroll при необходимости.
- `\r`: перейти в column 0 на текущей строке без смены row.
- `\b`: перейти на одну column left, очистить эту cell в buffer/display и оставить cursor там.

CKL lexer распознаёт `\r` как carriage return в string literals. Formatter выводит carriage return и backspace как escaped text, а не вставляет raw control bytes в formatted source.

Build resource generation не должен запускать Groovy template expansion на `.ck` files. CKL source files — это program text, а не metadata templates; expansion может испортить string escapes вроде `\r`, `\b` и `\n` до того, как их увидит CKL lexer.

## Будущая работа

Numeric glyph masks — отдельная language/runtime задача. Для неё нужны CKL bitwise operators и соответствующие lexer, parser, semantic analysis, bytecode/runtime execution, formatter/docs, а также downstream core ROM estimator updates. После этого ROM glyphs можно перевести с row-major string masks на numeric row masks.

## Тестирование

Добавляем regressions, которые проверяют:

- `terminal.ck` больше не делает local commit `line + "\n"` на Enter.
- `shell.ck` echo-ит `line + "\n"` сразу после `readLine(ctx)`.
- `terminal.ck` обрабатывает `\r` и `\b` в `appendText()`.
- CKL lexes `\r` как carriage return, а formatter сохраняет `\r`/`\b` escapes.
- Processed ROM resources сохраняют textual CKL escapes вместо raw expanded control bytes.
- Bundled ROM scripts продолжают компилироваться cleanly.
