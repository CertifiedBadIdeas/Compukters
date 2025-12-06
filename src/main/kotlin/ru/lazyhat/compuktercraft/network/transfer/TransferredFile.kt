// SPDX-FileCopyrightText: 2022 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.transfer

/**
 * A binary file handle that has been transferred to this computer.
 *
 *
 * This inherits all methods of [binary file handles][ReadHandle], meaning you can use the standard
 * [read functions][ReadHandle.read] to access the contents of the file.
 *
 * @cc.module [kind=event] file_transfer.TransferredFile
 * @see ReadHandle
 */
// class TransferredFile(
//    /**
//     * Get the name of this file being transferred.
//     *
//     * @return The file's name.
//     */
//    val name: String?,
//    contents: SeekableByteChannel?,
// ) : ObjectSource {
//    private val handle: ReadHandle
//
//    init {
//        handle = ReadHandle(contents, true)
//    }
//
//    val extra: Iterable<Any?>
//        get() = List.of<Any?>(handle)
// }
