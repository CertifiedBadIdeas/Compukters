## План: Автономная загрузка VM

> **Статус: Черновик — ожидает утверждения**

Сейчас `ServerComputer` владеет загрузкой и компиляцией boot-скрипта, нарушая принцип «VM — самодостаточный чёрный ящик». Рефакторинг: (1) при первом создании workspace клонировать всю папку `rom/` из classpath, (2) убрать fallback из `WorkspaceProgramLoader`, (3) заменить `start(program)` на `boot()` — VM сама загружает, компилирует и запускает boot-скрипт, (4) `ServerComputer.turnOn()` сводится к `ensureWorkspace → boot()`.

---

### Фаза 1 — Клонирование папки `rom/` при создании workspace

**Обоснование:** Сейчас в workspace при инициализации копируется только `bios.ck`. Остальные ROM-скрипты (shell.ck, ls.ck, mkdir.ck, rmdir.ck, pwd.ck) подгружаются на лету через fallback `bundledScriptLoader`. После этой фазы все скрипты живут в workspace с самого начала.

**Принцип:** Клонирование происходит **один раз** — при первом создании папки `computerId`. Если папка уже существует, никакие файлы не копируются и не перезаписываются. Это гарантирует, что пользовательские изменения никогда не будут потеряны.

#### Шаг 1.1: Выделить `ComputerWorkspaceInitializer` — отдельный класс для подготовки workspace
Сейчас `FileComputerWorkspace` смешивает две ответственности: (а) CRUD-операции с файлами (реализация `ComputerWorkspace`) и (б) инициализацию workspace (создание папки, копирование ROM). Это разные задачи — подготовка workspace ≠ работа с ним.

Создать новый класс `ComputerWorkspaceInitializer` (mod/.../ComputerWorkspaceHost.kt или отдельный файл):
```kotlin
class ComputerWorkspaceInitializer(
    private val rootPath: Path,
) {
    fun ensureInitialized(computerId: Int): Path {
        val root = rootPath.resolve(computerId.toString()).normalize()
        if (root.exists()) return root
        root.createDirectories()
        cloneRomTo(root)
        return root
    }

    private fun cloneRomTo(targetDir: Path) { /* classpath rom/ → targetDir */ }
}
```
- Если папка `computerRoot(computerId)` **уже существует** — ничего не делать, вернуть путь
- Если папки **нет** — создать её и скопировать **всё содержимое** classpath `rom/` в корень workspace
- Клонирование реализовать через `ClassLoader.getResourceAsStream()` / `getResource()` — класс сам знает откуда брать ROM-ресурсы

#### Шаг 1.2: Очистить `FileComputerWorkspace` — только CRUD
Из `FileComputerWorkspace` (mod/.../ComputerWorkspaceHost.kt):
- Убрать параметры `initialBundledScripts` и `bundledScriptLoader`
- Убрать метод `ensureInitialized()` и `seedBundledScript()`
- Убрать вызов `ensureInitialized()` из `resolve()` — инициализация теперь ответственность вызывающего кода
- Класс остаётся чистой реализацией `ComputerWorkspace`: `list`, `readDocument`, `writeDocument`, `makeDirectory`, `deleteDocument`, `computerRoot`, `resolve`

#### Шаг 1.3: Обновить `ComputerVmSupervisor`
В `ComputerVmSupervisor` (mod/.../ComputerVmSupervisor.kt):
- Создать `ComputerWorkspaceInitializer(rootPath)` рядом с `FileComputerWorkspace(rootPath)`
- Убрать `bundledScriptLoader = LanguageServices::bundledScript` из конструктора `FileComputerWorkspace` (параметр удалён)
- `ensureWorkspaceInitialized(computerId)` теперь делегирует в `ComputerWorkspaceInitializer`, а не в `FileComputerWorkspace`

#### Шаг 1.4: Обновить `FileComputerWorkspaceTest`
В `mod/src/test/kotlin/FileComputerWorkspaceTest.kt`:
- Убрать `bundledScriptLoader` из хелпера `createWorkspace()` — `FileComputerWorkspace` больше не принимает этот параметр
- Тесты на CRUD (`list`, `readDocument`, `writeDocument` и т.д.) остаются без изменений
- Добавить отдельные тесты для `ComputerWorkspaceInitializer`:
  - Новый workspace → все ROM-скрипты из classpath `rom/` присутствуют
  - Повторный `ensureInitialized()` на существующей папке → файлы не трогаются

