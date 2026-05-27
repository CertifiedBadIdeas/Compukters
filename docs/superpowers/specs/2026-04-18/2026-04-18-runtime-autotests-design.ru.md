# Дизайн runtime-автотестов

## Цель

Добавить практическую стратегию runtime-автотестов для мода, которая ловит регрессии в выполнении программ, интеграции устройств и world-facing поведении компьютера, но не превращает каждую проверку в полный запуск Minecraft.

Дизайн должен дать:

- быстрые headless-проверки для VM и language runtime;
- точечные platform-aware тесты для Minecraft-зависимых адаптеров и сериализации;
- небольшой слой NeoForge GameTest для настоящей интеграции с миром, тиками и блоками;
- явное разделение Gradle и CI между дешёвыми тестами и дорогими runtime integration тестами.

## Область изменений

Входит:

- пирамида тестов для runtime-поведения;
- общие runtime test fixtures для workspace, профилей и fake devices;
- NeoForge GameTest покрытие для интеграции computer block;
- Gradle-стратегия запуска локально и в CI;
- первые сценарии для boot runtime и подключения устройств.

Не входит:

- Fabric runtime GameTest в этой итерации;
- широкая UI-автоматизация экранов;
- полноценный performance benchmarking;
- замена всех существующих unit и integration тестов на GameTest.

## Текущее состояние

В кодовой базе уже есть быстрые runtime-ориентированные JVM-тесты в:

- [modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/runtime-autotests/modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt)
- [modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupportTest.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/runtime-autotests/modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupportTest.kt)
- [modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/runtime/ComputerProgramSupportTest.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/runtime-autotests/modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/runtime/ComputerProgramSupportTest.kt)

В репозитории также уже есть helper для bootstrap Minecraft-классов в тестах: [modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/workbench/test/TestMinecraftBootstrap.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/runtime-autotests/modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/workbench/test/TestMinecraftBootstrap.kt).

Конфигурация Loom уже показывает поддержку NeoForge GameTestServer, но отдельного runtime GameTest-набора для lifecycle компьютерного блока пока нет.

Это означает, что проект уже умеет дёшево проверять core runtime logic, но пока не проверяет полный путь от placement блока до ticking runtime в реальном Minecraft-мире.

## Обзор дизайна

Runtime-автотесты должны быть организованы как трёхслойная пирамида:

1. `Headless JVM runtime tests`
2. `Minecraft-aware platform integration tests`
3. `NeoForge GameTest world integration tests`

Нижние слои должны ловить большинство регрессий. Верхний слой должен оставаться намеренно маленьким и проверять только то, что граница интеграции с Minecraft действительно собрана правильно.

## Слой 1: Headless JVM Runtime Tests

Этот слой остаётся основным safety net.

Он должен покрывать:

- загрузку workspace и поиск исходников;
- компиляцию и preconditions запуска программ;
- ограничения VM profile, такие как ROM, RAM и размеры очередей;
- различие между доступностью runtime module и наличием устройства;
- доставку attach и detach событий;
- snapshot и restore поведения;
- поведение runtime host bridge через fake или recording hosts.

Эти тесты не должны зависеть от настоящего Minecraft-сервера или world ticks.

Если поведение можно проверить чисто через runtime abstractions, оно должно жить здесь, а не в GameTest.

## Слой 2: Minecraft-Aware Platform Integration Tests

Этот слой нужен для поведения, которому требуются Minecraft-классы или registries, но не нужен полный GameTest-мир.

Он должен использовать существующий bootstrap helper для проверки:

- сериализации и десериализации block entity;
- registry-backed runtime adapters;
- menu или inventory contracts, зависящих от Minecraft types;
- resource или data interactions, привязанных к Minecraft internals;
- platform-specific runtime wiring, которое можно наблюдать без world ticking.

Этот слой по-прежнему должен запускаться через обычный Gradle `test`.

## Слой 3: NeoForge GameTest World Integration

Этот слой предназначен только для поведения, которому реально нужен мир, server ticks или placement блоков.

Первые GameTests должны проверять наблюдаемое интеграционное поведение:

