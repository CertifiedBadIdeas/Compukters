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
package ck.mod.menu

import ck.mod.computer.ComputerEvents
import ck.mod.computer.ServerComputer
import ck.mod.gui.input.InputHandler
import ck.mod.network.upload.FileUpload
import ck.mod.utils.StringUtil
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.ints.IntSet
import net.minecraft.world.inventory.AbstractContainerMenu
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The default concrete implementation of [ServerInputHandler].
 *
 *
 * This keeps track of the current key and mouse state, and releases them when the container is closed.
 *
 * @param <T> The type of container this server input belongs to.
</T> */
class ServerInputState<T>(
    private val owner: T,
) : ServerInputHandler,
    InputHandler where T : AbstractContainerMenu, T : ComputerMenu {
    private val keysDown: IntSet = IntOpenHashSet(4)

    private var lastMouseX = 0
    private var lastMouseY = 0
    private var lastMouseDown = -1

    private var toUploadId: UUID? = null

    private var toUpload: MutableList<FileUpload>? = null

    override fun keyDown(
        key: Int,
        repeat: Boolean,
    ) {
        keysDown.add(key)
        ComputerEvents.keyDown(owner.getComputerPublic(), key, repeat)
    }

    override fun keyUp(key: Int) {
        keysDown.remove(key)
        ComputerEvents.keyUp(owner.getComputerPublic(), key)
    }

    override fun charTyped(chr: Byte) {
        if (StringUtil.isTypableChar(chr)) ComputerEvents.charTyped(owner.getComputerPublic(), chr)
    }

    override fun paste(contents: ByteBuffer?) {
        if (contents != null && contents.remaining() > 0 &&
            isValidClipboard(contents)
        ) {
            ComputerEvents.paste(owner.getComputerPublic(), contents)
        }
    }

    override fun mouseClick(
        button: Int,
        x: Int,
        y: Int,
    ) {
        lastMouseX = x
        lastMouseY = y
        lastMouseDown = button

        ComputerEvents.mouseClick(owner.getComputerPublic(), button, x, y)
    }

    override fun mouseUp(
        button: Int,
        x: Int,
        y: Int,
    ) {
        lastMouseX = x
        lastMouseY = y
        lastMouseDown = -1

        ComputerEvents.mouseUp(owner.getComputerPublic(), button, x, y)
    }

    override fun mouseDrag(
        button: Int,
        x: Int,
        y: Int,
    ) {
        lastMouseX = x
        lastMouseY = y
        lastMouseDown = button

        ComputerEvents.mouseDrag(owner.getComputerPublic(), button, x, y)
    }

    override fun mouseScroll(
        direction: Int,
        x: Int,
        y: Int,
    ) {
        lastMouseX = x
        lastMouseY = y

        ComputerEvents.mouseScroll(owner.getComputerPublic(), direction, x, y)
    }

    override fun terminate() {
        owner.getComputerPublic().queueEvent("terminate")
    }

    override fun shutdown() {
        owner.getComputerPublic().shutdown()
    }

    override fun turnOn() {
        owner.getComputerPublic().turnOn()
    }

    override fun reboot() {
        owner.getComputerPublic().reboot()
    }

//    override fun startUpload(
//        uploadId: UUID,
//        files: MutableList<FileUpload>,
//    ) {
//        toUploadId = uploadId
//        toUpload = files
//    }
//
//    override fun continueUpload(
//        uploadId: UUID,
//        slices: MutableList<FileSlice>,
//    ) {
//        if (toUploadId == null || toUpload == null || (toUploadId != uploadId)) {
//            LOG.warn("Invalid continueUpload call, skipping.")
//            return
//        }
//
//        for (slice in slices) slice.apply(toUpload)
//    }
//
//    public override fun finishUpload(
//        uploader: ServerPlayer,
//        uploadId: UUID,
//    ) {
//        if (toUploadId == null || toUpload == null || toUpload!!.isEmpty() || (toUploadId != uploadId)) {
//            LOG.warn("Invalid finishUpload call, skipping.")
//            return
//        }
//
//        ServerNetworking.sendToPlayer(finishUpload(uploader), uploader)
//    }
//
//    private fun finishUpload(player: ServerPlayer): UploadResultMessage {
//        val computer: ServerComputer = owner.computer
//        if (toUpload == null) {
//            return UploadResultMessage.error(owner, UploadResult.COMPUTER_OFF_MSG)
//        }
//
//        for (upload in toUpload) {
//            if (!upload.checksumMatches()) {
//                LOG.warn("Checksum failed to match for {}.", upload.name)
//                return UploadResultMessage.error(owner, Component.translatable("gui.computercraft.upload.failed.corrupted"))
//            }
//        }
//
//        computer.queueEvent(
//            TransferredFiles.EVENT,
//            arrayOf<Any>(
//                TransferredFiles(
//                    toUpload!!
//                        .stream()
//                        .map<Any?> { x: FileUpload -> TransferredFile(x.getName(), ByteBufferChannel(x.getBytes())) }
//                        .toList(),
//                    {
//                        if (player.isAlive() && player.containerMenu === owner) {
//                            ServerNetworking.sendToPlayer(UploadResultMessage.consumed(owner), player)
//                        }
//                    },
//                ),
//            ),
//        )
//        return UploadResultMessage.queued(owner)
//    }

    fun close() {
        val computer: ServerComputer = owner.getComputerPublic()
        val keys = keysDown.iterator()
        while (keys.hasNext()) ComputerEvents.keyUp(computer, keys.nextInt())

        if (lastMouseDown != -1) ComputerEvents.mouseUp(computer, lastMouseDown, lastMouseX, lastMouseY)

        keysDown.clear()
        lastMouseDown = -1
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(ServerInputState::class.java)

        private fun isValidClipboard(buffer: ByteBuffer): Boolean {
            var i = buffer.position()
            val max = buffer.limit()
            while (i < max) {
                if (!StringUtil.isTypableChar(buffer.get(i))) return false
                i++
            }
            return true
        }
    }
}
