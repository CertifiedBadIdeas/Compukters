# Дизайн триггеров CKL-форматирования

Дата: 2026-05-01

## Цель

Подключить уже существующие CKL Format Document и Cleanup Document к пользовательским триггерам Workbench editor.

MVP добавляет только ручной запуск:

- кнопки Format и Cleanup в toolbar;
- горячие клавиши для Format и Cleanup.

Автоформатирование при сохранении, серверные команды форматирования и настройки поведения не входят в этот этап.

## Текущий контекст

CKL formatter и cleanup pipeline уже доступны через IDE facade:

- `LanguageIde.formatDocument(path, source)` форматирует CKL и организует imports;
- `LanguageIde.cleanupDocument(path, source)` форматирует CKL и удаляет unused selective imports, если semantic analysis успешен;
- `WorkbenchIdeFacade.formatDocument(path, source)` и `cleanupDocument(path, source)` прокидывают эти действия в Workbench layer.

Workbench editor управляется через `WorkbenchStore`. UI должен оставаться тонким и делегировать изменение текста в store.

## Пользовательское поведение

Когда в Workbench editor открыт CKL-документ:

- toolbar показывает действия `Format` и `Clean`;
- `Format` вызывает существующий formatter и применяет returned edits;
- `Clean` вызывает существующий cleanup и применяет returned edits;
- `Ctrl+Alt+F` запускает Format;
- `Ctrl+Alt+L` запускает Cleanup.

Если документ не открыт, toolbar actions выключены, а shortcuts являются no-op.

Если formatter или cleanup возвращает пустой список edits, текст редактора не меняется. Diagnostics formatter-а в этом MVP не получают отдельную notification-систему; пользователь по-прежнему видит диагностику через обычный IDE analysis/status path.

## Архитектура

Добавить store-level trigger methods в `WorkbenchStore`, например:

- `formatOpenDocument(visibleEditorLines: Int)`
- `cleanupOpenDocument(visibleEditorLines: Int)`

Каждый метод:

1. читает `state.openDocument` и `state.editor.text`;
2. сразу выходит, если документ не открыт;
3. вызывает соответствующий метод `WorkbenchIdeFacade`;
4. применяет returned `TextEdit` через существующий local edit / CRDT path;
5. закрывает completion UI и обновляет IDE analysis после применения edits;
6. сохраняет курсор видимым с учётом переданного количества видимых строк.

Returned edits нужно применять от большего `startOffset` к меньшему `startOffset`. Сейчас formatter возвращает full-document edit, но такой порядок оставит store корректным, если formatter позже начнёт возвращать несколько edits.

Toolbar остаётся чистым UI layer:

- добавить кнопки `Format` и `Clean` в `buildToolbar`;
- включать их только при `store.state.openDocument != null`;
- делегировать clicks в методы store.

Keyboard path остаётся в `WorkbenchStore.keyPressed`:

- добавить `KeyCodes.KEY_F`, `KeyCodes.KEY_L` и `KeyCodes.MOD_ALT`, если их ещё нет;
- обрабатывать `Ctrl+Alt+F` до обычного text input как Format;
- обрабатывать `Ctrl+Alt+L` до обычного text input как Cleanup.

## Обработка ошибок

Форматирование и cleanup должны быть консервативными:

- нет открытого документа: no-op;
- нет returned edits: no-op;
- invalid edit offsets: игнорировать конкретный edit, не портить текст;
- formatter diagnostics без edits: не менять текст.

Store не добавляет отдельный механизм уведомлений в этой задаче.

## Тестирование

Добавить или расширить Workbench tests:

- store method применяет edits, возвращённые `WorkbenchIdeFacade.formatDocument`;
- store method применяет edits, возвращённые `WorkbenchIdeFacade.cleanupDocument`;
- `Ctrl+Alt+F` запускает format;
- `Ctrl+Alt+L` запускает cleanup;
- toolbar показывает Format/Clean и делегирует в store, если существующие UI tests позволяют проверить это без хрупкой привязки к пикселям.

Команды проверки:

- targeted Workbench tests;
- `./gradlew :core:test`;
- `./gradlew test`.
