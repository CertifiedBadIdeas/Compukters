package ru.lazyhat.compuktercraft.block

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.Nameable
import net.minecraft.world.inventory.MenuConstructor
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
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
    var family: ComputerFamily = family
        private set

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

    private var instanceUUID: UUID? = null

    fun serverTick() {
        if (level?.isClientSide ?: false) return
        if (_computerID != null) return

        _computerID = (0..9).random()
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
