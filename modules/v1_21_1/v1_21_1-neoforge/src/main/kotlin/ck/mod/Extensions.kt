/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ck.mod

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.neoforged.fml.ModList
import kotlin.jvm.optionals.getOrDefault

const val MOD_ID = "compukterkraft"
const val MOD_NAME = "Compukter Kraft"

val LOGGER: KLogger = KotlinLogging.logger(MOD_ID)

val INSTALLED_VERSION: String by lazy {
    ModList
        .get()
        .getModContainerById(MOD_ID)
        .map { it.modInfo.version.toString() }
        .getOrDefault("unknown")
}

fun String.asResource(): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, this)

fun String.literalComponent(): Component = Component.literal(this)
