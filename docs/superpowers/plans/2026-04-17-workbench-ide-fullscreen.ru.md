# План реализации полноэкранной IDE верстака

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Перестроить экран верстака в полноэкранную IDE со скрываемым нижним terminal dock, видимым target slot и восстановленными lifecycle controls.

**Architecture:** Заменить переключение целых страниц editor/terminal одной полноэкранной оболочкой верстака. Ввести явные fullscreen-regions layout, перенести видимость терминала в dock sub-state и провести target slot с lifecycle actions через реальные menu/runtime pathways вместо placeholder UI.

**Tech Stack:** Kotlin, Minecraft/NeoForge menu + screen API, текущая модель workbench store/state, Gradle test tasks.

---

## Карта файлов

- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchState.kt`
  - заменить page-level переключение terminal/editor на fullscreen IDE sub-state.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStore.kt`
  - управлять видимостью terminal dock и обновлённым action state.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModel.kt`
  - вычислять fullscreen bounds для header/sidebar/editor/status/dock/slot.
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModelTest.kt`
  - зафиксировать fullscreen и dock bounds чистыми layout tests.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt`
  - поддержать обновлённую синхронизацию menu/target slot.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/WorkbenchMenuWithoutInventory.kt`
  - заменить placeholder inactive slot behavior одним видимым target slot surface.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/data/WorkbenchContainerData.kt`
  - переносить достаточно данных target state для fullscreen header/slot surface.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt`
  - обеспечить target slot постоянным server-side target item/descriptor state.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/context/ServerWorkbench.kt`
  - чисто экспонировать runtime operations для target и reboot.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/network/server/WorkbenchWorkspaceServerMessage.kt`
  - добавить недостающие lifecycle action messages, если они нужны новым header controls.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`
  - убрать no-op path у reboot и провести lifecycle controls к серверу.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt`
  - отрисовать fullscreen IDE shell, slot surface, header controls и нижний docked terminal.
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt`
  - покрыть visibility terminal dock и lifecycle action dispatch.
- Modify: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/WorkbenchMenuSmokeTest.kt`
  - покрыть видимое active target slot behavior.

### Task 1: Перевести состояние верстака на fullscreen IDE + dock model

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchState.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStore.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt`

- [ ] **Step 1: Сначала написать падающие store tests**

```kotlin
@Test
fun terminalDockStartsHiddenAndCanBeToggled() =
    runTest(UnconfinedTestDispatcher()) {
        val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), FakeWorkbenchIdeFacade())

        assertFalse(store.state.terminalVisible)

        store.toggleTerminalVisibility()
        assertTrue(store.state.terminalVisible)

        store.toggleTerminalVisibility()
        assertFalse(store.state.terminalVisible)
    }

@Test
fun rebootDelegatesToControlGateway() =
    runTest(UnconfinedTestDispatcher()) {
        val controlGateway = FakeComputerControlGateway()
        val store = WorkbenchStore(FakeWorkspaceGateway(), controlGateway, FakeWorkbenchIdeFacade())

        store.rebootComputer()

        assertEquals(1, controlGateway.rebootCalls)
    }
```

- [ ] **Step 2: Запустить tests и убедиться, что они падают**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStoreTest' --console=plain`

Expected: FAIL, потому что `terminalVisible`, `toggleTerminalVisibility()` и нужные reboot assertions ещё не реализованы.

- [ ] **Step 3: Реализовать минимальные fullscreen state changes**

```kotlin
data class WorkbenchState(
    val terminalVisible: Boolean = false,
    // keep existing editor/target/sync fields
)

fun toggleTerminalVisibility() {
    _state.value = state.copy(terminalVisible = !state.terminalVisible)
}

fun toggleMode() {
    toggleTerminalVisibility()
}
```

- [ ] **Step 4: Обновить fake control gateway в tests**

```kotlin
private class FakeComputerControlGateway : ComputerControlGateway {
    var rebootCalls: Int = 0

    override fun reboot() {
        rebootCalls += 1
    }
}
```

- [ ] **Step 5: Снова запустить tests**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStoreTest' --console=plain`

Expected: PASS.

### Task 2: Добавить fullscreen layout bounds и геометрию нижнего dock

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModel.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModelTest.kt`

- [ ] **Step 1: Написать падающие layout tests**

```kotlin
@Test
fun fullscreenLayoutAllocatesHeaderSidebarEditorAndStatusBar() {
    val layout = WorkbenchLayoutModel.fullscreen(0, 0, 1280, 720, false, fontMetrics)

    assertEquals(UiRect(0, 0, 1280, 32), layout.headerBounds)
    assertTrue(layout.sidebarBounds.width > 0)
    assertTrue(layout.editorBounds.height > 0)
    assertEquals(20, layout.statusBarBounds.height)
}

