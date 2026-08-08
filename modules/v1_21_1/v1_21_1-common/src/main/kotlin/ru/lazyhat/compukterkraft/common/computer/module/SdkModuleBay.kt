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

package ru.lazyhat.compukterkraft.common.computer.module

import net.minecraft.core.NonNullList
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import kotlin.math.min

class SdkModuleBay(
    private val items: NonNullList<ItemStack> = NonNullList.withSize(1, ItemStack.EMPTY),
    private val artifactIdentity: (ItemStack) -> String?,
    private val isKnownArtifact: (String) -> Boolean,
    private val isRuntimeOn: () -> Boolean,
    private val commitMutation: (() -> Unit) -> Boolean,
) : Container {
    private var storedItem: ItemStack
        get() = items[0]
        set(value) {
            items[0] = value
        }

    val installedArtifactIdentity: String?
        get() = storedItem.takeUnless(ItemStack::isEmpty)?.let(artifactIdentity)

    override fun getContainerSize(): Int = 1

    override fun isEmpty(): Boolean = storedItem.isEmpty

    override fun getItem(slot: Int): ItemStack {
        requireSlot(slot)
        return storedItem.copy()
    }

    override fun removeItem(
        slot: Int,
        amount: Int,
    ): ItemStack {
        requireSlot(slot)
        if (amount <= 0 || storedItem.isEmpty || isRuntimeOn()) return ItemStack.EMPTY
        val removed = storedItem.copyWithCount(min(amount, storedItem.count))
        return if (replace(ItemStack.EMPTY)) removed else ItemStack.EMPTY
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        requireSlot(slot)
        if (storedItem.isEmpty || isRuntimeOn()) return ItemStack.EMPTY
        val removed = storedItem.copy()
        return if (replace(ItemStack.EMPTY)) removed else ItemStack.EMPTY
    }

    override fun setItem(
        slot: Int,
        stack: ItemStack,
    ) {
        requireSlot(slot)
        replace(stack)
    }

    override fun getMaxStackSize(): Int = 1

    override fun setChanged() = Unit

    override fun stillValid(player: Player): Boolean = true

    override fun canPlaceItem(
        slot: Int,
        stack: ItemStack,
    ): Boolean =
        slot == 0 &&
            !isRuntimeOn() &&
            validateInsertable(stack) != null

    override fun clearContent() {
        if (!storedItem.isEmpty) replace(ItemStack.EMPTY)
    }

    fun setFromPlayer(stack: ItemStack): Boolean = replace(stack)

    fun restoreStoredItem(stack: ItemStack): Boolean {
        if (stack.isEmpty) {
            storedItem = ItemStack.EMPTY
            return false
        }
        require(stack.count == 1) { "K16 SDK module bay stores exactly one item" }
        val identity =
            requireNotNull(artifactIdentity(stack)) {
                "Stored K16 SDK module has no artifact identity"
            }
        requireValidSdkArtifactIdentity(identity)
        storedItem = stack.copyWithCount(1)
        return true
    }

    private fun replace(stack: ItemStack): Boolean {
        if (isRuntimeOn()) return false
        val replacement =
            if (stack.isEmpty) {
                ItemStack.EMPTY
            } else {
                validateInsertable(stack) ?: return false
            }
        if (ItemStack.matches(storedItem, replacement)) return false
        return commitMutation {
            storedItem = replacement
        }
    }

    private fun validateInsertable(stack: ItemStack): ItemStack? {
        if (stack.isEmpty || stack.count != 1) return null
        val identity = artifactIdentity(stack) ?: return null
        return runCatching { requireValidSdkArtifactIdentity(identity) }
            .getOrNull()
            ?.takeIf(isKnownArtifact)
            ?.let { stack.copyWithCount(1) }
    }

    private fun requireSlot(slot: Int) {
        require(slot == 0) { "K16 SDK module bay slot must be 0, got $slot" }
    }
}
