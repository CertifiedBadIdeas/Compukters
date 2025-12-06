// SPDX-FileCopyrightText: 2021 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.upload

import net.minecraft.network.chat.Component

enum class UploadResult {
    QUEUED,
    CONSUMED,
    ERROR,
    ;

    companion object {
        val FAILED_TITLE: Component = Component.translatable("gui.compuktercraft.upload.failed")
        val COMPUTER_OFF_MSG: Component = Component.translatable("gui.compuktercraft.upload.failed.computer_off")
        val TOO_MUCH_MSG: Component = Component.translatable("gui.compuktercraft.upload.failed.too_much")
    }
}
