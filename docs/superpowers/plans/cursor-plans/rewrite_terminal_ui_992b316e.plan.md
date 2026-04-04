---
name: rewrite terminal ui
overview: Полностью заменить клиентскую реализацию terminal внутри текущего `ComputerWorkbenchKoolScreen`, привести её к стилю IDE и удалить legacy-ветку старого terminal UI после переноса нужных утилит.
todos:
  - id: extract-terminal-layout
    content: Вынести размеры и bounds terminal из legacy-классов в новый helper/layout слой
    status: completed
  - id: build-new-terminal-ui
    content: Сделать новый renderer и input controller для terminal в стиле IDE внутри KoolScreen
    status: completed
  - id: switch-active-screen
    content: Перевести ComputerWorkbenchKoolScreen на новый terminal path
    status: completed
  - id: remove-legacy-terminal
    content: Удалить старые terminal screen/widget классы после переноса зависимостей
    status: completed
  - id: verify-build-and-flow
    content: Проверить сборку и базовый сценарий terminal plus IDE
    status: completed
isProject: false
---

# Переписать Terminal UI

## Цель

Сделать новый terminal с нуля в рамках текущего `ComputerWorkbenchKoolScreen`, визуально и архитектурно согласованный с IDE, без зависимости от старой реализации, заимствованной из другого мода.

## Что меняем

- Оставляем terminal как режим внутри `[/home/lazyhat/IdeaProjects/Compukter-Kraft/mod/src/main/kotlin/ck/mod/gui/ComputerWorkbenchKoolScreen.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/mod/src/main/kotlin/ck/mod/gui/ComputerWorkbenchKoolScreen.kt)`.
- Переписываем terminal-клиент на новые классы в `mod/gui`.
- Сохраняем существующие `Terminal` / `TerminalState` / menu / network контракты, чтобы не ломать shell, VM и синхронизацию.
- Для первого прохода делаем минимальный чистый terminal: рендер, фокус, ввод символов, базовые клавиши, paste.
- После перевода активного экрана удаляем legacy-ветку старого terminal UI.

## Основные файлы

- `[/home/lazyhat/IdeaProjects/Compukter-Kraft/mod/src/main/kotlin/ck/mod/gui/ComputerWorkbenchKoolScreen.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/mod/src/main/kotlin/ck/mod/gui/ComputerWorkbenchKoolScreen.kt)`
- `[/home/lazyhat/IdeaProjects/Compukter-Kraft/mod/src/main/kotlin/ck/mod/gui/ComputerWorkbenchPresenter.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/mod/src/main/kotlin/ck/mod/gui/ComputerWorkbenchPresenter.kt)`
- `[/home/lazyhat/IdeaProjects/Compukter-Kraft/mod/src/main/kotlin/ck/mod/gui/TerminalInputController.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/mod/src/main/kotlin/ck/mod/gui/TerminalInputController.kt)`
- `[/home/lazyhat/IdeaProjects/Compukter-Kraft/mod/src/main/kotlin/ck/mod/gui/TerminalWidget.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/mod/src/main/kotlin/ck/mod/gui/TerminalWidget.kt)`
- `[/home/lazyhat/IdeaProjects/Compukter-Kraft/mod/src/main/kotlin/ck/mod/gui/FixedWidthFontRenderer.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/mod/src/main/kotlin/ck/mod/gui/FixedWidthFontRenderer.kt)`

## Подход

1. Вынести sizing/layout terminal из legacy `TerminalWidget` в новый нейтральный helper.
2. Добавить новый renderer/input-path для terminal в стиле текущего `Kool` UI.
3. Переключить `ComputerWorkbenchKoolScreen` на новые terminal-компоненты.
4. Удалить неиспользуемые legacy screen/widget классы.
5. Проверить сборку и базовый сценарий работы terminal/IDE.

## Ограничения

- Не трогаем архитектуру IDE, кроме мест соприкосновения в `ComputerWorkbenchKoolScreen`.
- Не меняем серверный runtime API terminal без необходимости.
- Не возвращаем старые `Ctrl+T/S/R` и mouse-фичи на первом проходе, если они не нужны для минимально чистой версии.

## Риск

Главный риск: `ComputerWorkbenchKoolScreen` сейчас зависит от helper-методов размера из `TerminalWidget`, поэтому удаление legacy делаем только после переноса этих частей в новый код.