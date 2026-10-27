/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.impl.display.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.lazyhat.compukters.minecraft.display.AttachedDisplaySurfaces;

@Mixin(LevelChunk.class)
public abstract class AttachedDisplayBackingMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void compukters$backingChanged(BlockPos position, BlockState state, int flags, CallbackInfoReturnable<BlockState> callback) {
        BlockState previous = callback.getReturnValue();
        if (previous != null && previous.getBlock() != state.getBlock()) {
            AttachedDisplaySurfaces.backingChanged(((LevelChunk) (Object) this).getLevel(), position);
        }
    }
}
