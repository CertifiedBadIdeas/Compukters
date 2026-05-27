# Computer Terminal-Only UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove IDE and file-management behavior from computer UIs so computers open a terminal-only runtime screen while Workbench remains the only authoring surface.

**Architecture:** Keep the runtime filesystem and VM unchanged, but cut the computer-side authoring integration at the Minecraft-facing layer. Replace the current computer editor screen with a dedicated terminal screen, simplify the computer menu contract to terminal/runtime state only, and delete the computer workspace packet path so authoring flows exist only through Workbench.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, Architectury multi-module layout, NeoForge 1.21.1, existing `WorkbenchTerminalRenderer`, existing computer input/network infrastructure, kotlin.test.

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/ComputerMenu.kt` | Modify | Remove workspace/editor methods from the computer menu contract |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt` | Modify | Keep only runtime-facing client/server state for computers |
| `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/MenuSideClientTest.kt` | Modify | Lock in terminal snapshot behavior that must survive the cleanup |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt` | Create | Dedicated terminal-only computer UI |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerWorkbenchScreen.kt` | Delete | Remove the old editor-oriented computer UI |
| `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ClientRegistry.kt` | Modify | Register the new terminal-only computer screen |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt` | Modify | Remove computer-only workspace gateway adapter and stale comments |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt` | Modify | Delete computer workspace client handlers |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt` | Modify | Stop routing computer workspace packets into menus |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt` | Modify | Remove computer workspace packet registrations while keeping terminal/workbench channels intact |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/ComputerWorkspaceServerMessage.kt` | Delete | Remove computer-side authoring packet entry point |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/ComputerWorkspaceClientMessage.kt` | Delete | Remove computer-side workspace response packet |
| `docs/ARCHITECTURE.md` | Modify | Update architecture docs to describe computers as runtime-only and Workbench as authoring-only |
| `docs/PACKAGE-GUIDELINE.md` | Modify | Remove workbench from the computer package responsibility description |

### Task 1: Reduce The Computer Menu Contract To Runtime State

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/ComputerMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/MenuSideClientTest.kt`

- [ ] **Step 1: Extend the client-side menu test around terminal snapshot flow**

Replace `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/MenuSideClientTest.kt` with:

```kotlin
package ru.lazyhat.compukterkraft.common.computer.menu

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MenuSideClientTest {
    @Test
    fun clientSideCanStartWithoutSnapshotAndLaterReceiveOne() {
        val client = MenuSide.Client(initialSnapshot = null)

        assertNull(client.screenSnapshot)

        val snapshot = ScreenBufferSnapshot.empty(width = 12, height = 6, colour = true)
        client.updateScreenSnapshot(snapshot)

        assertEquals(snapshot, client.screenSnapshot)
    }

    @Test
    fun screenSnapshotFlowEmitsLatestTerminalFrame() =
        runTest {
            val client = MenuSide.Client(initialSnapshot = null)
            val snapshot = ScreenBufferSnapshot.empty(width = 20, height = 8, colour = true)

            client.updateScreenSnapshot(snapshot)

            assertEquals(snapshot, client.screenSnapshotFlow.first())
        }
}
```

- [ ] **Step 2: Run the computer menu test before changing the contract**

Run: `./gradlew :v1_21_1-common:test --tests "ru.lazyhat.compukterkraft.common.computer.menu.MenuSideClientTest" --no-daemon`

Expected: PASS. This locks in the terminal-sync behavior that must remain after menu cleanup.

- [ ] **Step 3: Remove workspace/editor methods from the computer menu interface**

Update `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/ComputerMenu.kt` to:

```kotlin
package ru.lazyhat.compukterkraft.common.computer.menu

import ru.lazyhat.compukterkraft.core.block.ComputerFamily
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

interface ComputerMenu {
    val side: MenuSide

    val family: ComputerFamily

    val serverSide: MenuSide.Server
        get() = side as MenuSide.Server

    val clientSide: MenuSide.Client
        get() = side as MenuSide.Client