@Test
fun visibleTerminalDockReducesEditorHeight() {
    val hidden = WorkbenchLayoutModel.fullscreen(0, 0, 1280, 720, false, fontMetrics)
    val shown = WorkbenchLayoutModel.fullscreen(0, 0, 1280, 720, true, fontMetrics)

    assertTrue(shown.editorBounds.height < hidden.editorBounds.height)
    assertTrue(shown.terminalDockBounds!!.height > 0)
}
```

- [ ] **Step 2: Запустить tests и увидеть ожидаемое падение**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchLayoutModelTest' --console=plain`

Expected: FAIL, потому что fullscreen API и dock bounds ещё отсутствуют.

- [ ] **Step 3: Реализовать fullscreen layout regions**

```kotlin
data class WorkbenchLayoutModel(
    val headerBounds: UiRect,
    val sidebarBounds: UiRect,
    val editorBounds: UiRect,
    val statusBarBounds: UiRect,
    val terminalDockBounds: UiRect?,
    val targetSlotBounds: UiRect,
    val terminalToggleBounds: UiRect,
    val rebootBounds: UiRect,
)

companion object {
    fun fullscreen(
        leftPos: Int,
        topPos: Int,
        screenWidth: Int,
        screenHeight: Int,
        terminalVisible: Boolean,
        font: FontMetrics,
    ): WorkbenchLayoutModel { /* compute explicit regions */ }
}
```

- [ ] **Step 4: Привязать существующие editor helpers к новым regions**

```kotlin
fun visibleEditorLines(): Int = ((editorBounds.height - 6) / LINE_HEIGHT).coerceAtLeast(1)

fun editorTextOrigin(): Pair<Int, Int> = editorBounds.x + 40 to editorBounds.y + 6
```

- [ ] **Step 5: Снова запустить layout tests**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchLayoutModelTest' --console=plain`

Expected: PASS.

### Task 3: Подложить под target slot реальные данные верстака

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/data/WorkbenchContainerData.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/WorkbenchMenuWithoutInventory.kt`
- Test: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/WorkbenchMenuSmokeTest.kt`

- [ ] **Step 1: Написать падающий smoke test на активный target slot**

```kotlin
@Test
fun exposesActiveTargetSlotForWorkbenchHeader() {
    TestMinecraftBootstrap.ensureInitialized()

    val menu = WorkbenchMenuWithoutInventory(
        MenuType.GENERIC_9x1,
        5,
        TestInventoryFactory.create(),
        WorkbenchContainerData(),
    )

    assertTrue(menu.slots.first().isActive)
}
```

- [ ] **Step 2: Запустить smoke test и убедиться, что он падает**

Run: `./gradlew :v1_21_1-common:test --tests 'ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchMenuSmokeTest' --console=plain`

Expected: FAIL, потому что сейчас все workbench slots являются inactive placeholders.

- [ ] **Step 3: Заменить placeholder slot behavior одним dedicated target slot contract**

```kotlin
class WorkbenchMenuWithoutInventory(...) : AbstractWorkbenchMenu(...) {
    init {
        addSlot(
            object : Slot(targetContainer, 0, 0, 0) {
                override fun mayPlace(stack: ItemStack): Boolean = ServerWorkbench.extractTargetDescriptor(stack).computerId != null
                override fun isActive(): Boolean = true
            },
        )
    }
}

// Здесь `targetContainer` означает отдельный single-slot Container, который опирается на target stack в block entity верстака.
```

- [ ] **Step 4: Сохранить и пробросить backing target item/descriptor**

```kotlin
class WorkbenchBlockEntity(...) {
    private var targetStack: ItemStack = ItemStack.EMPTY

    fun setTargetStack(stack: ItemStack) {
        targetStack = stack.copy().also { it.count = 1 }
        setTarget(targetStack)
        setChanged()
    }
}

class WorkbenchContainerData(..., val targetPresent: Boolean)
```

- [ ] **Step 5: Повторно запустить smoke test**

Run: `./gradlew :v1_21_1-common:test --tests 'ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchMenuSmokeTest' --console=plain`

Expected: PASS.

### Task 4: Восстановить lifecycle actions через runtime path верстака

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/context/ServerWorkbench.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/network/server/WorkbenchWorkspaceServerMessage.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt`

- [ ] **Step 1: Написать падающий test для control-path на границе store**

```kotlin
@Test
fun runAndRebootStayAvailableWhenTargetIsConnected() =
    runTest(UnconfinedTestDispatcher()) {
        val controlGateway = FakeComputerControlGateway()
        val store = WorkbenchStore(FakeWorkspaceGateway(), controlGateway, FakeWorkbenchIdeFacade())
        val updates = FakeWorkbenchUpdateSource()

        store.bind(backgroundScope, updates)
        updates.push(WorkbenchRemoteState(target = WorkbenchTargetState(connected = true)))

        assertTrue(store.state.actions.canRun)
        store.rebootComputer()
        assertEquals(1, controlGateway.rebootCalls)
    }
