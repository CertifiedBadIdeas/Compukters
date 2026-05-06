# Дизайн удаления VM stdio/terminal

Дата: 2026-05-06
Статус: утверждённый дизайн для implementation planning

## Контекст

Предыдущая ветка display-only runtime I/O убрала client-server terminal/stdout transport. Runtime computer clients теперь получают display frame deltas, а ROM `terminal.ck` рендерит visible interaction через `display::*`, а не через `stdout::write`.

В VM и языке ещё остался staged compatibility layer:

- builtins `terminal` и `stdout` всё ещё есть в `LanguageBuiltins`;
- `RuntimeHostBridge` всё ещё dispatch-ит `terminal`/`stdout` calls;
- `DeviceRuntime` всё ещё expose-ит `DeviceTerminalApi` и `DeviceStdioApi`;
- `BackgroundDeviceVm` всё ещё владеет legacy `ScreenBuffer`/stdio plumbing для terminal compatibility;
- `firmware/bios.ck` всё ещё использует `terminal::println` для boot diagnostics.

Этот follow-up удаляет VM-side terminal/stdout concepts. Программы должны сами рендерить visible output через display/framebuffer APIs.

## Решения

- Реализацию делать в отдельном worktree.
- Удалить VM-side `terminal` и `stdout` APIs, а не оставлять их как internal output primitives.
- Не добавлять internal VM diagnostics renderer.
- Programs, firmware и ROM сами отвечают за rendering visible output через `display::*`.
- Использовать существующую convention stdio channels для parent/child process communication, но сделать её явной и tagged.
- Использовать только tagged stdio descriptors; совместимость со старым untagged descriptor format не сохранять.

## Цели

- Удалить `terminal` и `stdout` из public CKL runtime builtins.
- Удалить terminal/stdout dispatch из runtime execution.
- Удалить `DeviceTerminalApi`, `DeviceStdioApi`, `VmTerminalApi`, `ComputerStdioBroadcaster` и VM-owned terminal screen-buffer plumbing, когда не останется main-code consumers.
- Сохранить видимость process failures для программ: child loader/compiler/runtime errors пишутся в child stderr channel, если передан tagged stdio descriptor.
- Обновить bundled firmware/ROM scripts, чтобы boot и shell output рендерились display-driven programs.
- Сделать старые `terminal::`/`stdout::` usage ошибками unknown module.

## Не-цели

- Не реализовывать Workbench display viewer на этом этапе.
- Не добавлять новый network stdout/stderr transport.
- Не добавлять overloads для `process::run` или `process::spawn`.
- Не мигрировать автоматически существующие user workspace scripts.
- Не сохранять старые untagged stdio argument descriptors.
- Не удалять Workbench UI snapshot/terminal types, если они не стали unused как прямое следствие VM cleanup.

## Целевая runtime model

### Visible output

Visible runtime UI принадлежит CKL programs. Programs используют `display::*` для framebuffer state и вызывают `display::present()` для публикации. VM runtime сам не рисует diagnostics, prompts или errors.

### Builtin modules

Default runtime registry сохраняет device APIs вроде `display`, `filesystem`, `system`, `events`, `ipc`, `process`, `strings`, но удаляет `terminal` и `stdout`.

CKL code, который импортирует или вызывает `terminal::*` или `stdout::*`, должен падать во frontend resolution с unknown module/name error.

### Process stderr

Process API сохраняет текущие сигнатуры:

- `process::run(path: String): Int`
- `process::run(path: String, argument: String): Int`
- `process::spawn(path: String): Int`
- `process::spawn(path: String, argument: String): Int`
- `process::wait(pid: Int): Int`

Process startup использует tagged stdio descriptor внутри `argument`:

```text
stdio-v1 <stdin> <stdout> <stderr> <argument>
```

`stdio.ck` владеет encoding/decoding descriptor. Bundled callers используют `stdio::encode(ctx, argument)`, callees используют `stdio::fromArgument(process::argument())`.

Если `VmProcessManager` может decode-ить `stdio-v1` descriptor из child argument, он пишет собственные child-process errors в decoded `stderr` IPC channel:

- program not found;
- compilation error;
- runtime exception.

Если tagged stdio descriptor отсутствует, `VmProcessManager` логирует ошибку через server logger и возвращает non-zero exit code. Он не пишет в global terminal/stdout sink и не рендерит display output.

### Firmware и ROM

`firmware/bios.ck` должен перестать использовать `terminal::println`. Он должен рендерить boot status и boot failure text через `display::*` напрямую или через ROM helper.

Для запуска `boot.ck` BIOS открывает stdio channels, передаёт tagged descriptor в `process::run("boot.ck", ...)`, читает stdout/stderr channels и рендерит тот текст, который должен быть видимым.

