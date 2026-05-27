# Архитектура VM framebuffer display

Дата: 2026-05-05

## Проблема

Текущий UI компьютера построен вокруг терминала. Runtime output всё ещё описывается через terminal bytes,
terminal buffers и terminal-specific клиентские виджеты. Это конфликтует с новым направлением: компьютер должен сам
рендерить изображение внутри VM, а Minecraft-клиент должен оставаться только presentation-слоем с двойной буферизацией и
минимальной передачей данных.

Мод находится в alpha-стадии, поэтому этот дизайн не сохраняет старый терминальный путь как compatibility fallback.
Старую terminal-реализацию можно удалять или обходить, если она мешает новой display-first модели.

## Цели

- Заменить terminal-first output на display-first framebuffer contract.
- Дать VM возможность рендерить финальное изображение для display endpoint’а.
- Оставить клиентский presentation layer без семантики: применить frame deltas, swap buffers, показать pixels.
- Пусть разрешение задаёт клиент или display endpoint в мире, а не VM profile.
- Сохранить совместимость модели с будущими многоблочными дисплеями.
- Сделать terminal и shell обычными in-VM программами поверх display и input API.
- Оставить место для будущей IDE-сессии, которая подключается к живому устройству, а не запускает устройство внутри IDE.

## Не цели

- Нет backwards-compatible fallback для старого terminal screen или `ScreenBufferSnapshot` path.
- Нет полноценной реализации terminal shell в первом этапе.
- Нет реализации IDE agent в первом этапе.
- Нет реализации многоблочного монитора в первом этапе.
- Нет полноценного GPU-like command API. Transport contract — готовые pixels, не client-side draw commands.

## Целевая модель

Runtime Device больше не владеет фиксированным terminal resolution как частью VM profile. Display endpoint владеет
разрешением и подключается к runtime device. Endpoint может быть player GUI, terminal item screen, monitor block или
будущий multi-block display.

При attach или resize endpoint объявляет:

- `displayId`;
- ширину и высоту в logical pixels;
- pixel format;
- опциональные transport limits, например max frame rate или max payload size.

Сервер валидирует endpoint и доставляет изменение в VM как display events: `display_attach` и `display_resize`. VM
программа сама решает, что делать: перестроить layout, сделать letterbox, показать unsupported-size message или
проигнорировать display.

VM мутирует back buffer для display и затем presents it. Present создаёт versioned frame delta. Сервер доставляет delta в
endpoint. Клиент не знает, что означают pixels: terminal, shell, boot screen, file manager или IDE bridge UI.

## Компоненты

### Display endpoint

`DisplayEndpoint` — серверная модель подключённого display target. Он владеет display resolution и маршрутизирует frames
в конкретную output surface.

Endpoint первого этапа:

- один GUI endpoint, открытый игроком;
- width и height вычислены клиентом;
- достаточно одного active display на открытый GUI для первой реализации.

Будущие endpoints:

- terminal item screen;
- одиночный monitor block;
- multi-block monitor structure;
- remote или networked display.

Для multi-block display endpoint может показать VM одно logical resolution и разбить итоговую texture по блокам на
стороне Minecraft rendering. Другой VM API для этого не нужен.

### Device display API

Runtime предоставляет CKL-программам низкоуровневый display API. Первая версия должна быть намеренно маленькой:

- перечислить или запросить attached displays;
- получить display size и pixel format;
- clear или fill back buffer;
- записать pixel spans или rectangles;
- опционально blit из VM-owned image memory;
- present back buffer.

Высокоуровневый UI, terminal rendering, text drawing, shell и boot UI живут в CKL libraries или firmware code поверх этого
API.

### Framebuffer state

VM host хранит per-display framebuffer state:

- back buffer, который мутирует VM;
- front или last-presented state для расчёта deltas;
- sequence number;
- dirty tracking data.

Dirty tracking должен быть tile-based. Tile size вроде 8x8 или 16x16 pixels упрощает diffing и не заставляет отправлять
full frames при маленьких изменениях. Rect-based framing можно позже получить из tiles, если понадобится.

### Frame transport

Server-to-client frame messages несут:

- `displayId`;
- `sequence`;
- width и height;
- pixel format;
- dirty tile или dirty rect metadata;
- encoded pixel payload.

Если клиент видит missing sequence, format mismatch или size mismatch, он запрашивает full refresh. Full refresh —
штатный recovery path, не fatal error.

### Client double buffering

Клиент держит два buffers или textures для endpoint:

- visible front buffer;
- staging back buffer.

Входящие frame deltas применяются к staging. На render tick клиент swaps staging в visible buffer. Клиент не выполняет
terminal logic, VT parsing, text shaping, shell state или application rendering.

