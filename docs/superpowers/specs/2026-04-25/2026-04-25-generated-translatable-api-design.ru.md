# Дизайн разделённого API локализаций

## Цель

Сгенерировать три отдельных Kotlin API локализаций из `en_us.json`, чтобы production-код получал raw keys, UI DSL
`Value<String>` и `Component`-фабрики через разные, не перегруженные точки входа, без лишнего уровня `Compukterkraft`.

Сгенерированные API должны давать:

1. Raw key для каждого localization key.
2. `Value<String>` helper-ы для ключей без параметров.
3. `Component` accessor-ы для обычных ключей и `vararg` component factory для format-ключей.

## Текущий контекст

- Lang-ресурсы лежат в `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang`.
- Формат ключей изменился на modid-first для большинства записей, например `compukterkraft.gui.terminal.connecting`, но
  есть и ключи вроде `itemGroup.compukterkraft`, которые так не устроены.
- В генератор уже внесена пользовательская правка, которая исправляет избыточное создание дочерних object-ов, и это
  поведение надо сохранить.
- В `v1_21_1-common` уже есть `translatable(key: String): Value<String>` для UI DSL.
- Runtime-код также использует Minecraft `Component.translatable(...)` напрямую.

## Дизайн

### Источник истины

Для генерации используется только `en_us.json`.

Генератор должен читать ключи ровно в том виде, в котором они лежат в lang-файле, а затем применять одно правило
нормализации для API-структуры: если первый сегмент ключа ровно `compukterkraft`, этот сегмент убирается из generated
object path. Никакое другое вырезание сегментов или special-case переписывание не допускается.

### Форма генерируемого API

В `v1_21_1-common` генерируются три отдельных корневых объекта, каждый в своём файле:

- `CompukterKeys`
- `CompukterTranslatable`
- `CompukterComponents`

Все три дерева строятся из нормализованного пути ключа до leaf-сегмента.

Примеры:

- `compukterkraft.gui.terminal.connecting` -> `CompukterKeys.Gui.Terminal.CONNECTING`
- `compukterkraft.gui.terminal.connecting` -> `CompukterTranslatable.Gui.Terminal.connecting`
- `compukterkraft.gui.terminal.connecting` -> `CompukterComponents.Gui.Terminal.connecting`
- `compukterkraft.gui.tooltip.computer_id` -> `CompukterComponents.Gui.Tooltip.computerId(vararg args: Any)`
- `itemGroup.compukterkraft` -> `CompukterKeys.ItemGroup.COMPUKTERKRAFT`

### Ответственность API

#### `CompukterKeys`

Генерирует raw key constants для всех localization keys.

Пример:

- `const val CONNECTING = "compukterkraft.gui.terminal.connecting"`

#### `CompukterTranslatable`

Генерирует `Value<String>` getter properties только для non-parameterized localization entries.

Пример:

- `val connecting: Value<String> get() = translatable(CompukterKeys.Gui.Terminal.CONNECTING)`

Ключи с параметрами `Value<String>` helper-ов не получают.

#### `CompukterComponents`

Генерирует `Component` getter-ы для non-parameterized entries и `vararg` factory functions для parameterized entries.

Примеры:

- `val connecting: Component get() = Component.translatable(CompukterKeys.Gui.Terminal.CONNECTING)`
- `fun computerId(vararg args: Any): Component = Component.translatable(CompukterKeys.Gui.Tooltip.COMPUTER_ID, *args)`

### Правила построения дерева

- object-узлы используют PascalCase.
- `CompukterTranslatable` properties используют camelCase без суффикса `Value`, потому что тип уже выражен именем
  объекта.
- `CompukterComponents` getter-ы и functions тоже используют camelCase.
- raw key leaves используют UPPER_SNAKE_CASE.
- Логика поиска дочерних узлов должна оставаться prefix-aware, чтобы leaf-узлы не создавали несвязанные descendant
  object-ы.
- Generated tree не должен создавать object `Compukterkraft`, если удалённый ведущий namespace был только modid-prefix.

### Граница между модулями

Генератор читает lang-ресурсы из `v1_21_1-neoforge` и пишет generated Kotlin sources, которые потребляет
`v1_21_1-common`.

Gradle task записывает три output file в generated source directory и регистрирует эту директорию в `main` Kotlin source
set модуля `v1_21_1-common`.

## Обработка ошибок

Task генерации должен падать сразу, если:

- `en_us.json` отсутствует.
- `en_us.json` нельзя распарсить в key/value entries.
- Два или больше ключа нормализуются в одно и то же raw constant name внутри одного object-а.
- Два или больше ключа нормализуются в одно и то же имя property внутри одного object-а для `Translatable`.
- Два или больше ключа нормализуются в одно и то же имя getter-а или function внутри одного object-а для `Components`.

Сообщения об ошибках должны включать исходные localization keys, которые столкнулись.

## Стратегия тестирования

Реализация делается через TDD.

Обязательные тесты:

1. Тест генератора, который проверяет raw key generation в `CompukterKeys`.
2. Тест генератора, который проверяет, что `CompukterTranslatable` содержит только non-parameterized properties.
3. Тест генератора, который проверяет, что `CompukterComponents` генерирует getter-ы для обычных строк и `vararg`
   functions для parameterized strings.
4. Тест генератора, который проверяет ошибки коллизий имён для каждой API surface.
5. Smoke test в `v1_21_1-common`, который доказывает, что все три generated root object-а доступны обычной
   Kotlin-компиляции.
6. Тест генератора, который проверяет, что ключи не в формате modid-first, например `itemGroup.compukterkraft`,
   структурно сохраняются и не теряют первый сегмент.

## Что не входит в задачу

- Автоматическая миграция всех текущих call sites.
- Типизированный вывод типов аргументов для placeholders глубже, чем `vararg args: Any`.
- Генерация из неанглийских lang-файлов.
- Перестройка localization parity tests сверх необходимого минимума, чтобы генератор продолжал работать с новым форматом
  ключей.

## Критерии приёмки

1. `en_us.json` управляет генерацией `CompukterKeys`, `CompukterTranslatable` и `CompukterComponents`.
2. Каждый localization key получает raw constant в `CompukterKeys`.
3. Только non-parameterized keys получают `Value<String>` helper-ы в `CompukterTranslatable`.
4. Non-parameterized keys получают `Component` getter-ы в `CompukterComponents`.
5. Parameterized keys получают `vararg` component factory в `CompukterComponents`.
6. Генератор уважает пользовательскую правку child discovery и не создаёт лишние descendant object-ы.
7. Генератор убирает лишний уровень `Compukterkraft` только если ведущий сегмент равен modid-prefix.
8. Build падает с понятной ошибкой при parse errors или коллизиях нормализованных имён.