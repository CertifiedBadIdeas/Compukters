---
name: Migrate CK runtime
overview: Убрать Kotlin Scripting из проекта, перевести boot/runtime/IDE на новый язык с ключевыми словами `val` и `struct`, и сделать сборку снова рабочей.
todos:
  - id: fix-compiler-api
    content: Убрать legacy scripting-зависимости из compiler и восстановить сборку
    status: completed
  - id: build-ide-layer
    content: Реализовать IDE API поверх LanguageFrontend
    status: completed
  - id: switch-mod-runtime
    content: Переключить mod на новый compiler и VM
    status: completed
  - id: migrate-ck-files
    content: Перевести boot/workspace на .ck и обновить тесты/документацию
    status: completed
  - id: remove-scripting
    content: Удалить остатки Kotlin Scripting инфраструктуры
    status: completed
isProject: false
---

# Миграция на собственный язык и VM

## Цель

Полностью убрать зависимости на Kotlin Scripting, перевести проект на новый язык `CKL` с файлами `.ck`, сохранить рабочие IDE-функции и запуск компьютера через собственный байткод и VM.

## Шаги

- Починить `compiler`, убрав legacy IDE-типы и старые `scripting.*` импорты из shared runtime/workspace API.
- Синхронизировать синтаксис языка вокруг `val` и `struct`, обновить сообщения, тесты и документацию.
- Добавить новый IDE-слой поверх `LanguageFrontend` для диагностики, подсветки, hover, definition и completion.
- Переключить `mod` с `ScriptingEnvironment`/`execute()` на прямой pipeline `LanguageFrontend -> BytecodeModule -> BytecodeComputerProgram`.
- Перевести boot/workspace на `.ck` и обновить ROM/seeded BIOS путь.
- Удалить остатки Kotlin Scripting loader/config/test infrastructure и заменить её тестами нового runtime.

## Ключевые файлы

- `compiler/src/main/kotlin/ru/lazyhat/ck/lang/frontend/LanguageFrontend.kt`
- `compiler/src/main/kotlin/ru/lazyhat/ck/lang/runtime/LanguageRuntime.kt`
- `compiler/src/main/kotlin/ru/lazyhat/ck/lang/runtime/ComputerRuntime.kt`
- `compiler/src/main/kotlin/ru/lazyhat/ck/lang/runtime/ComputerWorkspace.kt`
- `mod/src/main/kotlin/ru/lazyhat/compukterkraft/computer/ServerComputer.kt`
- `mod/src/main/kotlin/ru/lazyhat/compukterkraft/computer/vm/ComputerWorkspaceHost.kt`
- `mod/src/main/kotlin/ru/lazyhat/compukterkraft/CompukterKraftMod.kt`
- `LANGUAGE.md`

## Проверка

- `./gradlew :compiler:test`
- `./gradlew :mod:test`
- при необходимости полная сборка проекта

