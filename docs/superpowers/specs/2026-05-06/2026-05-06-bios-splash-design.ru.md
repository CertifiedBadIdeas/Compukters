# Дизайн BIOS Splash

## Цель

Добавить короткий firmware-level boot splash, чтобы запуск выглядел осознанным и брендированным, не меняя архитектуру ROM terminal и shell.

## Область

- Для runtime-поведения меняется только `firmware/bios.ck`.
- Регрессия добавляется в `RomScriptCompileTest.kt`.
- Не добавляем host-side Minecraft UI, texture или native rendering path.
- Не меняем `rom/boot.ck`, `rom/terminal.ck` и shell stdio ради этой фичи.

## Пользовательский опыт

При запуске BIOS рисует pixel-art splash со словом `COMPUKTER` и маленькой строкой `KRAFT BIOS`/boot status. Splash виден примерно две секунды, потом BIOS продолжает существующий flow поиска и запуска `boot.ck`.

Если display attach/resize происходит во время splash, BIOS перерисовывает splash. Если boot падает или `boot.ck` отсутствует, BIOS продолжает использовать существующий status/error screen.

## Архитектура

Splash находится в `firmware/bios.ck`, потому что это firmware branding, а не пользовательский workspace-контент. Реализация использует только существующие display primitives:

- `display::clear`
- `display::fillRect`
- `display::blitMono`
- `display::present`

Задержка реализуется CKL-control-flow с yield и обработкой display events, а не host-side blocking UI.

## Тестирование

Добавляем source-level regression tests, которые проверяют:

- у BIOS есть отдельные splash helpers;
- splash запускается до проверки существования `boot.ck`;
- splash использует display primitives, а не stdout/terminal builtins;
- существующие firmware и ROM compile tests остаются зелёными.
