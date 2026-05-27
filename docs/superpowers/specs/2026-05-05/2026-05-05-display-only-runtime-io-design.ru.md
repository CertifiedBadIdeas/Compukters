# Дизайн display-only runtime I/O

Дата: 2026-05-05
Статус: черновик для ревью

## Контекст

Сейчас у runtime-компьютеров Compukter Kraft есть два output-пути:

1. Legacy terminal/stdout путь:
   - runtime terminal sessions;
   - stdout byte broadcasting;
   - client-side terminal buffers/surfaces;
   - workbench attach-terminal behavior.
2. Новый display/framebuffer путь:
   - display attach/resize/detach;
   - display frame deltas;
   - client-side framebuffer rendering.

Нужное направление: убрать stdin/stdout broadcasting из client-server модели после подготовки framebuffer/display render.

## Принятые решения

- Реализацию делать в отдельном git worktree.
- Полностью убрать terminal/stdout transport из client-server runtime UI модели.
- Не оставлять VM-side stdout/terminal APIs как финальную внутреннюю модель.
- Временно убрать или отключить workbench attach-terminal, а не портировать его в этой итерации.
- Использовать staged internal cleanup, а не один big-bang rewrite.
- Для интерактивного terminal rendering использовать ROM-side dirty-line renderer, а не full framebuffer redraw на каждый keypress.

## Цели

- Сделать display frames единственным server-to-client output путём для runtime computer UI.
- Оставить client-to-server input как discrete events: key, char, paste, mouse.
- Удалить stdout byte broadcast packets и terminal session management из runtime client-server модели.
- Перенести ROM terminal behavior на framebuffer rendering.
- Сохранить shell usability: prompt, typed input, paste, backspace, Enter и shell output.
- Не вернуть прошлую performance-регрессию от framebuffer redraw на каждый keypress.
- Сохранить workbench IDE/sync/run, временно убрав live attach-terminal viewing.

## Не-цели

- Не вводить новый network stdin/stdout stream под другим именем.
- Не реализовывать полноценный workbench display viewer в этой итерации.
- Не полагаться на автоматическое обновление ROM support scripts в существующих user workspaces; это поведение было откачено и должно рассматриваться отдельно.
- Не сохранять runtime UI через `stdout_bytes` compatibility.

## Целевая архитектура

### Client to server

Client отправляет только input events для running computer:

- key down/up events;
- character input events;
- paste events;
- mouse events.

Эти события попадают в VM event queue. Это не stdin bytes.

### Server to client

Server отправляет только display updates для runtime UI:

- display attach/resize/detach session management;
- framebuffer frame deltas.

Нет stdout byte stream, terminal session stream или terminal surface stream.

### VM и ROM

ROM terminal владеет text UI behavior:

- читает VM events через `events::tryPull`;
- отправляет shell commands через VM-local IPC;
- получает shell output через VM-local IPC;
- рисует prompt, current line и shell output через display/framebuffer APIs.

Shell остаётся независимым от client-server transport. Он общается с ROM terminal через VM-local mechanisms, а не через network stdin/stdout.

## Изменения компонентов

### Убрать из client-server runtime UI

- `TerminalNetworkBridge`.
- `StdoutBytesClientMessage`.
- Terminal attach/resize server messages для runtime terminal sessions.
- `RuntimeDeviceTerminalSessions`.
- Terminal session state и flushing в `RuntimeDeviceImpl`.
- Использование `ClientTerminalBuffer` как output для runtime computer screen.
- Terminal surface rendering как источник runtime computer UI output.

### Оставить и усилить

- `DisplayNetworkBridge`.
- Display attach/resize/detach messages.
- `FrameDeltaClientMessage`.
- `ClientDisplayBuffer`.
- `DisplayRegistry` и frame delta generation.
- Существующие input event packets как discrete event transport.

### Staged VM-side removal

Финальное состояние должно удалить VM-side stdout/terminal concepts как runtime UI primitives. Чтобы снизить риск, cleanup должен быть staged:

1. Сначала убрать client-server terminal/stdout path и переключить runtime UI на display-only.
2. Затем удалить или заменить internal `ComputerStdioBroadcaster`, `VmTerminalApi`, `DeviceStdioApi`, `ScreenBuffer` и snapshot dependencies после того, как все видимые diagnostics будут доступны через display или structured logs.
3. В конце обновить docs и tests, чтобы stdout/terminal APIs больше не описывались как runtime UI model.

## ROM terminal rendering

ROM terminal не должен использовать `stdout::write` для visible user interaction.

Он должен хранить:

- confirmed shell output lines;
- current editable input line;
- cursor/prompt state;
- display dimensions.

Interactive edits должны использовать dirty-line renderer:

- typed characters обновляют только region текущей input line;
- Backspace перерисовывает только затронутую часть текущей input line;
- paste обновляет изменённые line regions;
- shell output добавляет строки и помечает изменённые rows;
- resize помечает весь screen dirty;
- attach рендерит текущее состояние один раз.

Это не допускает full framebuffer redraw на каждый keypress.

## Workbench behavior

Workbench attach-terminal временно удаляется или отключается.

Требуемое поведение:

- нет live stdout terminal attachment через network;
- нет dependency на runtime `stdout_bytes`;
- IDE, file sync, compile/run controls остаются доступны;
- UI должен либо скрыть attach-terminal controls, либо показать disabled state с понятным сообщением.

Будущая feature может добавить workbench display viewer через display sessions, не stdout.

## Migration notes

Существующие компьютеры могут содержать старые copied ROM support scripts. Предыдущее автоматическое refresh bundled ROM support scripts было откачено. Поэтому дизайн не должен предполагать, что existing workspaces автоматически получат новый `terminal.ck`.

Implementation options должны быть явными:

- тестировать на newly-created computers; или
- дать manual migration guidance; или
- добавить отдельный reviewed ROM support-script migration mechanism.

Это migration decision отдельно от удаления stdout transport.

## Testing strategy

### No stdout network path

- Проверить, что runtime server ticks больше не вызывают stdout byte sending.
- Проверить, что stdout byte network message registration удалён или недостижим.
- Проверить, что computer UI не читает из `ClientTerminalBuffer`.

### Display-only shell

- Boot computer через BIOS и ROM terminal.
- Assert shell greeting/prompt виден через display frames.
- Напечатать `help`, нажать Enter и assert shell output появляется через display frames.
- Проверить, что Backspace корректно редактирует отображаемую current line.

### Performance guard

- Напечатать несколько символов без Enter.
- Assert renderer не emits full-frame redraw на каждый keypress.
- Предпочитать assertions на dirty regions/frame deltas, а не wall-clock timing.

### Workbench

- Проверить, что attach-terminal packet/UI path удалён, скрыт или отключён.
- Проверить, что non-terminal workbench functionality остаётся рабочей.

### VM cleanup

- Добавлять tests по мере удаления каждого VM-side terminal/stdout primitive.
- Убедиться, что startup errors и child process failures остаются visible через display или structured diagnostics.

## Риски

- Если убрать `stdout` слишком рано, можно скрыть BIOS/runtime diagnostics.
- Existing copied ROM scripts могут продолжать использовать old stdout behavior.
- Dirty-line rendering сложнее full redraw и требует focused tests.
- Workbench users временно теряют live terminal attachment до появления display viewer.

## Open follow-up decisions

- Добавлять ли отдельный ROM support-script migration mechanism.
- Должен ли workbench display viewer attach к тому же display endpoint, что computer screen, или использовать separate observer session.
- Как expose structured diagnostics после удаления VM-side stdout/terminal APIs.