    fun updateTerminal(snapshot: ScreenBufferSnapshot)
}
```

- [ ] **Step 4: Remove workspace state from `AbstractComputerMenu`**

Update `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt` by deleting `WorkbenchRemoteState`, `ComputerWorkspaceEntry`, and `ComputerWorkspaceDocument` imports and reducing the class body to:

```kotlin
abstract class AbstractComputerMenu(
    type: MenuType<out AbstractComputerMenu>,
    id: Int,
    private val canUse: (Player) -> Boolean,
    override val family: ComputerFamily,
    computer: ServerComputer?,
    containerData: ComputerContainerData?,
) : AbstractContainerMenu(type, id),
    ComputerMenu {
    val uploadMaxSize: Int

    private val data: ContainerData =
        if (computer == null) {
            SimpleContainerData(1)
        } else {
            SingleContainerData { if (computer.isOn) 1 else 0 }
        }

    override val side: MenuSide =
        if (computer != null) {
            MenuSide.Server(computer, ServerInputState(this))
        } else {
            MenuSide.Client(containerData?.terminalSnapshot)
        }

    val isComputerOn: Boolean get() = data.get(0) == 1

    val displayStack: ItemStack = containerData?.displayStack ?: ItemStack.EMPTY

    init {
        addDataSlots(data)
        uploadMaxSize = containerData?.uploadMaxSize ?: Config.uploadMaxSize
    }

    override fun stillValid(player: Player): Boolean {
        val server = side as? MenuSide.Server
        return (server == null || server.computer.checkUsable(player)) && canUse(player)
    }

    override fun updateTerminal(snapshot: ScreenBufferSnapshot) {
        val client =
            side as? MenuSide.Client
                ?: throw UnsupportedOperationException("Cannot update terminal on the server")
        client.updateScreenSnapshot(snapshot)
    }

    override fun removed(player: Player) {
        super.removed(player)
        (side as? MenuSide.Server)?.input?.close()
    }
}
```

- [ ] **Step 5: Run the targeted test again after the cleanup**

Run: `./gradlew :v1_21_1-common:test --tests "ru.lazyhat.compukterkraft.common.computer.menu.MenuSideClientTest" --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit the menu contract cleanup**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/ComputerMenu.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt \
        modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/MenuSideClientTest.kt
git commit -m "refactor: drop workspace state from computer menu"
```

### Task 2: Replace The Computer Editor Screen With A Terminal-Only Screen

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt`
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerWorkbenchScreen.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ClientRegistry.kt`

- [ ] **Step 1: Create a terminal-only computer screen**

Create `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt` with:

```kotlin
package ru.lazyhat.compukterkraft.common.computer.screen

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.computer.input.ClientInputHandler
import ru.lazyhat.compukterkraft.common.platform.MinecraftInputProvider
import ru.lazyhat.compukterkraft.common.ui.render.WorkbenchTerminalRenderer
import ru.lazyhat.compukterkraft.common.computer.menu.AbstractComputerMenu
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchMode
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalInputController
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalLayout
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetrics
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalInteractionPolicy
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalViewState
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

