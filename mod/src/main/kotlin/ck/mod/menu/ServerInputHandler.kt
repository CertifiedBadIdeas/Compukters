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

import ck.mod.gui.InputHandler
import ck.mod.network.upload.FileSlice
import ck.mod.network.upload.FileUpload
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * An [InputHandler] which operates on the server, receiving data from the client over the network.
 *
 * @see ServerInputState The default implementation of this interface.
 *
 * @see ComputerServerMessage Packets which consume this interface.
 *
 * @see ComputerMenu
 */
interface ServerInputHandler : InputHandler {
    /**
     * Start a file upload into this container.
     *
     * @param uploadId The unique ID of this upload.
     * @param files    The files to upload.
     */
//    fun startUpload(
//        uploadId: UUID,
//        files: MutableList<FileUpload>,
//    )

    /**
     * Append more data to partially uploaded files.
     *
     * @param uploadId The unique ID of this upload.
     * @param slices   Additional parts of file data to upload.
     */
//    fun continueUpload(
//        uploadId: UUID,
//        slices: MutableList<FileSlice>,
//    )

    /**
     * Finish off an upload. This either writes the uploaded files or informs the user that files will be overwritten.
     *
     * @param uploader The player uploading files.
     * @param uploadId The unique ID of this upload.
     */
//    fun finishUpload(
//        uploader: ServerPlayer,
//        uploadId: UUID,
//    )
}
