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
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.lazyhat.compukters.integration.propulsion.PropulsionGuestIntegration;
import ru.lazyhat.compukters.integration.propulsion.TransientThrusterControl;

import java.util.UUID;

/** Never deserialize a program lease: chunk saves and Sable copies retain engine settings only. */
@Mixin(value = AbstractThrusterBlockEntity.class, remap = false)
public abstract class TransientThrusterControlMixin implements TransientThrusterControl {
    private static final String COMPUKTERS_CONTROL = "compukters_propulsion:transient_control";

    @Shadow protected float digitalInput;
    @Shadow private float fadePower;
    @Shadow protected ControlMode controlMode;
    @Unique private UUID compukters$identity = UUID.randomUUID();

    @Override
    public String compukters$persistentIdentity() {
        ((AbstractThrusterBlockEntity) (Object) this).setChanged();
        return compukters$identity.toString();
    }

    @Override
    public float compukters$digitalInput() { return digitalInput; }

    @Override
    public boolean compukters$peripheralMode() { return controlMode == ControlMode.PERIPHERAL; }

    @Override
    public void compukters$clearProgramPower() {
        // Bypass the setter's epsilon without temporarily commanding full power.
        digitalInput = 0f;
        // The old envelope must not multiply the restored scroll-wheel thrust.
        fadePower = 0f;
        ((AbstractThrusterBlockEntity) (Object) this).dirtyThrust();
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void compukters$markTransientControl(CompoundTag tag, HolderLookup.Provider registries,
                                                boolean clientPacket, CallbackInfo callback) {
        if (!clientPacket) tag.putUUID("compukters_propulsion:identity", compukters$identity);
        if (!clientPacket && PropulsionGuestIntegration.isControlled(this)) {
            tag.putBoolean(COMPUKTERS_CONTROL, true);
        }
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void compukters$restoreUnownedControl(CompoundTag tag, HolderLookup.Provider registries,
                                                 boolean clientPacket, CallbackInfo callback) {
        if (!clientPacket && tag.hasUUID("compukters_propulsion:identity"))
            compukters$identity = tag.getUUID("compukters_propulsion:identity");
        if (!clientPacket && tag.getBoolean(COMPUKTERS_CONTROL)) {
            AbstractThrusterBlockEntity entity = (AbstractThrusterBlockEntity) (Object) this;
            compukters$clearProgramPower();
            entity.setControlMode(ControlMode.NORMAL);
        }
    }
}
