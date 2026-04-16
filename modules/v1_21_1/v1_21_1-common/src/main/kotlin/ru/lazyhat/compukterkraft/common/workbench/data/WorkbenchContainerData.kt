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

package ru.lazyhat.compukterkraft.common.workbench.data

import net.minecraft.network.RegistryFriendlyByteBuf
import ru.lazyhat.compukterkraft.common.computer.data.IContainerData
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchRemoteState
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchTargetState

class WorkbenchContainerData private constructor(
    val targetConnected: Boolean,
    val targetDisplayName: String?,
    val targetFamilyId: String?,
) : IContainerData {
    constructor() : this(false, null, null)

    constructor(buffer: RegistryFriendlyByteBuf) : this(
        buffer.readBoolean(),
        if (buffer.readBoolean()) buffer.readUtf() else null,
        if (buffer.readBoolean()) buffer.readUtf() else null,
    )

    override fun toBytes(buffer: RegistryFriendlyByteBuf) {
        buffer.writeBoolean(targetConnected)
        buffer.writeBoolean(targetDisplayName != null)
        targetDisplayName?.let(buffer::writeUtf)
        buffer.writeBoolean(targetFamilyId != null)
        targetFamilyId?.let(buffer::writeUtf)
    }

    fun toRemoteState(): WorkbenchRemoteState =
        WorkbenchRemoteState(
            target =
                WorkbenchTargetState(
                    connected = targetConnected,
                    displayName = targetDisplayName,
                    familyId = targetFamilyId,
                ),
        )

    companion object {
        fun from(target: WorkbenchTargetState): WorkbenchContainerData =
            WorkbenchContainerData(
                targetConnected = target.connected,
                targetDisplayName = target.displayName,
                targetFamilyId = target.familyId,
            )
    }
}