- placement computer block создаёт ожидаемый block entity и runtime host state;
- runtime переживает начальные тики и может загрузить программу из test workspace data;
- подключённые периферии становятся видимыми runtime после world-side attachment;
- persisted runtime или block state переживает reload, если это входит в контракт.

GameTest не должен становиться заменой для unit-тестов компилятора и VM.

Если падение можно локализовать ниже Minecraft-world boundary, оно должно оставаться в Layer 1 или Layer 2.

## Стратегия общих test fixtures

Набор runtime-тестов должен ввести общие fixtures для концепций, которые сейчас вручную собираются в нескольких тестах.

Общий fixture layer должен предоставлять:

- переиспользуемую фабрику `ComputerProfile`;
- фабрику временного `ComputerWorkspaceHost`;
- helper-методы для записи `.ck` файлов во временные workspace directories;
- fake или recording device registries и runtime hosts;
- helpers для повторяющихся runtime assertions вроде compile-and-run или attach-and-observe.

Слой fixtures должен жить рядом с runtime-тестами, а не внутри production sources.

Его задача — уменьшить дублирование, сохранив тесты читаемыми и явными по части сценария.

## Границы GameTest

GameTest coverage должна быть намеренно узкой.

GameTest — правильный инструмент, когда assertion зависит от:

- placement в мире;
- server ticking;
- lifecycle block entity;
- Minecraft persistence boundaries;
- реального взаимодействия между NeoForge registration и запуском runtime.

GameTest — неправильный инструмент, когда assertion в основном касается:

- compiler diagnostics;
- поведения bytecode VM;
- import validation;
- device-registry business logic, которую можно смоделировать без мира.

Эта граница критична, чтобы набор тестов оставался поддерживаемым и достаточно быстрым для повседневной разработки.

## Стратегия Gradle и CI

Модель запуска должна разделять дешёвую обратную связь и дорогую world integration:

- обычный `test` остаётся дефолтным локальным и CI verification path;
- GameTest получает отдельную task или workflow step;
- CI должен репортить GameTest failures отдельно от unit и integration test failures.

Такое разделение не заставляет каждую локальную правку проходить через Minecraft-based runtime check, но сохраняет автоматическое покрытие реального integration path.

## Первая итерация

Первая итерация runtime-автотестов должна принести один reusable fixture layer и небольшой набор high-value сценариев.

Рекомендуемые стартовые сценарии:

1. headless-тест, доказывающий, что программа из workspace компилируется и запускается с ожидаемым profile;
2. headless-тест, доказывающий, что доступность runtime module независима от фактического наличия устройства;
3. headless-тест, доказывающий, что attach events делают новое подключённое устройство видимым для typed APIs;
4. NeoForge GameTest, доказывающий, что placed computer block достигает стабильного ticking runtime state;
5. NeoForge GameTest, доказывающий, что world-side peripheral attachment становится наблюдаемым для запущенного компьютера.

Этот набор намеренно небольшой. Цель первой итерации — закрепить harness и границы ответственности, а не исчерпывающе покрыть все фичи.

## Риски

### Избыточное использование GameTest

Если слишком много логики уйдёт в GameTest, набор станет медленным, хрупким и дорогим в отладке.

Смягчение:

- держать логику VM и compiler в headless tests;
- поднимать assertion в GameTest только там, где действительно нужен мир.

### Расползание fixtures по модулям

Если каждый модуль будет собирать собственный runtime test setup отдельно, поведение и предположения начнут расходиться.

Смягчение:

- один раз определить shared fixture helpers;
- platform-specific additions делать явными, а не копировать setup code.

### Ложная уверенность от одного smoke-теста

Один boot smoke test полезен, но он не доказывает интеграцию периферии, persistence или semantics подключения.

Смягчение:

- считать первые GameTests минимальной точкой входа;
- расширять покрытие по integration contract, а не по случайному числу фич.

## Критерии успеха

- Большинство runtime-регрессий ловится обычным запуском Gradle `test`.
- В репозитории появляется явный shared fixture layer для runtime-тестов.
- NeoForge GameTest проверяет world-facing границу интеграции компьютера.
- CI pipeline умеет различать быстрые test failures и GameTest failures.
- У разработчиков есть ясное правило, когда новый runtime-сценарий должен идти в headless tests, platform tests или GameTest.