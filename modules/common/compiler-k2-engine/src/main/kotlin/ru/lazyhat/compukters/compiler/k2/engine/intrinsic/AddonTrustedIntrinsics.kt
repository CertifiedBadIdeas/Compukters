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

package ru.lazyhat.compukters.compiler.k2.engine.intrinsic

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import ru.lazyhat.compukters.addon.api.AddonCapabilitySchema
import ru.lazyhat.compukters.addon.api.AddonGuestApiBinding
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId

data class AddonIntrinsicContract(
    val module: PlatformModuleId,
    val capabilitySchemas: List<AddonCapabilitySchema>,
    val bindings: List<AddonGuestApiBinding>,
)

object AddonTrustedIntrinsics {
    fun extend(
        base: TrustedIntrinsicRegistry,
        contracts: List<AddonIntrinsicContract>,
    ): TrustedIntrinsicRegistry {
        val addonModules = contracts.mapTo(mutableSetOf(), AddonIntrinsicContract::module)
        val registrations =
            base.handlers
                .filterKeys { key -> key.module !in addonModules }
                .map { (key, handler) -> TrustedIntrinsicRegistration(key, handler) }
                .toMutableList()
        contracts.forEach { contract ->
            val schemas = contract.capabilitySchemas.associateBy { it.identity }
            contract.bindings.forEach { binding ->
                val operation = schemas.getValue(binding.capability).operations[binding.operation]
                registrations +=
                    TrustedIntrinsicRegistration(
                        TrustedIntrinsicKey(
                            contract.module,
                            binding.callableId(),
                            CanonicalCallableSignature(binding.signature),
                        ),
                        CapabilityOperationHandler(
                            PlatformCapabilityId(
                                binding.capability.namespace,
                                binding.capability.name,
                                binding.capability.abiMajor,
                            ),
                            binding.operation.toUInt(),
                            if (operation.asynchronous) IntrinsicBlockingMode.VM_TASK else IntrinsicBlockingMode.NONE,
                        ),
                    )
            }
        }
        return TrustedIntrinsicRegistry.create(registrations)
    }

    private fun AddonGuestApiBinding.callableId(): CallableId =
        if (owner == null) {
            CallableId(FqName(packageName), Name.identifier(callableName))
        } else {
            CallableId(FqName(packageName), FqName(requireNotNull(owner)), Name.identifier(callableName))
        }
}
