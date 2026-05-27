# Дизайн IPC Terminal Foundation

## Контекст

Phase 1A добавила framebuffer display path внутри VM: client endpoint подключается со своим разрешением, VM публикует `DisplayFrameDelta` tiles, а client применяет их в `ClientDisplayBuffer`. Следующий шаг — перенести поведение терминала и shell внутрь компьютера, а не полагаться на host terminal semantics.

Пользователь явно не хочет, чтобы `Runtime` предоставлял высокоуровневые `stdin`, `stdout` или `stderr`. Граница runtime должна оставаться низкоуровневой. Unix-like process I/O должен быть convention в ROM/CKL поверх generic IPC primitives, а не runtime-owned stdio.

## Цели

- Добавить низкоуровневые generic IPC channels внутри одного device VM.
- Добавить асинхронный запуск процессов, чтобы terminal renderer program мог работать параллельно с shell process.
- Открыть raw VM event payloads для CKL, чтобы terminal programs могли читать key codes, typed characters, paste text и позже mouse coordinates.
- Не добавлять в `Runtime` понятия TTY, prompt, cursor, scrollback, line editing, ANSI/VT semantics и имена stdio.
- Перевести ROM shell/commands с legacy `terminal::*` calls на CKL libraries поверх IPC channels.
- Сделать `terminal.ck` владельцем terminal rendering: он читает display/input events, читает command output из IPC и рисует через `display::*`.

## Не-цели

- Не добавлять host/client terminal fallback behavior.
- Не делать `stdin`, `stdout` или `stderr` runtime concepts.
- Не реализовывать полный Unix job control, process groups, pipes, redirection или PTY naming в этой фазе.
- Не делать on-screen texture renderer работу сверх уже существующей доставки в `ClientDisplayBuffer`.
- Не добавлять bytecode `Instruction` variants, если существующий host-call signal path не станет реальным blocker.

## Граница Runtime

Runtime предоставляет только kernel-like primitives:

- generic IPC byte/text channels;
- process lifecycle primitives (`spawn`, `wait`);
- существующие display framebuffer operations;
- raw VM events с payload access;
- существующие filesystem и process loading.

Runtime не знает, какой channel означает `stdin`, `stdout` или `stderr`. ROM code может определить convention вроде `in=<id> out=<id> err=<id>` и передавать его через process arguments. Так stdio остаётся CKL policy, а не runtime feature.

## IPC primitive model

Добавить ambient builtin module `ipc` с маленьким стартовым API:

- `ipc::open() -> Int` создаёт channel id.
- `ipc::write(channelId: Int, text: String) -> Unit` добавляет text в channel.
- `ipc::read(channelId: Int) -> String` блокирует текущий CKL process до появления text или close.
- `ipc::tryRead(channelId: Int) -> String` возвращает доступный text или пустую строку без блокировки.
- `ipc::close(channelId: Int) -> Unit` закрывает channel.

Channels живут внутри одного `BackgroundDeviceVm`. Это не network sockets и не persistent files. Implementation должен ограничивать buffering через существующие VM resource limits или новый явный IPC quota, чтобы child process не мог бесконечно копить output.

Первая версия использует text strings, потому что в CKL сейчас нет пользовательского byte array type. Если позже появится byte array type, IPC сможет получить byte-oriented operations без изменения архитектуры.

## Process model

Добавить asynchronous process APIs:

- `process::spawn(path: String, argument: String) -> Int` запускает child CKL program и сразу возвращает pid.
- `process::wait(pid: Int) -> Int` ждёт child и возвращает exit code.
- Существующий `process::run(path, argument)` становится compatibility helper: `spawn` плюс `wait`.

`spawn` не должен создавать OS thread. Он создаёт child coroutine/task, owned by the same `BackgroundDeviceVm`, с общими display registry, IPC registry, filesystem API, event manager и resource limits.

Child получает только argument string. Любой stdio-like смысл кодируется ROM conventions в этой строке, а не Runtime.

## Event payload model

Текущий CKL `Event` type открывает только `name`, а этого недостаточно для CKL terminal program. Эта фаза должна расширить event model низкоуровневым payload access, оставляя interpretation вне Runtime.

Минимальная форма:

- `Event.name: String` остаётся доступным.
- CKL может читать positional arguments как primitive values, например helper functions `events::argInt(event, index)` и `events::argString(event, index)`, или equivalent fields, если это чище ложится на language model.
- Runtime всё ещё не интерпретирует keybindings, line editing, paste handling или terminal control sequences. Он только открывает raw payload, уже доставленный server input events.

Implementation plan должен выбрать самый маленький event-payload API, который соответствует текущим ограничениям CKL type system.

## ROM/CKL terminal stack

Добавить или переписать ROM files вокруг IPC convention:

- `stdio.ck`
  - Парсит argument convention вроде `in=<id> out=<id> err=<id>`.
  - Даёт CKL helpers `readLine(ctx)`, `write(ctx, text)`, `println(ctx, text)`.
  - Это library, не runtime feature.

- `terminal.ck`
  - Ждёт `display_attach` / `display_resize`, когда display ещё нет.
  - Создаёт IPC channels для shell input/output/error.
  - Запускает `shell.ck` через `process::spawn`, передавая channel ids в argument string.
  - Pull'ит raw input events (`key`, `paste`, позже mouse), конвертирует их в text/control bytes и пишет в shell input channel.
  - Poll'ит или читает shell output channel и рендерит glyphs, cursor, wrapping и scrollback через `display::*`.

- `shell.ck`
  - Перестаёт использовать `terminal::readln`, `terminal::write` и `terminal::println`.
  - Использует `stdio.ck` для line input и prompt/output.
  - Запускает command programs с теми же channel ids в argument string.

- ROM command programs (`ls.ck`, `pwd.ck`, `mkdir.ck`, `rmdir.ck`, позже `nano.ck`)
  - Перестают использовать `terminal::*`.
  - Используют `stdio.ck` для output и errors.

`boot.ck` должен запускать `terminal.ck`, а не `shell.ck`. `bios.ck` остаётся firmware/bootstrap code и не должен владеть terminal UI после старта user boot.

## Несколько терминалов

Первая реализация может запускать один terminal instance, но дизайн не должен зашивать singleton TTY. Несколько terminal instances моделируются как несколько процессов `terminal.ck`, каждый со своим набором channels и display endpoint. Runtime видит только независимые channels и processes.

## Testing strategy

- Compiler tests проверяют, что `ipc` builtins и `process::spawn` / `process::wait` видны в runtime registry.
- Compiler/runtime tests проверяют, что CKL может читать event payloads, нужные terminal input.
- Core IPC tests покрывают open/write/read/tryRead/close и bounded buffering.
- Core process tests доказывают, что `spawn` возвращается без ожидания, а `wait` возвращает child exit code.
- VM integration tests доказывают, что parent может spawn child, продолжить выполнение и получить child output через IPC.
- ROM compile tests проверяют, что `bios.ck`, `boot.ck`, `terminal.ck`, `shell.ck` и commands компилируются без legacy `terminal::*` usage.
- Display integration tests доказывают, что terminal output доходит до `ClientDisplayBuffer` через существующий frame path.

## Риски и ограничения

- `spawn` меняет VM scheduling сильнее, чем Phase 1A; реализацию нужно вести маленькими TDD commits.
- IPC buffering должен быть bounded с самого начала.
- Blocking `ipc::read` и `process::wait` должны suspend'ить только текущий CKL task, а не всю VM.
- Child process failures должны превращаться в non-zero exit codes и diagnostic output через ROM stdio convention, где это возможно.
