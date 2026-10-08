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

package ru.lazyhat.compukters.integration.propulsion.mixin;

import dev.propulsionteam.propulsionsimulated.content.thruster.vector_thruster.creative_vector_thruster.CreativeVectorThrusterBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.lazyhat.compukters.integration.propulsion.CreativeVectorThrustAccess;
import ru.lazyhat.compukters.integration.propulsion.PropulsionGuestIntegration;

@Mixin(value = CreativeVectorThrusterBlockEntity.class, remap = false)
public abstract class CreativeVectorThrustMixin implements CreativeVectorThrustAccess {
    @Shadow private float peripheralThrustOutput;

    @Override
    public float compukters$peripheralThrustOutput() { return peripheralThrustOutput; }
    @Override
    @Invoker("getBaseThrust")
    public abstract double compukters$baseThrustKn();

    @Inject(method = "write", at = @At("TAIL"))
    private void compukters$writeUnownedThrust(CompoundTag tag, HolderLookup.Provider registries,
                                               boolean clientPacket, CallbackInfo callback) {
        if (!clientPacket && PropulsionGuestIntegration.isControlled(this)) {
            tag.putFloat("PeripheralThrustOutput", -1f);
        }
    }
}