class ComputerTerminalScreen<T : AbstractComputerMenu>(
    container: T,
    player: Inventory,
    title: Component,
) : ComputerScreen<T>(container, player, title) {
    private val inputHandler = ClientInputHandler(container)
    private val terminalInput = WorkbenchTerminalInputController(inputHandler, MinecraftInputProvider)

    init {
        val (terminalColumns, terminalRows) = terminalDimensions(container.clientSide.screenSnapshot)
        imageWidth = WorkbenchTerminalMetrics.imageWidth(terminalColumns, terminalRows)
        imageHeight = WorkbenchTerminalMetrics.imageHeight(terminalColumns, terminalRows)
    }

    override fun containerTick() {
        super.containerTick()
        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)
        if (terminalState !is WorkbenchTerminalViewState.Active && terminalInput.focused) {
            terminalInput.focused = false
        }
        syncTerminalWindowSize(terminalState)
        terminalInput.update()
    }

    override fun renderBg(
        graphics: GuiGraphics,
        partialTicks: Float,
        mouseX: Int,
        mouseY: Int,
    ) {
        val snapshot = menu.clientSide.screenSnapshot
        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, snapshot)
        val focused = WorkbenchTerminalInteractionPolicy.canAcceptInput(WorkbenchMode.TERMINAL, terminalState, terminalInput.focused)
        val showFocusHint = WorkbenchTerminalInteractionPolicy.showFocusHint(terminalState, terminalInput.focused)

        WorkbenchTerminalRenderer.render(
            graphics,
            minecraft!!.font,
            leftPos,
            topPos,
            imageWidth,
            imageHeight,
            terminalLayout(),
            terminalState,
            focused,
            showFocusHint,
            Component.translatable("gui.compukterkraft.terminal.powered_off").string,
            Component.translatable("gui.compukterkraft.terminal.connecting").string,
        )
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
    ) {
        renderBackground(graphics, mouseX, mouseY, partialTicks)
        super.render(graphics, mouseX, mouseY, partialTicks)
        renderTooltip(graphics, mouseX, mouseY)
    }

    override fun renderLabels(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
    }

    override fun keyPressed(
        key: Int,
        scancode: Int,
        modifiers: Int,
    ): Boolean {
        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)
        if (WorkbenchTerminalInteractionPolicy.canAcceptInput(WorkbenchMode.TERMINAL, terminalState, terminalInput.focused)) {
            if (terminalInput.keyPressed(key, scancode, modifiers)) {
                return true
            }
        }
        return super.keyPressed(key, scancode, modifiers)
    }

    override fun keyReleased(
        key: Int,
        scancode: Int,
        modifiers: Int,
    ): Boolean {
        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)
        if (WorkbenchTerminalInteractionPolicy.canAcceptInput(WorkbenchMode.TERMINAL, terminalState, terminalInput.focused)) {
            if (terminalInput.keyReleased(key, scancode)) {
                return true
            }
        }
        return super.keyReleased(key, scancode, modifiers)
    }

    override fun charTyped(
        ch: Char,
        modifiers: Int,
    ): Boolean {
        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)
        if (WorkbenchTerminalInteractionPolicy.canAcceptInput(WorkbenchMode.TERMINAL, terminalState, terminalInput.focused)) {
            return terminalInput.charTyped(ch)
        }
        return super.charTyped(ch, modifiers)
    }

    override fun mouseClicked(
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean {
        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)
        if (terminalState is WorkbenchTerminalViewState.Active) {
            terminalInput.focused = terminalInput.mouseClicked(terminalLayout().terminalBounds, mouseX, mouseY)
        } else {
            terminalInput.focused = false
        }
        return terminalInput.focused || super.mouseClicked(mouseX, mouseY, button)
    }

    private fun terminalLayout(): WorkbenchTerminalLayout {
        val (terminalColumns, terminalRows) = terminalDimensions(menu.clientSide.screenSnapshot)
        return WorkbenchTerminalMetrics.layout(leftPos, topPos, imageWidth, imageHeight, terminalColumns, terminalRows)
    }

    private fun syncTerminalWindowSize(terminalState: WorkbenchTerminalViewState) {
        val (terminalColumns, terminalRows) =
            when (terminalState) {
                is WorkbenchTerminalViewState.Active -> terminalDimensions(terminalState.snapshot)
                WorkbenchTerminalViewState.PoweredOff, WorkbenchTerminalViewState.Connecting -> 0 to 0
            }

        val nextWidth = WorkbenchTerminalMetrics.imageWidth(terminalColumns, terminalRows)
        val nextHeight = WorkbenchTerminalMetrics.imageHeight(terminalColumns, terminalRows)
        if (imageWidth != nextWidth || imageHeight != nextHeight) {
            imageWidth = nextWidth
            imageHeight = nextHeight
            leftPos = (width - imageWidth) / 2
            topPos = (height - imageHeight) / 2
        }
    }

    private fun terminalDimensions(snapshot: ScreenBufferSnapshot?): Pair<Int, Int> =
        if (snapshot == null) {
            0 to 0
        } else {
            snapshot.width to snapshot.height
        }
}
```

- [ ] **Step 2: Delete the old computer editor screen**

Delete `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerWorkbenchScreen.kt`.

- [ ] **Step 3: Register the new computer screen**

Update `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ClientRegistry.kt` to:

```kotlin
package ru.lazyhat.compukterkraft.impl