`rom/terminal.ck` остаётся владельцем interactive UI. Он читает shell stdout/stderr IPC channels и рендерит их в display frames. `rom/shell.ck` и external ROM programs используют helpers из `stdio.ck` для stdout/stderr.

## Изменения компонентов

### Compiler/frontend

- Удалить `terminal` и `stdout` из `LanguageBuiltins.defaultRuntimeRegistry`.
- Обновить IDE completion/hover/import tests, чтобы они больше не ожидали эти modules.
- Переписать compiler snippets с `terminal::println` на pure computations, `display::*` или другие оставшиеся builtins.
- Добавить tests, доказывающие, что `terminal`/`stdout` imports и calls reject-ятся.

### Runtime bridge и APIs

- Удалить handlers `terminal` и `stdout` из `RuntimeHostBridge`.
- Удалить `DeviceRuntime.terminal` и `DeviceRuntime.stdio`.
- Удалить `DeviceTerminalApi` и `DeviceStdioApi`.
- Обновить construction `VmRuntime` и все runtime API call sites.
- Удалить `VmTerminalApi`, `ComputerStdioBroadcaster`, `ScreenBufferVtSink` и cursor/VT support, если не останется main-code references.

### Process manager

- Удалить terminal API dependency из `VmProcessApi` и `VmProcessManager`.
- Добавить stdio descriptor decoding в runtime code, желательно с теми же rules, что и `stdio.ck`, чтобы format semantics не расходились.
- При child load/compile/runtime errors писать message в decoded stderr IPC channel, если он есть.
- Cancellation остаётся quiet.

### VM handle и runtime device

- Удалить VM-owned `ScreenBuffer` из `BackgroundDeviceVm`, когда terminal APIs больше не требуют его.
- Удалить `readScreenSnapshot()` / `forceScreenSnapshot()` из VM handle, если не останется обязательных main-code callers.
- Обновить `RuntimeDeviceScreen` / Workbench snapshot paths до no-op, disabled или удаления по фактическим remaining dependencies.

### Bundled scripts

- Обновить `firmware/bios.ck`, чтобы он использовал display APIs и tagged stdio descriptors.
- Обновить `rom/stdio.ck`, чтобы он emit/parse только `stdio-v1` descriptors.
- Обновить `rom/shell.ck` и external commands, чтобы они использовали tagged stdio helpers.
- Проверить все bundled firmware/ROM scripts на `terminal::` и `stdout::`.

## Testing strategy

### Red tests first

Добавить tests до implementation для:

- default runtime registry не содержит `terminal` или `stdout`;
- `RuntimeHostBridge` не dispatch-ит `terminal`/`stdout`;
- bundled firmware/ROM sources не содержат `terminal::` или `stdout::`;
- process load/compile/runtime errors идут в tagged stderr IPC, если descriptor передан;
- `stdio.ck` encodes tagged descriptors и rejects old untagged descriptors;
- IDE completions больше не показывают modules `terminal`/`stdout`.

### Focused verification

Использовать focused module tests при изменении каждого слоя:

- compiler tests после удаления builtins и rewrite snippets;
- core VM tests после process stderr и API deletion;
- NeoForge ROM compile/tests после firmware/ROM changes.

### Final audit

Финальный source audit по main source и bundled ROM/firmware для removed symbols:

- `terminal::`
- `stdout::`
- `DeviceTerminalApi`
- `DeviceStdioApi`
- `VmTerminalApi`
- `ComputerStdioBroadcaster`
- `ScreenBufferVtSink`
- `RuntimeHostBridge` terminal/stdout dispatch functions

Historical docs under `docs/superpowers` могут сохранять старые references как design history.

## Compatibility и migration

Это breaking runtime-language cleanup. Existing user programs, которые вызывают `terminal::` или `stdout::`, перестанут компилироваться. Existing workspaces со старым untagged stdio argument convention тоже перестанут работать до обновления scripts.

Bundled firmware и ROM scripts должны быть обновлены в этой же ветке. Automatic migration для существующих user workspaces out of scope и должна планироваться отдельно, если понадобится.

## Риски

- Boot failures могут стать невидимыми, если BIOS не отрендерит display messages после terminal removal.
- Process errors могут потеряться, если caller не передал tagged stdio descriptor.
- Удаление `ScreenBuffer` может вскрыть hidden Workbench snapshot dependencies.
- Compiler и IDE tests содержат много старых `terminal::println` snippets и потребуют широких rewrite.

## Open follow-up decisions

- Нужно ли строить Workbench display viewer, который observes display sessions.
- Нужна ли user workspace migration для старых stdio descriptors и terminal-based examples.
- Нужен ли в будущем structured machine-readable diagnostics state дополнительно к display-rendered text.
