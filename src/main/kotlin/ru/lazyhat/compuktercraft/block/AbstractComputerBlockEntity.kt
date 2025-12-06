package ru.lazyhat.compuktercraft.block

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Nameable
import net.minecraft.world.inventory.MenuConstructor
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compuktercraft.computer.ServerComputer
import ru.lazyhat.compuktercraft.context.ServerContext
import ru.lazyhat.compuktercraft.utils.computerID
import ru.lazyhat.compuktercraft.utils.computerLabel
import ru.lazyhat.compuktercraft.utils.ifServerSide
import ru.lazyhat.compuktercraft.utils.updateBlock
import java.util.UUID

abstract class AbstractComputerBlockEntity(
    type: BlockEntityType<out AbstractComputerBlockEntity>,
    pos: BlockPos,
    state: BlockState,
    family: ComputerFamily,
) : BlockEntity(type, pos, state),
    Nameable,
    MenuConstructor {
    private var instanceUUID: UUID? = null
    var family: ComputerFamily = family
        private set

    private var fresh = false
    private var _label: String? = null
    private var _computerID: Int? = null

    var label: String? = _label
        get() = _label
        set(value) {
            value
                ?.ifServerSide(level)
                ?.takeIf { field != value }
                ?.let {
                    field = value
                    updateBlock()
                }
        }

    var computerID: Int? = _computerID
        get() = _computerID
        set(value) {
            value
                ?.ifServerSide(level)
                ?.takeIf { _computerID != value }
                ?.let {
                    field = value
                    updateBlock()
                }
        }

    fun serverTick() {
        if (level?.isClientSide ?: false) return
        if (_computerID != null) return

        _computerID = (0..9).random()
    }

    fun createServerComputer(): ServerComputer {
        val server = level?.server ?: error("Cannot access server computer on the client.")
        val serverLevel = level as? ServerLevel ?: error("[SERVER_LEVEL_GET] Cannot access server computer on the client.")

        val changed = false

        return ServerContext.getComputer(instanceUUID) ?: ServerContext.createComputer(serverLevel, blockPos, family).let {
            fresh = true
            instanceUUID = it.first
            _computerID = it.second.instanceID

            updateBlock()
            it.second
        }

//        val computer = ServerContext.get(server).registry().get(instanceID)
//        if (computer == null) {
//            if (computerID == null) {
//                computerID = ComputerCraftAPI.createUniqueNumberedSaveDir(server, IDAssigner.COMPUTER)
//                BlockEntityHelpers.updateBlock(this)
//            }
//
//            computer = createComputer(computerID)
//            instanceID = computer.register()
//
//
//            fresh = true
//            changed = true
//        }
//
//        //if (changed) updateInputsImmediately(computer)
//        return computer!!
    }

    override fun saveAdditional(tag: CompoundTag) {
        tag.computerID = _computerID
        tag.computerLabel = _label

        super.saveAdditional(tag)
    }

    override fun load(tag: CompoundTag) {
        _computerID = tag.computerID
        _label = tag.computerLabel
    }

    override fun getName(): Component = customName ?: Component.translatable(blockState.block.getDescriptionId())

    override fun hasCustomName(): Boolean = !_label.isNullOrEmpty()

    override fun getCustomName(): Component? = _label?.takeIf { it.isEmpty() }?.let { Component.literal(it) }
}
