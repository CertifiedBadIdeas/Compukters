// SPDX-FileCopyrightText: 2017 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.menu

import net.minecraft.server.level.ServerPlayer
import ru.lazyhat.compuktercraft.gui.InputHandler
import ru.lazyhat.compuktercraft.network.upload.FileSlice
import ru.lazyhat.compuktercraft.network.upload.FileUpload
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
