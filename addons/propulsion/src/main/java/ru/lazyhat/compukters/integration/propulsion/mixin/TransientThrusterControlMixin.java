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

import dev.propulsionteam.propulsionsimulated.content.thruster.AbstractThrusterBlockEntity;
import dev.propulsionteam.propulsionsimulated.content.thruster.AbstractThrusterBlockEntity.ControlMode;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.lazyhat.compukters.integration.propulsion.PropulsionGuestIntegration;

/** Never deserialize a program lease: chunk saves and Sable copies retain engine settings only. */
@Mixin(value = AbstractThrusterBlockEntity.class, remap = false)
public abstract class TransientThrusterControlMixin {
    private static final String COMPUKTERS_CONTROL = "compukters_propulsion:transient_control";

    @Inject(method = "write", at = @At("TAIL"))
    private void compukters$markTransientControl(CompoundTag tag, HolderLookup.Provider registries,
                                                boolean clientPacket, CallbackInfo callback) {
        if (!clientPacket && PropulsionGuestIntegration.isControlled(this)) {
            tag.putBoolean(COMPUKTERS_CONTROL, true);
        }
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void compukters$restoreUnownedControl(CompoundTag tag, HolderLookup.Provider registries,
                                                 boolean clientPacket, CallbackInfo callback) {
        if (!clientPacket && tag.getBoolean(COMPUKTERS_CONTROL)) {
            AbstractThrusterBlockEntity entity = (AbstractThrusterBlockEntity) (Object) this;
            // The public input setter has an epsilon; clear arbitrarily small saved commands too.
            entity.setDigitalInput(1f);
            entity.setDigitalInput(0f);
            entity.setControlMode(ControlMode.NORMAL);
        }
    }
}
