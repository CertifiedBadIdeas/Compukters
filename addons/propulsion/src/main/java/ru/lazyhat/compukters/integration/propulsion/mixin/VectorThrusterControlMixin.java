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

import dev.propulsionteam.propulsionsimulated.content.thruster.vector_thruster.VectorThrusterBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.lazyhat.compukters.integration.propulsion.PropulsionGuestIntegration;
import ru.lazyhat.compukters.integration.propulsion.VectorThrusterControl;

@Mixin(value = VectorThrusterBlockEntity.class, remap = false)
public abstract class VectorThrusterControlMixin implements VectorThrusterControl {
    @Shadow private float targetVectorX;
    @Shadow private float targetVectorY;
    @Unique private boolean compukters$vectorOverride;
    @Unique private float compukters$vectorX;
    @Unique private float compukters$vectorY;
    @Shadow protected abstract void updateMappedTargets();

    @Override
    public boolean compukters$hasVectorOverride() { return compukters$vectorOverride; }

    @Override
    public void compukters$setVector(float x, float y) {
        compukters$vectorX = x;
        compukters$vectorY = y;
        compukters$vectorOverride = true;
        updateMappedTargets();
        VectorThrusterBlockEntity entity = (VectorThrusterBlockEntity) (Object) this;
        entity.setChanged();
        entity.notifyUpdate();
    }

    @Override
    public void compukters$clearVector() {
        compukters$vectorOverride = false;
        updateMappedTargets();
        VectorThrusterBlockEntity entity = (VectorThrusterBlockEntity) (Object) this;
        entity.setChanged();
        entity.notifyUpdate();
    }

    @Inject(method = "updateMappedTargets", at = @At("TAIL"))
    private void compukters$overrideTargets(CallbackInfo callback) {
        if (compukters$vectorOverride) {
            targetVectorX = compukters$vectorX;
            targetVectorY = compukters$vectorY;
        }
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void compukters$writeUnownedVector(CompoundTag tag, HolderLookup.Provider registries,
                                               boolean clientPacket, CallbackInfo callback) {
        if (!clientPacket && PropulsionGuestIntegration.isControlled(this)) {
            // Keep current redstone inputs and configuration, never a program's steering target or tween.
            float x = Math.clamp((tag.getInt("WestSignal") - tag.getInt("EastSignal")) / 15f, -1f, 1f);
            float y = Math.clamp((tag.getInt("DownSignal") - tag.getInt("UpSignal")) / 15f, -1f, 1f);
            tag.putFloat("TargetVectorX", x);
            tag.putFloat("TargetVectorY", y);
            tag.putFloat("CurrentVectorX", x);
            tag.putFloat("CurrentVectorY", y);
        }
    }
}
