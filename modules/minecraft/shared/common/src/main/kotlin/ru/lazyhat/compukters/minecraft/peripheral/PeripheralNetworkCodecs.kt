/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.minecraft.peripheral

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import java.util.Optional
import java.util.UUID

internal object PeripheralNetworkCodecs {
    private val uuid = Codec.STRING.xmap(UUID::fromString, UUID::toString)
    private val member: Codec<PeripheralNetworkMember> =
        RecordCodecBuilder.create { instance ->
            instance
                .group(
                    uuid.fieldOf("instance").forGetter(PeripheralNetworkMember::instance),
                    Codec.STRING.fieldOf("provider").forGetter { it.identity.providerId },
                    Codec.STRING.fieldOf("dimension").forGetter { it.identity.dimension },
                    BlockPos.CODEC.fieldOf("anchor").forGetter { it.identity.anchor },
                    Codec.STRING.fieldOf("device_key").forGetter { it.identity.deviceKey },
                    Codec.STRING.optionalFieldOf("name").forGetter { Optional.ofNullable(it.name) },
                ).apply(instance) { id, provider, dimension, anchor, key, name ->
                    PeripheralNetworkMember(id, PeripheralDeviceIdentity(provider, dimension, anchor, key), name.orElse(null))
                }
        }
    private val network: Codec<PeripheralNetwork> =
        RecordCodecBuilder.create { instance ->
            instance
                .group(
                    uuid.fieldOf("id").forGetter(PeripheralNetwork::id),
                    Codec.STRING.fieldOf("name").forGetter(PeripheralNetwork::name),
                    member.listOf(0, PeripheralNetworkDirectory.MAXIMUM_MEMBERS).fieldOf("members").forGetter(PeripheralNetwork::members),
                ).apply(instance, ::PeripheralNetwork)
        }
    val directory: Codec<PeripheralNetworkDirectory> =
        RecordCodecBuilder.create { instance ->
            instance
                .group(
                    Codec.INT.fieldOf("version").forGetter { _: PeripheralNetworkDirectory -> 1 },
                    network
                        .listOf(
                            0,
                            PeripheralNetworkDirectory.MAXIMUM_NETWORKS,
                        ).fieldOf("networks")
                        .forGetter(PeripheralNetworkDirectory::snapshot),
                ).apply(instance) { version, networks ->
                    require(version == 1) { "Unsupported peripheral network format" }
                    PeripheralNetworkDirectory(networks)
                }
        }
}
