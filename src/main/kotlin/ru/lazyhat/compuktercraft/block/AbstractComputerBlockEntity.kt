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

abstract class AbstractComputerBlockEntity(
    type: BlockEntityType<out AbstractComputerBlockEntity>,
    pos: BlockPos,
    state: BlockState,
    family: ComputerFamily,
) : BlockEntity(type, pos, state),
    Nameable,
    MenuConstructor {
    var family: ComputerFamily = family
        private set

    private var fresh = false
    private var _label: String? = null
    private var _computerID: Int? = null

    var label: String?
        get() = _label
        set(value) {
            value
                ?.ifServerSide(level)
                ?.takeIf { _label != value }
                ?.let {
                    _label = value
                    updateBlock()
                }
        }

    var computerID: Int?
        get() = _computerID
        set(value) {
            value
                ?.ifServerSide(level)
                ?.takeIf { _computerID != value }
                ?.let {
                    _computerID = value
                    updateBlock()
                }
        }

    abstract fun updateBlockState(newState: ComputerState)

    abstract fun createComputer(id: Int): ServerComputer

    fun serverTick() {
        if (level?.isClientSide ?: true) return
        if (_computerID == null) return
    }

    fun createServerComputer(): ServerComputer {
        val server = level?.server ?: error("Cannot access server computer on the client.")
        val serverLevel = level as? ServerLevel ?: error("[SERVER_LEVEL_GET] Cannot access server computer on the client.")

        return computerID
            ?.let {
                ServerContext.registry.getServerComputer(it)
            } ?: run {
            val newId = (0..9).random()

            val computer = createComputer(newId)
            ServerContext.registry.addServerComputer(computer)

            fresh = true
            _computerID = newId

            updateBlock()
            computer
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
