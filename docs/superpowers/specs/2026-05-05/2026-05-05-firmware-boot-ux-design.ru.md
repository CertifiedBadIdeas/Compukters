# UX запуска через firmware BIOS

Дата: 2026-05-05

## Проблема

Текущий путь запуска может упасть до того, как пользователь увидит вывод в терминале. Ошибки компиляции startup-файла
видны в логах как crash-состояние VM, но UI терминала строит состояние только из power state и полученных terminal
bytes. Если startup падает быстро, pending stdout тоже может потеряться до следующего server tick flush. Итог — чёрный
или бесполезный экран: компьютер будто не запускается, но игрок не видит причину.

## Цели

- Показывать ошибки запуска пользователю в терминале, а не только в server logs.
- Смоделировать запуск как у реального компьютера: сначала стартует firmware, потом пользовательский startup-файл.
- Оставлять VM включённой, если пользовательский startup-файл отсутствует или сломан.
- Переименовать пользовательский startup-файл в `boot.ck` без compatibility migration со старого пользовательского
  `bios.ck`.
- Зарезервировать `bios.ck` для настоящей firmware вне обычной пользовательской файловой системы.
- Сохранять текущее поведение для реально выключенного компьютера: терминал скрыт или неактивен.

## Термины

- `bios.ck`: firmware-код. Он находится в скрытом firmware partition, а не в обычной пользовательской файловой системе.
- `boot.ck`: пользовательская startup-программа. Она находится в обычной filesystem устройства и является первой
  user-controlled программой, которую запускает firmware.
- Firmware partition: скрытое per-device хранилище для firmware. На первом этапе оно может быть backed by дефолтным ROM
  `bios.ck` из мода; позже его можно открыть через programmer item/block для read/write flashing.

## Модель запуска

Host всегда запускает device VM с firmware `bios.ck`. Host больше не считает пользовательский startup-файл entrypoint-ом
VM.

Startup flow:

1. Игрок включает компьютер.
2. Host создаёт VM и запускает firmware `bios.ck` из firmware partition или default ROM fallback.
3. Firmware пишет boot progress в stdout.
4. Firmware ищет `boot.ck` в обычной filesystem.
5. Если `boot.ck` существует, firmware запускает его через `process::run("boot.ck")`.
6. Firmware сообщает результат и остаётся жить, пока игрок не выключит или не перезагрузит компьютер.

Если `boot.ck` отсутствует, невалиден или падает, VM остаётся включённой, а терминал показывает читаемую диагностику.
Это не ошибка запуска VM; это ошибка пользовательского startup, обработанная firmware.

## Хранение firmware

Firmware не должна быть обычным пользовательским файлом. Обычные filesystem API и shell tools не должны случайно видеть,
редактировать или удалять `bios.ck`.

Первая реализация может использовать read-only firmware provider, который всегда возвращает bundled default `bios.ck`.
Data model должен оставить место для per-device firmware partition, чтобы будущий programmer tool мог перепрошивать его.
Сломанная custom firmware может сломать обычную загрузку, но должна быть восстановима через programmer.

## Контракт Process API

Для первой реализации достаточно `process::run(path)`. Он не должен пробрасывать compile/runtime failures
child-программы наружу в firmware. Вместо этого он должен:

- печатать `Program not found: <path>` и возвращать non-zero для отсутствующих файлов;
- печатать `Compilation Error in <path>: ...` и возвращать non-zero для compile diagnostics child-программы;
- печатать `Program error in <path>: ...` и возвращать non-zero для runtime failures child-программы;
- возвращать `0` при успехе.

Так `bios.ck` остаётся простым, но остаётся путь к будущему структурированному API `process::runResult(path)`.

## Модель ошибок

Есть два уровня ошибок.

### Firmware-level failure

Примеры: firmware отсутствует, firmware partition сломан, `bios.ck` невалиден.

VM не может полагаться на CK stdout, если сама firmware не стартовала. Host/UI должен показать это как device-level
BIOS/Firmware error. Это отдельный класс ошибок, отличный от обычных ошибок пользовательского startup, и в будущем он
восстанавливается через firmware reflash tooling.

### User boot failure

Примеры: отсутствует `boot.ck`, syntax/semantic diagnostics, нет `pub fun main()`, runtime exception, non-zero exit.

Firmware печатает диагностику в терминал и остаётся живой. Пользователь может исправить `boot.ck` и reboot, либо
выключить компьютер.

## Требования к терминалу и flush

Stdout — главный diagnostic channel для firmware boot. Он должен быть надёжным, даже если child-программа падает быстро.

Обязательное поведение:

- pending stdout bytes не должны теряться, когда child-программа быстро завершается или падает;
- final stdout flush должен происходить на terminal-state transitions, detach и crash paths, а не только на регулярных
  server ticks при живом VM handle;
- ошибки пользовательского `boot.ck` обычно выводятся всё ещё работающей firmware, но final flush остаётся safety net;
- UI терминала на первой версии может сохранить состояния `PoweredOff`, `Connecting` и `Active`, потому что user boot
  failure — это текст в терминале, а не terminal-state crash.

## Миграция

- Новый пользовательский startup-файл: только `boot.ck`.
- Старые пользовательские startup-файлы `bios.ck` не мигрируются.
- Workspace initialization должен создавать или поставлять `boot.ck`, `shell.ck` и utility programs в пользовательской
  filesystem.
- Firmware `bios.ck` хранится в скрытом firmware partition / ROM provider.

## Стратегия тестирования

- Runtime tests для `process::run("missing.ck")`: возвращает non-zero и печатает понятное сообщение.
- Runtime tests для `process::run("boot.ck")` с compile diagnostics: возвращает non-zero, печатает diagnostics и не
  роняет caller.
- Background VM tests для отсутствующего `boot.ck`: VM остаётся active, terminal содержит missing-file diagnostic.
- Background VM tests для невалидного `boot.ck`: VM остаётся active, terminal содержит compile diagnostic.
- Background VM tests для успешного `boot.ck`: firmware запускает его, сообщает результат и не выключает компьютер сама.
- Flush regression tests: быстрый stdout перед exit/failure доходит до attached terminal sessions.
- ROM compile tests: настоящий firmware `bios.ck` компилируется, default user programs включая `boot.ck` и `shell.ck`
  компилируются.

## Acceptance criteria

- Включение компьютера всегда даёт видимый исход: firmware boot log, missing `boot.ck`, compile error, runtime error,
  successful boot result или device-level firmware error.
- Сломанный или отсутствующий `boot.ck` никогда не оставляет пользователя с необъяснённым чёрным терминалом.
- Сломанный или отсутствующий `boot.ck` не выключает VM автоматически.
- `bios.ck` больше не является обычным пользовательским startup-файлом; `boot.ck` является пользовательским
  startup-файлом.
- Реально выключенные компьютеры по-прежнему не показывают активную терминальную поверхность.