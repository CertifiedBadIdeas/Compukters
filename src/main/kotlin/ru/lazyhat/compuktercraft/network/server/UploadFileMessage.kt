// SPDX-FileCopyrightText: 2021 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.server

import com.google.common.annotations.VisibleForTesting
import io.netty.handler.codec.DecoderException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.function.Consumer
import kotlin.ByteArray
import kotlin.Int
import kotlin.String
import kotlin.Unit
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.indices
import kotlin.collections.plus
import kotlin.let
import kotlin.math.min
import kotlin.plus
import kotlin.sequences.plus
import kotlin.text.plus
import kotlin.text.toInt
import kotlin.times

// class UploadFileMessage : ComputerServerMessage {
//    private val uuid: UUID
//
//    @VisibleForTesting
//    val flag: Int
//
//    @VisibleForTesting
//    @Nullable
//    val files: MutableList<FileUpload>?
//
//    @VisibleForTesting
//    val slices: MutableList<FileSlice>
//
//    internal constructor(
//        menu: AbstractContainerMenu,
//        uuid: UUID,
//        flag: Int,
//        @Nullable
//        files: MutableList<FileUpload>?,
//        slices: MutableList<FileSlice>,
//    ) : super(menu) {
//        this.uuid = uuid
//        this.flag = flag
//        this.files = files
//        this.slices = slices
//    }
//
//    constructor(buf: FriendlyByteBuf) : super(buf) {
//        uuid = buf.readUUID()
//        this.flag = buf.readByte().toInt()
//        val flag = this.flag
//
//        var totalSize = 0
//        if ((flag and FLAG_FIRST) != 0) {
//            val nFiles: Int = buf.readVarInt()
//            if (nFiles > MAX_FILES) throw DecoderException("Too many files")
//
//            this.files = ArrayList<FileUpload>(nFiles)
//            val files: MutableList<FileUpload> = this.files
//            for (i in 0..<nFiles) {
//                val name: String = buf.readUtf(MAX_FILE_NAME)
//                val size: Int = buf.readVarInt()
//                if (size > Config.uploadMaxSize || (
//                        size.let {
//                            totalSize += it
//                            totalSize
//                        }
//                    ) > Config.uploadMaxSize
//                ) {
//                    throw DecoderException("Files are too large")
//                }
//
//                val digest = ByteArray(FileUpload.CHECKSUM_LENGTH)
//                buf.readBytes(digest)
//
//                files.add(FileUpload(name, ByteBuffer.allocateDirect(size), digest))
//            }
//        } else {
//            files = null
//        }
//
//        val nSlices: Int = buf.readVarInt()
//        this.slices = ArrayList<FileSlice>(nSlices)
//        val slices: MutableList<FileSlice> = this.slices
//        for (i in 0..<nSlices) {
//            val fileId = buf.readUnsignedByte().toInt()
//            val offset: Int = buf.readVarInt()
//
//            val size: Int = buf.readUnsignedShort()
//            if (size > MAX_PACKET_SIZE) throw DecoderException("File is too large")
//
//            val buffer = ByteBuffer.allocateDirect(size)
//            buf.readBytes(buffer)
//            buffer.flip()
//
//            slices.add(FileSlice(fileId, offset, buffer))
//        }
//    }
//
//    override fun write(buf: FriendlyByteBuf) {
//        super.write(buf)
//        buf.writeUUID(uuid)
//        buf.writeByte(flag)
//
//        if ((flag and FLAG_FIRST) != 0) {
//            val files: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = assertNonNull(this.files)
//            buf.writeVarInt(files.size())
//            for (file in files) {
//                buf.writeUtf(file.getName(), MAX_FILE_NAME)
//                buf.writeVarInt(file.getLength())
//                buf.writeBytes(file.getChecksum())
//            }
//        }
//
//        buf.writeVarInt(slices.size)
//        for (slice in slices) {
//            buf.writeByte(slice.fileId())
//            buf.writeVarInt(slice.offset())
//
//            val bytes: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = slice.bytes().duplicate()
//            buf.writeShort(bytes.remaining())
//            buf.writeBytes(bytes)
//        }
//    }
//
//    override fun handle(
//        context: ServerNetworkContext,
//        container: ComputerMenu,
//    ) {
//        val player: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = context.sender
//
//        val input: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = container.getInput()
//        if ((flag and FLAG_FIRST) != 0) input.startUpload(uuid, assertNonNull(files))
//        input.continueUpload(uuid, slices)
//        if ((flag and FLAG_LAST) != 0) input.finishUpload(player, uuid)
//    }
//
//    public override fun type(): MessageType<UploadFileMessage?> = NetworkMessages.UPLOAD_FILE
//
//    companion object {
//        val MAX_PACKET_SIZE: Int = 30 * 1024 // Max packet size is 32767.
//        private val HEADER_SIZE = 16 + 1 // 16 bytes for the UUID, 4 for the flag.
//
//        const val MAX_FILES: Int = 32
//        const val MAX_FILE_NAME: Int = 128
//
//        @VisibleForTesting
//        const val FLAG_FIRST: Int = 1
//
//        @VisibleForTesting
//        const val FLAG_LAST: Int = 2
//
//        fun send(
//            container: AbstractContainerMenu,
//            files: MutableList<FileUpload>,
//            send: Consumer<UploadFileMessage?>,
//        ) {
//            val uuid = UUID.randomUUID()
//
//            var remaining: Int = MAX_PACKET_SIZE - HEADER_SIZE
//            for (file in files) remaining -= 4 + file.getName().length() * 4 + FileUpload.CHECKSUM_LENGTH
//
//            var first = true
//            val slices: MutableList<FileSlice?> = ArrayList<FileSlice?>(files.size)
//            for (fileId in files.indices) {
//                val file: FileUpload = files.get(fileId)
//                val contents: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = file.getBytes()
//                val capacity: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = contents.limit()
//
//                var currentOffset = 0
//                while (currentOffset < capacity) {
//                    if (remaining <= 0) {
//                        send.accept(
//                            if (first) {
//                                UploadFileMessage(container, uuid, FLAG_FIRST, files, ArrayList<FileSlice>(slices))
//                            } else {
//                                UploadFileMessage(container, uuid, 0, null, ArrayList<FileSlice>(slices))
//                            },
//                        )
//                        slices.clear()
//                        remaining = MAX_PACKET_SIZE - HEADER_SIZE
//                        first = false
//                    }
//
//                    val canWrite = min(remaining, capacity - currentOffset)
//
//                    contents.position(currentOffset).limit(currentOffset + canWrite)
//                    slices.add(FileSlice(fileId, currentOffset, contents.slice()))
//                    currentOffset += canWrite
//                    remaining -= canWrite
//                }
//
//                contents.position(0).limit(capacity)
//            }
//
//            send.accept(
//                if (first) {
//                    UploadFileMessage(container, uuid, FLAG_FIRST or FLAG_LAST, files, ArrayList<FileSlice>(slices))
//                } else {
//                    UploadFileMessage(container, uuid, FLAG_LAST, null, ArrayList<FileSlice>(slices))
//                },
//            )
//        }
//    }
// }