## Data flow

1. Клиентский или world display открывается и объявляет endpoint parameters серверу.
2. Сервер валидирует endpoint и отправляет `display_attach` или `display_resize` в VM event queue.
3. VM программа перерисовывает endpoint back buffer.
4. VM вызывает `present` для endpoint.
5. Host считает dirty tiles и создаёт frame delta с новым sequence number.
6. Сервер отправляет не больше одного актуального frame delta на endpoint за tick.
7. Клиент применяет delta к staging и swaps buffers на render.

## Input model

Input становится endpoint-first, не terminal-first. Client или world input адресуется display/input session и попадает в VM
как neutral events:

- key down и key up;
- typed character;
- mouse move, click, release, drag и scroll;
- paste;
- focus и blur;
- display resize.

Каждое input event содержит identity, достаточную для routing в VM, обычно `displayId` и опциональные session/user
metadata. Сервер валидирует distance, chunk state, ownership и текущую валидность endpoint перед enqueue input.

## Terminal как in-VM программа

Terminal больше не является native Minecraft UI concept. Он становится CKL-программой или стандартной библиотекой внутри
устройства. Он читает endpoint input events, ведёт shell state и рендерит terminal pixels в display framebuffer.

Ответственность terminal program:

- input line editing;
- command history;
- cursor и selection state;
- scrollback;
- shell command execution;
- rendering text и UI chrome в pixels.

Так boot screens, shells, file managers и debug UIs используют один display/input substrate.

## Будущая IDE session model

Будущая IDE не должна запускать computer внутри IDE. Она должна подключаться к живому Runtime Device. Программа или
firmware service на устройстве, например `ide_agent.ck`, предоставляет IDE protocol:

- workspace metadata;
- file read и write operations;
- diagnostics и capabilities;
- push, pull, run и debug commands;
- connection status.

Workbench или IDE client говорит с этим agent’ом. Если device выключен, недоступен или agent не запущен, IDE показывает
connection state вместо создания локального replacement runtime.

Первый framebuffer phase не реализует IDE agent, но вся display и input identity должна оставаться neutral, чтобы agent
можно было добавить позже без coupling к terminal internals.

## Error handling

- Unsupported endpoint parameters отклоняются на attach или передаются VM как display errors.
- Missing frame sequences вызывают full refresh.
- Resize invalidates old frame state и начинает новый sequence stream.
- Слишком большие payloads можно rate-limit’ить или конвертировать в full refresh с меньшим frame rate.
- Если endpoint исчезает, сервер отправляет detach event в VM и перестаёт отправлять frames.
- VM errors — это device/runtime errors; их не нужно кодировать как terminal UI state.

## Performance constraints

- Для первого pixel format лучше выбрать compact вариант, например palette indices или `RGB565`, прежде чем `RGBA8888`.
- Использовать tile dirty tracking.
- Ограничить present/frame publication runtime budget’ом и server tick rate.
- Отправлять не больше одного актуального frame на endpoint за tick.
- Избегать client-side semantic rebuilds. Клиент только применяет bytes к buffers.

## Scope первой реализации

Первый этап должен дать:

1. Display endpoint и framebuffer data models.
2. Runtime display API surface.
3. Client-defined GUI endpoint resolution.
4. VM display attach/resize events.
5. Frame delta serialization и recovery через full refresh.
6. Client double-buffer apply/swap model.
7. Минимальный CKL или firmware demo, который рисует pixels и реагирует на input.
8. Удаление или обход старых terminal-specific UI paths там, где они конфликтуют с новой архитектурой.

Первый этап не должен давать:

- полноценный terminal shell;
- полноценный IDE agent;
- multi-block display rendering;
- complex sprite, font или GPU command abstractions.

## Testing strategy

- Unit tests для framebuffer mutation и dirty tile calculation.
- Unit tests для frame sequence handling и full-refresh recovery.
- Serialization tests для frame delta messages.
- Runtime tests для display attach, resize и present events.
- Input routing tests для neutral key, char, mouse, paste и resize events.
- Client model tests для применения frame deltas к staging и swap buffers, если текущая client architecture позволяет
  isolated testing.
- Smoke test: active device получает client-defined resolution, рендерит frame в VM, и client presents it без
  terminal-specific rendering logic.

## Acceptance criteria

- Display resolution приходит от endpoint/client, а не от VM profile.
- VM рендерит complete image pixels для endpoint.
- Клиент только применяет frame data и swaps buffers.
- Terminal semantics больше не нужны в client display layer.
- Дизайн может представить future multi-block display как один logical endpoint.
- Дизайн позже сможет принять terminal-as-program и IDE-agent workflows без изменения display transport model.