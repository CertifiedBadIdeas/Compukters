/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.common.workbench.block

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.computer.context.ServerContext
import ru.lazyhat.compukterkraft.common.localization.CompukterComponents
import ru.lazyhat.compukterkraft.common.network.ServerNetworking
import ru.lazyhat.compukterkraft.common.workbench.context.ServerWorkbench
import ru.lazyhat.compukterkraft.common.workbench.context.WorkbenchTargetRuntimeBridge
import ru.lazyhat.compukterkraft.common.workbench.data.WorkbenchContainerData
import ru.lazyhat.compukterkraft.common.workbench.menu.AbstractWorkbenchMenu
import ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchMenuWithoutInventory
import ru.lazyhat.compukterkraft.common.workbench.network.client.WorkbenchTerminalClientMessage
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDevice
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceImpl
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DeviceStateSink
import ru.lazyhat.compukterkraft.core.device.runtime.ports.GameTimeSource
import ru.lazyhat.compukterkraft.core.device.runtime.ports.NoopDisplayNetworkBridge
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

class WorkbenchBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModObjects.workbenchBlockEntityType(), pos, state),
    MenuProvider {
    private var workspaceId: Int? = null
    private var targetStack: ItemStack = ItemStack.EMPTY
    private var targetComputerId: Int? = null
    private var targetDisplayName: String? = null
    private var targetFamilyId: String? = null
    private var serverWorkbench: ServerWorkbench? = null
    private var detachedTargetComputer: RuntimeDevice? = null
    private var lastSyncedSnapshot: ScreenBufferSnapshot? = null

    override fun createMenu(
        containerId: Int,
        playerInventory: Inventory,
        player: Player,
    ): AbstractContainerMenu {
        val workbench = getOrCreateServerWorkbench()
        return WorkbenchMenuWithoutInventory(
            ModObjects.workbenchMenuType(),
            containerId,
            playerInventory,
            WorkbenchContainerData.from(workbench.targetState(), currentTargetStack()),
            workbench,
            ::setTargetStack,
        )
    }

    override fun getDisplayName(): Component = CompukterComponents.Block.workbench

    fun openFor(player: Player) {
        val serverPlayer = player as? ServerPlayer ?: return
        ModObjects.openWorkbenchMenu(
            serverPlayer,
            this,
            WorkbenchContainerData.from(getOrCreateServerWorkbench().targetState(), currentTargetStack()),
        )
    }

    fun setTargetStack(stack: ItemStack) {
        val singleStack = if (stack.isEmpty) ItemStack.EMPTY else stack.copy().also { it.count = 1 }
        val descriptor = ServerWorkbench.extractTargetDescriptor(singleStack)
        if (descriptor.deviceId != targetComputerId) {
            releaseDetachedTargetComputer()
            lastSyncedSnapshot = null
        }
        targetStack = singleStack
        targetComputerId = descriptor.deviceId
        targetDisplayName = descriptor.displayName
        targetFamilyId = descriptor.familyId
        serverWorkbench?.setTarget(descriptor)
        setChanged()
    }

    fun currentTargetStack(): ItemStack = targetStack.copy()

    fun getOrCreateServerWorkbench(): ServerWorkbench {
        check(level?.isClientSide == false) { "Cannot access server workbench on the client." }

        val resolvedWorkspaceId =
            workspaceId ?: ServerContext.allocateDeviceId().also { allocatedWorkspaceId ->
                workspaceId = allocatedWorkspaceId
                ServerContext.deviceManager.ensureWorkspaceInitialized(allocatedWorkspaceId)
                setChanged()
            }

        return serverWorkbench
            ?: ServerWorkbench(
                workspaceId = resolvedWorkspaceId,
                workspace = ServerContext.deviceManager.workspace,
                initialTarget =
                    ServerWorkbench.TargetDescriptor(
                        deviceId = targetComputerId,
                        displayName = targetDisplayName,
                        familyId = targetFamilyId,
                    ),
            ).also {
                it.bindRuntimeBridge(RuntimeBridge())
                serverWorkbench = it
            }
    }

    fun serverTick() {
        if (level?.isClientSide != false) return

        val workbench = serverWorkbench ?: return
        val targetDescriptor = workbench.targetDescriptor()
        val targetId = targetDescriptor.deviceId

        if (targetId == null) {
            releaseDetachedTargetComputer()
            syncTargetSnapshot(null)
            return
        }

        val liveComputer = ServerContext.deviceManager.get(targetId)
        if (liveComputer != null) {
            releaseDetachedTargetComputer()
            syncTargetSnapshot(liveComputer.lastScreenSnapshot)
            return
        }

        detachedTargetComputer?.serverTick()
        syncTargetSnapshot(detachedTargetComputer?.lastScreenSnapshot)
    }

    override fun saveAdditional(
        tag: CompoundTag,
        registries: HolderLookup.Provider,
    ) {
        workspaceId?.let { tag.putInt(WORKSPACE_ID_TAG, it) }
        if (!targetStack.isEmpty) {
            tag.put(TARGET_STACK_TAG, targetStack.save(registries))
        }
        targetComputerId?.let { tag.putInt(TARGET_COMPUTER_ID_TAG, it) }
        targetDisplayName?.let { tag.putString(TARGET_DISPLAY_NAME_TAG, it) }
        targetFamilyId?.let { tag.putString(TARGET_FAMILY_ID_TAG, it) }
        super.saveAdditional(tag, registries)
    }

    override fun loadAdditional(
        tag: CompoundTag,
        registries: HolderLookup.Provider,
    ) {
        super.loadAdditional(tag, registries)
        workspaceId = tag.takeIf { it.contains(WORKSPACE_ID_TAG) }?.getInt(WORKSPACE_ID_TAG)
        targetStack =
            if (tag.contains(TARGET_STACK_TAG)) ItemStack.parseOptional(registries, tag.getCompound(TARGET_STACK_TAG)) else ItemStack.EMPTY
        targetComputerId = tag.takeIf { it.contains(TARGET_COMPUTER_ID_TAG) }?.getInt(TARGET_COMPUTER_ID_TAG)
        targetDisplayName = tag.takeIf { it.contains(TARGET_DISPLAY_NAME_TAG) }?.getString(TARGET_DISPLAY_NAME_TAG)
        targetFamilyId = tag.takeIf { it.contains(TARGET_FAMILY_ID_TAG) }?.getString(TARGET_FAMILY_ID_TAG)
        serverWorkbench = null
        releaseDetachedTargetComputer()
        lastSyncedSnapshot = null
    }

    override fun setRemoved() {
        releaseDetachedTargetComputer()
        super.setRemoved()
    }

    private fun resolveTargetComputer(createDetached: Boolean): RuntimeDevice? {
        val targetId = getOrCreateServerWorkbench().targetDescriptor().deviceId ?: return null
        val liveComputer = ServerContext.deviceManager.get(targetId)
        if (liveComputer != null) {
            releaseDetachedTargetComputer()
            return liveComputer
        }

        val existingDetached = detachedTargetComputer?.takeIf { it.deviceId == targetId }
        if (existingDetached != null || !createDetached) {
            return existingDetached
        }

        val serverLevel = level as? ServerLevel ?: return null
        ServerContext.deviceManager.ensureWorkspaceInitialized(targetId)
        return RuntimeDeviceImpl(
            deviceId = targetId,
            properties = DeviceProperties(resolveTargetFamily(targetFamilyId), targetDisplayName),
            manager = ServerContext.deviceManager,
            gameTime = GameTimeSource { serverLevel.gameTime },
            displayNetwork = NoopDisplayNetworkBridge,
            stateSink = DeviceStateSink { /* detached: no block state to update */ },
        ).also {
            detachedTargetComputer = it
        }
    }

    private fun syncTargetSnapshot(snapshot: ScreenBufferSnapshot?) {
        if (snapshot == lastSyncedSnapshot) return
        lastSyncedSnapshot = snapshot

        val workbench = serverWorkbench ?: return
        viewingPlayers(workbench).forEach { player ->
            val menu = player.containerMenu as? AbstractWorkbenchMenu ?: return@forEach
            menu.updateScreenSnapshot(snapshot)
            ServerNetworking.sendToPlayer(WorkbenchTerminalClientMessage(menu, snapshot), player)
        }
    }

    private fun viewingPlayers(workbench: ServerWorkbench): List<ServerPlayer> =
        ServerContext.server.playerList.players.filter { player ->
            val menu = player.containerMenu as? AbstractWorkbenchMenu ?: return@filter false
            menu.serverWorkbenchIdentity() === workbench
        }

    private fun releaseDetachedTargetComputer() {
        detachedTargetComputer?.close()
        detachedTargetComputer = null
    }

    private fun resolveTargetFamily(familyId: String?): DeviceFamily =
        familyId
            ?.let { id -> DeviceFamily.entries.firstOrNull { it.name.equals(id, ignoreCase = true) } }
            ?: DeviceFamily.NORMAL

    private inner class RuntimeBridge : WorkbenchTargetRuntimeBridge {
        override fun rebootTarget(target: ServerWorkbench.TargetDescriptor) {
            resolveTargetComputer(createDetached = true)?.reboot()
            syncTargetSnapshot(resolveTargetComputer(createDetached = false)?.lastScreenSnapshot)
        }

        override fun runTargetProgram(target: ServerWorkbench.TargetDescriptor) {
            resolveTargetComputer(createDetached = true)?.turnOn()
            syncTargetSnapshot(resolveTargetComputer(createDetached = false)?.lastScreenSnapshot)
        }

        override fun attachTerminal(target: ServerWorkbench.TargetDescriptor) {
            // Disabled with stdout terminal transport removal. Workbench display
            // viewing should be reintroduced as a display session observer.
        }

        override fun queueEvent(
            target: ServerWorkbench.TargetDescriptor,
            event: String,
            arguments: Array<Any>,
        ): Boolean {
            val computer = resolveTargetComputer(createDetached = false) ?: return false
            if (!computer.isOn) return false
            computer.queueEvent(event, arguments)
            return true
        }

        override fun currentScreenSnapshot(target: ServerWorkbench.TargetDescriptor): ScreenBufferSnapshot? =
            resolveTargetComputer(createDetached = false)?.lastScreenSnapshot
    }

    companion object {
        private const val WORKSPACE_ID_TAG = "WorkbenchWorkspaceId"
        private const val TARGET_STACK_TAG = "TargetStack"
        private const val TARGET_COMPUTER_ID_TAG = "TargetComputerId"
        private const val TARGET_DISPLAY_NAME_TAG = "TargetDisplayName"
        private const val TARGET_FAMILY_ID_TAG = "TargetFamilyId"
    }
}