import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import ru.lazyhat.compukterkraft.common.computer.screen.ComputerTerminalScreen
import ru.lazyhat.compukterkraft.common.workbench.screen.WorkbenchEditorScreen
import ru.lazyhat.compukterkraft.core.LOGGER

object ClientRegistry {
    fun register(event: RegisterMenuScreensEvent) {
        try {
            event.register(
                ModRegistry.Menus.COMPUTER.get(),
                { container, inventory, title -> ComputerTerminalScreen(container, inventory, title) },
            )
            event.register(
                ModRegistry.Menus.WORKBENCH.get(),
                { container, inventory, title -> WorkbenchEditorScreen(container, inventory, title) },
            )
            LOGGER.info { "ClientRegistry: terminal-only computer screen successfully registered" }
        } catch (e: Exception) {
            LOGGER.error { "ClientRegistry: computer screen registration failed: ${e.message}" }
        }
    }
}
```

- [ ] **Step 4: Compile the common and NeoForge client code**

Run: `./gradlew :v1_21_1-common:compileKotlin :v1_21_1-neoforge:compileKotlin --no-daemon`

Expected: PASS.

- [ ] **Step 5: Verify the computer UI path no longer constructs `WorkbenchStore`**

Run: `rg "WorkbenchStore|requestListing|requestDocument|openImportPicker" modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer`

Expected: no output.

- [ ] **Step 6: Commit the screen replacement**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt \
        modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ClientRegistry.kt
git rm modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerWorkbenchScreen.kt
git commit -m "refactor: make computer ui terminal only"
```

### Task 3: Delete The Computer Workspace Packet Path

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt`
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/ComputerWorkspaceServerMessage.kt`
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/ComputerWorkspaceClientMessage.kt`

- [ ] **Step 1: Remove the computer workspace gateway adapter**

In `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`, delete the `NetworkWorkspaceGateway` class and update the `MenuWorkspaceUpdateSource` comment to match Workbench-only usage:

```kotlin
/**
 * Adapts menu-owned Workbench remote state to the [WorkbenchUpdateSource] interface.
 */
class MenuWorkspaceUpdateSource(
    private val remoteStateFlow: StateFlow<WorkbenchRemoteState>,
) : WorkbenchUpdateSource {
    override val stateFlow: StateFlow<WorkbenchRemoteState> = remoteStateFlow
}
```

- [ ] **Step 2: Remove computer workspace handlers from the client network context**

Update `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt` to:

```kotlin
package ru.lazyhat.compukterkraft.common.network

import ru.lazyhat.compukterkraft.common.network.text.TableBuilder
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchRemoteState
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

interface ClientNetworkContext {
    fun handleChatTable(table: TableBuilder)

    fun handleComputerTerminal(
        containerId: Int,
        snapshot: ScreenBufferSnapshot,
    )

    fun handleWorkbenchWorkspace(
        containerId: Int,
        remoteState: WorkbenchRemoteState,
    )

    fun handleWorkbenchTerminal(
        containerId: Int,
        snapshot: ScreenBufferSnapshot?,
    )
}
```

- [ ] **Step 3: Remove dead computer workspace routing from `ClientNetworkContextImpl`**

Update `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt` by deleting the `handleComputerWorkspaceEntries` and `handleComputerWorkspaceDocument` overrides so the implementation keeps only terminal and Workbench routes:

```kotlin
override fun handleComputerTerminal(
    containerId: Int,
    snapshot: ScreenBufferSnapshot,
) = withCheckedContainerMenu(containerId) {
    updateTerminal(snapshot)
}

override fun handleWorkbenchWorkspace(
    containerId: Int,
    remoteState: WorkbenchRemoteState,
) = withCheckedWorkbenchMenu(containerId) {
    updateRemoteState(remoteState)
}