```

- [ ] **Step 2: Запустить store test и увидеть, что reboot path всё ещё не доведён до конца**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStoreTest' --console=plain`

Expected: FAIL, пока reboot path полностью не проведён.

- [ ] **Step 3: Добавить явное workbench reboot action в common networking path**

```kotlin
enum class Action {
    LIST,
    READ,
    WRITE,
    PULL,
    PUSH,
    RUN,
    REBOOT,
    ATTACH_TERMINAL,
}

override fun reboot() {
    ClientNetworking.sendToServer(WorkbenchWorkspaceServerMessage(menu, WorkbenchWorkspaceServerMessage.Action.REBOOT))
}
```

- [ ] **Step 4: Научить `ServerWorkbench` и `AbstractWorkbenchMenu` выполнять reboot**

```kotlin
fun rebootTarget() {
    if (targetDescriptor.computerId == null) return
    runtimeBridge.rebootTarget(targetDescriptor)
}

when (action) {
    WorkbenchWorkspaceServerMessage.Action.REBOOT -> {
        workbench.rebootTarget()
        workbench.snapshot(_workspaceStateFlow.value.document?.path)
    }
}
```

- [ ] **Step 5: Повторно запустить focused tests**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStoreTest' --console=plain`

Expected: PASS.

### Task 5: Пересобрать `WorkbenchEditorScreen` как fullscreen IDE shell

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModel.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModelTest.kt`

- [ ] **Step 1: Написать падающие fullscreen layout assertions, которые нужны screen**

```kotlin
@Test
fun fullscreenLayoutExposesTargetSlotAndDockToggleArea() {
    val layout = WorkbenchLayoutModel.fullscreen(0, 0, 1280, 720, false, fontMetrics)

    assertTrue(layout.targetSlotBounds.width > 0)
    assertTrue(layout.headerBounds.height > 0)
}
```

- [ ] **Step 2: Запустить layout test и увидеть ожидаемое падение**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchLayoutModelTest' --console=plain`

Expected: FAIL, пока fullscreen header/slot API не завершён.

- [ ] **Step 3: Заменить page-based rendering в screen на fullscreen shell rendering**

```kotlin
override fun init() {
    imageWidth = width
    imageHeight = height
    leftPos = 0
    topPos = 0
    super.init()
}

override fun renderBg(...) {
    renderFullscreenChrome(graphics)
    renderHeader(graphics)
    renderSidebar(graphics)
    renderEditor(graphics)
    if (store.state.terminalVisible) {
        renderDockedTerminal(graphics, mouseX, mouseY)
    }
}
```

- [ ] **Step 4: Превратить старую кнопку IDE/Console в terminal visibility toggle и отрисовать target slot/header controls**

```kotlin
private fun handleHeaderClick(mouseX: Int, mouseY: Int): Boolean {
    if (layout().terminalToggleBounds.contains(mouseX, mouseY)) {
        store.toggleTerminalVisibility()
        return true
    }
    if (layout().rebootBounds.contains(mouseX, mouseY)) {
        store.rebootComputer()
        return true
    }
    return false
}
```

- [ ] **Step 5: Запустить compile и focused tests**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchLayoutModelTest' --tests 'ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStoreTest' :v1_21_1-common:test --tests 'ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchMenuSmokeTest' :v1_21_1-common:compileKotlin --console=plain`

Expected: PASS.

### Task 6: Финальная проверка

**Files:**
- Modify: none

- [ ] **Step 1: Запустить полный focused verification set**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStoreTest' --tests 'ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchLayoutModelTest' :v1_21_1-common:test --tests 'ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchMenuSmokeTest' --console=plain`

Expected: PASS.

- [ ] **Step 2: Запустить compile verification для затронутого графа модулей**

Run: `./gradlew :core:compileKotlin :v1_21_1-common:compileKotlin :v1_21_1-neoforge:compileKotlin --console=plain`

Expected: PASS.

- [ ] **Step 3: Провести ручные проверки в игре**

```text
1. Открыть workbench.
2. Проверить, что IDE заполняет экран.
3. Проверить, что в header виден один target computer slot.
4. Вставить или проверить target computer и убедиться, что его metadata обновляется.
5. Показать и скрыть нижний terminal dock.
6. Проверить, что editor расширяется, когда dock скрыт.
7. Запустить Run и Reboot через header controls.
8. Проверить, что terminal focus по-прежнему работает, когда dock видим.
```

- [ ] **Step 4: Зафиксировать manual-only gaps перед execution handoff**

```text
Если какую-то ручную проверку нельзя выполнить в текущем окружении, это нужно явно указать в итоговой execution summary.
```