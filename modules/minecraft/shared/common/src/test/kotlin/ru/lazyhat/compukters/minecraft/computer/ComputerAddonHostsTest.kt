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

package ru.lazyhat.compukters.minecraft.computer

import ru.lazyhat.compukters.addon.api.AddonCapabilityIdentity
import ru.lazyhat.compukters.addon.api.AddonCapabilityOperation
import ru.lazyhat.compukters.addon.api.AddonCapabilitySchema
import ru.lazyhat.compukters.addon.api.AddonCapabilityValueType
import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ComputerAddonHostsTest {
    @Test
    fun `registered guest API capability must exist exactly on the runtime host`() {
        val addonIdentity = AddonCapabilityIdentity("fixture", "meters", 1, 2)
        val operation = AddonCapabilityOperation(listOf(AddonCapabilityValueType.I32), AddonCapabilityValueType.F32, true)
        val bundle =
            AddonGuestApiBundleCodec.assemble(
                "fixture",
                PlatformBundleCodec.SUPPORTED_PLATFORM_ABI,
                PlatformModule(
                    PlatformModuleId("fixture", "meters"),
                    "1.0.0",
                    emptyList(),
                    ImmutableBytes.of(byteArrayOf(1)),
                    null,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                listOf(AddonCapabilitySchema(addonIdentity, listOf(operation))),
                emptyList(),
            )
        val matching =
            HostCapabilitySchema(
                CapabilityIdentity("fixture", "meters", 1, 2),
                listOf(HostOperationSchema(listOf(HostValueType.I32), HostValueType.F32, true)),
            )

        requireAddonCapabilitySchemas(listOf(bundle), listOf(matching))
        assertFailsWith<IllegalArgumentException> { requireAddonCapabilitySchemas(listOf(bundle), emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            requireAddonCapabilitySchemas(
                listOf(bundle),
                listOf(
                    HostCapabilitySchema(
                        CapabilityIdentity("fixture", "meters", 1, 1),
                        listOf(HostOperationSchema(listOf(HostValueType.I32), HostValueType.F32, true)),
                    ),
                ),
            )
        }
    }
}