override fun handleWorkbenchTerminal(
    containerId: Int,
    snapshot: ScreenBufferSnapshot?,
) = withCheckedWorkbenchMenu(containerId) {
    updateScreenSnapshot(snapshot)
}
```

- [ ] **Step 4: Remove computer workspace message registrations but keep packet ids stable**

Update `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt` so:

```kotlin
import ru.lazyhat.compukterkraft.common.computer.network.client.ComputerTerminalClientMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.ComputerActionServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.KeyEventServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.MouseEventServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.PasteEventComputerMessage
import ru.lazyhat.compukterkraft.common.network.text.ChatTableClientMessage
import ru.lazyhat.compukterkraft.common.workbench.network.client.WorkbenchTerminalClientMessage
import ru.lazyhat.compukterkraft.common.workbench.network.client.WorkbenchWorkspaceClientMessage
import ru.lazyhat.compukterkraft.common.workbench.network.server.WorkbenchInputServerMessage
import ru.lazyhat.compukterkraft.common.workbench.network.server.WorkbenchWorkspaceServerMessage

// Keep ids 4 and 14 unused instead of renumbering the rest of the protocol.

val WORKBENCH_WORKSPACE_REQUEST: MessageType<WorkbenchWorkspaceServerMessage> =
    registerServerbound(
        5,
        "workbench_workspace_request",
        { buf -> WorkbenchWorkspaceServerMessage(buf) },
    )

val WORKBENCH_WORKSPACE: MessageType<WorkbenchWorkspaceClientMessage> =
    registerClientbound(
        15,
        "workbench_workspace",
        { buf -> WorkbenchWorkspaceClientMessage(buf) },
    )
```

Also delete the `computer_workspace_request` and `computer_workspace` rows from the protocol docs in the same file.

- [ ] **Step 5: Delete the obsolete computer workspace packet classes**

Delete:

```text
modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/ComputerWorkspaceServerMessage.kt
modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/ComputerWorkspaceClientMessage.kt
```

- [ ] **Step 6: Compile and grep for stray computer-authoring references**

Run: `./gradlew :v1_21_1-common:compileKotlin :v1_21_1-neoforge:compileKotlin --no-daemon`

Expected: PASS.

Run: `rg "ComputerWorkspace(Server|Client)Message|handleComputerWorkspace|NetworkWorkspaceGateway|workspaceStateFlow" modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench`

Expected: output only from Workbench-owned files that intentionally keep `workspaceStateFlow`; no hits from computer UI/network code.

- [ ] **Step 7: Commit the network cleanup**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt
git rm modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/ComputerWorkspaceServerMessage.kt \
       modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/ComputerWorkspaceClientMessage.kt
git commit -m "refactor: remove computer workspace packet path"
```

### Task 4: Update Product Documentation And Run Final Verification

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/PACKAGE-GUIDELINE.md`

- [ ] **Step 1: Update the architecture package map and Workbench description**

In `docs/ARCHITECTURE.md`, make these content changes:

```md
| `compukterkraft.common.computer.screen`           | `ComputerScreen`, `ComputerTerminalScreen`                         |

### `WorkbenchStore`

Client-side state management for the Workbench authoring GUI.

- Manages editor state, workspace file list, IDE features (diagnostics, completions).
- Pure state container — no Minecraft dependencies.
```

Also remove any active-flow references that describe `ComputerWorkbenchScreen` as the computer UI.

- [ ] **Step 2: Update the package guideline to separate Workbench from computer responsibilities**

In `docs/PACKAGE-GUIDELINE.md`, change the package description to:

```md
- `computer/` — computer-specific runtime logic: VM, runtime, input, terminal-facing UI
- `workbench/` — authoring logic: editor, workspace sync, target-aware development UI
```

- [ ] **Step 3: Run the final verification set**

Run: `./gradlew :v1_21_1-common:test :v1_21_1-common:compileKotlin :v1_21_1-neoforge:compileKotlin --no-daemon`

Expected: PASS.

Run: `rg "ComputerWorkbenchScreen|computer workbench GUI|computer_workspace_request|computer_workspace" docs/ARCHITECTURE.md docs/PACKAGE-GUIDELINE.md modules/v1_21_1/v1_21_1-common/src/main/kotlin modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin`

Expected: no output.

- [ ] **Step 4: Commit the documentation and verification pass**

```bash
git add docs/ARCHITECTURE.md docs/PACKAGE-GUIDELINE.md
git commit -m "docs: describe computers as terminal-only runtime ui"
```