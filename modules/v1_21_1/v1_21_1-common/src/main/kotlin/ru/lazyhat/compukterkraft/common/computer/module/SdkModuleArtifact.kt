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

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import java.util.function.Supplier

const val SDK_ARTIFACT_IDENTITY_MAX_BYTES: Int = 64

private val SDK_ARTIFACT_IDENTITY_PATTERN = Regex("[a-z0-9_]+")

val SDK_ARTIFACT_IDENTITY_CODEC: Codec<String> =
    Codec.STRING.comapFlatMap(
        { identity ->
            sdkArtifactIdentityError(identity)
                ?.let { message -> DataResult.error(Supplier(message)) }
                ?: DataResult.success(identity)
        },
        { it },
    )

val SDK_ARTIFACT_IDENTITY_STREAM_CODEC: StreamCodec<in io.netty.buffer.ByteBuf, String> =
    ByteBufCodecs.stringUtf8(SDK_ARTIFACT_IDENTITY_MAX_BYTES).map(
        ::requireValidSdkArtifactIdentity,
        ::requireValidSdkArtifactIdentity,
    )

fun requireValidSdkArtifactIdentity(identity: String): String {
    sdkArtifactIdentityError(identity)?.let { throw IllegalArgumentException(it()) }
    return identity
}

var ItemStack.sdkArtifactIdentity: String?
    get() = get(ModObjects.sdkArtifactIdentityComponentType())
    set(value) {
        val component = ModObjects.sdkArtifactIdentityComponentType()
        if (value == null) {
            remove(component)
        } else {
            set(component, requireValidSdkArtifactIdentity(value))
        }
    }

private fun sdkArtifactIdentityError(identity: String): (() -> String)? =
    when {
        identity.encodeToByteArray().size > SDK_ARTIFACT_IDENTITY_MAX_BYTES -> {
            { "K16 SDK artifact identity exceeds $SDK_ARTIFACT_IDENTITY_MAX_BYTES UTF-8 bytes" }
        }

        !SDK_ARTIFACT_IDENTITY_PATTERN.matches(identity) -> {
            { "Invalid K16 SDK artifact identity: $identity" }
        }

        else -> {
            null
        }
    }
