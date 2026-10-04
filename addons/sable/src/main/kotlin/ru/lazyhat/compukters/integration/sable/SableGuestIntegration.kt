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

package ru.lazyhat.compukters.integration.sable

import ru.lazyhat.compukters.api.addon.AddonCallResult
import ru.lazyhat.compukters.api.addon.addonCompleted
import ru.lazyhat.compukters.api.addon.addonFailed
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersAddonHostFactory
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersAddonRegistry
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersComputerContext
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import sable.SableAddonContract
import sable.SableCapabilityHandler
import sable.PhysicsRotation as GuestRotation
import sable.PhysicsSnapshot as GuestSnapshot
import sable.PhysicsVector as GuestVector

internal object SableGuestIntegration {
    fun register() {
        CompuktersAddonRegistry.register(
            SableAddonContract.guestApi(SableGuestIntegration::class.java),
            CompuktersAddonHostFactory { computer -> SableAddonContract.host(SableHost(computer)) },
        )
    }
}

private class SableHost(
    private val computer: CompuktersComputerContext,
) : SableCapabilityHandler {
    override fun snapshot(): AddonCallResult<GuestSnapshot> {
        val snapshot =
            SablePhysicsSnapshots.read(computer)
                ?: return addonFailed(HostFailureKind.UNAVAILABLE, "Computer is not on an available Sable construction")
        return addonCompleted(
            GuestSnapshot(
                snapshot.constructionId.toString(),
                snapshot.dimension,
                snapshot.gameTick,
                snapshot.paused,
                snapshot.position.toGuest(),
                GuestRotation(snapshot.orientation.x, snapshot.orientation.y, snapshot.orientation.z, snapshot.orientation.w),
                snapshot.scale.toGuest(),
                snapshot.rotationPoint.toGuest(),
                snapshot.linearVelocity.toGuest(),
                snapshot.angularVelocity.toGuest(),
            ),
        )
    }
}

private fun PhysicsVector.toGuest(): GuestVector = GuestVector(x, y, z)
