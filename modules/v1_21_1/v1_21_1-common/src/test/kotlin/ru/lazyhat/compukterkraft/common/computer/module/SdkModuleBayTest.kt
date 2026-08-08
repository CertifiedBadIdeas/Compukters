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
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SdkModuleBayTest {
    init {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private val component =
        DataComponentType
            .builder<String>()
            .persistent(SDK_ARTIFACT_IDENTITY_CODEC)
            .networkSynchronized(SDK_ARTIFACT_IDENTITY_STREAM_CODEC)
            .build()
    private val item = Items.PAPER

    @Test
    fun `artifact identity rejects paths punctuation and oversized values`() {
        listOf("", "../sdk", "sdk.v1", "sdk:v1", "sdk v1", "/tmp/sdk").forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { requireValidSdkArtifactIdentity(invalid) }
        }
        assertFailsWith<IllegalArgumentException> {
            requireValidSdkArtifactIdentity("a".repeat(SDK_ARTIFACT_IDENTITY_MAX_BYTES + 1))
        }
        assertEquals("sdk_fixture_v1", requireValidSdkArtifactIdentity("sdk_fixture_v1"))
    }

    @Test
    fun `known single module inserts only while runtime is off`() {
        val fixture = Fixture()

        assertTrue(fixture.bay.setFromPlayer(module("sdk_fixture_v1")))

        assertEquals("sdk_fixture_v1", fixture.bay.installedArtifactIdentity)
        assertEquals(1, fixture.commits)
        fixture.powered = true
        assertFalse(fixture.bay.setFromPlayer(module("c_sdk_v1")))
        assertEquals("sdk_fixture_v1", fixture.bay.installedArtifactIdentity)
        assertEquals(1, fixture.commits)
    }

    @Test
    fun `unknown and stacked modules cannot be inserted`() {
        val fixture = Fixture()

        fixture.bay.setItem(0, module("unknown_sdk"))
        fixture.bay.setItem(0, module("sdk_fixture_v1", count = 2))

        assertTrue(fixture.bay.isEmpty)
        assertEquals(0, fixture.commits)
    }

    @Test
    fun `syntactically valid unknown module restored from storage remains removable`() {
        val fixture = Fixture()
        fixture.bay.restoreStoredItem(module("removed_from_manifest"))

        assertEquals("removed_from_manifest", fixture.bay.installedArtifactIdentity)
        assertEquals("removed_from_manifest", fixture.bay.removeItemNoUpdate(0).get(component))
        assertTrue(fixture.bay.isEmpty)
        assertEquals(1, fixture.commits)
    }

    @Test
    fun `every removal route is blocked while runtime is on`() {
        val fixture = Fixture()
        fixture.bay.restoreStoredItem(module("sdk_fixture_v1"))
        fixture.powered = true

        assertTrue(fixture.bay.removeItem(0, 1).isEmpty)
        assertTrue(fixture.bay.removeItemNoUpdate(0).isEmpty)
        fixture.bay.clearContent()
        fixture.bay.setItem(0, ItemStack.EMPTY)

        assertEquals("sdk_fixture_v1", fixture.bay.installedArtifactIdentity)
        assertEquals(0, fixture.commits)
    }

    @Test
    fun `failed hardware commit leaves installed module unchanged`() {
        val fixture = Fixture(commitSucceeds = false)
        fixture.bay.restoreStoredItem(module("sdk_fixture_v1"))

        fixture.bay.setItem(0, module("c_sdk_v1"))

        assertEquals("sdk_fixture_v1", fixture.bay.installedArtifactIdentity)
        assertEquals(1, fixture.commitAttempts)
    }

    @Test
    fun `hardware commit exception leaves installed module unchanged`() {
        val fixture = Fixture(commitFailure = IllegalStateException("snapshot delete failed"))
        fixture.bay.restoreStoredItem(module("sdk_fixture_v1"))

        assertFailsWith<IllegalStateException> {
            fixture.bay.setItem(0, module("c_sdk_v1"))
        }

        assertEquals("sdk_fixture_v1", fixture.bay.installedArtifactIdentity)
        assertEquals(1, fixture.commitAttempts)
    }

    @Test
    fun `stored module still requires bounded syntactic identity and one item`() {
        val fixture = Fixture()

        assertFailsWith<IllegalArgumentException> { fixture.bay.restoreStoredItem(module("bad/path")) }
        assertFailsWith<IllegalArgumentException> {
            fixture.bay.restoreStoredItem(module("sdk_fixture_v1", count = 2))
        }
        assertFalse(fixture.bay.restoreStoredItem(ItemStack.EMPTY))
    }

    private fun module(
        identity: String,
        count: Int = 1,
    ): ItemStack = ItemStack(item, count).also { it.set(component, identity) }

    private inner class Fixture(
        private val commitSucceeds: Boolean = true,
        private val commitFailure: RuntimeException? = null,
    ) {
        var powered: Boolean = false
        var commits: Int = 0
        var commitAttempts: Int = 0
        val bay =
            SdkModuleBay(
                artifactIdentity = { it.get(component) },
                isKnownArtifact = { it == "sdk_fixture_v1" || it == "c_sdk_v1" },
                isRuntimeOn = { powered },
                commitMutation = { mutation ->
                    commitAttempts += 1
                    commitFailure?.let { throw it }
                    if (commitSucceeds) {
                        mutation()
                        commits += 1
                        true
                    } else {
                        false
                    }
                },
            )
    }
}