---

### Фаза 2 — Убрать bundled fallback из WorkspaceProgramLoader

**Обоснование:** Раз все ROM-скрипты уже в workspace, fallback в `WorkspaceProgramLoader.load()` — мёртвый код. Удаление закрепляет принцип «workspace — единственный источник истины».

#### Шаг 2.1: Упростить WorkspaceProgramLoader
В `ComputerProgramSupport.kt` (mod/.../ComputerProgramSupport.kt, L37-56):
- Убрать параметр `bundledScriptLoader`
- Убрать fallback-логику (L49-54)
- `load()` становится: прочитать из workspace → вернуть `LoadedComputerProgramSource` или null

#### Шаг 2.2: Обновить все call sites
- `BackgroundComputerVm` (L85): `WorkspaceProgramLoader(workspace)` без `bundledScriptLoader`
- `ServerComputer` (L88-90): убрать `bundledScriptLoader`. *Поле `programLoader` полностью удаляется в Фазе 4.*

---

### Фаза 3 — Заменить `start(program)` на `boot()` в ComputerVmHandle

**Обоснование:** VM должна быть самодостаточной. Она уже владеет `WorkspaceProgramLoader` (L85). Метод `boot()` инкапсулирует цепочку загрузка→компиляция→запуск внутри VM. Метод `start(program: ComputerProgram)` удаляется — внешние вызывающие стороны не должны знать о программах и компиляции.

#### Шаг 3.1: Заменить `start(program)` на `boot()` в интерфейсе `ComputerVmHandle`
В `ComputerVmModels.kt` (compiler/.../ComputerVmModels.kt):
- Убрать `fun start(program: ComputerProgram): Boolean`
- Добавить `fun boot(): Boolean`

#### Шаг 3.2: Реализовать `boot()` в `BackgroundComputerVm`
В `BackgroundComputerVm` (mod/.../BackgroundComputerVm.kt):
- `fun boot(): Boolean` — читает `profile.bootScriptName` через `programLoader`, компилирует через `ComputerProgramCompiler.compile()`, запускает корутину (та же логика, что сейчас в `start()`), отправляет событие `VmEvent("boot")`
- Возвращает `false` если boot-скрипт не найден или компиляция провалилась (логирует ошибки)

#### Шаг 3.3: Убрать `bundledScriptLoader` из конструктора BackgroundComputerVm
В `BackgroundComputerVm` (L77): убрать параметр `bundledScriptLoader`. Внутренний `programLoader` (L85) становится `WorkspaceProgramLoader(workspace)`.

#### Шаг 3.4: Обновить `ComputerVmSupervisor.getOrCreate()`
В `ComputerVmSupervisor` (mod/.../ComputerVmSupervisor.kt, L67-77): убрать `bundledScriptLoader = LanguageServices::bundledScript` из конструктора `BackgroundComputerVm(...)`.

---

### Фаза 4 — Упростить ServerComputer.turnOn()

**Обоснование:** С `boot()` на VM, `ServerComputer` больше не должен знать о загрузке программ и компиляции. Он становится чистым связующим звеном с Minecraft.

#### Шаг 4.1: Переписать `turnOn()`
В `ServerComputer` (mod/.../ServerComputer.kt, L131-158) заменить:
```
ensureWorkspace → programLoader.load → ComputerProgramCompiler.compile → getOrCreateVm → handle.start(program) → enqueueEvent("boot") → observeLifecycle
```
На:
```
ensureWorkspace → removeVm → getOrCreateVm → handle.boot() → observeLifecycle
```

#### Шаг 4.2: Удалить мёртвые поля и импорты
Из `ServerComputer`:
- Удалить lazy-поле `programLoader` (L88-90)
- Удалить `import ComputerProgramCompiler` (L28)
- Удалить `import WorkspaceProgramLoader` (L30)
- Удалить `import LanguageServices` (L35)

---

### Затронутые файлы

