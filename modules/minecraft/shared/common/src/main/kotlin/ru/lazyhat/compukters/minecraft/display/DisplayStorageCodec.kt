/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import ru.lazyhat.compukters.core.display.DisplayDirectory
import java.nio.ByteBuffer

internal object DisplayStorageCodec {
    val codec: Codec<ByteArray> =
        Codec.BYTE_BUFFER.fieldOf("rgb_displays").codec().comapFlatMap({ buffer ->
            runCatching {
                require(buffer.remaining() <= DisplayDirectory.MAXIMUM_ENCODED_BYTES)
                val bytes = ByteArray(buffer.remaining())
                buffer.duplicate().get(bytes)
                bytes
            }.fold(DataResult<ByteArray>::success) { error -> DataResult.error { error.message ?: "Invalid display directory" } }
        }, { bytes -> ByteBuffer.wrap(bytes) })
}
