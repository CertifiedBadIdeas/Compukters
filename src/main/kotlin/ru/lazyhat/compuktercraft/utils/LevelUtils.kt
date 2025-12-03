package ru.lazyhat.compuktercraft.utils

import net.minecraft.world.level.Level

fun <T : Any> T.ifClientSide(level: Level?): T? = takeIf { level?.isClientSide == true }

fun <T : Any> T.ifServerSide(level: Level?): T? = takeIf { level?.isClientSide == false }
