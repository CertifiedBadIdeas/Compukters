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

import net.minecraft.SharedConstants
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CProgrammingSdkItemTest {
    private val component =
        DataComponentType
            .builder<String>()
            .persistent(SDK_ARTIFACT_IDENTITY_CODEC)
            .networkSynchronized(SDK_ARTIFACT_IDENTITY_STREAM_CODEC)
            .build()

    init {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @BeforeTest
    fun bindIdentityComponent() {
        ModObjects.sdkArtifactIdentityComponentType = { component }
    }

    @Test
    fun defaultSdkStackCarriesOnlyImmutableArtifactIdentityAndCopiesIt() {
        val stack = cProgrammingSdkStack(ItemStack(Items.PAPER))
        val copied = stack.copy()
        val itemSource =
            Path
                .of("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/module/CProgrammingSdkItem.kt")
                .readText()

        assertEquals("c_sdk_v1", C_PROGRAMMING_SDK_ARTIFACT_IDENTITY)
        assertEquals("c_sdk_v1", stack.sdkArtifactIdentity)
        assertEquals("c_sdk_v1", copied.sdkArtifactIdentity)
        assertFalse(stack.has(DataComponents.CUSTOM_DATA), "SDK media bytes must not be stored in ItemStack data")
        assertFalse(copied.has(DataComponents.CUSTOM_DATA), "copied SDK stacks must still carry identity only")
        assertTrue(itemSource.contains("override fun getDefaultInstance(): ItemStack"))
        assertTrue(itemSource.contains("cProgrammingSdkStack(super.getDefaultInstance())"))
    }
}
