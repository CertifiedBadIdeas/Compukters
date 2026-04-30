# План реализации Phase 2a-bis — переименование Device Workspace и IDE

Спека: `docs/superpowers/specs/2026-04-30-device-workspace-ide-rename-design.md`

План — строгая механическая последовательность. Каждая задача — один коммит, проверенный `./gradlew test --no-daemon`. Шаблон совпадает с Phase 2a:

1. `git mv` (или split-write) файла(ов)
2. `for sym in ...; do grep -rl --include='*.kt' '\bSym\b' modules | xargs sed -i 's/\bSym\b/NewSym/g'; done`
3. сканирование fakes: `(Fake|Stub|Mock|Test)Computer*` (должно быть пусто)
4. сканирование leftover'ов: `\bComputer*\b` для in-scope имён (должно быть пусто)
5. `./gradlew test --no-daemon`
6. `git commit`

Все команды — из корня worktree: `/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/phase2a-bis-device-workspace-ide`.

Полный текст шагов и команд см. в английской версии `2026-04-30-phase2a-bis-device-workspace-ide-rename.md`. Здесь — краткий обзор задач:

- **Task 0 — Pre-flight.** Чистое дерево, baseline тесты зелёные.
- **Task 1 — Storage-типы.** `git mv ComputerWorkspace.kt → DeviceWorkspace.kt`; sed по 3 символам (`ComputerWorkspace`, `ComputerWorkspaceEntry`, `ComputerWorkspaceDocument`).
- **Task 2 — Расщепление + IDE-типы.** Извлечь IDE-типы из `DeviceWorkspace.kt` в новый `DeviceIdeHost.kt`; sed по 8 символам (`ComputerIdeHost`, `ComputerIdeSnapshot`, `Computer{Completion,Hover,Definition}{Request,Response}`).
- **Task 3 — `ComputerWorkspaceHost` → `DeviceWorkspaceHost`.** `git mv` + sed.
- **Task 4 — `WorkspaceComputerIdeHost` → `WorkspaceDeviceIdeHost`.** `git mv` + sed.
- **Task 5 — `computerId` → `deviceId`.** Mass-sed по `\bcomputerId\b` во всех `*.kt` под `modules/`.
- **Task 6 — `docs/ARCHITECTURE.md`.** Заменить `ComputerWorkspace`/`computerId` упоминания на новые имена.
- **Финальная верификация.** `./gradlew clean test --no-daemon`, грепы на in-scope/out-of-scope, проверка коммитов на ветке.

Handoff: `superpowers:finishing-a-development-branch`. Рекомендация по умолчанию — `merge --no-ff` в `dev`, удалить worktree и ветку.