- `compiler/.../ComputerVmModels.kt` — заменить `start(program)` на `boot()` в интерфейсе `ComputerVmHandle`
- `mod/.../ComputerWorkspaceHost.kt` — выделить `ComputerWorkspaceInitializer` (клонирование `rom/` из classpath), очистить `FileComputerWorkspace` до чистого CRUD (убрать `initialBundledScripts`, `bundledScriptLoader`, `seedBundledScript()`, `ensureInitialized()`)
- `mod/.../ComputerProgramSupport.kt` — упростить `WorkspaceProgramLoader`, оставить `ComputerProgramCompiler`
- `mod/.../BackgroundComputerVm.kt` — реализовать `boot()`, убрать `start()`, убрать `bundledScriptLoader`
- `mod/.../ComputerVmSupervisor.kt` — создать `ComputerWorkspaceInitializer`, убрать `bundledScriptLoader` из `getOrCreate()` и из конструктора `FileComputerWorkspace`, делегировать `ensureWorkspaceInitialized()` в инициализатор
- `mod/.../ServerComputer.kt` — упростить `turnOn()`, удалить `programLoader`
- `mod/.../VmProcessApi.kt` — без изменений (использует `WorkspaceProgramLoader` через `BackgroundComputerVm.programLoader`)
- `mod/src/test/kotlin/FileComputerWorkspaceTest.kt` — убрать `bundledScriptLoader`, добавить тесты для `ComputerWorkspaceInitializer`

---

### Проверка

1. `./gradlew :compiler:test :mod:test` — все существующие тесты проходят
2. `FileComputerWorkspaceTest` — новый тест: все ROM-скрипты клонированы в новый workspace
3. `FileComputerWorkspaceTest` — новый тест: повторный `ensureInitialized()` не трогает существующие файлы
4. Ручной тест в игре: загрузить новый компьютер → bios.ck запускается → shell.ck загружается → `ls` работает (всё из workspace, без ROM fallback)
5. Ручной тест в игре: загрузить существующий компьютер → пользовательские скрипты не затронуты
6. Ручной тест в игре: перезагрузка → `handleVmStopped` вызывает `turnOn()` → `boot()` отрабатывает корректно

---

### Решения

- `boot()` находится на интерфейсе `ComputerVmHandle` — это единственный способ запуска VM. Метод `start(program)` удаляется полностью.
- Подготовка workspace вынесена в отдельный класс `ComputerWorkspaceInitializer`. `FileComputerWorkspace` — чистый CRUD, не знает про ROM и инициализацию. Параметры `bundledScriptLoader` и `initialBundledScripts` удаляются.
- Если папка с `computerId` уже существует, `ensureInitialized()` не копирует и не перезаписывает ничего.
- `ComputerProgramCompiler` остаётся в модуле `:mod`. Зависит от `LanguageServices`, который специфичен для мода.

---

### Дальнейшие соображения

1. **`boot()` на интерфейсе `ComputerVmHandle` — плюсы и минусы:**

   **Плюсы:**
   - **Единый контракт:** Все реализации VM загружаются одинаково — через workspace. Внешний код не может передать произвольную программу, что усиливает инкапсуляцию.
   - **Простота API:** Один метод `boot()` вместо цепочки `load → compile → start`. Вызывающий код (ServerComputer) не знает про `ComputerProgram`, `ComputerProgramCompiler` и т.д.
   - **Самодостаточность:** VM сама решает, что загрузить и как скомпилировать. Если в будущем изменится формат программ или процесс компиляции — менять нужно только реализацию VM.
   - **Безопасность:** Невозможно случайно запустить VM с «неправильной» программой — boot-скрипт определяется профилем (`ComputerProfile.bootScriptName`).

   **Минусы:**
   - **Тестирование:** Нельзя напрямую передать mock-программу в `start()`. Вместо этого тесты должны подготовить workspace с нужными файлами и вызвать `boot()`. Это чуть больше настройки, но точнее отражает реальный сценарий использования.
   - **Граница модулей:** Интерфейс `ComputerVmHandle` живёт в `:compiler`, а `boot()` будет зависеть от `ComputerProgramCompiler` и `LanguageServices` — которые в `:mod`. Реализация `boot()` в `BackgroundComputerVm` (`:mod`) — не проблема, но сигнатура `boot()` на интерфейсе не раскрывает зависимость от компилятора, что может вводить в заблуждение при чтении только `:compiler` модуля.
   - **Гибкость:** Если когда-нибудь понадобится запустить VM с программой из другого источника (не из workspace) — придётся менять интерфейс. Однако текущий дизайн намеренно исключает такой сценарий: VM работает только с собственным workspace.

   **Вывод:** Плюсы перевешивают. `boot()` на интерфейсе усиливает принцип «VM — чёрный ящик». Потеря `start(program)` компенсируется тем, что тесты через workspace точнее моделируют реальное поведение.
