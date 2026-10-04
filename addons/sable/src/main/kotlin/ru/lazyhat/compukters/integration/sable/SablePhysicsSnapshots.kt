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

import dev.ryanhcode.sable.Sable
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.joml.Vector3d
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersComputerContext

/** Request-only adapter. No event handlers, body registry, cached feeds or subscriptions. */
internal object SablePhysicsSnapshots {
    fun read(computer: CompuktersComputerContext): PhysicsSnapshot? = read(computer.level, computer.position)

    fun read(
        level: ServerLevel,
        position: BlockPos,
    ): PhysicsSnapshot? {
        check(level.server.isSameThread) { "Sable snapshots must be copied on the server thread" }
        if (!level.hasChunkAt(position)) return null
        val body = Sable.HELPER.getContaining(level, position) as? ServerSubLevel ?: return null
        if (body.isRemoved) return null
        val system = checkNotNull(SubLevelContainer.getContainer(level)).physicsSystem()
        val handle = system.getPhysicsHandle(body)
        val linear = handle.getLinearVelocity(Vector3d())
        val angular = handle.getAngularVelocity(Vector3d())
        val pose = body.logicalPose()
        val location = pose.position()
        val rotation = pose.orientation()
        val scale = pose.scale()
        val pivot = pose.rotationPoint()
        return PhysicsSnapshot(
            body.uniqueId,
            level.gameTime,
            level.dimension().location().toString(),
            system.paused,
            PhysicsVector(location.x(), location.y(), location.z()),
            PhysicsRotation(rotation.x(), rotation.y(), rotation.z(), rotation.w()),
            PhysicsVector(scale.x(), scale.y(), scale.z()),
            PhysicsVector(pivot.x(), pivot.y(), pivot.z()),
            PhysicsVector(linear.x(), linear.y(), linear.z()),
            PhysicsVector(angular.x(), angular.y(), angular.z()),
        )
    }
}
