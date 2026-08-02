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

package ru.lazyhat.compukterkraft.core.device.display.retained

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RetainedDisplayReplicaTest {
    @Test
    fun appliesImagePatchAtomicallyAndPreservesLocalIdentity() {
        val replica = RetainedDisplayReplica()
        val installed = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(snapshotImage(sequence = 1uL)))
        val before = assertIs<RetainedImageRgb565>(installed.state.resource(1u)?.content)
        val identity = installed.state.resource(1u)?.localIdentity

        val patched = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(deltaPatchImage()))
        val after = assertIs<RetainedImageRgb565>(patched.state.resource(1u)?.content)

        assertEquals(0x1234, before.pixelAt(0, 0))
        assertEquals(0x4321, after.pixelAt(0, 0))
        assertEquals(identity, patched.state.resource(1u)?.localIdentity)
        assertEquals(2uL, patched.state.sequence)
    }

    @Test
    fun rejectsSequenceGapWithoutReplacingInstalledState() {
        val replica = RetainedDisplayReplica()
        val installed = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(snapshotImage(sequence = 1uL)))
        val gap =
            deltaPatchImage().also {
                putU64(it, 24, 99uL)
                putU64(it, 32, 100uL)
            }

        val result = assertIs<RetainedDisplayApplyResult.ResyncRequired>(replica.apply(gap))

        assertEquals(RetainedDisplayResyncReason.BASE_MISMATCH, result.reason)
        assertSame(installed.state, replica.state)
    }

    @Test
    fun malformedDeltaPreservesInstalledStateAndRequestsValidationResync() {
        val replica = RetainedDisplayReplica()
        val installed = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(snapshotImage(sequence = 1uL)))
        val malformed = deltaPatchImage().copyOf(deltaPatchImage().size - 1).also { putU32(it, 8, it.size.toUInt()) }

        val result = assertIs<RetainedDisplayApplyResult.ResyncRequired>(replica.apply(malformed))

        assertEquals(RetainedDisplayResyncReason.MESSAGE_VALIDATION_FAILED, result.reason)
        assertSame(installed.state, replica.state)
    }

    @Test
    fun rejectsSnapshotThatExceedsResourceCountQuota() {
        val result = RetainedDisplayReplica().apply(snapshotWithImages(resourceCount = 129))

        assertEquals(
            RetainedDisplayResyncReason.MESSAGE_VALIDATION_FAILED,
            assertIs<RetainedDisplayApplyResult.ResyncRequired>(result).reason,
        )
    }

    @Test
    fun recreationAllocatesNewLocalIdentityAndRebindsEqualIdDrawList() {
        val replica = RetainedDisplayReplica()
        val installed = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(snapshotImage(sequence = 1uL)))
        val oldResource = installed.state.resource(1u)!!
        val oldBinding =
            assertIs<RetainedDrawCommand.DrawImage>(
                installed.state.drawList.commands
                    .single(),
            ).image

        val recreated = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(deltaRecreateImage()))
        val newResource = recreated.state.resource(1u)!!
        val newBinding =
            assertIs<RetainedDrawCommand.DrawImage>(
                recreated.state.drawList.commands
                    .single(),
            ).image

        assertNotEquals(oldResource.localIdentity, newResource.localIdentity)
        assertEquals(oldBinding.resourceId, newBinding.resourceId)
        assertEquals(newResource.localIdentity, newBinding.localIdentity)
    }

    @Test
    fun explicitReplicaLossClearsStateAndCarriesCurrentSequence() {
        val replica = RetainedDisplayReplica()
        replica.apply(snapshotImage(sequence = 1uL))

        val request = replica.clearAndRequestResync(RetainedDisplayResyncReason.RENDER_RESOURCE_LOST)!!

        assertEquals(null, replica.state)
        assertEquals(1L, u64(request, 24))
        assertEquals(RetainedDisplayResyncReason.RENDER_RESOURCE_LOST.code, u16(request, 32))
        assertEquals(1, u16(request, 34))
    }

    @Test
    fun validatesAndPatchesMaskInstanceResourcesWithoutReplacingBindings() {
        val replica = RetainedDisplayReplica()
        val installed = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(snapshotMaskInstances()))
        val initialMask = assertIs<RetainedMask1Bpp>(installed.state.resource(1u)?.content)
        val initialInstances = assertIs<RetainedMaskInstanceBuffer>(installed.state.resource(2u)?.content)
        val initialDraw =
            assertIs<RetainedDrawCommand.DrawMaskInstances>(
                installed.state.drawList.commands
                    .single(),
            )

        assertTrue(initialMask.bitAt(0, 0))
        assertEquals(0xffff, initialInstances.instances.single().foregroundRgb565)

        val patched = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(deltaPatchMaskInstances()))
        val patchedMask = assertIs<RetainedMask1Bpp>(patched.state.resource(1u)?.content)
        val patchedInstances = assertIs<RetainedMaskInstanceBuffer>(patched.state.resource(2u)?.content)
        val patchedDraw =
            assertIs<RetainedDrawCommand.DrawMaskInstances>(
                patched.state.drawList.commands
                    .single(),
            )

        assertFalse(patchedMask.bitAt(0, 0))
        assertEquals(0x07e0, patchedInstances.instances.single().foregroundRgb565)
        assertEquals(initialDraw.mask, patchedDraw.mask)
        assertEquals(initialDraw.instances, patchedDraw.instances)
    }

    @Test
    fun deltaWithoutRequiredRebindIsRejectedAtomically() {
        val replica = RetainedDisplayReplica()
        val installed = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(snapshotImage(sequence = 1uL)))
        val invalid = deltaRecreateImageWithoutDrawList()

        val result = assertIs<RetainedDisplayApplyResult.ResyncRequired>(replica.apply(invalid))

        assertEquals(RetainedDisplayResyncReason.MESSAGE_VALIDATION_FAILED, result.reason)
        assertSame(installed.state, replica.state)
    }

    @Test
    fun deltaBeforeSnapshotRequestsReplicaStateResyncWithoutCurrentSequence() {
        val result = assertIs<RetainedDisplayApplyResult.ResyncRequired>(RetainedDisplayReplica().apply(deltaPatchImage()))

        assertEquals(RetainedDisplayResyncReason.REPLICA_STATE_LOST, result.reason)
        assertEquals(0L, u64(result.request!!, 24))
        assertEquals(0, u16(result.request, 34))
    }

    @Test
    fun resolvesEveryDrawCommandAgainstTypedResources() {
        val installed =
            assertIs<RetainedDisplayApplyResult.Installed>(
                RetainedDisplayReplica().apply(snapshotEveryDrawCommand()),
            )

        assertEquals(
            listOf(
                RetainedDrawCommand.PushClip::class,
                RetainedDrawCommand.FillRect::class,
                RetainedDrawCommand.DrawImage::class,
                RetainedDrawCommand.DrawMask::class,
                RetainedDrawCommand.DrawMaskInstances::class,
                RetainedDrawCommand.PopClip::class,
            ),
            installed.state.drawList.commands
                .map { it::class },
        )
    }

    private fun snapshotImage(sequence: ULong): ByteArray {
        val resource = fullImage(1u, 2, 1, intArrayOf(0x1234, 0xabcd))
        val drawList = drawImageList(1u, 2, 1)
        return message(
            kind = 1,
            payload =
                bytes {
                    u64(sequence)
                    u32(1u)
                    u32(drawList.size.toUInt())
                    raw(resource)
                    raw(drawList)
                },
        )
    }

    private fun snapshotWithImages(resourceCount: Int): ByteArray {
        val resources = (1..resourceCount).map { fullImage(it.toUInt(), 1, 1, intArrayOf(it)) }
        val drawList = emptyDrawList()
        return message(
            kind = 1,
            payload =
                bytes {
                    u64(0uL)
                    u32(resourceCount.toUInt())
                    u32(drawList.size.toUInt())
                    resources.forEach(::raw)
                    raw(drawList)
                },
        )
    }

    private fun deltaPatchImage(): ByteArray {
        val patch =
            bytes {
                u16(0x0010)
                u16(0)
                u32(28u)
                u32(1u)
                u32(1u)
                u16(0)
                u16(0)
                u16(1)
                u16(1)
                u16(0x4321)
                u16(0)
            }
        return message(
            kind = 2,
            payload =
                bytes {
                    u64(1uL)
                    u64(2uL)
                    u32(1u)
                    u32(0u)
                    raw(patch)
                },
        )
    }

    private fun deltaRecreateImage(): ByteArray {
        val drop =
            bytes {
                u16(0x0020)
                u16(0)
                u32(12u)
                u32(1u)
            }
        val create = fullImage(1u, 2, 1, intArrayOf(0x9999, 0x8888))
        val drawList = drawImageList(1u, 2, 1)
        return message(
            kind = 2,
            payload =
                bytes {
                    u64(1uL)
                    u64(2uL)
                    u32(2u)
                    u32(drawList.size.toUInt())
                    raw(drop)
                    raw(create)
                    raw(drawList)
                },
        )
    }

    private fun deltaRecreateImageWithoutDrawList(): ByteArray {
        val drop =
            bytes {
                u16(0x0020)
                u16(0)
                u32(12u)
                u32(1u)
            }
        val create = fullImage(1u, 2, 1, intArrayOf(0x9999, 0x8888))
        return message(
            kind = 2,
            payload =
                bytes {
                    u64(1uL)
                    u64(2uL)
                    u32(2u)
                    u32(0u)
                    raw(drop)
                    raw(create)
                },
        )
    }

    private fun snapshotMaskInstances(): ByteArray {
        val mask = fullMask(1u, 8, 1, byteArrayOf(0x80.toByte()))
        val instances = fullInstances(2u, foreground = 0xffff)
        val drawList = drawMaskInstancesList(1u, 2u)
        return message(
            kind = 1,
            payload =
                bytes {
                    u64(1uL)
                    u32(2u)
                    u32(drawList.size.toUInt())
                    raw(mask)
                    raw(instances)
                    raw(drawList)
                },
        )
    }

    private fun snapshotEveryDrawCommand(): ByteArray {
        val image = fullImage(1u, 2, 1, intArrayOf(0x1234, 0xabcd))
        val mask = fullMask(2u, 8, 1, byteArrayOf(0x80.toByte()))
        val instances = fullInstances(3u, foreground = 0xffff)
        val drawList = everyDrawCommandList()
        return message(
            kind = 1,
            payload =
                bytes {
                    u64(1uL)
                    u32(3u)
                    u32(drawList.size.toUInt())
                    raw(image)
                    raw(mask)
                    raw(instances)
                    raw(drawList)
                },
        )
    }

    private fun deltaPatchMaskInstances(): ByteArray {
        val maskPatch =
            bytes {
                u16(0x0011)
                u16(0)
                u32(28u)
                u32(1u)
                u32(1u)
                u16(0)
                u16(0)
                u16(1)
                u16(1)
                raw(byteArrayOf(0))
                raw(byteArrayOf(0, 0, 0))
            }
        val instancePatch =
            bytes {
                u16(0x0012)
                u16(0)
                u32(44u)
                u32(2u)
                u32(1u)
                u16(0)
                u16(1)
                raw(instance(foreground = 0x07e0))
            }
        return message(
            kind = 2,
            payload =
                bytes {
                    u64(1uL)
                    u64(2uL)
                    u32(2u)
                    u32(0u)
                    raw(maskPatch)
                    raw(instancePatch)
                },
        )
    }

    private fun fullImage(
        resourceId: UInt,
        width: Int,
        height: Int,
        pixels: IntArray,
    ): ByteArray =
        bytes {
            u16(1)
            u16(0)
            u32((16 + pixels.size * 2).toUInt())
            u32(resourceId)
            u16(width)
            u16(height)
            pixels.forEach(::u16)
        }

    private fun fullMask(
        resourceId: UInt,
        width: Int,
        height: Int,
        rows: ByteArray,
    ): ByteArray =
        bytes {
            u16(2)
            u16(0)
            u32((16 + rows.size).toUInt())
            u32(resourceId)
            u16(width)
            u16(height)
            raw(rows)
        }

    private fun fullInstances(
        resourceId: UInt,
        foreground: Int,
    ): ByteArray =
        bytes {
            u16(3)
            u16(0)
            u32(40u)
            u32(resourceId)
            u16(1)
            u16(0)
            raw(instance(foreground))
        }

    private fun instance(foreground: Int): ByteArray =
        bytes {
            u16(0)
            u16(0)
            u16(8)
            u16(1)
            i16(0)
            i16(0)
            u16(8)
            u16(1)
            u16(foreground)
            u16(0)
            u16(1)
            u16(0)
        }

    private fun drawImageList(
        resourceId: UInt,
        width: Int,
        height: Int,
    ): ByteArray =
        bytes {
            u16(0)
            u16(0)
            u32(1u)
            u16(0x0020)
            u16(0)
            u32(28u)
            u32(resourceId)
            u16(0)
            u16(0)
            u16(width)
            u16(height)
            i16(0)
            i16(0)
            u16(width)
            u16(height)
        }

    private fun drawMaskInstancesList(
        maskId: UInt,
        instancesId: UInt,
    ): ByteArray =
        bytes {
            u16(0)
            u16(0)
            u32(1u)
            u16(0x0022)
            u16(0)
            u32(24u)
            u32(maskId)
            u32(instancesId)
            u16(0)
            u16(1)
            i16(0)
            i16(0)
        }

    private fun everyDrawCommandList(): ByteArray =
        bytes {
            u16(0)
            u16(0)
            u32(6u)

            u16(0x0001)
            u16(0)
            u32(16u)
            i16(0)
            i16(0)
            u16(320)
            u16(200)

            u16(0x0010)
            u16(0)
            u32(20u)
            i16(1)
            i16(2)
            u16(3)
            u16(4)
            u16(0xf800)
            u16(0)

            raw(drawImageList(1u, 2, 1).copyOfRange(8, 36))

            u16(0x0021)
            u16(1)
            u32(32u)
            u32(2u)
            u16(0)
            u16(0)
            u16(8)
            u16(1)
            i16(0)
            i16(0)
            u16(8)
            u16(1)
            u16(0xffff)
            u16(0x001f)

            raw(drawMaskInstancesList(2u, 3u).copyOfRange(8, 32))

            u16(0x0002)
            u16(0)
            u32(8u)
        }

    private fun emptyDrawList(): ByteArray =
        bytes {
            u16(0)
            u16(0)
            u32(0u)
        }

    private fun message(
        kind: Int,
        payload: ByteArray,
    ): ByteArray {
        val bytes =
            bytes {
                u32(0x5053_444bu)
                u16(1)
                u16(kind)
                u32((24 + payload.size).toUInt())
                u32(42u)
                u64(7uL)
                raw(payload)
            }
        return bytes
    }

    private fun bytes(block: LeBytes.() -> Unit): ByteArray = LeBytes().apply(block).toByteArray()

    private class LeBytes {
        private val output = ByteArrayOutputStream()

        fun raw(bytes: ByteArray) = output.write(bytes)

        fun u16(value: Int) {
            output.write(value and 0xff)
            output.write(value ushr 8 and 0xff)
        }

        fun i16(value: Int) = u16(value and 0xffff)

        fun u32(value: UInt) {
            repeat(4) { output.write((value shr (it * 8)).toInt() and 0xff) }
        }

        fun u64(value: ULong) {
            repeat(8) { output.write((value shr (it * 8)).toInt() and 0xff) }
        }

        fun toByteArray(): ByteArray = output.toByteArray()
    }

    private fun putU32(
        bytes: ByteArray,
        offset: Int,
        value: UInt,
    ) {
        repeat(4) { bytes[offset + it] = (value shr (it * 8)).toByte() }
    }

    private fun putU64(
        bytes: ByteArray,
        offset: Int,
        value: ULong,
    ) {
        repeat(8) { bytes[offset + it] = (value shr (it * 8)).toByte() }
    }

    private fun u16(
        bytes: ByteArray,
        offset: Int,
    ): Int = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(
        bytes: ByteArray,
        offset: Int,
    ): Int = u16(bytes, offset) or (u16(bytes, offset + 2) shl 16)

    private fun u64(
        bytes: ByteArray,
        offset: Int,
    ): Long = u32(bytes, offset).toLong() and 0xffff_ffffL or (u32(bytes, offset + 4).toLong() shl 32)
}
